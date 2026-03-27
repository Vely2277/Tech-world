/*
 * ============================================================================
 * NETWORK MONITOR
 * ============================================================================
 *
 * PURPOSE:
 * Monitor network connectivity state and quality to optimize location tracking
 * behavior. Enables intelligent decisions about data uploads, caching, and
 * tracking frequency based on network conditions.
 *
 * KEY FEATURES:
 * - Real-time network state monitoring (WiFi/Cellular/Offline)
 * - Network quality assessment
 * - Connection change detection
 * - Bandwidth-aware upload optimization
 * - Automatic offline mode switching
 * - Network-based retry strategies
 *
 * FUNCTIONALITY:
 * - startMonitoring(): Begin network state tracking
 * - stopMonitoring(): Stop network monitoring
 * - isNetworkAvailable(): Check if any network is available
 * - isWiFiConnected(): Check specifically for WiFi
 * - getNetworkQuality(): Assess current network quality
 * - shouldUploadNow(): Determine if conditions are right for upload
 * - registerCallback(): Listen for network changes
 *
 * USAGE:
 * val monitor = NetworkMonitor(context)
 * monitor.startMonitoring()
 * monitor.registerCallback { isAvailable, quality ->
 *     if (isAvailable) uploadPendingData()
 * }
 *
 * OPTIMIZATION STRATEGY:
 * - WiFi: Full uploads, real-time sync, high frequency
 * - Cellular: Compressed uploads, batch sync, reduced frequency
 * - Poor Connection: Local cache only, minimal uploads
 * - Offline: Full offline mode, queue for later
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger

class NetworkMonitor(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callbacks = mutableListOf<NetworkCallback>()
    private var isMonitoring = false

    private var currentNetworkStatus = NetworkStatus.UNKNOWN
    private var lastNetworkChange = System.currentTimeMillis()

    // Network callback for API 24+
    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LocationLogger.i(LocationConstants.TAG_NETWORK, "Network available: $network")
                handleNetworkChange(true)
            }

            override fun onLost(network: Network) {
                LocationLogger.w(LocationConstants.TAG_NETWORK, "Network lost: $network")
                handleNetworkChange(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val quality = assessNetworkQuality(networkCapabilities)
                LocationLogger.d(LocationConstants.TAG_NETWORK, "Network quality: $quality")
                handleNetworkQualityChange(quality)
            }
        }
    } else {
        null
    }

    // Broadcast receiver for older Android versions
    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                val isAvailable = isNetworkAvailable()
                handleNetworkChange(isAvailable)
            }
        }
    }

    /**
     * Start monitoring network changes
     */
    fun startMonitoring() {
        if (isMonitoring) {
            LocationLogger.w(LocationConstants.TAG_NETWORK, "Already monitoring network")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use NetworkCallback for API 24+
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } else {
            // Use BroadcastReceiver for older versions
            @Suppress("DEPRECATION")
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.registerReceiver(connectivityReceiver, filter)
        }

        isMonitoring = true
        currentNetworkStatus = getCurrentNetworkStatus()
        LocationLogger.i(LocationConstants.TAG_NETWORK, "Network monitoring started. Status: $currentNetworkStatus")
    }

    /**
     * Stop monitoring network changes
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
            } else {
                context.unregisterReceiver(connectivityReceiver)
            }

            isMonitoring = false
            LocationLogger.i(LocationConstants.TAG_NETWORK, "Network monitoring stopped")
        } catch (e: Exception) {
            LocationLogger.e(LocationConstants.TAG_NETWORK, "Error stopping network monitor", e)
        }
    }

    /**
     * Check if network is currently available
     */
    fun isNetworkAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.isConnected == true
        }
    }

    /**
     * Check if device has network connectivity
     * Alias for isNetworkAvailable() - used by BatchUploader
     *
     * @return true if WiFi or Cellular connected, false if offline
     */
    fun isConnected(): Boolean {
        return isNetworkAvailable()
    }

    /**
     * Check if WiFi is connected
     */
    fun isWiFiConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }

    /**
     * Check if cellular data is connected
     */
    fun isCellularConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.type == ConnectivityManager.TYPE_MOBILE && networkInfo.isConnected
        }
    }

    /**
     * Get current network quality assessment
     */
    fun getNetworkQuality(): String {
        if (!isNetworkAvailable()) {
            return LocationConstants.NETWORK_OFFLINE
        }

        return when {
            isWiFiConnected() -> LocationConstants.NETWORK_EXCELLENT
            isCellularConnected() -> {
                // Could add signal strength check here if needed
                LocationConstants.NETWORK_GOOD
            }
            else -> LocationConstants.NETWORK_POOR
        }
    }

    /**
     * Get current network status
     */
    fun getCurrentNetworkStatus(): NetworkStatus {
        return when {
            !isNetworkAvailable() -> NetworkStatus.OFFLINE
            isWiFiConnected() -> NetworkStatus.WIFI
            isCellularConnected() -> NetworkStatus.CELLULAR
            else -> NetworkStatus.UNKNOWN
        }
    }

    /**
     * Get current network type as string
     * Used by LocationTrackingService to store in LocationData
     *
     * @return "wifi", "cellular", "offline", or "unknown"
     */
    fun getNetworkType(): String {
        return when {
            !isNetworkAvailable() -> "offline"
            isWiFiConnected() -> "wifi"
            isCellularConnected() -> "cellular"
            else -> "unknown"
        }
    }

    /**
     * Determine if conditions are suitable for uploading data
     */
    fun shouldUploadNow(dataSize: Int = 0): Boolean {
        val quality = getNetworkQuality()

        return when (quality) {
            LocationConstants.NETWORK_EXCELLENT -> true // WiFi - always upload
            LocationConstants.NETWORK_GOOD -> {
                // Cellular - upload if data size is reasonable
                dataSize < 100 * 1024 // Less than 100KB
            }
            LocationConstants.NETWORK_POOR -> {
                // Poor connection - only critical uploads
                dataSize < 10 * 1024 // Less than 10KB
            }
            else -> false // Offline - don't upload
        }
    }

    /**
     * Get recommended batch size based on network quality
     */
    fun getRecommendedBatchSize(): Int {
        return when (getNetworkQuality()) {
            LocationConstants.NETWORK_EXCELLENT -> 100 // WiFi - large batches
            LocationConstants.NETWORK_GOOD -> 50 // Cellular - medium batches
            LocationConstants.NETWORK_POOR -> 10 // Poor - small batches
            else -> 0 // Offline - no uploads
        }
    }

    /**
     * Get recommended retry delay based on network quality
     */
    fun getRecommendedRetryDelay(): Long {
        return when (getNetworkQuality()) {
            LocationConstants.NETWORK_EXCELLENT -> 5000L // 5 seconds
            LocationConstants.NETWORK_GOOD -> 15000L // 15 seconds
            LocationConstants.NETWORK_POOR -> 60000L // 1 minute
            else -> 300000L // 5 minutes
        }
    }

    /**
     * Check if upload should use compression
     */
    fun shouldCompressData(): Boolean {
        // Compress on cellular, not on WiFi
        return isCellularConnected()
    }

    /**
     * Register callback for network changes
     */
    fun registerCallback(callback: NetworkCallback) {
        callbacks.add(callback)
    }

    /**
     * Unregister callback
     */
    fun unregisterCallback(callback: NetworkCallback) {
        callbacks.remove(callback)
    }

    /**
     * Handle network availability change
     */
    private fun handleNetworkChange(isAvailable: Boolean) {
        val newStatus = getCurrentNetworkStatus()

        if (newStatus != currentNetworkStatus) {
            val oldStatus = currentNetworkStatus
            currentNetworkStatus = newStatus
            lastNetworkChange = System.currentTimeMillis()

            LocationLogger.i(
                LocationConstants.TAG_NETWORK,
                "Network changed: $oldStatus → $newStatus"
            )

            // Notify callbacks
            callbacks.forEach { callback ->
                try {
                    callback.onNetworkChanged(newStatus, getNetworkQuality())
                } catch (e: Exception) {
                    LocationLogger.e(LocationConstants.TAG_NETWORK, "Error in network callback", e)
                }
            }
        }
    }

    /**
     * Handle network quality change
     */
    private fun handleNetworkQualityChange(quality: String) {
        callbacks.forEach { callback ->
            try {
                callback.onQualityChanged(quality)
            } catch (e: Exception) {
                LocationLogger.e(LocationConstants.TAG_NETWORK, "Error in quality callback", e)
            }
        }
    }

    /**
     * Assess network quality from capabilities
     */
    private fun assessNetworkQuality(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                LocationConstants.NETWORK_EXCELLENT
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val signalStrength = capabilities.signalStrength
                    when {
                        signalStrength >= -70 -> LocationConstants.NETWORK_GOOD
                        signalStrength >= -90 -> LocationConstants.NETWORK_POOR
                        else -> LocationConstants.NETWORK_POOR
                    }
                } else {
                    LocationConstants.NETWORK_GOOD
                }
            }
            else -> LocationConstants.NETWORK_POOR
        }
    }

    /**
     * Get time since last network change
     */
    fun getTimeSinceLastChange(): Long {
        return System.currentTimeMillis() - lastNetworkChange
    }

    /**
     * Check if network is stable (no recent changes)
     */
    fun isNetworkStable(thresholdMs: Long = 30000): Boolean {
        return getTimeSinceLastChange() > thresholdMs
    }

    /**
     * Get network statistics
     */
    fun getNetworkStats(): Map<String, Any> {
        return mapOf(
            "isAvailable" to isNetworkAvailable(),
            "isWiFi" to isWiFiConnected(),
            "isCellular" to isCellularConnected(),
            "quality" to getNetworkQuality(),
            "status" to currentNetworkStatus.name,
            "timeSinceLastChange" to getTimeSinceLastChange(),
            "isStable" to isNetworkStable()
        )
    }

    // Network status enum
    enum class NetworkStatus {
        WIFI,
        CELLULAR,
        OFFLINE,
        UNKNOWN
    }

    // Network callback interface
    interface NetworkCallback {
        fun onNetworkChanged(status: NetworkStatus, quality: String)
        fun onQualityChanged(quality: String) {}
    }
}

