package com.example.newconstructionappwithlocationtracking.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.newconstructionappwithlocationtracking.services.LocationTrackingService
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.google.firebase.auth.FirebaseAuth

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 * SERVICE RESURRECTION RECEIVER - BULLETPROOF SERVICE RESTART MECHANISM
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 *
 * This receiver GUARANTEES that the LocationTrackingService is ALWAYS running.
 *
 * FEATURES:
 * 1. Receives resurrection alarms every 2 minutes
 * 2. Checks if service is alive using heartbeat mechanism
 * 3. Restarts service immediately if dead
 * 4. Reschedules itself for continuous monitoring
 * 5. Works even when device is in Doze mode (exact alarms)
 * 6. Survives OEM battery optimization kills
 *
 * TRIGGER ACTIONS:
 * - RESURRECT_SERVICE: Immediate service restart check
 * - SERVICE_HEARTBEAT_CHECK: Verify service health via heartbeat
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════
 */
class ServiceResurrectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RESURRECTION"

        const val ACTION_RESURRECT = "com.example.newconstructionappwithlocationtracking.RESURRECT_SERVICE"
        const val ACTION_HEARTBEAT_CHECK = "com.example.newconstructionappwithlocationtracking.SERVICE_HEARTBEAT_CHECK"

        // Check every 2 minutes (aggressive monitoring)
        private const val RESURRECTION_INTERVAL_MS = 2 * 60 * 1000L

        // Heartbeat is considered stale if older than 3 minutes
        private const val HEARTBEAT_STALE_THRESHOLD_MS = 3 * 60 * 1000L

        // Wake lock timeout
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 1000L

        /**
         * Schedule resurrection alarms - call this from Application.onCreate()
         */
        fun scheduleResurrectionAlarms(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                // Create resurrection intent
                val resurrectIntent = Intent(context, ServiceResurrectionReceiver::class.java).apply {
                    action = ACTION_RESURRECT
                }

                val resurrectPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1001, // Unique request code for resurrection
                    resurrectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Create heartbeat check intent
                val heartbeatIntent = Intent(context, ServiceResurrectionReceiver::class.java).apply {
                    action = ACTION_HEARTBEAT_CHECK
                }

                val heartbeatPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1002, // Unique request code for heartbeat
                    heartbeatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Schedule resurrection alarm (every 2 minutes)
                val triggerTime = SystemClock.elapsedRealtime() + RESURRECTION_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Use setExactAndAllowWhileIdle for Doze mode compatibility
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        resurrectPendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        resurrectPendingIntent
                    )
                }

                // Schedule heartbeat check (offset by 1 minute from resurrection)
                val heartbeatTriggerTime = SystemClock.elapsedRealtime() + RESURRECTION_INTERVAL_MS + 60000L

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        heartbeatTriggerTime,
                        heartbeatPendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        heartbeatTriggerTime,
                        heartbeatPendingIntent
                    )
                }

                Log.d(TAG, "✅ Resurrection alarms scheduled (every ${RESURRECTION_INTERVAL_MS / 60000} minutes)")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to schedule resurrection alarms: ${e.message}")
            }
        }

        /**
         * Cancel resurrection alarms
         */
        fun cancelResurrectionAlarms(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                val resurrectIntent = Intent(context, ServiceResurrectionReceiver::class.java).apply {
                    action = ACTION_RESURRECT
                }
                val resurrectPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1001,
                    resurrectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val heartbeatIntent = Intent(context, ServiceResurrectionReceiver::class.java).apply {
                    action = ACTION_HEARTBEAT_CHECK
                }
                val heartbeatPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1002,
                    heartbeatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.cancel(resurrectPendingIntent)
                alarmManager.cancel(heartbeatPendingIntent)

                Log.d(TAG, "✅ Resurrection alarms cancelled")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to cancel resurrection alarms: ${e.message}")
            }
        }

        /**
         * Trigger immediate resurrection check
         */
        fun triggerImmediateCheck(context: Context) {
            try {
                val intent = Intent(context, ServiceResurrectionReceiver::class.java).apply {
                    action = ACTION_RESURRECT
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "📢 Immediate resurrection check triggered")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to trigger immediate check: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val action = intent?.action ?: return

        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "📡 RESURRECTION RECEIVER TRIGGERED")
        Log.d(TAG, "   Action: $action")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        // Acquire wake lock to ensure we complete our work
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Construct Connect:ResurrectionWakeLock"
        )

        try {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "🔒 Wake lock acquired")

            when (action) {
                ACTION_RESURRECT -> handleResurrection(context)
                ACTION_HEARTBEAT_CHECK -> handleHeartbeatCheck(context)
            }

            // Always reschedule for continuous monitoring
            scheduleResurrectionAlarms(context)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Resurrection error: ${e.message}")
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "🔓 Wake lock released")
            }
        }
    }

    private fun handleResurrection(context: Context) {
        Log.d(TAG, "🔄 RESURRECTION CHECK STARTED")

        // Check if user is logged in
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "👤 No user logged in - skipping resurrection")
            return
        }

        // Check if tracking should be running
        val shouldBeRunning = ServiceStatePersistence.shouldTrackingBeRunning(context)
        Log.d(TAG, "📋 Tracking should be running: $shouldBeRunning")

        // Check if service is actually running
        val isServiceRunning = LocationServiceHelper.isServiceRunning(context)
        Log.d(TAG, "📋 Service is running: $isServiceRunning")

        // Check heartbeat freshness
        val lastHeartbeat = ServiceStatePersistence.getLastHeartbeat(context)
        val heartbeatAge = System.currentTimeMillis() - lastHeartbeat
        val isHeartbeatFresh = heartbeatAge < HEARTBEAT_STALE_THRESHOLD_MS
        Log.d(TAG, "💓 Heartbeat age: ${heartbeatAge / 1000}s (fresh: $isHeartbeatFresh)")

        // Decision logic
        val needsResurrection = when {
            !isServiceRunning -> {
                Log.w(TAG, "⚠️ Service NOT running - needs resurrection")
                true
            }
            !isHeartbeatFresh -> {
                Log.w(TAG, "⚠️ Heartbeat STALE (${heartbeatAge / 1000}s old) - service may be stuck")
                true
            }
            else -> {
                Log.d(TAG, "✅ Service is healthy - no action needed")
                false
            }
        }

        if (needsResurrection) {
            Log.w(TAG, "🚀 RESURRECTING SERVICE...")
            resurrectService(context)
        }
    }

    private fun handleHeartbeatCheck(context: Context) {
        Log.d(TAG, "💓 HEARTBEAT CHECK STARTED")

        val lastHeartbeat = ServiceStatePersistence.getLastHeartbeat(context)
        val heartbeatAge = System.currentTimeMillis() - lastHeartbeat

        Log.d(TAG, "   Last heartbeat: ${heartbeatAge / 1000}s ago")

        if (heartbeatAge > HEARTBEAT_STALE_THRESHOLD_MS) {
            Log.w(TAG, "⚠️ HEARTBEAT STALE - Service may be dead!")

            // Check if service thinks it's running
            val isServiceRunning = LocationServiceHelper.isServiceRunning(context)

            if (isServiceRunning) {
                Log.w(TAG, "⚠️ Service thinks it's running but heartbeat is stale - ZOMBIE STATE!")
                Log.w(TAG, "🔄 Forcing service restart...")
            }

            // Force resurrection
            resurrectService(context)
        } else {
            Log.d(TAG, "✅ Heartbeat is fresh - service is alive")
        }
    }

    private fun resurrectService(context: Context) {
        try {
            Log.d(TAG, "═══════════════════════════════════════════════════════")
            Log.d(TAG, "🚀 STARTING SERVICE RESURRECTION")
            Log.d(TAG, "═══════════════════════════════════════════════════════")

            // Mark that resurrection is in progress
            ServiceStatePersistence.setTrackingShouldBeRunning(context, true)

            // Start the service
            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = "com.example.START_TRACKING"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "✅ Service started via startForegroundService()")
            } else {
                context.startService(serviceIntent)
                Log.d(TAG, "✅ Service started via startService()")
            }

            // Record resurrection attempt
            ServiceStatePersistence.recordResurrection(context)

            Log.d(TAG, "✅ RESURRECTION COMPLETE")
            Log.d(TAG, "═══════════════════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "❌ RESURRECTION FAILED: ${e.message}")

            // Try alternative start method
            try {
                Log.d(TAG, "🔄 Trying alternative start method...")
                LocationTrackingService.start(context)
                Log.d(TAG, "✅ Alternative start succeeded")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Alternative start also failed: ${e2.message}")
            }
        }
    }
}
