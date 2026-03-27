/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * WATCHDOG ALARM MANAGER - 5-MINUTE DOZE-PROOF HEALTH CHECKS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Provides a FASTER watchdog than WorkManager's 15-minute minimum using AlarmManager.
 * Uses setExactAndAllowWhileIdle() to work even in Doze mode.
 *
 * WHY THIS IS ESSENTIAL:
 * - WorkManager has a MINIMUM 15-minute interval (Android enforced)
 * - AlarmManager with setExactAndAllowWhileIdle() can run every 5 minutes even in Doze
 * - This catches service deaths much faster than WorkManager alone
 *
 * HOW IT WORKS:
 * 1. Schedule exact alarm for 5 minutes from now
 * 2. When alarm fires, check if service is healthy
 * 3. If not healthy, restart service
 * 4. Reschedule alarm for next 5 minutes
 * 5. Repeat forever
 *
 * DESIGN PRINCIPLES:
 * - Self-rescheduling (each alarm schedules the next one)
 * - Survives Doze mode with setExactAndAllowWhileIdle()
 * - Minimal battery impact (only runs every 5 minutes)
 * - Independent of service lifecycle (runs even if service is dead)
 * - Persists across reboots (via Boot Receiver scheduling)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WatchdogAlarmManager - Fast, Doze-proof health monitoring
 *
 * Uses AlarmManager to check service health every 5 minutes,
 * much faster than WorkManager's 15-minute minimum.
 */
object WatchdogAlarmManager {

    private const val TAG = "WatchdogAlarmManager"

    // Watchdog interval - 5 minutes is aggressive but still battery-friendly
    // Can be adjusted based on needs
    const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    // Alarm action
    const val ACTION_WATCHDOG_CHECK = "com.example.newconstructionappwithlocationtracking.WATCHDOG_CHECK"

    // Request code for PendingIntent
    private const val REQUEST_CODE_WATCHDOG = 5001

    // Track if watchdog is active
    private var isActive = false

    /**
     * Start the watchdog alarm system
     * Should be called when tracking starts
     */
    fun startWatchdog(context: Context) {
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.i(TAG, "🐕 STARTING BULLETPROOF WATCHDOG ALARM SYSTEM")
        android.util.Log.i(TAG, "   Interval: ${WATCHDOG_INTERVAL_MS / 60000} minutes")
        android.util.Log.i(TAG, "   Self-rescheduling: YES")
        android.util.Log.i(TAG, "   Doze-proof: YES (setExactAndAllowWhileIdle)")
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "Starting bulletproof watchdog with ${WATCHDOG_INTERVAL_MS / 60000}min interval")

        // CRITICAL: Verify SCHEDULE_EXACT_ALARM permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val canScheduleExact = alarmManager.canScheduleExactAlarms()

            if (!canScheduleExact) {
                android.util.Log.e(TAG, "❌ CRITICAL: SCHEDULE_EXACT_ALARM permission NOT granted!")
                android.util.Log.e(TAG, "   Watchdog will use inexact alarms (less reliable)")
                android.util.Log.e(TAG, "   App may not wake up reliably in Doze mode")
                LocationLogger.e(TAG, "SCHEDULE_EXACT_ALARM permission missing - reliability degraded")
            } else {
                android.util.Log.i(TAG, "✅ SCHEDULE_EXACT_ALARM permission granted")
            }
        }

        scheduleNextAlarm(context)
        isActive = true

        // Save state with detailed tracking
        context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("watchdog_active", true)
            .putLong("watchdog_started", System.currentTimeMillis())
            .putInt("watchdog_start_count",
                context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
                    .getInt("watchdog_start_count", 0) + 1)
            .apply()

        android.util.Log.i(TAG, "✅ Watchdog alarm scheduled and activated")
    }

    /**
     * Stop the watchdog alarm system
     * Should be called when tracking is intentionally stopped by user
     */
    fun stopWatchdog(context: Context) {
        android.util.Log.i(TAG, "🛑 STOPPING WATCHDOG ALARM SYSTEM")
        LocationLogger.i(TAG, "Stopping watchdog")

        cancelAlarm(context)
        isActive = false

        // Save state
        context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("watchdog_active", false)
            .apply()

        android.util.Log.i(TAG, "✅ Watchdog stopped")
    }

    /**
     * Schedule the next watchdog alarm
     */
    fun scheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(ACTION_WATCHDOG_CHECK).apply {
            setPackage(context.packageName)
            setClass(context, WatchdogAlarmReceiver::class.java)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WATCHDOG,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule for 5 minutes from now
        val triggerTime = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

        try {
            // Check if we can schedule exact alarms (Android 12+)
            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canScheduleExact) {
                // Use exact alarm that works in Doze mode
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                android.util.Log.d(TAG, "⏰ EXACT watchdog alarm scheduled for ${WATCHDOG_INTERVAL_MS / 60000}min")
            } else {
                // Fall back to inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                android.util.Log.d(TAG, "⏰ INEXACT watchdog alarm scheduled (exact not permitted)")
            }

            // Save next scheduled time
            context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
                .edit()
                .putLong("watchdog_next_check", System.currentTimeMillis() + WATCHDOG_INTERVAL_MS)
                .apply()

        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to schedule watchdog alarm", e)
            LocationLogger.e(TAG, "Failed to schedule watchdog", e)
        }
    }

    /**
     * Cancel any pending watchdog alarm
     */
    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(ACTION_WATCHDOG_CHECK).apply {
            setPackage(context.packageName)
            setClass(context, WatchdogAlarmReceiver::class.java)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WATCHDOG,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.d(TAG, "⏰ Watchdog alarm cancelled")
        }
    }

    /**
     * Check if watchdog should be active based on saved state
     */
    fun shouldWatchdogBeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        return prefs.getBoolean("watchdog_active", false)
    }

    /**
     * Get watchdog statistics
     */
    fun getStats(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        return mapOf(
            "isActive" to isActive,
            "startedAt" to prefs.getLong("watchdog_started", 0L),
            "nextCheck" to prefs.getLong("watchdog_next_check", 0L),
            "checkCount" to prefs.getInt("watchdog_check_count", 0),
            "restartCount" to prefs.getInt("watchdog_restart_count", 0)
        )
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * WATCHDOG ALARM RECEIVER - Handles watchdog alarm triggers
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * MUST be registered in AndroidManifest.xml:
 * <receiver android:name=".location.WatchdogAlarmReceiver"
 *           android:enabled="true"
 *           android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.example.newconstructionappwithlocationtracking.WATCHDOG_CHECK"/>
 *     </intent-filter>
 * </receiver>
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogAlarmReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WatchdogAlarmManager.ACTION_WATCHDOG_CHECK) {
            return
        }

        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.i(TAG, "🐕 WATCHDOG ALARM FIRED!")
        android.util.Log.i(TAG, "   Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "Watchdog check triggered")

        // Increment check count
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        val checkCount = prefs.getInt("watchdog_check_count", 0) + 1
        prefs.edit().putInt("watchdog_check_count", checkCount).apply()
        android.util.Log.d(TAG, "   Check #$checkCount")

        // CRITICAL: RESCHEDULE NEXT ALARM IMMEDIATELY BEFORE DOING WORK
        // This ensures even if health check crashes, next alarm is already scheduled
        android.util.Log.d(TAG, "🔄 RESCHEDULING next watchdog BEFORE health check...")
        WatchdogAlarmManager.scheduleNextAlarm(context)
        android.util.Log.d(TAG, "✅ Next alarm scheduled (bulletproof self-rescheduling)")

        // Use goAsync() because we need to do work in receiver
        val pendingResult = goAsync()

        scope.launch {
            try {
                performHealthCheck(context)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Watchdog check error", e)
                LocationLogger.e(TAG, "Watchdog check error", e)
            } finally {
                // NOTE: We already rescheduled at the beginning, so this is redundant
                // but keeping it for extra safety
                try {
                    WatchdogAlarmManager.scheduleNextAlarm(context)
                    android.util.Log.d(TAG, "⏰ Next watchdog scheduled (redundant safety)")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "⚠️ Failed to reschedule watchdog", e)
                }

                // Finish broadcast
                pendingResult.finish()
            }
        }
    }

    /**
     * Perform comprehensive health check and restart if needed
     */
    private suspend fun performHealthCheck(context: Context) {
        android.util.Log.d(TAG, "   ├─ Checking if tracking should be enabled...")

        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)

        // Check if tracking is supposed to be enabled
        val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
        if (!trackingEnabled) {
            android.util.Log.d(TAG, "   └─ Tracking disabled by user, skipping check")
            return
        }

        // Check for user session
        val savedUserId = prefs.getString("saved_user_id", null)
        val firebaseUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        }
        val userId = firebaseUser?.uid ?: savedUserId

        if (userId == null) {
            android.util.Log.d(TAG, "   └─ No user session, skipping check")
            return
        }

        android.util.Log.d(TAG, "   ├─ User ID: $userId")

        // Check permissions
        val permissionManager = LocationPermissionManager(context)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking) {
            android.util.Log.w(TAG, "   └─ Missing permissions: ${permissionStatus.missingCritical}")
            return
        }

        if (!permissionStatus.isLocationEnabled) {
            android.util.Log.w(TAG, "   └─ Location services disabled")
            return
        }

        android.util.Log.d(TAG, "   ├─ Permissions OK, location enabled")

        // ═══════════════════════════════════════════════════════════════════════
        // CHECK 1: Verify WorkManager worker is running (always check this)
        // ═══════════════════════════════════════════════════════════════════════

        android.util.Log.d(TAG, "   ├─ Checking WorkManager worker status...")
        val workerLastRun = prefs.getLong("unified_worker_last_run", 0L)
        val timeSinceWorkerRun = System.currentTimeMillis() - workerLastRun
        val workerAgeMinutes = timeSinceWorkerRun / 60000

        if (workerLastRun == 0L) {
            android.util.Log.w(TAG, "   │  ⚠️ Worker never run - this is first launch")
        } else if (timeSinceWorkerRun > 30 * 60 * 1000L) { // 30 minutes
            android.util.Log.e(TAG, "   │  ❌ CRITICAL: Worker hasn't run in ${workerAgeMinutes} minutes!")
            android.util.Log.e(TAG, "   │  This indicates WorkManager may have failed.")
            android.util.Log.e(TAG, "   │  Worker will auto-reschedule on next app restart.")
        } else {
            android.util.Log.d(TAG, "   │  ✅ Worker healthy (last run: ${workerAgeMinutes} min ago)")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // CHECK 2: Verify service is running
        // ═══════════════════════════════════════════════════════════════════════

        // Check if service is running
        val isRunning = LocationServiceHelper.isServiceRunning(context)
        android.util.Log.d(TAG, "   ├─ Service running: $isRunning")

        if (isRunning) {
            // Service is running, check if it's healthy
            val lastLocationTime = prefs.getLong("last_location_time", 0L)
            val timeSinceLastLocation = System.currentTimeMillis() - lastLocationTime

            android.util.Log.d(TAG, "   ├─ Last location: ${timeSinceLastLocation / 60000} minutes ago")

            // If last location is over 1 hour old, consider it stale
            if (lastLocationTime > 0 && timeSinceLastLocation > 60 * 60 * 1000L) {
                android.util.Log.w(TAG, "   ⚠️ Location is STALE (${timeSinceLastLocation / 60000} min)")
                // Don't restart, just log - service has its own recovery
            }

            android.util.Log.i(TAG, "   ✅ Service healthy")
        } else {
            // SERVICE IS NOT RUNNING - RESTART IT!
            android.util.Log.w(TAG, "   ❌ SERVICE NOT RUNNING - RESTARTING!")
            android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
            android.util.Log.i(TAG, "🚀 WATCHDOG RESTARTING LOCATION SERVICE")
            android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")

            // Increment restart count
            val restartCount = prefs.getInt("watchdog_restart_count", 0) + 1
            prefs.edit()
                .putInt("watchdog_restart_count", restartCount)
                .putLong("watchdog_last_restart", System.currentTimeMillis())
                .apply()

            android.util.Log.d(TAG, "   Restart #$restartCount by watchdog")

            // Use LocationServiceHelper to start (handles all validation)
            val result = LocationServiceHelper.startLocationService(context, userId)

            when (result) {
                is LocationServiceHelper.ServiceStartResult.Success -> {
                    android.util.Log.i(TAG, "   ✅ Service restarted successfully!")
                    LocationLogger.i(TAG, "Watchdog restart successful")
                }
                is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                    android.util.Log.i(TAG, "   ✅ Service already running")
                }
                is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                    android.util.Log.w(TAG, "   ⏳ Circuit breaker active, will retry later")
                }
                else -> {
                    android.util.Log.w(TAG, "   ⚠️ Restart result: $result")
                }
            }
        }
    }
}
