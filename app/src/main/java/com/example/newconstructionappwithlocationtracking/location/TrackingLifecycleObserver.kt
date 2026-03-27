/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * TRACKING LIFECYCLE OBSERVER - APP LIFECYCLE MONITORING FOR CRASH RECOVERY
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Monitors app lifecycle to detect unexpected deaths and ensure tracking recovery.
 * Called from Application.onCreate() to check for crashes and restart tracking.
 *
 * KEY FEATURES:
 * - Detects process start (for crash recovery)
 * - Triggers recovery mechanisms when appropriate
 * - Works with ServiceStatePersistence for crash detection
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context

/**
 * TrackingLifecycleObserver - Monitors app lifecycle for crash recovery
 *
 * Simplified implementation that doesn't require ProcessLifecycleOwner.
 * Called directly from Application.onCreate() to handle crash detection.
 */
object TrackingLifecycleObserver {

    private const val TAG = "TrackingLifecycle"

    /**
     * Initialize and perform crash detection
     * Should be called from Application.onCreate()
     */
    fun initialize(context: Context) {
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "🚀 TrackingLifecycleObserver.initialize()")
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")

        // Increment process start count
        val processCount = ServiceStatePersistence.incrementProcessStartCount(context)
        android.util.Log.d(TAG, "   Process start count: $processCount")

        // Check for unexpected death (crash recovery)
        if (ServiceStatePersistence.detectUnexpectedDeath(context)) {
            android.util.Log.w(TAG, "⚠️ UNEXPECTED SERVICE DEATH DETECTED!")
            android.util.Log.w(TAG, "   Tracking was running before process died")
            android.util.Log.w(TAG, "   Triggering recovery...")

            // Schedule immediate recovery
            triggerCrashRecovery(context)
        }

        // Log current state
        ServiceStatePersistence.logCompleteState(context)

        android.util.Log.i(TAG, "✅ TrackingLifecycleObserver initialized")
    }

    /**
     * Trigger crash recovery - called when unexpected death detected
     */
    private fun triggerCrashRecovery(context: Context) {
        android.util.Log.i(TAG, "🔄 Triggering crash recovery...")

        try {
            // Start NetworkRecoveryManager immediately
            NetworkRecoveryManager.getInstance(context).startMonitoring()
            android.util.Log.d(TAG, "   ✅ NetworkRecoveryManager started")

            // Start WatchdogAlarmManager
            WatchdogAlarmManager.startWatchdog(context)
            android.util.Log.d(TAG, "   ✅ WatchdogAlarmManager started")

            // Check and start tracking if needed
            LocationServiceHelper.checkAndStartTrackingIfNeeded(context)
            android.util.Log.d(TAG, "   ✅ Tracking check triggered")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Error in crash recovery", e)
        }
    }

    /**
     * Called when app goes to foreground - verify tracking status
     */
    fun onAppForegrounded(context: Context) {
        android.util.Log.d(TAG, "📱 App entered FOREGROUND")

        val shouldRun = ServiceStatePersistence.shouldTrackingBeRunning(context)
        val isRunning = LocationServiceHelper.isServiceRunning(context)

        android.util.Log.d(TAG, "   Tracking should run: $shouldRun")
        android.util.Log.d(TAG, "   Tracking is running: $isRunning")

        if (shouldRun && !isRunning) {
            android.util.Log.w(TAG, "⚠️ Tracking should be running but isn't!")
            LocationServiceHelper.checkAndStartTrackingIfNeeded(context)
        }
    }

    /**
     * Called when app goes to background - ensure monitoring layers active
     */
    fun onAppBackgrounded(context: Context) {
        android.util.Log.d(TAG, "📱 App entered BACKGROUND")

        if (!ServiceStatePersistence.shouldTrackingBeRunning(context)) {
            return
        }

        try {
            // Make sure NetworkRecoveryManager is running
            if (!NetworkRecoveryManager.getInstance(context).isMonitoring()) {
                android.util.Log.d(TAG, "   Restarting NetworkRecoveryManager before background")
                NetworkRecoveryManager.getInstance(context).startMonitoring()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error ensuring monitoring layers", e)
        }
    }
}
