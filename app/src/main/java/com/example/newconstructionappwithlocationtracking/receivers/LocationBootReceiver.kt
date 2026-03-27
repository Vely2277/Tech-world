/*
 * ============================================================================
 * LOCATION BOOT RECEIVER - AUTONOMOUS RESTART IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Automatically restarts location tracking service after device reboot and
 * other critical system events. Ensures 24/7 tracking autonomy.
 *
 * KEY FEATURES:
 * - BOOT_COMPLETED: Restart after device reboot
 * - MY_PACKAGE_REPLACED: Restart after app update
 * - Smart delay: 30 min (normal) or 10 min (ForceCheck ON)
 * - Uses cached settings (no network required)
 * - Comprehensive permission validation
 * - WorkManager for delayed, battery-friendly startup
 * - Complete error handling
 *
 * SMART DELAY LOGIC:
 * - Reads ForceCheck state from cache (doesn't require network)
 * - ForceCheck ON → 10 minute delay (BOOT_STARTUP_DELAY_FORCECHECK_MS)
 * - ForceCheck OFF → 30 minute delay (BOOT_STARTUP_DELAY_NORMAL_MS)
 * - Allows device to stabilize: network, battery, other apps
 *
 * INTEGRATION:
 * - LocationServiceHelper: Proper service start with circuit breaker
 * - LocationPermissionManager: Permission validation
 * - LocationSettingsManager: Cached ForceCheck state
 * - WorkManager: Delayed, battery-friendly execution
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.LocationSettingsManager
import com.example.newconstructionappwithlocationtracking.location.NetworkRecoveryManager
import com.example.newconstructionappwithlocationtracking.location.WatchdogAlarmManager
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.example.newconstructionappwithlocationtracking.location.OemBatteryHandler
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION BOOT RECEIVER
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Catches BOOT_COMPLETED and MY_PACKAGE_REPLACED, schedules delayed service start
 */
class LocationBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = LocationConstants.TAG_BOOT
        private const val WORK_NAME_BOOT_RESTART = "location_boot_restart"

        // Delay values
        private const val QUICK_START_DELAY_MS = 5000L // 5 seconds for quick verification
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        LocationLogger.i(TAG, "🔄 Broadcast received: $action")
        android.util.Log.i("BOOT_RECEIVER", "═══════════════════════════════════════════")
        android.util.Log.i("BOOT_RECEIVER", "🔄 BROADCAST: $action")
        android.util.Log.i("BOOT_RECEIVER", "═══════════════════════════════════════════")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                LocationLogger.i(TAG, "📱 BOOT COMPLETED - Device rebooted")
                android.util.Log.i("BOOT_RECEIVER", "📱 BOOT_COMPLETED detected!")
                handleBootCompleted(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                LocationLogger.i(TAG, "📦 MY_PACKAGE_REPLACED - App updated")
                android.util.Log.i("BOOT_RECEIVER", "📦 APP UPDATED detected!")
                handlePackageReplaced(context)
            }
            else -> {
                LocationLogger.d(TAG, "   ℹ️  Unhandled action: $action")
            }
        }
    }

    /**
     * Handle device boot completed
     * PRIORITY: Get tracking started as soon as possible after boot
     */
    private fun handleBootCompleted(context: Context) {
        android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────────")
        android.util.Log.d("BOOT_RECEIVER", "🔄 handleBootCompleted() START")

        // CRITICAL: Check if device is known to block AutoStart
        if (OemBatteryHandler.isAggressiveOem()) {
            android.util.Log.w("BOOT_RECEIVER", "⚠️ AGGRESSIVE OEM DETECTED!")
            OemBatteryHandler.logOemInfo()

            // Check if we've already warned (don't spam)
            if (!OemBatteryHandler.hasShownOemWarning(context)) {
                android.util.Log.w("BOOT_RECEIVER", "   📢 Showing OEM AutoStart warning...")
                OemBatteryHandler.showAutoStartBlockedWarning(context)
                OemBatteryHandler.markOemWarningShown(context)
            } else {
                android.util.Log.d("BOOT_RECEIVER", "   ℹ️ OEM warning already shown previously")
            }
        }

        incrementBootCount(context)

        // Check if tracking should have been running (detect unexpected death)
        val unexpectedDeath = ServiceStatePersistence.detectUnexpectedDeath(context)
        if (unexpectedDeath) {
            android.util.Log.w("BOOT_RECEIVER", "⚠️ UNEXPECTED SERVICE DEATH DETECTED!")
            android.util.Log.w("BOOT_RECEIVER", "   Service was running before reboot - will restart")
        }

        // ALWAYS start monitoring layers (they'll check if tracking is needed)
        android.util.Log.d("BOOT_RECEIVER", "   ├─ Starting monitoring layers...")

        // Start NetworkRecoveryManager EARLY - it's critical
        try {
            NetworkRecoveryManager.getInstance(context).startMonitoring()
            android.util.Log.d("BOOT_RECEIVER", "   │  ✅ NetworkRecoveryManager started")
        } catch (e: Exception) {
            android.util.Log.e("BOOT_RECEIVER", "   │  ⚠️ NetworkRecoveryManager error: ${e.message}")
        }

        // Start WatchdogAlarmManager if tracking should be running
        val shouldTrackingRun = ServiceStatePersistence.shouldTrackingBeRunning(context)
        if (shouldTrackingRun) {
            try {
                WatchdogAlarmManager.startWatchdog(context)
                android.util.Log.d("BOOT_RECEIVER", "   │  ✅ WatchdogAlarmManager started")
            } catch (e: Exception) {
                android.util.Log.e("BOOT_RECEIVER", "   │  ⚠️ WatchdogAlarmManager error: ${e.message}")
            }
        }

        // CRITICAL: Schedule resurrection alarms for bulletproof service restart
        try {
            ServiceResurrectionReceiver.scheduleResurrectionAlarms(context)
            android.util.Log.d("BOOT_RECEIVER", "   │  ✅ Resurrection alarms scheduled")
        } catch (e: Exception) {
            android.util.Log.e("BOOT_RECEIVER", "   │  ⚠️ Resurrection alarms error: ${e.message}")
        }

        // Check if we have a saved user session
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        val savedUserId = prefs.getString("saved_user_id", null)
        val hasValidSession = prefs.getBoolean("has_valid_session", false)

        android.util.Log.d("BOOT_RECEIVER", "   saved_user_id: $savedUserId")
        android.util.Log.d("BOOT_RECEIVER", "   has_valid_session: $hasValidSession")

        // Try to get user from Firebase Auth first
        val firebaseUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            android.util.Log.e("BOOT_RECEIVER", "   Firebase not ready: ${e.message}")
            null
        }

        val userId = firebaseUser?.uid ?: savedUserId
        android.util.Log.d("BOOT_RECEIVER", "   effective userId: $userId")

        if (userId == null) {
            android.util.Log.w("BOOT_RECEIVER", "   ❌ No user session - cannot start tracking")
            LocationLogger.w(TAG, "   ❌ No user session found")
            recordBootFailure(context, "No user session")
            return
        }

        // Check if tracking is enabled
        val trackingEnabled = LocationServiceHelper.isTrackingEnabled(context)
        android.util.Log.d("BOOT_RECEIVER", "   tracking_enabled: $trackingEnabled")

        if (!trackingEnabled) {
            android.util.Log.i("BOOT_RECEIVER", "   📴 Tracking is disabled, skipping service start")
            LocationLogger.i(TAG, "   📴 Tracking is disabled, skipping service start")
            return
        }

        // Validate permissions
        android.util.Log.d("BOOT_RECEIVER", "   ├─ Validating permissions...")
        val permissionManager = LocationPermissionManager(context)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        android.util.Log.d("BOOT_RECEIVER", "   hasFineLocation: ${permissionStatus.hasFineLocation}")
        android.util.Log.d("BOOT_RECEIVER", "   hasBackgroundLocation: ${permissionStatus.hasBackgroundLocation}")
        android.util.Log.d("BOOT_RECEIVER", "   isLocationEnabled: ${permissionStatus.isLocationEnabled}")
        android.util.Log.d("BOOT_RECEIVER", "   canStartTracking: ${permissionStatus.canStartTracking}")

        if (!permissionStatus.canStartTracking) {
            val missing = permissionStatus.missingCritical + permissionStatus.missingOptional
            android.util.Log.w("BOOT_RECEIVER", "   ❌ Cannot start: Missing permissions - $missing")
            LocationLogger.w(TAG, "   ❌ Cannot start: Missing permissions - $missing")
            recordBootFailure(context, "Missing permissions: $missing")
            return
        }

        if (!permissionStatus.isLocationEnabled) {
            android.util.Log.w("BOOT_RECEIVER", "   ❌ Cannot start: Location services disabled")
            LocationLogger.w(TAG, "   ❌ Cannot start: Location services disabled")
            recordBootFailure(context, "Location services disabled")
            return
        }

        android.util.Log.d("BOOT_RECEIVER", "   ✅ All conditions met!")
        LocationLogger.d(TAG, "   ✅ Permissions validated")

        // Determine delay based on cached ForceCheck state
        // IMPORTANT: Use SHORT delay (1 min) when all conditions are met
        // Only use long delay (10-30 min) if there were recent issues
        android.util.Log.d("BOOT_RECEIVER", "   ├─ Reading cached settings...")
        val settingsManager = LocationSettingsManager(context)
        val currentSettings = settingsManager.getCurrentSettings()
        val forceCheckEnabled = currentSettings.forceCheck
        val emergencyMode = currentSettings.emergencyMode

        // Smart delay calculation:
        // - Emergency mode: 30 seconds (ASAP!)
        // - ForceCheck mode: 1 minute
        // - Normal mode: 2 minutes (allow system to stabilize)
        val delayMs = when {
            emergencyMode -> {
                android.util.Log.i("BOOT_RECEIVER", "   🚨 EMERGENCY MODE → 30 second delay")
                30000L // 30 seconds
            }
            forceCheckEnabled -> {
                android.util.Log.i("BOOT_RECEIVER", "   ⚡ ForceCheck ON → 1 minute delay")
                60000L // 1 minute
            }
            else -> {
                android.util.Log.i("BOOT_RECEIVER", "   🕐 Normal mode → 2 minute delay")
                120000L // 2 minutes
            }
        }

        // CRITICAL FIX: Android 12+ requires foreground services to be started DIRECTLY from boot receiver
        // WorkManager CANNOT start foreground services from background - it gets blocked with "3rd-normal-service" error
        // Use Handler with delay instead of WorkManager
        android.util.Log.i("BOOT_RECEIVER", "   ├─ Starting service directly with ${delayMs / 1000}s delay (Android 12+ requirement)...")
        LocationLogger.i(TAG, "   ├─ Starting service directly with ${delayMs / 1000}s delay...")

        // Use Handler.postDelayed - this inherits boot receiver's exemption
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                android.util.Log.i("BOOT_RECEIVER", "⏰ Delay completed - starting service now...")
                val result = LocationServiceHelper.startLocationService(context, userId)
                android.util.Log.i("BOOT_RECEIVER", "✅ Service start result: $result")

                when (result) {
                    is LocationServiceHelper.ServiceStartResult.Success,
                    is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                        recordBootSuccess(context)
                    }
                    else -> {
                        recordBootFailure(context, "Service start failed: $result")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BOOT_RECEIVER", "❌ Failed to start service after delay", e)
                recordBootFailure(context, "Exception: ${e.message}")
            }
        }, delayMs)

        android.util.Log.i("BOOT_RECEIVER", "✅ Boot sequence completed - service will start directly after delay")
        LocationLogger.i(TAG, "✅ Boot sequence completed - service will start directly after delay")
    }

    /**
     * Handle app package replaced (after update)
     * Restart tracking quickly since user data persists through updates
     */
    private fun handlePackageReplaced(context: Context) {
        android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────────")
        android.util.Log.d("BOOT_RECEIVER", "📦 handlePackageReplaced() START")

        // Get user session
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        val savedUserId = prefs.getString("saved_user_id", null)
        val firebaseUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        }
        val userId = firebaseUser?.uid ?: savedUserId

        android.util.Log.d("BOOT_RECEIVER", "   userId: $userId")

        if (userId == null) {
            android.util.Log.w("BOOT_RECEIVER", "   ❌ No user session")
            LocationLogger.w(TAG, "   ❌ No user session after update")
            return
        }

        // Check if tracking was enabled before update
        val trackingEnabled = LocationServiceHelper.isTrackingEnabled(context)
        android.util.Log.d("BOOT_RECEIVER", "   trackingEnabled: $trackingEnabled")

        if (!trackingEnabled) {
            android.util.Log.i("BOOT_RECEIVER", "   📴 Tracking was disabled, skipping restart")
            LocationLogger.i(TAG, "   📴 Tracking was disabled, skipping restart")
            return
        }

        android.util.Log.i("BOOT_RECEIVER", "   ✅ Tracking was enabled - restarting after update")
        LocationLogger.i(TAG, "   ✅ Tracking was enabled - restarting after update")

        // Validate permissions
        val permissionManager = LocationPermissionManager(context)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        android.util.Log.d("BOOT_RECEIVER", "   canStartTracking: ${permissionStatus.canStartTracking}")
        android.util.Log.d("BOOT_RECEIVER", "   isLocationEnabled: ${permissionStatus.isLocationEnabled}")

        if (!permissionStatus.canStartTracking || !permissionStatus.isLocationEnabled) {
            android.util.Log.w("BOOT_RECEIVER", "   ❌ Cannot restart: Permissions or location issue")
            LocationLogger.w(TAG, "   ❌ Cannot restart: Permissions or location services issue")
            return
        }

        // Quick restart after app update - start DIRECTLY (Android 12+ requires direct start from receiver)
        android.util.Log.i("BOOT_RECEIVER", "   ├─ Starting service directly with 5 second delay...")
        LocationLogger.i(TAG, "   ├─ Starting service directly with 5 second delay...")

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                android.util.Log.i("BOOT_RECEIVER", "⏰ Delay completed - restarting service now...")
                val result = LocationServiceHelper.startLocationService(context, userId)
                android.util.Log.i("BOOT_RECEIVER", "✅ Service restart result: $result")

                when (result) {
                    is LocationServiceHelper.ServiceStartResult.Success,
                    is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                        recordBootSuccess(context)
                    }
                    else -> {
                        android.util.Log.w("BOOT_RECEIVER", "⚠️ Restart unsuccessful: $result")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BOOT_RECEIVER", "❌ Failed to restart service", e)
            }
        }, QUICK_START_DELAY_MS)

        android.util.Log.i("BOOT_RECEIVER", "✅ Package replaced sequence completed")
        LocationLogger.i(TAG, "✅ Package replaced sequence completed")
    }

    /**
     * Schedule delayed service start using WorkManager
     */
    private fun scheduleDelayedStart(context: Context, userId: String, delayMs: Long) {
        try {
            val inputData = workDataOf(
                "userId" to userId,
                "bootTime" to System.currentTimeMillis()
            )

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Can start even on low battery
                .build()

            val workRequest = OneTimeWorkRequestBuilder<BootServiceStartWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag(WORK_NAME_BOOT_RESTART)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_BOOT_RESTART,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            LocationLogger.d(TAG, "   ✅ WorkManager scheduled: $WORK_NAME_BOOT_RESTART (${delayMs / 1000}s delay)")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to schedule WorkManager, using Handler fallback", e)

            // CRITICAL: Check if this is AutoStart blocking
            val errorMsg = e.message?.lowercase() ?: ""
            if (errorMsg.contains("autostart") || errorMsg.contains("unable to launch")) {
                android.util.Log.e(TAG, "🚨 AUTOSTART BLOCKING DETECTED!")
                OemBatteryHandler.showAutoStartBlockedWarning(context)
            }

            // Fallback to Handler if WorkManager fails
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val result = LocationServiceHelper.startLocationService(context, userId)
                    LocationLogger.i(TAG, "   Handler fallback result: $result")
                } catch (ex: Exception) {
                    LocationLogger.e(TAG, "   Handler fallback failed", ex)
                }
            }, delayMs)
        }
    }

    /**
     * Get saved user ID from preferences
     */
    private fun getSavedUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        return prefs.getString("saved_user_id", null)
    }

    /**
     * Increment boot count for diagnostics
     */
    private fun incrementBootCount(context: Context) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        val currentCount = prefs.getInt("boot_count", 0)
        val newCount = currentCount + 1

        prefs.edit()
            .putInt("boot_count", newCount)
            .putLong("last_boot_time", System.currentTimeMillis())
            .apply()

        LocationLogger.d(TAG, "   📊 Boot count: $newCount")
    }

    /**
     * Record boot success for diagnostics
     */
    private fun recordBootSuccess(context: Context) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putBoolean("last_boot_success", true)
            .putString("last_boot_failure_reason", "")
            .putLong("last_boot_success_time", System.currentTimeMillis())
            .apply()

        LocationLogger.i(TAG, "✅ Boot success recorded")
    }

    /**
     * Record boot failure for diagnostics
     */
    private fun recordBootFailure(context: Context, reason: String) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putBoolean("last_boot_success", false)
            .putString("last_boot_failure_reason", reason)
            .putLong("last_boot_failure_time", System.currentTimeMillis())
            .apply()

        LocationLogger.e(TAG, "❌ Boot failure recorded: $reason")
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * BOOT SERVICE START WORKER
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Worker that starts the location service after boot delay
 */
class BootServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = LocationConstants.TAG_BOOT
    }

    override fun doWork(): Result {
        LocationLogger.i(TAG, "🚀 Starting location service after boot delay...")

        val userId = inputData.getString("userId") ?: "unknown"
        val bootTime = inputData.getLong("bootTime", 0L)
        val delayActual = System.currentTimeMillis() - bootTime

        LocationLogger.d(TAG, "   ├─ User ID: $userId")
        LocationLogger.d(TAG, "   ├─ Actual delay: ${delayActual / 60000} minutes")

        // Final permission check
        LocationLogger.d(TAG, "   ├─ Final permission validation...")
        val permissionManager = LocationPermissionManager(applicationContext)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking) {
            LocationLogger.w(TAG, "   ❌ Permissions revoked during delay")
            recordBootFailure("Permissions revoked during delay")
            return Result.failure()
        }

        if (!permissionStatus.isLocationEnabled) {
            LocationLogger.w(TAG, "   ❌ Location services disabled")
            recordBootFailure("Location services disabled")
            return Result.failure()
        }

        LocationLogger.d(TAG, "   ✅ Permissions still valid")

        // Start service using LocationServiceHelper
        LocationLogger.d(TAG, "   ├─ Starting service via LocationServiceHelper...")
        val result = LocationServiceHelper.startLocationService(applicationContext, userId)

        // Handle all ServiceStartResult cases
        return when (result) {
            is LocationServiceHelper.ServiceStartResult.Success -> {
                LocationLogger.i(TAG, "✅ Service started successfully after boot")
                recordBootSuccess()
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                LocationLogger.i(TAG, "   ℹ️  Service already running")
                recordBootSuccess()
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.PermissionsMissing -> {
                LocationLogger.w(TAG, "   ❌ Permissions missing: ${result.missing}")
                recordBootFailure("Permissions missing: ${result.missing}")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.LocationServicesDisabled -> {
                LocationLogger.w(TAG, "   ❌ Location services disabled")
                recordBootFailure("Location services disabled")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                LocationLogger.w(TAG, "   🔴 Circuit breaker active, will retry later (${result.nextAttemptIn / 1000}s)")
                // Return retry - WorkManager will retry automatically
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.BatteryTooLow -> {
                LocationLogger.w(TAG, "   🔋 Battery too low, will retry later")
                recordBootFailure("Battery too low")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.NoUserSession -> {
                LocationLogger.w(TAG, "   ❌ No user session")
                recordBootFailure("No user session")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.PowerSaveModeActive -> {
                LocationLogger.w(TAG, "   ⚡ Power save mode active, attempting start anyway")
                // Still try to start, just log the warning
                recordBootSuccess()
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.Failed -> {
                LocationLogger.e(TAG, "   ❌ Failed to start: ${result.reason}")
                recordBootFailure(result.reason)
                Result.retry() // Retry on failure
            }
        }
    }

    /**
     * Record boot failure for diagnostics
     */
    private fun recordBootFailure(reason: String) {
        val prefs = applicationContext.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putBoolean("last_boot_success", false)
            .putString("last_boot_failure_reason", reason)
            .putLong("last_boot_failure_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * Record boot success for diagnostics
     */
    private fun recordBootSuccess() {
        val prefs = applicationContext.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putBoolean("last_boot_success", true)
            .putString("last_boot_failure_reason", null)
            .putLong("last_boot_success_time", System.currentTimeMillis())
            .putBoolean("boot_in_progress", false)
            .apply()
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * BOOT RETRY WORKER
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Worker that retries boot sequence when initial conditions weren't met
 * (e.g., Firebase Auth not ready yet after reboot)
 */
class BootRetryWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "BootRetryWorker"
        private const val MAX_RETRIES = 3
    }

    override fun doWork(): Result {
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "🔄 BootRetryWorker - Retrying boot sequence...")
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")

        try {
            val prefs = applicationContext.getSharedPreferences(
                LocationConstants.PREFS_NAME_SERVICE_STATE,
                Context.MODE_PRIVATE
            )

            // Check retry count
            val retryCount = prefs.getInt("boot_retry_count", 0)
            if (retryCount >= MAX_RETRIES) {
                android.util.Log.w(TAG, "   ❌ Max retries reached ($MAX_RETRIES)")
                prefs.edit()
                    .putBoolean("boot_in_progress", false)
                    .putInt("boot_retry_count", 0)
                    .apply()
                return Result.failure()
            }

            // Increment retry count
            prefs.edit().putInt("boot_retry_count", retryCount + 1).apply()
            android.util.Log.d(TAG, "   Retry attempt: ${retryCount + 1}/$MAX_RETRIES")

            // Try to get user ID
            val savedUserId = prefs.getString("saved_user_id", null)
            val firebaseUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                android.util.Log.e(TAG, "   Firebase still not ready: ${e.message}")
                null
            }

            val userId = firebaseUser?.uid ?: savedUserId

            if (userId == null) {
                android.util.Log.w(TAG, "   Still no user session - will retry")
                return Result.retry()
            }

            android.util.Log.d(TAG, "   ✅ User ID found: $userId")

            // Check if tracking enabled
            val trackingEnabled = LocationServiceHelper.isTrackingEnabled(applicationContext)
            if (!trackingEnabled) {
                android.util.Log.d(TAG, "   Tracking disabled by user")
                prefs.edit()
                    .putBoolean("boot_in_progress", false)
                    .putInt("boot_retry_count", 0)
                    .apply()
                return Result.success()
            }

            // Check permissions
            val permissionManager = LocationPermissionManager(applicationContext)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()

            if (!permissionStatus.canStartTracking) {
                android.util.Log.w(TAG, "   Missing permissions - will retry")
                return Result.retry()
            }

            if (!permissionStatus.isLocationEnabled) {
                android.util.Log.w(TAG, "   Location disabled - will retry when enabled")
                // Don't retry - LocationSettingsChangedReceiver will handle this
                prefs.edit()
                    .putBoolean("boot_in_progress", false)
                    .putInt("boot_retry_count", 0)
                    .apply()
                return Result.success()
            }

            // Start service
            android.util.Log.d(TAG, "   ✅ All conditions met - starting service...")
            val result = LocationServiceHelper.startLocationService(applicationContext, userId)

            prefs.edit()
                .putBoolean("boot_in_progress", false)
                .putInt("boot_retry_count", 0)
                .apply()

            return when (result) {
                is LocationServiceHelper.ServiceStartResult.Success,
                is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                    android.util.Log.i(TAG, "   ✅ Service started successfully!")
                    Result.success()
                }
                is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                    android.util.Log.w(TAG, "   Circuit breaker active - will retry")
                    Result.retry()
                }
                else -> {
                    android.util.Log.e(TAG, "   Start failed: $result")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "   ❌ Boot retry failed: ${e.message}")
            return Result.retry()
        }
    }
}
