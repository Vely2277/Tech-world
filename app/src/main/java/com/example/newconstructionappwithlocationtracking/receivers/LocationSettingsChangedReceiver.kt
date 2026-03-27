/*
 * ============================================================================
 * LOCATION SETTINGS RECEIVER - AUTO-START WHEN LOCATION ENABLED
 * ============================================================================
 *
 * PURPOSE:
 * Listens for system-wide location settings changes and automatically starts
 * tracking when the user enables GPS/Location services on the device.
 *
 * TRIGGERS:
 * - User enables GPS in Settings
 * - User enables Location Services
 * - User switches location mode (high accuracy, battery saving, etc.)
 *
 * BEHAVIOR:
 * When location is enabled:
 * 1. Check if user has a valid session
 * 2. Check if tracking is enabled in app preferences
 * 3. Check if required permissions are granted
 * 4. If all conditions met → Schedule tracking via WorkManager!
 *
 * IMPORTANT (Android 12+ Foreground Service Restrictions):
 * We use WorkManager to schedule service start because starting a foreground
 * service from a background broadcast receiver is restricted on Android 12+.
 * WorkManager has exemptions and can reliably start foreground services.
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Receiver that detects when location providers are enabled/disabled.
 * Automatically starts tracking when conditions are met.
 */
class LocationSettingsChangedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationSettingsRx"
        private const val WORK_NAME_LOCATION_ENABLED_START = "location_enabled_service_start"

        // IMMEDIATE start - no delay! Location was just enabled, start NOW!
        private const val SERVICE_START_DELAY_MS = 500L // 0.5 seconds (just enough for system to stabilize)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action

        // Log to Android system log (survives app process death)
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "📡 BROADCAST RECEIVED: $action")
        Log.i(TAG, "   Time: ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())}")
        Log.i(TAG, "   Process ID: ${android.os.Process.myPid()}")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        if (action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            handleProvidersChanged(context)
        }
    }

    private fun handleProvidersChanged(context: Context) {
        Log.i(TAG, "📍 handleProvidersChanged() called")

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        if (locationManager == null) {
            Log.e(TAG, "❌ LocationManager is NULL!")
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isAnyEnabled = isGpsEnabled || isNetworkEnabled

        Log.i(TAG, "   GPS enabled: $isGpsEnabled")
        Log.i(TAG, "   Network enabled: $isNetworkEnabled")
        Log.i(TAG, "   Any location enabled: $isAnyEnabled")

        // Track location state change for diagnostic purposes
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean("was_location_enabled", isAnyEnabled)
            .putLong("location_state_changed_at", System.currentTimeMillis())
            .apply()

        if (!isAnyEnabled) {
            Log.i(TAG, "   ℹ️ Location disabled - tracking will pause")
            return
        }

        // Location was just enabled - this is a KEY RECOVERY SCENARIO!
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "📍 LOCATION SERVICES ENABLED - Triggering immediate recovery!")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")


        // Notify LocationServiceHelper that the condition was fixed
        LocationServiceHelper.checkAndResumeIfConditionFixed(
            context,
            LocationServiceHelper.FailureReason.LOCATION_SERVICES_DISABLED
        )

        try {
            // 1. Check tracking preference
            val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
            Log.i(TAG, "   1️⃣ Tracking enabled in prefs: $trackingEnabled")

            if (!trackingEnabled) {
                Log.i(TAG, "   ❌ STOPPED: Tracking disabled by user preference")
                return
            }

            // 2. Check for user session
            val savedUserId = prefs.getString("saved_user_id", null)
            val firebaseUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                null
            }
            val userId = firebaseUser?.uid ?: savedUserId
            Log.i(TAG, "   2️⃣ User session: ${if (userId != null) "✅ $userId" else "❌ NULL"}")

            if (userId == null) {
                Log.i(TAG, "   ❌ STOPPED: No user session available")
                return
            }

            // 3. Check permissions
            val permissionManager = LocationPermissionManager(context)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()
            Log.i(TAG, "   3️⃣ Permissions: ${if (permissionStatus.canStartTracking) "✅ Granted" else "❌ Missing"}")

            if (!permissionStatus.canStartTracking) {
                Log.i(TAG, "   ❌ STOPPED: Missing required permissions")
                return
            }

            // 4. Check if already running
            val isRunning = LocationServiceHelper.isServiceRunning(context)
            Log.i(TAG, "   4️⃣ Service running: ${if (isRunning) "✅ Yes" else "❌ No"}")

            if (isRunning) {
                Log.i(TAG, "   ✅ Service already running - no action needed")
                return
            }

            // 5. ALL CONDITIONS MET - START TRACKING!
            // CRITICAL FIX: Android 12+ blocks WorkManager from starting foreground services from background
            // We need to start the service DIRECTLY from this receiver context
            Log.i(TAG, "═══════════════════════════════════════════════════════════════")
            Log.i(TAG, "🚀 LOCATION ENABLED + ALL CONDITIONS MET → STARTING TRACKING!")
            Log.i(TAG, "═══════════════════════════════════════════════════════════════")

            // Start service directly with a small delay to let the system stabilize
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "⏰ Delay completed - starting service now...")
                    val result = LocationServiceHelper.startLocationService(context, userId)
                    Log.i(TAG, "✅ Service start result: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start service after location enabled", e)
                }
            }, SERVICE_START_DELAY_MS)

            Log.i(TAG, "✅ Service will start in ${SERVICE_START_DELAY_MS / 1000}s")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling location settings change: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Schedule service start via WorkManager
     * WorkManager has exemptions from Android 12+ foreground service restrictions
     */
    private fun scheduleServiceStart(context: Context, userId: String) {
        try {
            Log.d(TAG, "   ├─ Scheduling service start via WorkManager...")

            val inputData = workDataOf(
                "userId" to userId,
                "triggerTime" to System.currentTimeMillis(),
                "trigger" to "LOCATION_ENABLED"
            )

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Start even on low battery
                .build()

            val workRequest = OneTimeWorkRequestBuilder<LocationEnabledServiceStartWorker>()
                .setInitialDelay(SERVICE_START_DELAY_MS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag(WORK_NAME_LOCATION_ENABLED_START)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_LOCATION_ENABLED_START,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            Log.i(TAG, "   ✅ WorkManager scheduled: service will start in ${SERVICE_START_DELAY_MS / 1000}s")

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Failed to schedule WorkManager: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION ENABLED SERVICE START WORKER
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Worker that starts the location service when location is enabled on device.
 * WorkManager workers have exemptions from Android 12+ foreground service restrictions.
 */
class LocationEnabledServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "LocationEnabledWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "🚀 LocationEnabledServiceStartWorker.doWork() START")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        val userId = inputData.getString("userId") ?: "unknown"
        val triggerTime = inputData.getLong("triggerTime", 0L)
        val trigger = inputData.getString("trigger") ?: "unknown"
        val actualDelay = System.currentTimeMillis() - triggerTime

        Log.d(TAG, "   ├─ User ID: $userId")
        Log.d(TAG, "   ├─ Trigger: $trigger")
        Log.d(TAG, "   ├─ Actual delay: ${actualDelay}ms")

        // Final permission check
        Log.d(TAG, "   ├─ Final permission validation...")
        val permissionManager = LocationPermissionManager(applicationContext)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking) {
            Log.w(TAG, "   ❌ Permissions no longer valid")
            return Result.failure()
        }

        if (!permissionStatus.isLocationEnabled) {
            Log.w(TAG, "   ❌ Location services no longer enabled")
            return Result.failure()
        }

        // Check if already running
        if (LocationServiceHelper.isServiceRunning(applicationContext)) {
            Log.i(TAG, "   ℹ️ Service already running")
            return Result.success()
        }

        Log.d(TAG, "   ✅ All conditions still valid")

        // Start service using LocationServiceHelper
        Log.d(TAG, "   ├─ Starting service via LocationServiceHelper...")
        val result = LocationServiceHelper.startLocationService(applicationContext, userId)

        return when (result) {
            is LocationServiceHelper.ServiceStartResult.Success -> {
                Log.i(TAG, "✅ Service started successfully after location enabled!")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                Log.i(TAG, "   ℹ️ Service already running")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.PermissionsMissing -> {
                Log.w(TAG, "   ❌ Permissions missing: ${result.missing}")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.LocationServicesDisabled -> {
                Log.w(TAG, "   ❌ Location services disabled")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                Log.w(TAG, "   ⏳ Circuit breaker active, will retry later")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.BatteryTooLow -> {
                Log.w(TAG, "   🔋 Battery too low, will retry later")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.NoUserSession -> {
                Log.w(TAG, "   ❌ No user session")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.PowerSaveModeActive -> {
                Log.w(TAG, "   ⚡ Power save mode active, starting anyway")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.Failed -> {
                Log.e(TAG, "   ❌ Failed: ${result.reason}")
                Result.retry()
            }
        }
    }
}

