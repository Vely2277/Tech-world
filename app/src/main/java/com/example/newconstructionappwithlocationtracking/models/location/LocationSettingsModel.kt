/*
 * ============================================================================
 * LOCATION SETTINGS MODEL - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Complete data class for admin-controlled location tracking settings with full
 * support for 44% battery protection, ForceCheck override, Emergency mode,
 * Realtime mode, and dynamic interval management.
 *
 * KEY FEATURES:
 * - 3 Tracking Intervals (normal, realtime, emergency)
 * - 3 Mode Flags (ForceCheck, Realtime, Emergency)
 * - 44% Battery Protection System
 * - Real-time Firebase synchronization
 * - Local caching for offline operation
 * - Validation and fail-safe defaults
 * - Helper methods for interval selection
 * - Firebase conversion utilities
 *
 * INTERVAL SYSTEM:
 * - normalIntervalMs: Standard tracking (2 hours default)
 * - realtimeIntervalMs: High-frequency tracking (10 seconds default)
 * - emergencyIntervalMs: Critical tracking (30 seconds default)
 * - forceCheckIntervalMs: Optional override (defaults to normal)
 *
 * MODE FLAGS:
 * - forceCheck: Admin override to ignore 44% battery protection
 * - realtimeMode: Enable high-frequency tracking
 * - emergencyMode: Enable emergency tracking (overrides all)
 *
 * PRIORITY LOGIC:
 * 1. Emergency Mode → emergencyIntervalMs
 * 2. Realtime Mode → realtimeIntervalMs
 * 3. Normal Mode → normalIntervalMs
 * 4. Battery ≤ 44% + ForceCheck OFF → Force 2 hours (protection)
 *
 * USAGE:
 * val settings = LocationSettings(
 *     normalIntervalMs = 7200000L,      // 2 hours
 *     realtimeIntervalMs = 10000L,      // 10 seconds
 *     emergencyIntervalMs = 30000L,     // 30 seconds
 *     forceCheck = false,
 *     realtimeMode = false,
 *     emergencyMode = false
 * )
 *
 * val currentInterval = settings.getCurrentInterval()
 * val activeMode = settings.getActiveMode()
 *
 * SYNCHRONIZATION:
 * Firebase → Real-time listener → Parse → Validate → Cache → Apply → Notify
 * Offline → Read cache → Apply cached settings → Continue operation
 *
 * FIREBASE STRUCTURE:
 * {
 *   "normalIntervalMs": 7200000,
 *   "realtimeIntervalMs": 10000,
 *   "emergencyIntervalMs": 30000,
 *   "forceCheck": false,
 *   "realtimeMode": false,
 *   "emergencyMode": false,
 *   "trackingEnabled": true,
 *   "lastUpdated": 1701388800000,
 *   "version": 2
 * }
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.models.location

import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import java.io.Serializable
import java.util.Locale

data class LocationSettings(
    // ============================================================================
    // TRACKING INTERVALS (CRITICAL - FROM FIREBASE)
    // ============================================================================

    /**
     * Normal tracking interval in milliseconds
     * Default: 2 hours (7200000ms)
     * Used when battery > 44% or ForceCheck enabled
     */
    val normalIntervalMs: Long = LocationConstants.FALLBACK_UPDATE_INTERVAL_MS,

    /**
     * Realtime tracking interval in milliseconds
     * Default: 10 seconds (10000ms)
     * Used when realtimeMode = true
     */
    val realtimeIntervalMs: Long = LocationConstants.FALLBACK_REALTIME_INTERVAL_MS,

    /**
     * Emergency tracking interval in milliseconds
     * Default: 30 seconds (30000ms)
     * Used when emergencyMode = true (highest priority)
     */
    val emergencyIntervalMs: Long = LocationConstants.FALLBACK_EMERGENCY_INTERVAL_MS,

    /**
     * ForceCheck interval in milliseconds (optional)
     * If null, uses normalIntervalMs
     * Used when forceCheck = true
     */
    val forceCheckIntervalMs: Long? = null,

    // ============================================================================
    // MODE FLAGS (CRITICAL - FROM FIREBASE)
    // ============================================================================

    /**
     * ForceCheck mode - Override 44% battery protection
     * When true: Ignore battery level, always use admin interval
     * When false: Enforce 2-hour interval when battery ≤ 44%
     */
    val forceCheck: Boolean = false,

    /**
     * Realtime mode - High-frequency tracking
     * When true: Use realtimeIntervalMs (typically 10 seconds)
     * Priority: Lower than emergency, higher than normal
     */
    val realtimeMode: Boolean = false,

    /**
     * Emergency mode - Critical tracking (highest priority)
     * When true: Use emergencyIntervalMs (typically 30 seconds)
     * Overrides all other settings including battery protection
     */
    val emergencyMode: Boolean = false,

    // ============================================================================
    // BACKWARD COMPATIBILITY (KEEP FOR OLD CODE)
    // ============================================================================

    /**
     * Legacy interval in seconds (backward compatibility)
     * Kept for old code that might reference it
     * New code should use normalIntervalMs instead
     */
    val updateIntervalSeconds: Int = (normalIntervalMs / 1000).toInt(),

    // ============================================================================
    // FEATURE FLAGS
    // ============================================================================

    val trackingEnabled: Boolean = true,
    val movementDetectionEnabled: Boolean = true,
    val offlineCachingEnabled: Boolean = true,
    val batteryOptimizationEnabled: Boolean = true,
    val debugLoggingEnabled: Boolean = false,

    // ============================================================================
    // ACCURACY THRESHOLDS
    // ============================================================================

    val minGpsAccuracyMeters: Float = LocationConstants.GPS_GOOD_ACCURACY,
    val minNetworkAccuracyMeters: Float = LocationConstants.NETWORK_GOOD_ACCURACY,

    // ============================================================================
    // MOVEMENT DETECTION
    // ============================================================================

    val movementThresholdMeters: Float = LocationConstants.MOVEMENT_THRESHOLD_METERS,
    val stationaryTimeoutMinutes: Int = (LocationConstants.STATIONARY_TIMEOUT_MS / 60000).toInt(),

    // ============================================================================
    // BATTERY MANAGEMENT
    // ============================================================================

    val lowBatteryThreshold: Int = 15,
    val criticalBatteryThreshold: Int = 5,

    // ============================================================================
    // NETWORK SETTINGS
    // ============================================================================

    val maxRetryAttempts: Int = LocationConstants.TIER1_MAX_ATTEMPTS,
    val retryDelaySeconds: Int = (LocationConstants.TIER1_RETRY_INTERVAL_MS / 1000).toInt(),

    // ============================================================================
    // METADATA
    // ============================================================================

    val lastUpdated: Long = System.currentTimeMillis(),
    val source: String = SOURCE_SERVER,
    val version: Int = 2  // Incremented for new fields

) : Serializable {

    companion object {
        // ========================================================================
        // SOURCE CONSTANTS
        // ========================================================================
        const val SOURCE_SERVER = "server"
        const val SOURCE_LOCAL = "local"
        const val SOURCE_DEFAULT = "default"

        // ========================================================================
        // INTERVAL RANGES (milliseconds)
        // ========================================================================
        const val MIN_INTERVAL_MS = LocationConstants.MIN_UPDATE_INTERVAL_MS          // 1 minute (normal mode)
        const val MIN_REALTIME_INTERVAL_MS = LocationConstants.MIN_REALTIME_INTERVAL_MS  // 1 second (realtime mode)
        const val MIN_EMERGENCY_INTERVAL_MS = LocationConstants.MIN_EMERGENCY_INTERVAL_MS  // 1 second (emergency mode)
        const val MIN_FORCECHECK_INTERVAL_MS = LocationConstants.MIN_FORCECHECK_INTERVAL_MS  // 1 second (forceCheck mode)
        const val MAX_INTERVAL_MS = LocationConstants.MAX_UPDATE_INTERVAL_MS          // 48 hours
        const val DEFAULT_NORMAL_MS = LocationConstants.FALLBACK_UPDATE_INTERVAL_MS   // 2 hours
        const val DEFAULT_REALTIME_MS = LocationConstants.FALLBACK_REALTIME_INTERVAL_MS // 10 seconds
        const val DEFAULT_EMERGENCY_MS = LocationConstants.FALLBACK_EMERGENCY_INTERVAL_MS // 30 seconds

        // ========================================================================
        // MODE CONSTANTS
        // ========================================================================
        const val MODE_NORMAL = "normal"
        const val MODE_REALTIME = "realtime"
        const val MODE_EMERGENCY = "emergency"
        const val MODE_FORCECHECK = "forceCheck"
        const val MODE_FORCED = "forced"  // Battery protection forced 2 hours

        // ========================================================================
        // BATTERY THRESHOLDS
        // ========================================================================
        const val BATTERY_FULL = 100
        const val BATTERY_HIGH = 50
        const val BATTERY_MEDIUM = 30
        const val BATTERY_LOW = 15
        const val BATTERY_CRITICAL = 5
        const val BATTERY_PROTECTION_THRESHOLD = LocationConstants.BATTERY_PROTECTION_THRESHOLD  // 44%

        // ========================================================================
        // FACTORY METHODS
        // ========================================================================

        /**
         * Create default settings (used when Firebase unreachable)
         */
        fun getDefault(): LocationSettings {
            return LocationSettings(
                normalIntervalMs = DEFAULT_NORMAL_MS,
                realtimeIntervalMs = DEFAULT_REALTIME_MS,
                emergencyIntervalMs = DEFAULT_EMERGENCY_MS,
                forceCheck = false,
                realtimeMode = false,
                emergencyMode = false,
                source = SOURCE_DEFAULT
            )
        }

        /**
         * Create from Firebase document
         * Handles both old and new field naming conventions
         */
        fun fromFirebase(data: Map<String, Any>): LocationSettings {
            // Helper: Get interval (checks ms first, then seconds*1000, then default)
            fun getInterval(msKey: String, secKey: String, default: Long): Long {
                return (data[msKey] as? Number)?.toLong()
                    ?: ((data[secKey] as? Number)?.toLong()?.times(1000))
                    ?: default
            }

            // Helper: Get boolean (checks primary key, then fallback)
            fun getBool(key1: String, key2: String, default: Boolean = false): Boolean {
                return (data[key1] as? Boolean) ?: (data[key2] as? Boolean) ?: default
            }

            // Helper: Get float with fallback
            fun getFloat(key1: String, key2: String, default: Float): Float {
                return (data[key1] as? Number)?.toFloat() ?: (data[key2] as? Number)?.toFloat() ?: default
            }

            return LocationSettings(
                // Intervals
                normalIntervalMs = getInterval("normalIntervalMs", "updateInterval", DEFAULT_NORMAL_MS),
                realtimeIntervalMs = getInterval("realtimeIntervalMs", "realtimeInterval", DEFAULT_REALTIME_MS),
                emergencyIntervalMs = getInterval("emergencyIntervalMs", "emergencyInterval", DEFAULT_EMERGENCY_MS),
                forceCheckIntervalMs = (data["forceCheckIntervalMs"] as? Number)?.toLong()
                    ?: ((data["forceCheckInterval"] as? Number)?.toLong()?.times(1000)),

                // Mode flags
                forceCheck = getBool("forceCheck", "forceCheckEnabled"),
                realtimeMode = getBool("realtimeMode", "realtimeModeEnabled"),
                emergencyMode = getBool("emergencyMode", "emergencyModeEnabled"),

                // Feature flags
                trackingEnabled = data["trackingEnabled"] as? Boolean ?: true,
                movementDetectionEnabled = data["movementDetectionEnabled"] as? Boolean ?: true,
                offlineCachingEnabled = data["offlineCachingEnabled"] as? Boolean ?: true,
                batteryOptimizationEnabled = data["batteryOptimizationEnabled"] as? Boolean ?: true,
                debugLoggingEnabled = data["debugLoggingEnabled"] as? Boolean ?: false,

                // Accuracy
                minGpsAccuracyMeters = getFloat("minGpsAccuracyMeters", "minGpsAccuracy", LocationConstants.GPS_GOOD_ACCURACY),
                minNetworkAccuracyMeters = getFloat("minNetworkAccuracyMeters", "minNetworkAccuracy", LocationConstants.NETWORK_GOOD_ACCURACY),

                // Movement
                movementThresholdMeters = getFloat("movementThresholdMeters", "movementThreshold", LocationConstants.MOVEMENT_THRESHOLD_METERS),
                stationaryTimeoutMinutes = (data["stationaryTimeoutMinutes"] as? Number)?.toInt()
                    ?: (LocationConstants.STATIONARY_TIMEOUT_MS / 60000).toInt(),

                // Battery
                lowBatteryThreshold = (data["lowBatteryThreshold"] as? Number)?.toInt() ?: 15,
                criticalBatteryThreshold = (data["criticalBatteryThreshold"] as? Number)?.toInt() ?: 5,

                // Network
                maxRetryAttempts = (data["maxRetryAttempts"] as? Number)?.toInt() ?: LocationConstants.TIER1_MAX_ATTEMPTS,
                retryDelaySeconds = (data["retryDelaySeconds"] as? Number)?.toInt() ?: (LocationConstants.TIER1_RETRY_INTERVAL_MS / 1000).toInt(),

                // Metadata
                lastUpdated = (data["lastUpdated"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                source = data["source"] as? String ?: SOURCE_SERVER,
                version = (data["version"] as? Number)?.toInt() ?: 2
            )
        }

        /**
         * Create from local cache (SharedPreferences)
         */
        fun fromCache(
            normalMs: Long,
            realtimeMs: Long,
            emergencyMs: Long,
            forceCheck: Boolean,
            realtimeMode: Boolean,
            emergencyMode: Boolean
        ): LocationSettings {
            return LocationSettings(
                normalIntervalMs = normalMs,
                realtimeIntervalMs = realtimeMs,
                emergencyIntervalMs = emergencyMs,
                forceCheck = forceCheck,
                realtimeMode = realtimeMode,
                emergencyMode = emergencyMode,
                source = SOURCE_LOCAL
            )
        }
    }

    // ============================================================================
    // INTERVAL SELECTION (MAIN LOGIC)
    // ============================================================================

    /**
     * Get current active interval based on mode flags
     * Priority: Emergency > Realtime > Normal
     * Note: Does NOT consider battery level - that's handled by BatteryOptimizationHelper
     */
    fun getCurrentInterval(): Long {
        return when {
            emergencyMode -> emergencyIntervalMs
            realtimeMode -> realtimeIntervalMs
            forceCheck && forceCheckIntervalMs != null -> forceCheckIntervalMs
            else -> normalIntervalMs
        }
    }

    /**
     * Get interval by specific type
     * Used when you need a specific interval regardless of current mode
     */
    fun getIntervalByType(type: String): Long {
        return when (type.lowercase(Locale.ROOT)) {
            MODE_EMERGENCY -> emergencyIntervalMs
            MODE_REALTIME -> realtimeIntervalMs
            MODE_FORCECHECK -> forceCheckIntervalMs ?: normalIntervalMs
            MODE_NORMAL -> normalIntervalMs
            MODE_FORCED -> LocationConstants.BATTERY_LOW_FORCED_INTERVAL_MS  // 2 hours
            else -> normalIntervalMs
        }
    }

    /**
     * Get all intervals as a map (for logging/debugging)
     */
    fun getAllIntervals(): Map<String, Long> {
        return mapOf(
            MODE_NORMAL to normalIntervalMs,
            MODE_REALTIME to realtimeIntervalMs,
            MODE_EMERGENCY to emergencyIntervalMs,
            MODE_FORCECHECK to (forceCheckIntervalMs ?: normalIntervalMs),
            MODE_FORCED to LocationConstants.BATTERY_LOW_FORCED_INTERVAL_MS
        )
    }

    // ============================================================================
    // MODE DETECTION
    // ============================================================================

    /**
     * Check if any special mode is active
     * Special modes: ForceCheck, Realtime, Emergency
     */
    fun isInSpecialMode(): Boolean {
        return forceCheck || realtimeMode || emergencyMode
    }

    /**
     * Get current active mode name
     * Returns: "emergency", "realtime", "forceCheck", or "normal"
     */
    fun getActiveMode(): String {
        return when {
            emergencyMode -> MODE_EMERGENCY
            realtimeMode -> MODE_REALTIME
            forceCheck -> MODE_FORCECHECK
            else -> MODE_NORMAL
        }
    }

    /**
     * Get priority level (higher = more important)
     * Emergency = 3, Realtime = 2, ForceCheck = 1, Normal = 0
     */
    fun getModePriority(): Int {
        return when {
            emergencyMode -> 3
            realtimeMode -> 2
            forceCheck -> 1
            else -> 0
        }
    }

    // ============================================================================
    // VALIDATION
    // ============================================================================

    /**
     * Validate all settings are within acceptable ranges
     * Returns true if settings are safe to use
     */
    fun isValid(): Boolean {
        return areIntervalsValid() &&
               areAccuracyThresholdsValid() &&
               areMovementSettingsValid() &&
               areBatteryThresholdsValid() &&
               areNetworkSettingsValid()
    }

    /**
     * Validate intervals are within acceptable ranges
     * - Normal mode: minimum 1 minute
     * - Realtime mode: minimum 1 second (admin can set very short intervals)
     * - Emergency mode: minimum 1 second (admin can set very short intervals)
     * - ForceCheck mode: minimum 1 second (admin can set very short intervals)
     */
    fun areIntervalsValid(): Boolean {
        return normalIntervalMs in MIN_INTERVAL_MS..MAX_INTERVAL_MS &&
               realtimeIntervalMs >= MIN_REALTIME_INTERVAL_MS &&
               emergencyIntervalMs >= MIN_EMERGENCY_INTERVAL_MS &&
               (forceCheckIntervalMs == null || forceCheckIntervalMs >= MIN_FORCECHECK_INTERVAL_MS)
    }

    /**
     * Validate accuracy thresholds
     */
    private fun areAccuracyThresholdsValid(): Boolean {
        return minGpsAccuracyMeters > 0 && minNetworkAccuracyMeters > 0
    }

    /**
     * Validate movement settings
     */
    private fun areMovementSettingsValid(): Boolean {
        return movementThresholdMeters > 0 && stationaryTimeoutMinutes > 0
    }

    /**
     * Validate battery thresholds
     */
    private fun areBatteryThresholdsValid(): Boolean {
        return lowBatteryThreshold in 0..100 &&
               criticalBatteryThreshold in 0..100 &&
               criticalBatteryThreshold < lowBatteryThreshold
    }

    /**
     * Validate network settings
     */
    private fun areNetworkSettingsValid(): Boolean {
        return maxRetryAttempts > 0 && retryDelaySeconds > 0
    }

    /**
     * Get validation errors (for debugging)
     * Returns list of validation issues, empty if valid
     *
     * Note: Different modes have different minimums:
     * - Normal mode: minimum 1 minute (60000ms)
     * - Realtime/Emergency/ForceCheck: minimum 1 second (1000ms)
     */
    fun getValidationErrors(): List<String> {
        val errors = mutableListOf<String>()

        if (normalIntervalMs !in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            errors.add("normalIntervalMs out of range: $normalIntervalMs (min: $MIN_INTERVAL_MS, max: $MAX_INTERVAL_MS)")
        }
        if (realtimeIntervalMs < MIN_REALTIME_INTERVAL_MS) {
            errors.add("realtimeIntervalMs too small: $realtimeIntervalMs (min: $MIN_REALTIME_INTERVAL_MS)")
        }
        if (emergencyIntervalMs < MIN_EMERGENCY_INTERVAL_MS) {
            errors.add("emergencyIntervalMs too small: $emergencyIntervalMs (min: $MIN_EMERGENCY_INTERVAL_MS)")
        }
        if (forceCheckIntervalMs != null && forceCheckIntervalMs < MIN_FORCECHECK_INTERVAL_MS) {
            errors.add("forceCheckIntervalMs too small: $forceCheckIntervalMs (min: $MIN_FORCECHECK_INTERVAL_MS)")
        }
        if (minGpsAccuracyMeters <= 0) {
            errors.add("minGpsAccuracyMeters invalid: $minGpsAccuracyMeters")
        }
        if (minNetworkAccuracyMeters <= 0) {
            errors.add("minNetworkAccuracyMeters invalid: $minNetworkAccuracyMeters")
        }
        if (movementThresholdMeters <= 0) {
            errors.add("movementThresholdMeters invalid: $movementThresholdMeters")
        }
        if (lowBatteryThreshold !in 0..100) {
            errors.add("lowBatteryThreshold out of range: $lowBatteryThreshold")
        }

        return errors
    }

    // ============================================================================
    // CONVERSION & SERIALIZATION
    // ============================================================================

    /**
     * Convert to Firebase map for upload
     */
    fun toFirebaseMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            // Intervals
            "normalIntervalMs" to normalIntervalMs,
            "realtimeIntervalMs" to realtimeIntervalMs,
            "emergencyIntervalMs" to emergencyIntervalMs,

            // Mode flags
            "forceCheck" to forceCheck,
            "realtimeMode" to realtimeMode,
            "emergencyMode" to emergencyMode,

            // Feature flags
            "trackingEnabled" to trackingEnabled,
            "movementDetectionEnabled" to movementDetectionEnabled,
            "offlineCachingEnabled" to offlineCachingEnabled,
            "batteryOptimizationEnabled" to batteryOptimizationEnabled,
            "debugLoggingEnabled" to debugLoggingEnabled,

            // Accuracy
            "minGpsAccuracyMeters" to minGpsAccuracyMeters,
            "minNetworkAccuracyMeters" to minNetworkAccuracyMeters,

            // Movement
            "movementThresholdMeters" to movementThresholdMeters,
            "stationaryTimeoutMinutes" to stationaryTimeoutMinutes,

            // Battery
            "lowBatteryThreshold" to lowBatteryThreshold,
            "criticalBatteryThreshold" to criticalBatteryThreshold,

            // Network
            "maxRetryAttempts" to maxRetryAttempts,
            "retryDelaySeconds" to retryDelaySeconds,

            // Metadata
            "lastUpdated" to System.currentTimeMillis(),
            "source" to source,
            "version" to version
        )

        // Add optional forceCheckIntervalMs if present
        forceCheckIntervalMs?.let {
            map["forceCheckIntervalMs"] = it
        }

        return map
    }

    /**
     * Convert to SharedPreferences map (key-value pairs)
     */
    fun toCacheMap(): Map<String, String> {
        return mapOf(
            "normalIntervalMs" to normalIntervalMs.toString(),
            "realtimeIntervalMs" to realtimeIntervalMs.toString(),
            "emergencyIntervalMs" to emergencyIntervalMs.toString(),
            "forceCheckIntervalMs" to (forceCheckIntervalMs?.toString() ?: ""),
            "forceCheck" to forceCheck.toString(),
            "realtimeMode" to realtimeMode.toString(),
            "emergencyMode" to emergencyMode.toString(),
            "trackingEnabled" to trackingEnabled.toString(),
            "lastUpdated" to lastUpdated.toString(),
            "source" to source,
            "version" to version.toString()
        )
    }

    // ============================================================================
    // UTILITY METHODS
    // ============================================================================

    /**
     * Get update interval in milliseconds (backward compatibility)
     */
    fun getUpdateIntervalMillis(): Long {
        return getCurrentInterval()
    }

    /**
     * Check if settings are from server
     */
    fun isFromServer(): Boolean {
        return source == SOURCE_SERVER
    }

    /**
     * Check if settings are from local cache
     */
    fun isFromCache(): Boolean {
        return source == SOURCE_LOCAL
    }

    /**
     * Check if settings are default fallbacks
     */
    fun isDefault(): Boolean {
        return source == SOURCE_DEFAULT
    }

    /**
     * Check if settings are outdated (older than 1 hour)
     */
    fun isOutdated(): Boolean {
        val oneHourMs = 3600000L
        return System.currentTimeMillis() - lastUpdated > oneHourMs
    }

    /**
     * Get age of settings in milliseconds
     */
    fun getAgeMs(): Long {
        return System.currentTimeMillis() - lastUpdated
    }

    /**
     * Format current interval as human-readable string
     */
    fun getFormattedCurrentInterval(): String {
        val intervalMs = getCurrentInterval()
        val seconds = intervalMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "$seconds second${if (seconds > 1) "s" else ""}"
        }
    }

    /**
     * Create a copy with updated timestamp
     */
    fun refreshTimestamp(): LocationSettings {
        return this.copy(lastUpdated = System.currentTimeMillis())
    }

    /**
     * Create a summary string for logging
     */
    fun toSummaryString(): String {
        return "LocationSettings(" +
                "mode=${getActiveMode()}, " +
                "interval=${getFormattedCurrentInterval()}, " +
                "forceCheck=$forceCheck, " +
                "realtime=$realtimeMode, " +
                "emergency=$emergencyMode, " +
                "source=$source, " +
                "age=${getAgeMs() / 1000}s)"
    }

    // ============================================================================
    // EQUALITY & COMPARISON
    // ============================================================================

    /**
     * Check if settings have meaningful differences from another
     * (ignores timestamp and source)
     */
    fun hasMeaningfulDifferences(other: LocationSettings): Boolean {
        return this.normalIntervalMs != other.normalIntervalMs ||
               this.realtimeIntervalMs != other.realtimeIntervalMs ||
               this.emergencyIntervalMs != other.emergencyIntervalMs ||
               this.forceCheck != other.forceCheck ||
               this.realtimeMode != other.realtimeMode ||
               this.emergencyMode != other.emergencyMode ||
               this.trackingEnabled != other.trackingEnabled
    }

    /**
     * Check if mode flags changed
     */
    fun modesChanged(other: LocationSettings): Boolean {
        return this.forceCheck != other.forceCheck ||
               this.realtimeMode != other.realtimeMode ||
               this.emergencyMode != other.emergencyMode
    }

    /**
     * Check if intervals changed
     */
    fun intervalsChanged(other: LocationSettings): Boolean {
        return this.normalIntervalMs != other.normalIntervalMs ||
               this.realtimeIntervalMs != other.realtimeIntervalMs ||
               this.emergencyIntervalMs != other.emergencyIntervalMs ||
               this.forceCheckIntervalMs != other.forceCheckIntervalMs
    }
}

