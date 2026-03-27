/*
 * ============================================================================
 * SERVICE STATUS MODEL
 * ============================================================================
 *
 * PURPOSE:
 * Data class representing the health and status of location tracking services.
 * Used by watchdog service to monitor system health and trigger recovery actions.
 *
 * KEY FEATURES:
 * - Service operational status tracking
 * - Performance metrics collection
 * - Error status monitoring
 * - Resource usage statistics
 * - Recovery action history
 *
 * USAGE:
 * - Updated continuously by running services
 * - Monitored by LocationWatchdogService
 * - Used for health checks and diagnostics
 * - Reported to backend for monitoring
 *
 * MONITORING FLOW:
 * Services → Status updates → Watchdog monitoring → Recovery actions
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.models.location

import com.example.newconstructionappwithlocationtracking.location.BatteryOptimizationHelper
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import java.io.Serializable

data class ServiceStatus(
    // Service operational status
    val isRunning: Boolean = false,
    val isEnabled: Boolean = true,

    // Permission status
    val permissionStatus: LocationPermissionManager.ComprehensivePermissionStatus,

    // Battery information
    val batteryInfo: BatteryOptimizationHelper.BatteryInfo,

    // Settings information
    val settingsInfo: LocationSettings,

    // Circuit breaker status
    val circuitBreakerActive: Boolean = false,
    val restartAttempts: Int = 0,
    val nextAttemptTime: Long = 0L,

    // Health monitoring
    val lastHealthCheck: Long = 0L,

    // Legacy fields for backward compatibility
    val serviceName: String = "LocationTrackingService",
    val lastHeartbeat: Long = System.currentTimeMillis(),
    val uptime: Long = 0,
    val lastLocationUpdate: Long? = null,
    val locationUpdateCount: Int = 0,
    val lastLocationAccuracy: Float? = null,
    val currentProvider: String? = null,
    val memoryUsageMb: Float = 0f,
    val cpuUsagePercent: Float = 0f,
    val batteryImpactPercent: Float = 0f,

    // Synchronization status
    val pendingUploads: Int = 0,
    val failedUploads: Int = 0,
    val lastSuccessfulSync: Long? = null,

    // Error tracking
    val errorCount: Int = 0,
    val lastError: String? = null,
    val lastErrorTime: Long? = null,

    // Resource status
    val hasLocationPermission: Boolean = false,
    val hasBackgroundPermission: Boolean = false,
    val isBatteryOptimized: Boolean = true,
    val networkStatus: String = "unknown",

    // Recovery history
    val restartCount: Int = 0,
    val lastRestartTime: Long? = null,
    val recoveryLevel: Int = 0,

    // Settings
    val currentUpdateInterval: Long = 7200000, // 2 hours default
    val settingsVersion: Int = 1,
    val settingsLastSync: Long? = null
) : Serializable {

    companion object {
        // Service names
        const val SERVICE_LOCATION_TRACKING = "LocationTrackingService"
        const val SERVICE_WATCHDOG = "LocationWatchdogService"
        const val SERVICE_SYNC = "LocationSyncService"

        // Status thresholds
        const val HEARTBEAT_TIMEOUT_MS = 300000L // 5 minutes
        const val LOCATION_UPDATE_TIMEOUT_MS = 7200000L // 2 hours default
        const val SYNC_TIMEOUT_MS = 3600000L // 1 hour

        // Recovery levels
        const val RECOVERY_NONE = 0
        const val RECOVERY_GENTLE_RESTART = 1
        const val RECOVERY_FORCE_RESTART = 2
        const val RECOVERY_FULL_RESET = 3
        const val RECOVERY_EMERGENCY_MODE = 4

        // Network status
        const val NETWORK_WIFI = "wifi"
        const val NETWORK_CELLULAR = "cellular"
        const val NETWORK_OFFLINE = "offline"
        const val NETWORK_UNKNOWN = "unknown"
    }

    /**
     * Check if service is healthy
     */
    fun isHealthy(): Boolean {
        return isRunning &&
               !isHeartbeatExpired() &&
               !isLocationUpdateStagnant() &&
               errorCount < 10
    }

    /**
     * Check if heartbeat has expired
     */
    fun isHeartbeatExpired(): Boolean {
        return System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS
    }

    /**
     * Check if location updates are stagnant
     */
    fun isLocationUpdateStagnant(): Boolean {
        return lastLocationUpdate?.let {
            System.currentTimeMillis() - it > currentUpdateInterval + 600000 // interval + 10 min grace
        } ?: false
    }

    /**
     * Check if sync is overdue
     */
    fun isSyncOverdue(): Boolean {
        return lastSuccessfulSync?.let {
            System.currentTimeMillis() - it > SYNC_TIMEOUT_MS
        } ?: true
    }

    /**
     * Check if permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        return hasLocationPermission && hasBackgroundPermission
    }

    /**
     * Check if service needs recovery
     */
    fun needsRecovery(): Boolean {
        return !isHealthy() || isHeartbeatExpired() || !hasRequiredPermissions()
    }

    /**
     * Get health score (0-100)
     */
    fun getHealthScore(): Int {
        var score = 100

        if (!isRunning) score -= 50
        if (isHeartbeatExpired()) score -= 30
        if (isLocationUpdateStagnant()) score -= 20
        if (!hasRequiredPermissions()) score -= 40
        if (isBatteryOptimized) score -= 10
        if (errorCount > 5) score -= 15
        if (pendingUploads > 100) score -= 10

        return maxOf(0, score)
    }
}

