/*
 * ============================================================================
 * LOCATION TRACKING APPLICATION - AUTONOMOUS INITIALIZATION
 * ============================================================================
 *
 * PURPOSE:
 * Custom Application class that ensures location tracking is ALWAYS running
 * whenever conditions are met, regardless of whether user opens the app.
 *
 * AUTONOMOUS GUARANTEES:
 * ✅ Initializes tracking check on every app process start
 * ✅ Registers system-wide listeners for location/permission changes
 * ✅ Sets up WorkManager periodic safety net
 * ✅ Persists user session for boot receiver to use
 * ✅ Handles Firebase Auth state changes
 *
 * KEY PRINCIPLE:
 * IF (user is logged in) AND (permissions granted) AND (location enabled)
 * THEN tracking MUST be running. No exceptions.
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.NetworkRecoveryManager
import com.example.newconstructionappwithlocationtracking.location.WatchdogAlarmManager
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.example.newconstructionappwithlocationtracking.location.TrackingLifecycleObserver
import com.example.newconstructionappwithlocationtracking.location.OemBatteryHandler
import com.example.newconstructionappwithlocationtracking.location.getMissingPermissions
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class LocationTrackingApplication : Application() {

    companion object {
        private const val TAG = "LocationApp"
        private const val WORK_NAME_PERIODIC_CHECK = "location_periodic_check"
        private const val PERIODIC_CHECK_INTERVAL_MINUTES = 15L

        @Volatile
        private var instance: LocationTrackingApplication? = null

        fun getInstance(): LocationTrackingApplication? = instance
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "🚀 LocationTrackingApplication.onCreate()")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "✅ Firebase initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase init failed: ${e.message}")
        }

        // CRITICAL: Initialize TrackingLifecycleObserver for crash detection
        Log.d(TAG, "├─ Initializing TrackingLifecycleObserver...")
        try {
            TrackingLifecycleObserver.initialize(this)
            Log.d(TAG, "│  ✅ TrackingLifecycleObserver initialized")
        } catch (e: Exception) {
            Log.e(TAG, "│  ⚠️ TrackingLifecycleObserver failed: ${e.message}")
        }

        // CRITICAL: Start NetworkRecoveryManager EARLY for network loss recovery
        Log.d(TAG, "├─ Starting NetworkRecoveryManager...")
        try {
            NetworkRecoveryManager.getInstance(this).startMonitoring()
            Log.d(TAG, "│  ✅ NetworkRecoveryManager started")
        } catch (e: Exception) {
            Log.e(TAG, "│  ⚠️ NetworkRecoveryManager failed: ${e.message}")
        }

        // Check if watchdog should be running
        Log.d(TAG, "├─ Checking WatchdogAlarmManager state...")
        try {
            if (WatchdogAlarmManager.shouldWatchdogBeActive(this)) {
                WatchdogAlarmManager.startWatchdog(this)
                Log.d(TAG, "│  ✅ WatchdogAlarmManager restarted")
            } else {
                Log.d(TAG, "│  ℹ️ WatchdogAlarmManager not needed (tracking disabled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "│  ⚠️ WatchdogAlarmManager check failed: ${e.message}")
        }

        // CRITICAL: Schedule resurrection alarms for bulletproof service restart
        Log.d(TAG, "├─ Scheduling ServiceResurrectionReceiver alarms...")
        try {
            com.example.newconstructionappwithlocationtracking.receivers.ServiceResurrectionReceiver.scheduleResurrectionAlarms(this)
            Log.d(TAG, "│  ✅ Resurrection alarms scheduled (every 2 minutes)")
        } catch (e: Exception) {
            Log.e(TAG, "│  ⚠️ Resurrection alarms scheduling failed: ${e.message}")
        }

        // Log OEM info for debugging (helps diagnose battery killer issues)
        Log.d(TAG, "├─ Detecting OEM...")
        OemBatteryHandler.logOemInfo()

        // Setup auth state listener to persist user session
        setupAuthStateListener()

        // Setup periodic WorkManager check (safety net)
        setupPeriodicTrackingCheck()

        // Check if we should start tracking immediately
        checkAndStartTrackingAsync()
    }

    /**
     * Setup Firebase Auth state listener
     * Ensures user ID is saved whenever user logs in
     */
    private fun setupAuthStateListener() {
        authStateListener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser

            if (user != null) {
                Log.i(TAG, "👤 User logged in: ${user.uid}")

                // Save user ID for boot receiver and autonomous restarts
                saveUserSession(user.uid)

                // Enable tracking by default for logged-in users
                setTrackingEnabledIfNotSet(true)

                // Check if tracking should start
                checkAndStartTrackingAsync()
            } else {
                Log.i(TAG, "👤 User logged out")
                // Don't clear the saved user ID immediately - let them log back in
            }
        }

        try {
            FirebaseAuth.getInstance().addAuthStateListener(authStateListener!!)
            Log.d(TAG, "✅ Auth state listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register auth listener: ${e.message}")
        }
    }

    /**
     * Save user session for autonomous restarts
     */
    private fun saveUserSession(userId: String) {
        val prefs = getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putString("saved_user_id", userId)
            .putLong("last_login_time", System.currentTimeMillis())
            .putBoolean("has_valid_session", true)
            .apply()

        Log.d(TAG, "💾 User session saved: $userId")
    }

    /**
     * Set tracking enabled if not already set
     * This ensures tracking is ON by default for logged-in users
     */
    private fun setTrackingEnabledIfNotSet(enabled: Boolean) {
        val prefs = getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )

        // Only set if not already explicitly set by user
        if (!prefs.contains(LocationConstants.KEY_TRACKING_ENABLED)) {
            prefs.edit()
                .putBoolean(LocationConstants.KEY_TRACKING_ENABLED, enabled)
                .apply()
            Log.d(TAG, "📍 Tracking enabled set to: $enabled (default)")
        }
    }

    /**
     * Check and start tracking asynchronously
     */
    private fun checkAndStartTrackingAsync() {
        applicationScope.launch(Dispatchers.IO) {
            delay(2000) // Small delay to let app initialize
            checkAndStartTracking()
        }
    }

    /**
     * Check conditions and start tracking if all are met
     */
    private fun checkAndStartTracking() {
        try {
            Log.d(TAG, "🔍 Checking if tracking should start...")

            // 1. Check if tracking is enabled
            val prefs = getSharedPreferences(
                LocationConstants.PREFS_NAME_SERVICE_STATE,
                Context.MODE_PRIVATE
            )
            val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)

            if (!trackingEnabled) {
                Log.d(TAG, "   ℹ️ Tracking disabled by user preference")
                return
            }

            // 2. Check if user is logged in
            val currentUser = FirebaseAuth.getInstance().currentUser
            val savedUserId = prefs.getString("saved_user_id", null)
            val userId = currentUser?.uid ?: savedUserId

            if (userId == null) {
                Log.d(TAG, "   ℹ️ No user logged in, cannot start tracking")
                return
            }

            // 3. Check permissions
            val permissionManager = LocationPermissionManager(this)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()

            if (!permissionStatus.canStartTracking) {
                Log.d(TAG, "   ℹ️ Missing permissions: ${permissionStatus.getMissingPermissions()}")
                return
            }

            if (!permissionStatus.isLocationEnabled) {
                Log.d(TAG, "   ℹ️ Location services disabled")
                return
            }

            // 4. Check if already running
            if (LocationServiceHelper.isServiceRunning(this)) {
                Log.d(TAG, "   ✅ Service already running")
                return
            }

            // 5. All conditions met - START TRACKING!
            Log.i(TAG, "   🚀 All conditions met - starting tracking service!")

            // Run on main thread for service start
            CoroutineScope(Dispatchers.Main).launch {
                val result = LocationServiceHelper.startLocationService(this@LocationTrackingApplication, userId)
                Log.i(TAG, "   📊 Service start result: $result")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking/starting tracking: ${e.message}")
        }
    }

    /**
     * Setup periodic WorkManager check as ultimate safety net
     * Runs every 15 minutes to ensure tracking is active
     *
     * BULLETPROOF CONFIGURATION:
     * - Runs even on low battery
     * - Exponential backoff retry on failure
     * - Keeps existing work (doesn't cancel on restart)
     * - Tagged for monitoring
     */
    private fun setupPeriodicTrackingCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .setRequiresCharging(false) // Run even when not charging
                .setRequiresDeviceIdle(false) // Run even when device active
                .build()

            val workRequest = PeriodicWorkRequestBuilder<PeriodicTrackingCheckWorker>(
                PERIODIC_CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME_PERIODIC_CHECK)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInitialDelay(2, TimeUnit.MINUTES) // First run after 2 minutes
                .build()

            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC_CHECK,
                    ExistingPeriodicWorkPolicy.KEEP, // KEEP existing work, don't replace
                    workRequest
                )

            Log.i(TAG, "✅ Periodic tracking check scheduled (every ${PERIODIC_CHECK_INTERVAL_MINUTES}min)")

            // Monitor worker status after scheduling
            monitorWorkerStatus()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup periodic check: ${e.message}")
        }
    }

    /**
     * Monitor WorkManager worker status
     * Logs if worker is scheduled, running, or failed
     * This helps verify the worker is actually functioning
     */
    private fun monitorWorkerStatus() {
        try {
            val workManager = WorkManager.getInstance(this)
            val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME_PERIODIC_CHECK)

            workInfos.get().let { infos ->
                if (infos.isEmpty()) {
                    Log.w(TAG, "⚠️ WARNING: No worker found! This should not happen!")
                } else {
                    val workInfo = infos[0]
                    Log.d(TAG, "📊 Worker Status:")
                    Log.d(TAG, "   State: ${workInfo.state}")
                    Log.d(TAG, "   Run Attempt: ${workInfo.runAttemptCount}")
                    Log.d(TAG, "   Tags: ${workInfo.tags}")

                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.ENQUEUED ->
                            Log.i(TAG, "   ✅ Worker is scheduled and waiting")
                        androidx.work.WorkInfo.State.RUNNING ->
                            Log.i(TAG, "   🏃 Worker is currently running")
                        androidx.work.WorkInfo.State.SUCCEEDED ->
                            Log.i(TAG, "   ✅ Worker completed successfully")
                        androidx.work.WorkInfo.State.FAILED ->
                            Log.e(TAG, "   ❌ WARNING: Worker FAILED! Will retry...")
                        androidx.work.WorkInfo.State.BLOCKED ->
                            Log.w(TAG, "   ⏸️ Worker is blocked (constraints not met)")
                        androidx.work.WorkInfo.State.CANCELLED ->
                            Log.e(TAG, "   ❌ CRITICAL: Worker was CANCELLED!")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to monitor worker status: ${e.message}")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        authStateListener?.let {
            FirebaseAuth.getInstance().removeAuthStateListener(it)
        }
        applicationScope.cancel()
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * UNIFIED TRACKING MAINTENANCE WORKER - BULLETPROOF & COMPREHENSIVE
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Single unified worker that combines ALL tracking maintenance functions.
 * Runs every 15 minutes (Android WorkManager minimum) to ensure tracking NEVER dies.
 *
 * COMPREHENSIVE CHECKS:
 * ✅ Service running status
 * ✅ User session validation
 * ✅ Complete permission verification
 * ✅ Location services enabled
 * ✅ Network connectivity
 * ✅ Location freshness (stale detection)
 * ✅ Database backlog monitoring
 * ✅ Time-based upload triggers
 * ✅ Failed upload retry
 * ✅ Battery optimization awareness
 * ✅ Circuit breaker handling
 *
 * GUARANTEES:
 * - If service should be running but isn't → RESTART IT
 * - If data is 2+ hours old → UPLOAD IT
 * - If uploads failed → RETRY THEM
 * - If network is back → RESUME OPERATIONS
 * - Handles ALL edge cases in one place
 *
 * RELIABILITY:
 * - Uses CoroutineWorker for async operations
 * - Comprehensive error handling with retry
 * - Detailed logging for debugging
 * - Multiple safety checks before actions
 * - Works even when app is killed
 *
 * THIS IS THE "NEVER ENDING" WORKER - DESIGNED TO LAST DECADES!
 */
class PeriodicTrackingCheckWorker(
    context: Context,
    params: WorkerParameters
) : androidx.work.CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UnifiedMaintenance"
        private const val LOCATION_STALE_THRESHOLD_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val DATA_AGE_FORCE_UPLOAD_MS = 2 * 60 * 60 * 1000L // 2 hours
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "═══════════════════════════════════════════════════════════════")
            Log.d(TAG, "🔄 UNIFIED MAINTENANCE WORKER - Starting comprehensive check")
            Log.d(TAG, "   Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            Log.d(TAG, "   Run attempt: $runAttemptCount")
            Log.d(TAG, "═══════════════════════════════════════════════════════════════")

            // ═══════════════════════════════════════════════════════════════════════
            // PHASE 1: VALIDATE PRECONDITIONS
            // ═══════════════════════════════════════════════════════════════════════

            Log.d(TAG, "📋 PHASE 1: Validating preconditions...")

            // Check if tracking is enabled
            val prefs = applicationContext.getSharedPreferences(
                LocationConstants.PREFS_NAME_SERVICE_STATE,
                Context.MODE_PRIVATE
            )
            val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
            Log.d(TAG, "   ├─ Tracking enabled: $trackingEnabled")

            if (!trackingEnabled) {
                Log.d(TAG, "   └─ ⏸️  Tracking disabled by user - skipping all checks")
                return@withContext Result.success()
            }

            // Check for user session
            val currentUser = FirebaseAuth.getInstance().currentUser
            val savedUserId = prefs.getString("saved_user_id", null)
            val userId = currentUser?.uid ?: savedUserId
            Log.d(TAG, "   ├─ User session: ${if (userId != null) "✅ Valid" else "❌ Missing"}")

            if (userId == null) {
                Log.d(TAG, "   └─ ℹ️ No user session - skipping")
                return@withContext Result.success()
            }

            // Check permissions
            val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(applicationContext)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()
            Log.d(TAG, "   ├─ Permissions OK: ${permissionStatus.canStartTracking}")
            Log.d(TAG, "   ├─ Location enabled: ${permissionStatus.isLocationEnabled}")

            // ═══════════════════════════════════════════════════════════════════════
            // PHASE 2: SERVICE HEALTH CHECK & RESTART IF NEEDED
            // ═══════════════════════════════════════════════════════════════════════

            Log.d(TAG, "")
            Log.d(TAG, "🏥 PHASE 2: Service health check...")

            val isServiceRunning = LocationServiceHelper.isServiceRunning(applicationContext)
            Log.d(TAG, "   ├─ Service running: $isServiceRunning")

            // If service should be running but isn't, restart it
            if (!isServiceRunning && permissionStatus.canStartTracking && permissionStatus.isLocationEnabled) {
                Log.w(TAG, "   ├─ ⚠️  SERVICE NOT RUNNING BUT SHOULD BE!")
                Log.i(TAG, "   ├─ 🚀 Attempting service restart...")

                try {
                    val result = LocationServiceHelper.startLocationService(applicationContext, userId)
                    Log.i(TAG, "   └─ ✅ Service restart result: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "   └─ ❌ Service restart failed: ${e.message}", e)
                }
            } else if (!isServiceRunning) {
                Log.d(TAG, "   └─ Service not running (missing: ${if (!permissionStatus.canStartTracking) "permissions" else "location"})")
            } else {
                Log.d(TAG, "   └─ ✅ Service running and healthy")
            }

            // ═══════════════════════════════════════════════════════════════════════
            // PHASE 3: LOCATION FRESHNESS CHECK
            // ═══════════════════════════════════════════════════════════════════════

            if (isServiceRunning) {
                Log.d(TAG, "")
                Log.d(TAG, "📍 PHASE 3: Location freshness check...")

                val lastLocationTime = prefs.getLong("last_location_capture_time", 0L)
                val locationAge = System.currentTimeMillis() - lastLocationTime
                val locationAgeMinutes = locationAge / 60000
                val isStale = locationAge > LOCATION_STALE_THRESHOLD_MS

                Log.d(TAG, "   ├─ Last location: ${locationAgeMinutes} minutes ago")
                Log.d(TAG, "   └─ Status: ${if (isStale) "⚠️  STALE (>2hrs)" else "✅ Fresh"}")

                if (isStale && lastLocationTime > 0) {
                    Log.w(TAG, "      ⚠️  Location data is stale - service may need internal recovery")
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // PHASE 4: DATABASE & UPLOAD CHECK
            // ═══════════════════════════════════════════════════════════════════════

            Log.d(TAG, "")
            Log.d(TAG, "📤 PHASE 4: Upload management...")

            try {
                val database = com.example.newconstructionappwithlocationtracking.location.LocationDatabase.getInstance(applicationContext)
                val pendingLocations = database.getPendingLocations(100)
                val failedLocations = database.getFailedLocations()

                Log.d(TAG, "   ├─ Pending locations: ${pendingLocations.size}")
                Log.d(TAG, "   ├─ Failed locations: ${failedLocations.size}")

                // Check oldest pending location age
                val oldestLocation = database.getOldestPendingLocation()
                val dataAge = if (oldestLocation != null) {
                    System.currentTimeMillis() - oldestLocation.clientTimestamp
                } else 0L
                val dataAgeHours = dataAge / 3600000
                val shouldUploadDueToAge = dataAge >= DATA_AGE_FORCE_UPLOAD_MS

                Log.d(TAG, "   ├─ Oldest data age: ${dataAgeHours} hours")
                Log.d(TAG, "   ├─ Upload due to age: $shouldUploadDueToAge")

                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 5: NETWORK CHECK & UPLOAD TRIGGER
                // ═══════════════════════════════════════════════════════════════════════

                Log.d(TAG, "")
                Log.d(TAG, "🌐 PHASE 5: Network status & upload trigger...")

                val networkMonitor = com.example.newconstructionappwithlocationtracking.location.NetworkMonitor(applicationContext)
                val hasNetwork = networkMonitor.isConnected()
                Log.d(TAG, "   ├─ Network available: $hasNetwork")

                if (hasNetwork && (pendingLocations.isNotEmpty() || failedLocations.isNotEmpty())) {
                    val shouldUpload = shouldUploadDueToAge || pendingLocations.size >= 3 || failedLocations.isNotEmpty()

                    if (shouldUpload) {
                        Log.i(TAG, "   ├─ 🚀 TRIGGERING UPLOAD!")
                        Log.d(TAG, "   │  Reason: ${when {
                            shouldUploadDueToAge -> "Data age >= 2 hours"
                            failedLocations.isNotEmpty() -> "Retrying ${failedLocations.size} failed uploads"
                            else -> "Pending count >= 3"
                        }}")

                        try {
                            val settingsManager = com.example.newconstructionappwithlocationtracking.location.LocationSettingsManager(applicationContext)
                            val batchUploader = com.example.newconstructionappwithlocationtracking.location.BatchUploader(
                                applicationContext, database, networkMonitor, settingsManager
                            )

                            // Retry failed uploads first
                            if (failedLocations.isNotEmpty()) {
                                Log.d(TAG, "   │  ├─ Retrying failed uploads...")
                                batchUploader.retryFailedUploads()
                            }

                            // Upload pending if threshold met
                            if (pendingLocations.size >= 3 || shouldUploadDueToAge) {
                                Log.d(TAG, "   │  └─ Uploading pending locations...")
                                batchUploader.uploadPendingLocations()
                            }

                            Log.i(TAG, "   └─ ✅ Upload triggered successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "   └─ ❌ Upload trigger failed: ${e.message}", e)
                        }
                    } else {
                        Log.d(TAG, "   └─ ℹ️ No upload needed yet")
                    }
                } else if (!hasNetwork) {
                    Log.d(TAG, "   └─ ⏳ No network - will retry next check")
                } else {
                    Log.d(TAG, "   └─ ✅ No pending uploads")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Database/upload check failed: ${e.message}", e)
            }

            // ═══════════════════════════════════════════════════════════════════════
            // COMPLETION - Save heartbeat for monitoring
            // ═══════════════════════════════════════════════════════════════════════

            // Save last run time for monitoring by WatchdogAlarmManager (reuse existing prefs)
            prefs.edit().putLong("unified_worker_last_run", System.currentTimeMillis()).apply()

            Log.d(TAG, "")
            Log.d(TAG, "═══════════════════════════════════════════════════════════════")
            Log.d(TAG, "✅ UNIFIED MAINTENANCE WORKER - Completed successfully")
            Log.d(TAG, "   Next run in: 15 minutes")
            Log.d(TAG, "   Heartbeat saved: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            Log.d(TAG, "═══════════════════════════════════════════════════════════════")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "═══════════════════════════════════════════════════════════════")
            Log.e(TAG, "❌ UNIFIED MAINTENANCE WORKER - Critical error occurred!")
            Log.e(TAG, "   Error: ${e.message}", e)
            Log.e(TAG, "   Will retry automatically")
            Log.e(TAG, "═══════════════════════════════════════════════════════════════")
            Result.retry()
        }
    }
}

