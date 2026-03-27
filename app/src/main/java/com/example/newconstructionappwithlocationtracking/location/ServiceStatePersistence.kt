/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * SERVICE STATE PERSISTENCE - SURVIVES PROCESS DEATH
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Provides robust state persistence that survives:
 * - Process death
 * - Service crashes
 * - App force stops
 * - Device reboots
 *
 * WHY THIS IS ESSENTIAL:
 * - In-memory state is lost when process dies
 * - SharedPreferences with commit() provides synchronous writes
 * - State must be available to all recovery mechanisms
 *
 * STORED STATE:
 * - Tracking enabled/disabled
 * - User session info
 * - Last known service status
 * - Failure tracking
 * - Watchdog state
 * - Network state
 * - Last location time
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import android.content.SharedPreferences
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants

/**
 * ServiceStatePersistence - Robust state persistence across process deaths
 */
object ServiceStatePersistence {

    private const val TAG = "ServiceStatePersistence"

    // Keys
    private const val KEY_TRACKING_SHOULD_BE_RUNNING = "tracking_should_be_running"
    private const val KEY_SERVICE_WAS_RUNNING = "service_was_running"
    private const val KEY_LAST_SERVICE_STOP_TIME = "last_service_stop_time"
    private const val KEY_LAST_SERVICE_STOP_REASON = "last_service_stop_reason"
    private const val KEY_UNEXPECTED_STOP = "unexpected_stop"
    private const val KEY_PROCESS_START_COUNT = "process_start_count"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    private const val KEY_RESURRECTION_COUNT = "resurrection_count"
    private const val KEY_LAST_RESURRECTION_TIME = "last_resurrection_time"
    private const val KEY_SERVICE_START_TIME = "service_start_time"
    private const val KEY_TOTAL_RESURRECTIONS = "total_resurrections"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // TRACKING STATE
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Mark that tracking SHOULD be running
     * Called when user enables tracking or when service successfully starts
     */
    fun setTrackingShouldBeRunning(context: Context, shouldRun: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_TRACKING_SHOULD_BE_RUNNING, shouldRun)
            .commit() // Use commit() for synchronous write
        android.util.Log.d(TAG, "📝 Tracking should be running: $shouldRun")
    }

    /**
     * Check if tracking should be running
     */
    fun shouldTrackingBeRunning(context: Context): Boolean {
        // Must have both: user enabled AND tracking should run
        val prefs = getPrefs(context)
        val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
        val shouldBeRunning = prefs.getBoolean(KEY_TRACKING_SHOULD_BE_RUNNING, false)
        return trackingEnabled && shouldBeRunning
    }

    /**
     * Mark service as currently running
     * Called in service onCreate/onStartCommand
     */
    fun markServiceRunning(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_SERVICE_WAS_RUNNING, true)
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .putBoolean(KEY_UNEXPECTED_STOP, false)
            .commit()
        android.util.Log.d(TAG, "📝 Service marked as running")
    }

    /**
     * Mark service as stopped (intentionally)
     */
    fun markServiceStopped(context: Context, reason: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_SERVICE_WAS_RUNNING, false)
            .putLong(KEY_LAST_SERVICE_STOP_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_SERVICE_STOP_REASON, reason)
            .putBoolean(KEY_UNEXPECTED_STOP, false)
            .commit()
        android.util.Log.d(TAG, "📝 Service marked as stopped: $reason")
    }

    /**
     * Check if service was running before (for crash detection)
     */
    fun wasServiceRunning(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_WAS_RUNNING, false)
    }

    /**
     * Record heartbeat - called periodically by service
     */
    fun recordHeartbeat(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply() // Can use apply() for heartbeats
    }

    /**
     * Get last heartbeat time
     */
    fun getLastHeartbeat(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_HEARTBEAT, 0L)
    }

    /**
     * Check if service died unexpectedly (crash detection)
     * Call this on process start
     */
    fun detectUnexpectedDeath(context: Context): Boolean {
        val prefs = getPrefs(context)

        // Was service supposed to be running?
        val wasRunning = prefs.getBoolean(KEY_SERVICE_WAS_RUNNING, false)
        val shouldBeRunning = prefs.getBoolean(KEY_TRACKING_SHOULD_BE_RUNNING, false)

        if (wasRunning && shouldBeRunning) {
            // Service was running and should still be running
            // But we're in a fresh process start - this means crash/kill
            android.util.Log.w(TAG, "⚠️ UNEXPECTED SERVICE DEATH DETECTED!")
            android.util.Log.w(TAG, "   Service was running and should be running, but process restarted")

            // Mark as unexpected stop
            prefs.edit()
                .putBoolean(KEY_UNEXPECTED_STOP, true)
                .putLong(KEY_LAST_SERVICE_STOP_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_SERVICE_STOP_REASON, "UNEXPECTED_PROCESS_DEATH")
                .commit()

            return true
        }

        return false
    }

    /**
     * Increment process start count (for tracking restarts)
     */
    fun incrementProcessStartCount(context: Context): Int {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_PROCESS_START_COUNT, 0) + 1
        prefs.edit().putInt(KEY_PROCESS_START_COUNT, count).apply()
        return count
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // USER SESSION
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Save user session info
     */
    fun saveUserSession(context: Context, userId: String) {
        getPrefs(context).edit()
            .putString("saved_user_id", userId)
            .putBoolean("has_valid_session", true)
            .putLong("session_saved_time", System.currentTimeMillis())
            .commit()
        android.util.Log.d(TAG, "📝 User session saved: $userId")
    }

    /**
     * Get saved user ID
     */
    fun getSavedUserId(context: Context): String? {
        return getPrefs(context).getString("saved_user_id", null)
    }

    /**
     * Clear user session
     */
    fun clearUserSession(context: Context) {
        getPrefs(context).edit()
            .remove("saved_user_id")
            .putBoolean("has_valid_session", false)
            .commit()
        android.util.Log.d(TAG, "📝 User session cleared")
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Get complete state for diagnostics
     */
    fun getCompleteState(context: Context): Map<String, Any?> {
        val prefs = getPrefs(context)
        return mapOf(
            "trackingEnabled" to prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true),
            "trackingShouldBeRunning" to prefs.getBoolean(KEY_TRACKING_SHOULD_BE_RUNNING, false),
            "serviceWasRunning" to prefs.getBoolean(KEY_SERVICE_WAS_RUNNING, false),
            "unexpectedStop" to prefs.getBoolean(KEY_UNEXPECTED_STOP, false),
            "lastStopTime" to prefs.getLong(KEY_LAST_SERVICE_STOP_TIME, 0L),
            "lastStopReason" to prefs.getString(KEY_LAST_SERVICE_STOP_REASON, null),
            "lastHeartbeat" to prefs.getLong(KEY_LAST_HEARTBEAT, 0L),
            "processStartCount" to prefs.getInt(KEY_PROCESS_START_COUNT, 0),
            "savedUserId" to prefs.getString("saved_user_id", null),
            "lastLocationTime" to prefs.getLong("last_location_time", 0L),
            "watchdogActive" to prefs.getBoolean("watchdog_active", false),
            "networkLossTime" to prefs.getLong("network_loss_time", 0L)
        )
    }

    /**
     * Log complete state for debugging
     */
    fun logCompleteState(context: Context) {
        val state = getCompleteState(context)
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "📊 COMPLETE SERVICE STATE:")
        state.forEach { (key, value) ->
            android.util.Log.d(TAG, "   $key: $value")
        }
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // RESURRECTION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Record resurrection attempt
     */
    fun recordResurrection(context: Context) {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_RESURRECTION_COUNT, 0) + 1
        val totalCount = prefs.getInt(KEY_TOTAL_RESURRECTIONS, 0) + 1

        prefs.edit()
            .putInt(KEY_RESURRECTION_COUNT, count)
            .putInt(KEY_TOTAL_RESURRECTIONS, totalCount)
            .putLong(KEY_LAST_RESURRECTION_TIME, System.currentTimeMillis())
            .commit()

        android.util.Log.w(TAG, "🔄 RESURRECTION #$count recorded (total: $totalCount)")
    }

    /**
     * Reset resurrection count (call when service runs successfully for extended period)
     */
    fun resetResurrectionCount(context: Context) {
        getPrefs(context).edit()
            .putInt(KEY_RESURRECTION_COUNT, 0)
            .apply()
    }

    /**
     * Get resurrection count
     */
    fun getResurrectionCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_RESURRECTION_COUNT, 0)
    }

    /**
     * Get total resurrections ever
     */
    fun getTotalResurrections(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_RESURRECTIONS, 0)
    }

    /**
     * Get last resurrection time
     */
    fun getLastResurrectionTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_RESURRECTION_TIME, 0L)
    }

    /**
     * Mark service start time
     */
    fun markServiceStartTime(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_SERVICE_START_TIME, System.currentTimeMillis())
            .commit()
    }

    /**
     * Get service uptime in milliseconds
     */
    fun getServiceUptime(context: Context): Long {
        val startTime = getPrefs(context).getLong(KEY_SERVICE_START_TIME, 0L)
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0L
    }

    /**
     * Check if service has been stable (running > 10 minutes without restart)
     * If stable, reset resurrection count
     */
    fun checkAndResetIfStable(context: Context) {
        val uptime = getServiceUptime(context)
        if (uptime > 10 * 60 * 1000L) { // 10 minutes
            val resCount = getResurrectionCount(context)
            if (resCount > 0) {
                resetResurrectionCount(context)
                android.util.Log.d(TAG, "✅ Service stable for ${uptime/60000}min - resurrection count reset")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // FCM WAKE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    private const val KEY_LAST_FCM_WAKE_TIME = "last_fcm_wake_time"
    private const val KEY_LAST_FCM_WAKE_REQUEST_ID = "last_fcm_wake_request_id"
    private const val KEY_FCM_WAKE_COUNT = "fcm_wake_count"

    /**
     * Record FCM wake event
     */
    fun recordFcmWake(context: Context, requestId: String) {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_FCM_WAKE_COUNT, 0) + 1

        prefs.edit()
            .putLong(KEY_LAST_FCM_WAKE_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_FCM_WAKE_REQUEST_ID, requestId)
            .putInt(KEY_FCM_WAKE_COUNT, count)
            .commit()

        android.util.Log.d(TAG, "📱 FCM wake recorded: requestId=$requestId, total wakes=$count")
    }

    /**
     * Get last FCM wake time
     */
    fun getLastFcmWakeTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_FCM_WAKE_TIME, 0L)
    }

    /**
     * Get total FCM wake count
     */
    fun getFcmWakeCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_FCM_WAKE_COUNT, 0)
    }
}
