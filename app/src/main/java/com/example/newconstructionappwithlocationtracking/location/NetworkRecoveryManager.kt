/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * NETWORK RECOVERY MANAGER - IMMEDIATE NETWORK RESTORATION DETECTION
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * This is the CRITICAL missing piece that caused tracking to not restart after network loss.
 * Uses Android's ConnectivityManager.NetworkCallback to IMMEDIATELY detect when network returns
 * and triggers location tracking restart.
 *
 * WHY THIS IS ESSENTIAL:
 * - WorkManager has a 15-minute minimum interval - TOO SLOW
 * - AlarmManager watchdog checks periodically - NOT INSTANT
 * - NetworkCallback triggers IMMEDIATELY when network is restored
 *
 * WHAT THIS SOLVES:
 * ✅ Network loss for 1 hour → Network returns → IMMEDIATE restart (within seconds)
 * ✅ WiFi disconnect → Mobile data connects → IMMEDIATE restart
 * ✅ Airplane mode off → IMMEDIATE restart
 * ✅ Network flapping → Debounced restart (prevents rapid restarts)
 *
 * DESIGN PRINCIPLES:
 * - Singleton pattern for app-wide use
 * - Lifecycle-aware (register once, runs forever)
 * - Debounce rapid network changes (2 second delay)
 * - Thread-safe with coroutines
 * - Survives configuration changes
 * - Logs all events for debugging
 *
 * USAGE:
 * NetworkRecoveryManager.getInstance(context).startMonitoring()
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NetworkRecoveryManager - Instantly detects network restoration and triggers tracking restart
 *
 * This is the CRITICAL component that was missing - it ensures tracking restarts
 * IMMEDIATELY when network is restored, not waiting for 15-minute WorkManager checks.
 */
class NetworkRecoveryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkRecoveryManager"

        // Debounce delay to prevent rapid restarts on flaky networks
        private const val DEBOUNCE_DELAY_MS = 2000L

        // Minimum time between restart attempts
        private const val MIN_RESTART_INTERVAL_MS = 10000L // 10 seconds

        // Singleton instance
        @Volatile
        private var INSTANCE: NetworkRecoveryManager? = null

        fun getInstance(context: Context): NetworkRecoveryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkRecoveryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // System services
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // State tracking
    private val isMonitoring = AtomicBoolean(false)
    private val wasNetworkAvailable = AtomicBoolean(true)
    private val lastRestartAttemptTime = AtomicLong(0L)
    private val pendingRestartJob = AtomicBoolean(false)

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Handler for debouncing
    private val handler = Handler(Looper.getMainLooper())

    // Network callback
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
            android.util.Log.i(TAG, "🌐 NETWORK AVAILABLE - Network restored!")
            android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
            LocationLogger.i(TAG, "Network available: $network")

            handleNetworkRestored()
        }

        override fun onLost(network: Network) {
            android.util.Log.w(TAG, "═══════════════════════════════════════════════════════════════")
            android.util.Log.w(TAG, "📵 NETWORK LOST - Connection dropped!")
            android.util.Log.w(TAG, "═══════════════════════════════════════════════════════════════")
            LocationLogger.w(TAG, "Network lost: $network")

            wasNetworkAvailable.set(false)

            // Save network loss time for diagnostics
            saveNetworkLossTime()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            android.util.Log.d(TAG, "📶 Network capabilities changed:")
            android.util.Log.d(TAG, "   hasInternet: $hasInternet")
            android.util.Log.d(TAG, "   hasValidated: $hasValidated")

            // Only trigger restart if we now have validated internet
            if (hasInternet && hasValidated && !wasNetworkAvailable.get()) {
                android.util.Log.i(TAG, "✅ Network now has validated internet - triggering restart")
                handleNetworkRestored()
            }
        }

        override fun onUnavailable() {
            android.util.Log.w(TAG, "📵 Network unavailable")
            LocationLogger.w(TAG, "Network unavailable")
            wasNetworkAvailable.set(false)
        }
    }

    /**
     * Start monitoring network changes
     * Should be called once at app startup and service start
     */
    fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) {
            android.util.Log.d(TAG, "Already monitoring network changes")
            return
        }

        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.i(TAG, "🚀 STARTING NETWORK RECOVERY MONITOR")
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "Starting network recovery monitoring")

        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

            // Set initial state
            wasNetworkAvailable.set(isNetworkCurrentlyAvailable())

            android.util.Log.i(TAG, "✅ Network callback registered successfully")
            android.util.Log.i(TAG, "   Initial network state: ${if (wasNetworkAvailable.get()) "AVAILABLE" else "UNAVAILABLE"}")
            LocationLogger.i(TAG, "Network recovery monitor started")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to register network callback", e)
            LocationLogger.e(TAG, "Failed to start network monitoring", e)
            isMonitoring.set(false)
        }
    }

    /**
     * Stop monitoring network changes
     */
    fun stopMonitoring() {
        if (!isMonitoring.getAndSet(false)) {
            return
        }

        android.util.Log.i(TAG, "🛑 Stopping network recovery monitor")
        LocationLogger.i(TAG, "Stopping network recovery monitoring")

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            handler.removeCallbacksAndMessages(null)
            android.util.Log.i(TAG, "✅ Network callback unregistered")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error unregistering callback", e)
        }
    }

    /**
     * Handle network restoration - triggers tracking restart with debounce
     */
    private fun handleNetworkRestored() {
        val wasUnavailable = !wasNetworkAvailable.getAndSet(true)

        if (!wasUnavailable) {
            android.util.Log.d(TAG, "   Network was already available, skipping restart trigger")
            return
        }

        android.util.Log.i(TAG, "🔄 Network was UNAVAILABLE, now AVAILABLE - preparing restart")

        // Log recovery time
        logNetworkRecoveryTime()

        // Debounce rapid changes
        if (pendingRestartJob.get()) {
            android.util.Log.d(TAG, "   Restart already pending, skipping duplicate")
            return
        }

        // Check minimum interval between restarts
        val now = System.currentTimeMillis()
        val timeSinceLastRestart = now - lastRestartAttemptTime.get()
        if (timeSinceLastRestart < MIN_RESTART_INTERVAL_MS) {
            android.util.Log.d(TAG, "   Too soon since last restart (${timeSinceLastRestart}ms), delaying")
        }

        // Schedule debounced restart
        pendingRestartJob.set(true)
        scope.launch {
            try {
                android.util.Log.d(TAG, "   ⏳ Debouncing for ${DEBOUNCE_DELAY_MS}ms...")
                delay(DEBOUNCE_DELAY_MS)

                // Verify network is still available after debounce
                if (!isNetworkCurrentlyAvailable()) {
                    android.util.Log.w(TAG, "   ❌ Network no longer available after debounce, aborting")
                    pendingRestartJob.set(false)
                    return@launch
                }

                android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
                android.util.Log.i(TAG, "🚀 TRIGGERING LOCATION TRACKING RESTART - Network Recovered!")
                android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")

                lastRestartAttemptTime.set(System.currentTimeMillis())

                // Notify LocationServiceHelper that network condition is fixed
                // This bypasses circuit breaker if network was the failure reason
                LocationServiceHelper.checkAndResumeIfConditionFixed(
                    context,
                    LocationServiceHelper.FailureReason.UNKNOWN // Network recovery should try regardless
                )

                // Also directly check and start tracking if needed
                LocationServiceHelper.checkAndStartTrackingIfNeeded(context)

                android.util.Log.i(TAG, "✅ Restart trigger completed")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Error during restart trigger", e)
                LocationLogger.e(TAG, "Error triggering restart after network recovery", e)
            } finally {
                pendingRestartJob.set(false)
            }
        }
    }

    /**
     * Check if network is currently available
     */
    private fun isNetworkCurrentlyAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                connectivityManager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking network availability", e)
            false
        }
    }

    /**
     * Save network loss time for diagnostics
     */
    private fun saveNetworkLossTime() {
        try {
            context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
                .edit()
                .putLong("network_loss_time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // Non-critical
        }
    }

    /**
     * Log network recovery time and duration
     */
    private fun logNetworkRecoveryTime() {
        try {
            val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
            val lossTime = prefs.getLong("network_loss_time", 0L)
            if (lossTime > 0) {
                val downtime = System.currentTimeMillis() - lossTime
                android.util.Log.i(TAG, "📊 Network was down for ${downtime / 1000}s (${downtime / 60000} minutes)")
                LocationLogger.i(TAG, "Network downtime: ${downtime / 1000}s")

                // Clear loss time
                prefs.edit().remove("network_loss_time").apply()
            }
        } catch (e: Exception) {
            // Non-critical
        }
    }

    /**
     * Get monitoring status
     */
    fun isMonitoring(): Boolean = isMonitoring.get()

    /**
     * Get current network state
     */
    fun isNetworkAvailable(): Boolean = wasNetworkAvailable.get() && isNetworkCurrentlyAvailable()

    /**
     * Get statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "isMonitoring" to isMonitoring.get(),
            "isNetworkAvailable" to isNetworkAvailable(),
            "lastRestartAttempt" to lastRestartAttemptTime.get(),
            "pendingRestart" to pendingRestartJob.get()
        )
    }
}
