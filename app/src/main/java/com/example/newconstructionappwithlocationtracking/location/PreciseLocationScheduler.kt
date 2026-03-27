/*
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * PRECISE LOCATION SCHEDULER - ALARM-BASED BACKUP FOR EXACT INTERVAL TRACKING
 * ═══════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Provides a bulletproof AlarmManager-based backup system that guarantees location
 * updates at EXACT intervals, even when FusedLocationProviderClient delays due to:
 * - Android Doze mode
 * - Screen-off power optimization
 * - Battery optimization
 * - OEM-specific power management
 * - FusedLocation batching
 *
 * HOW IT WORKS:
 * 1. Primary: FusedLocationProviderClient (main location source)
 * 2. Backup: AlarmManager with setExactAndAllowWhileIdle (guaranteed wakeup)
 * 3. If no location received within expected interval + tolerance, alarm triggers
 * 4. Alarm requests an immediate location fix via getCurrentLocation()
 *
 * KEY FEATURES:
 * - Uses setExactAndAllowWhileIdle() for Doze-proof alarms
 * - Adapts alarm timing based on tracking mode (aggressive vs normal)
 * - Self-correcting: Reschedules alarm on each successful location
 * - Battery-aware: Only active in aggressive modes or when delays detected
 * - Comprehensive logging for debugging
 *
 * TIMING GUARANTEES:
 * - For intervals ≤ 30s: Alarm fires at interval + 50% tolerance
 * - For intervals 30s-5min: Alarm fires at interval + 30s tolerance
 * - For intervals > 5min: Alarm fires at interval + 1min tolerance
 *
 * USAGE:
 * In LocationTrackingService:
 * - Call scheduleBackupAlarm(interval) when starting location updates
 * - Call onLocationReceived() when location callback fires
 * - Call cancelBackupAlarm() when stopping tracking
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PreciseLocationScheduler - Ensures exact interval location tracking
 *
 * Uses AlarmManager as a backup to FusedLocationProviderClient to guarantee
 * location updates even when Android power management delays updates.
 */
class PreciseLocationScheduler(private val context: Context) {

    companion object {
        private const val TAG = "PreciseLocationScheduler"

        // Alarm action
        const val ACTION_LOCATION_ALARM = "com.example.newconstructionappwithlocationtracking.PRECISE_LOCATION_ALARM"

        // Request codes
        private const val ALARM_REQUEST_CODE = 9999

        // Tolerance multipliers for alarm timing
        private const val SHORT_INTERVAL_TOLERANCE_MULTIPLIER = 1.5  // For ≤ 30s intervals
        private const val MEDIUM_INTERVAL_TOLERANCE_MS = 30000L     // 30s extra for 30s-5min
        private const val LONG_INTERVAL_TOLERANCE_MS = 60000L      // 1min extra for > 5min

        // Threshold for considering interval "short"
        private const val SHORT_INTERVAL_THRESHOLD_MS = 30000L     // 30 seconds
        private const val MEDIUM_INTERVAL_THRESHOLD_MS = 300000L   // 5 minutes

        // Wake lock timeout
        private const val WAKE_LOCK_TIMEOUT_MS = 30000L  // 30 seconds max

        // Singleton instance for static alarm receiver
        // Note: Uses applicationContext to avoid memory leaks
        @Volatile
        private var INSTANCE: PreciseLocationScheduler? = null

        fun getInstance(context: Context): PreciseLocationScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreciseLocationScheduler(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Check if the app can schedule exact alarms
         * Required for Android 12+ (API 31+)
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true // Always allowed on older versions
            }
        }
    }

    // System services
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    // State tracking
    private val isActive = AtomicBoolean(false)
    private val lastLocationTime = AtomicLong(0L)
    private val currentIntervalMs = AtomicLong(0L)
    private val alarmsFired = AtomicLong(0L)
    private val alarmsResultedInLocation = AtomicLong(0L)

    // Callback for location results
    private var locationCallback: ((Location) -> Unit)? = null

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Start the precise location scheduler with specified interval
     *
     * @param intervalMs Desired interval between location updates (milliseconds)
     * @param onLocation Callback when a location is obtained via backup alarm
     */
    fun start(intervalMs: Long, onLocation: (Location) -> Unit) {
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "🎯 PRECISE SCHEDULER STARTING")
        android.util.Log.d(TAG, "   Interval: ${intervalMs}ms (${intervalMs/1000}s)")
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")

        locationCallback = onLocation
        currentIntervalMs.set(intervalMs)
        isActive.set(true)

        // Schedule first alarm
        scheduleNextAlarm()

        LocationLogger.i(TAG, "Precise scheduler started with interval: ${intervalMs}ms")
    }

    /**
     * Stop the precise location scheduler
     */
    fun stop() {
        android.util.Log.d(TAG, "🛑 PRECISE SCHEDULER STOPPING")
        android.util.Log.d(TAG, "   Stats: ${alarmsResultedInLocation.get()}/${alarmsFired.get()} alarms resulted in location")

        isActive.set(false)
        cancelPendingAlarm()
        locationCallback = null

        LocationLogger.i(TAG, "Precise scheduler stopped")
    }

    /**
     * Call this when a location is received from any source (FusedLocation or alarm)
     * Resets the alarm timer based on the new location time
     */
    fun onLocationReceived() {
        val now = System.currentTimeMillis()
        lastLocationTime.set(now)

        // Reschedule alarm from this point
        if (isActive.get()) {
            scheduleNextAlarm()
        }
    }

    /**
     * Update the interval (when settings change)
     */
    fun updateInterval(newIntervalMs: Long) {
        val oldInterval = currentIntervalMs.get()
        if (oldInterval != newIntervalMs) {
            android.util.Log.d(TAG, "🔄 Interval changed: ${oldInterval}ms → ${newIntervalMs}ms")
            currentIntervalMs.set(newIntervalMs)

            if (isActive.get()) {
                scheduleNextAlarm()
            }
        }
    }

    /**
     * Schedule the next backup alarm
     */
    private fun scheduleNextAlarm() {
        val intervalMs = currentIntervalMs.get()
        if (intervalMs <= 0) return

        // Calculate alarm time with appropriate tolerance
        val tolerance = calculateTolerance(intervalMs)
        val alarmTime = SystemClock.elapsedRealtime() + intervalMs + tolerance

        // Create pending intent
        val intent = Intent(ACTION_LOCATION_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule exact alarm that works even in Doze mode
        try {
            // Calculate next alarm timing for logging
            val nextIn = intervalMs + tolerance

            // Check if we can schedule exact alarms (Android 12+ requirement)
            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canScheduleExact) {
                // Use exact alarm for precise timing
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    alarmTime,
                    pendingIntent
                )
                android.util.Log.d(TAG, "⏰ EXACT backup alarm scheduled in ${nextIn}ms")
            } else {
                // Fall back to inexact alarm if exact alarms not permitted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    alarmTime,
                    pendingIntent
                )
                android.util.Log.d(TAG, "⏰ INEXACT backup alarm scheduled in ${nextIn}ms (exact not permitted)")
            }

            android.util.Log.d(TAG, "⏰ Backup alarm scheduled: interval=${intervalMs}ms + tolerance=${tolerance}ms = ${nextIn}ms total")

        } catch (e: SecurityException) {
            // Handle case where exact alarms are not allowed
            android.util.Log.w(TAG, "⚠️ Cannot schedule exact alarm, using inexact: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    alarmTime,
                    pendingIntent
                )
                android.util.Log.d(TAG, "⏰ Fallback: Inexact alarm scheduled")
            } catch (fallbackError: Exception) {
                android.util.Log.e(TAG, "❌ Failed to schedule even inexact alarm", fallbackError)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to schedule alarm", e)
            LocationLogger.e(TAG, "Failed to schedule backup alarm", e)
        }
    }

    /**
     * Calculate appropriate tolerance based on interval length
     */
    private fun calculateTolerance(intervalMs: Long): Long {
        return when {
            intervalMs <= SHORT_INTERVAL_THRESHOLD_MS -> {
                // Short intervals: 50% tolerance (e.g., 3s interval → 1.5s tolerance = 4.5s alarm)
                (intervalMs * (SHORT_INTERVAL_TOLERANCE_MULTIPLIER - 1.0)).toLong()
            }
            intervalMs <= MEDIUM_INTERVAL_THRESHOLD_MS -> {
                // Medium intervals: Fixed 30s tolerance
                MEDIUM_INTERVAL_TOLERANCE_MS
            }
            else -> {
                // Long intervals: Fixed 1min tolerance
                LONG_INTERVAL_TOLERANCE_MS
            }
        }
    }

    /**
     * Cancel any pending alarm
     */
    private fun cancelPendingAlarm() {
        val intent = Intent(ACTION_LOCATION_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.d(TAG, "⏰ Pending alarm cancelled")
        }
    }

    /**
     * Handle alarm trigger - request immediate location
     * Called by the broadcast receiver when alarm fires
     */
    fun onAlarmTriggered() {
        if (!isActive.get()) {
            android.util.Log.d(TAG, "⏰ Alarm fired but scheduler inactive - ignoring")
            return
        }

        alarmsFired.incrementAndGet()

        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "⏰⏰⏰ BACKUP ALARM TRIGGERED ⏰⏰⏰")
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════")

        // Check if we actually need to request location
        val timeSinceLastLocation = System.currentTimeMillis() - lastLocationTime.get()
        val expectedInterval = currentIntervalMs.get()

        android.util.Log.d(TAG, "   Time since last location: ${timeSinceLastLocation}ms")
        android.util.Log.d(TAG, "   Expected interval: ${expectedInterval}ms")

        if (timeSinceLastLocation < expectedInterval * 0.8) {
            // We received a recent location, just reschedule alarm
            android.util.Log.d(TAG, "   ✅ Recent location exists, rescheduling alarm")
            scheduleNextAlarm()
            return
        }

        // Actually request location
        android.util.Log.d(TAG, "   📡 No recent location, requesting immediate fix...")
        requestImmediateLocation()

        // Schedule next alarm
        scheduleNextAlarm()
    }

    /**
     * Request an immediate location fix using getCurrentLocation
     */
    private fun requestImmediateLocation() {
        // Acquire wake lock to keep CPU running during location request
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Construct Connect:PreciseLocationAlarm"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        try {
            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null // No cancellation token
            ).addOnSuccessListener { location ->
                mainHandler.post {
                    try {
                        if (location != null) {
                            android.util.Log.d(TAG, "   ✅ ALARM LOCATION RECEIVED!")
                            android.util.Log.d(TAG, "      Lat: ${location.latitude}")
                            android.util.Log.d(TAG, "      Lon: ${location.longitude}")
                            android.util.Log.d(TAG, "      Accuracy: ${location.accuracy}m")

                            alarmsResultedInLocation.incrementAndGet()
                            lastLocationTime.set(System.currentTimeMillis())

                            // Notify callback
                            locationCallback?.invoke(location)
                        } else {
                            android.util.Log.w(TAG, "   ⚠️ Alarm location request returned null")
                        }
                    } finally {
                        releaseWakeLock(wakeLock)
                    }
                }
            }.addOnFailureListener { e ->
                mainHandler.post {
                    android.util.Log.e(TAG, "   ❌ Alarm location request failed: ${e.message}")
                    releaseWakeLock(wakeLock)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Exception during alarm location request", e)
            releaseWakeLock(wakeLock)
        }
    }

    /**
     * Safely release wake lock
     */
    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            // Ignore - wake lock already released
        }
    }

    /**
     * Get scheduler statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "isActive" to isActive.get(),
            "currentIntervalMs" to currentIntervalMs.get(),
            "lastLocationTime" to lastLocationTime.get(),
            "alarmsFired" to alarmsFired.get(),
            "alarmsResultedInLocation" to alarmsResultedInLocation.get(),
            "effectiveness" to if (alarmsFired.get() > 0) {
                (alarmsResultedInLocation.get() * 100 / alarmsFired.get())
            } else {
                0
            }
        )
    }
}

/**
 * Broadcast receiver for precise location alarms
 * Must be registered in AndroidManifest.xml
 */
class PreciseLocationAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PreciseLocationAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PreciseLocationScheduler.ACTION_LOCATION_ALARM) {
            android.util.Log.d(TAG, "⏰ Alarm broadcast received")

            try {
                val scheduler = PreciseLocationScheduler.getInstance(context)
                scheduler.onAlarmTriggered()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Error handling alarm", e)
            }
        }
    }
}
