/*
 * ============================================================================
 * LOCATION DATA MODEL - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Comprehensive data class for location tracking with full metadata support.
 * Supports 4-tier accuracy, 3-tier retry, battery protection, mode tracking,
 * batch upload, data retention, and all features from LocationConstants.kt.
 *
 * KEY FEATURES:
 * - 4-tier accuracy classification (Excellent/Better/Good/Okay)
 * - 3-tier retry system with unlimited tier 3
 * - Battery protection tracking (44% threshold)
 * - Mode flags (ForceCheck, Realtime, Emergency)
 * - Batch upload support with checkpoints
 * - Data retention enforcement (0 days synced, 3 days pending)
 * - Complete validation and helper methods
 *
 * DATA FLOW:
 * Location Provider → LocationData → Cache/Upload → Retry → Backend
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.models.location

import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import java.io.Serializable

data class LocationData(
    // ============================================================================
    // CORE LOCATION INFORMATION
    // ============================================================================
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    var accuracyLevel: String? = null,  // Auto-classified: Excellent/Better/Good/Okay

    // ============================================================================
    // PROVIDER INFORMATION
    // ============================================================================
    val provider: String? = LocationConstants.PROVIDER_UNKNOWN,
    val providerType: String = LocationConstants.PROVIDER_UNKNOWN,
    val providerFallback: Boolean = false,  // Did we fallback from GPS to Network?
    val rawProvider: String? = null,        // Original Android provider name

    // ============================================================================
    // TIMESTAMPS
    // ============================================================================
    val clientTimestamp: Long = System.currentTimeMillis(),
    var serverTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var syncedAt: Long? = null,

    // ============================================================================
    // MODE FLAGS (3 Tracking Modes)
    // ============================================================================
    val forceCheckEnabled: Boolean = false,  // Was ForceCheck active?
    val realtimeMode: Boolean = false,       // Was Realtime mode active?
    val emergencyMode: Boolean = false,      // Was Emergency mode active?

    // ============================================================================
    // BATTERY INFORMATION
    // ============================================================================
    val batteryLevel: Int? = null,
    val batteryThresholdActive: Boolean = false,  // Was battery < 44%?
    val forcedInterval: Boolean = false,          // Was interval forced due to low battery?

    // ============================================================================
    // INTERVAL METADATA
    // ============================================================================
    val intervalAppliedMs: Long? = null,  // Which interval was used (milliseconds)
    val intervalType: String? = null,     // "normal", "realtime", "emergency", "forced"

    // ============================================================================
    // MOVEMENT DETECTION
    // ============================================================================
    val movementStatus: String? = MOVEMENT_UNKNOWN,  // moving/stationary/high_speed/unknown
    val speed: Float? = null,  // Speed in meters per second

    // ============================================================================
    // SYNC STATUS
    // ============================================================================
    var syncStatus: String = SYNC_PENDING,  // pending/in_progress/synced/failed
    var retryCount: Int = 0,                // Total retry attempts

    // ============================================================================
    // 3-TIER RETRY SYSTEM
    // ============================================================================
    var currentRetryTier: Int = RETRY_TIER_1,
    var tier1Attempts: Int = 0,
    var tier2Attempts: Int = 0,
    var tier3Attempts: Int = 0,
    var lastRetryAttemptTime: Long? = null,
    var nextRetryScheduledTime: Long? = null,

    // ============================================================================
    // BATCH UPLOAD SUPPORT
    // ============================================================================
    var batchId: String? = null,
    var batchSequence: Int? = null,
    var uploadAttempts: Int = 0,
    var lastUploadAttemptTime: Long? = null,

    // ============================================================================
    // DATA RETENTION
    // ============================================================================
    var markedForDeletion: Boolean = false,
    var retentionExpiresAt: Long? = null,

    // ============================================================================
    // ERROR TRACKING
    // ============================================================================
    var errorMessage: String? = null,
    var errorCode: Int? = null,
    var errorTimestamp: Long? = null,

    // ============================================================================
    // LOCATION ACQUISITION METADATA
    // ============================================================================
    val acquisitionDurationMs: Long? = null,
    val gpsRetryAttempts: Int = 0,
    val locationRequestTimedOut: Boolean = false,

    // ============================================================================
    // NETWORK INFORMATION
    // ============================================================================
    val networkQuality: String? = null,  // excellent/good/poor/offline
    val networkType: String? = null,     // "wifi", "cellular", "none"
    val uploadBandwidthKbps: Int? = null,

    // ============================================================================
    // PRIORITY & IMPORTANCE
    // ============================================================================
    var priority: Int = 0,           // 0=normal, 1=high, 2=critical
    var isCritical: Boolean = false, // Must be uploaded ASAP

    // ============================================================================
    // DEVICE & SYSTEM INFO
    // ============================================================================
    val deviceUptimeMs: Long? = null,
    val appVersion: String? = null,
    val osVersion: String? = null,

    // ============================================================================
    // CLUSTERING (Optional)
    // ============================================================================
    var clusterId: String? = null,
    var isClusterRepresentative: Boolean = false,

    // ============================================================================
    // RAW LOCATION DATA (GPS specific)
    // ============================================================================
    val satelliteCount: Int? = null,
    val altitude: Double? = null,
    val bearing: Float? = null,

    // ============================================================================
    // DATABASE & USER IDENTIFICATION
    // ============================================================================
    var id: Long? = null,
    val userId: String? = null

) : Serializable {

    companion object {
        // ========================================================================
        // SYNC STATUS CONSTANTS
        // ========================================================================
        const val SYNC_PENDING = "pending"
        const val SYNC_IN_PROGRESS = "in_progress"
        const val SYNC_SYNCED = "synced"
        const val SYNC_FAILED = "failed"

        // ========================================================================
        // MOVEMENT STATUS CONSTANTS
        // ========================================================================
        const val MOVEMENT_MOVING = "moving"
        const val MOVEMENT_STATIONARY = "stationary"
        const val MOVEMENT_HIGH_SPEED = "high_speed"
        const val MOVEMENT_UNKNOWN = "unknown"

        // ========================================================================
        // NETWORK QUALITY CONSTANTS
        // ========================================================================
        const val NETWORK_EXCELLENT = "excellent"
        const val NETWORK_GOOD = "good"
        const val NETWORK_POOR = "poor"
        const val NETWORK_OFFLINE = "offline"

        // ========================================================================
        // ACCURACY LEVEL CONSTANTS (4-Tier System)
        // ========================================================================
        const val ACCURACY_EXCELLENT = "Excellent"  // 0-20 meters
        const val ACCURACY_BETTER = "Better"        // 20-50 meters
        const val ACCURACY_GOOD = "Good"            // 50-100 meters
        const val ACCURACY_OKAY = "Okay"            // >100 meters

        // ========================================================================
        // RETRY TIER CONSTANTS
        // ========================================================================
        const val RETRY_TIER_1 = 1  // 1 min intervals, 3 attempts
        const val RETRY_TIER_2 = 2  // 5 min intervals, 3 attempts
        const val RETRY_TIER_3 = 3  // 10 min intervals, unlimited

        // ========================================================================
        // INTERVAL TYPE CONSTANTS
        // ========================================================================
        const val INTERVAL_TYPE_NORMAL = "normal"
        const val INTERVAL_TYPE_REALTIME = "realtime"
        const val INTERVAL_TYPE_EMERGENCY = "emergency"
        const val INTERVAL_TYPE_FORCED = "forced"  // Battery protection forced

        // ========================================================================
        // PRIORITY CONSTANTS
        // ========================================================================
        const val PRIORITY_NORMAL = 0
        const val PRIORITY_HIGH = 1
        const val PRIORITY_CRITICAL = 2
    }

    init {
        // Auto-classify accuracy level if not set
        if (accuracyLevel == null) {
            accuracyLevel = classifyAccuracyLevel()
        }

        // Auto-set retention expiration
        if (retentionExpiresAt == null) {
            retentionExpiresAt = createdAt + (LocationConstants.PENDING_DATA_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        }

        // Auto-determine priority
        if (priority == 0 && (emergencyMode || realtimeMode)) {
            priority = determinePriority()
            isCritical = emergencyMode
        }
    }

    // ============================================================================
    // VALIDATION METHODS
    // ============================================================================

    /**
     * Check if location data is valid for upload
     */
    fun isValid(): Boolean {
        return latitude in -90.0..90.0 &&
               longitude in -180.0..180.0 &&
               (accuracy == null || accuracy > 0) &&
               userId != null &&
               userId.isNotBlank()
    }

    /**
     * Check if location needs synchronization
     */
    fun needsSync(): Boolean {
        return syncStatus == SYNC_PENDING || syncStatus == SYNC_FAILED
    }

    /**
     * Check if retry limit exceeded for current tier
     */
    fun exceededRetryLimit(): Boolean {
        return when (currentRetryTier) {
            RETRY_TIER_1 -> tier1Attempts >= LocationConstants.TIER1_MAX_ATTEMPTS
            RETRY_TIER_2 -> tier2Attempts >= LocationConstants.TIER2_MAX_ATTEMPTS
            RETRY_TIER_3 -> false  // Unlimited
            else -> true
        }
    }

    // ============================================================================
    // ACCURACY CLASSIFICATION
    // ============================================================================

    /**
     * Classify accuracy into 4-tier system
     * Excellent: 0-20m, Better: 20-50m, Good: 50-100m, Okay: >100m
     */
    fun classifyAccuracyLevel(): String {
        return when {
            accuracy == null -> ACCURACY_OKAY
            accuracy <= LocationConstants.GPS_EXCELLENT_ACCURACY -> ACCURACY_EXCELLENT
            accuracy <= LocationConstants.GPS_BETTER_ACCURACY -> ACCURACY_BETTER
            accuracy <= LocationConstants.GPS_GOOD_ACCURACY -> ACCURACY_GOOD
            else -> ACCURACY_OKAY
        }
    }

    // ============================================================================
    // RETRY TIER MANAGEMENT
    // ============================================================================

    /**
     * Check if can retry in current tier
     */
    fun canRetry(): Boolean {
        return when (currentRetryTier) {
            RETRY_TIER_1 -> tier1Attempts < LocationConstants.TIER1_MAX_ATTEMPTS
            RETRY_TIER_2 -> tier2Attempts < LocationConstants.TIER2_MAX_ATTEMPTS
            RETRY_TIER_3 -> true  // Unlimited retries
            else -> false
        }
    }

    /**
     * Increment retry tier if current tier exhausted
     */
    fun incrementRetryTier() {
        when (currentRetryTier) {
            RETRY_TIER_1 -> {
                if (tier1Attempts >= LocationConstants.TIER1_MAX_ATTEMPTS) {
                    currentRetryTier = RETRY_TIER_2
                    tier2Attempts = 0
                }
            }
            RETRY_TIER_2 -> {
                if (tier2Attempts >= LocationConstants.TIER2_MAX_ATTEMPTS) {
                    currentRetryTier = RETRY_TIER_3
                    tier3Attempts = 0
                }
            }
        }
    }

    /**
     * Get next retry interval in milliseconds
     */
    fun getNextRetryInterval(): Long {
        return when (currentRetryTier) {
            RETRY_TIER_1 -> LocationConstants.TIER1_RETRY_INTERVAL_MS
            RETRY_TIER_2 -> LocationConstants.TIER2_RETRY_INTERVAL_MS
            RETRY_TIER_3 -> LocationConstants.TIER3_RETRY_INTERVAL_MS
            else -> LocationConstants.TIER1_RETRY_INTERVAL_MS
        }
    }

    /**
     * Record retry attempt
     */
    fun recordRetryAttempt() {
        retryCount++
        when (currentRetryTier) {
            RETRY_TIER_1 -> tier1Attempts++
            RETRY_TIER_2 -> tier2Attempts++
            RETRY_TIER_3 -> tier3Attempts++
        }
        lastRetryAttemptTime = System.currentTimeMillis()

        // Auto-increment tier if needed
        if (exceededRetryLimit()) {
            incrementRetryTier()
        }

        // Schedule next retry
        nextRetryScheduledTime = System.currentTimeMillis() + getNextRetryInterval()
    }

    // ============================================================================
    // DATA RETENTION MANAGEMENT
    // ============================================================================

    /**
     * Check if location should be deleted
     * - Synced data: delete immediately (0 days retention)
     * - Pending data: delete after 3 days
     */
    fun shouldDelete(): Boolean {
        if (markedForDeletion) return true

        // Delete synced immediately (SYNCED_DATA_RETENTION_DAYS = 0)
        if (syncStatus == SYNC_SYNCED && syncedAt != null) {
            return true
        }

        // Delete pending after 3 days
        if (syncStatus == SYNC_PENDING || syncStatus == SYNC_FAILED) {
            return isExpired()
        }

        return false
    }

    /**
     * Check if pending location expired (>3 days old)
     */
    fun isExpired(): Boolean {
        if (syncStatus != SYNC_PENDING && syncStatus != SYNC_FAILED) {
            return false
        }

        val ageMs = System.currentTimeMillis() - createdAt
        val maxAgeMs = LocationConstants.PENDING_DATA_RETENTION_DAYS * 24 * 60 * 60 * 1000L
        return ageMs > maxAgeMs
    }

    /**
     * Mark location for deletion
     */
    fun markForDeletion() {
        markedForDeletion = true
    }

    // ============================================================================
    // PRIORITY DETERMINATION
    // ============================================================================

    /**
     * Determine priority based on mode flags
     * Emergency = 2 (Critical), Realtime = 1 (High), Normal = 0
     */
    fun determinePriority(): Int {
        return when {
            emergencyMode -> PRIORITY_CRITICAL
            realtimeMode -> PRIORITY_HIGH
            else -> PRIORITY_NORMAL
        }
    }

    // ============================================================================
    // UPLOAD PAYLOAD CONVERSION
    // ============================================================================

    /**
     * Convert to backend upload payload
     */
    fun toUploadPayload(): Map<String, Any?> {
        return mapOf(
            // Core location
            "userId" to userId,
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy" to accuracy,
            "accuracyLevel" to accuracyLevel,

            // Provider
            "provider" to provider,
            "providerType" to providerType,
            "providerFallback" to providerFallback,

            // Timestamps
            "timestamp" to clientTimestamp,
            "clientTimestamp" to clientTimestamp,
            "createdAt" to createdAt,

            // Mode flags
            "forceCheckEnabled" to forceCheckEnabled,
            "realtimeMode" to realtimeMode,
            "emergencyMode" to emergencyMode,

            // Battery
            "batteryLevel" to batteryLevel,
            "batteryThresholdActive" to batteryThresholdActive,
            "forcedInterval" to forcedInterval,

            // Interval
            "intervalAppliedMs" to intervalAppliedMs,
            "intervalType" to intervalType,

            // Movement
            "movementStatus" to movementStatus,
            "speed" to speed,

            // Network
            "networkQuality" to networkQuality,
            "networkType" to networkType,

            // Acquisition
            "acquisitionDurationMs" to acquisitionDurationMs,
            "gpsRetryAttempts" to gpsRetryAttempts,
            "locationRequestTimedOut" to locationRequestTimedOut,

            // Priority
            "priority" to priority,
            "isCritical" to isCritical,

            // Device info
            "deviceUptimeMs" to deviceUptimeMs,
            "appVersion" to appVersion,
            "osVersion" to osVersion,

            // GPS specific
            "satelliteCount" to satelliteCount,
            "altitude" to altitude,
            "bearing" to bearing
        )
    }

    /**
     * Convert to JSON string for backend
     */
    fun toJsonString(): String {
        val payload = toUploadPayload()
        return org.json.JSONObject(payload).toString()
    }

    // ============================================================================
    // SYNC MANAGEMENT
    // ============================================================================

    /**
     * Mark as synced successfully
     */
    fun markAsSynced() {
        syncStatus = SYNC_SYNCED
        syncedAt = System.currentTimeMillis()
    }

    /**
     * Mark as failed with error
     */
    fun markAsFailed(errorMsg: String, errorCod: Int? = null) {
        syncStatus = SYNC_FAILED
        errorMessage = errorMsg
        errorCode = errorCod
        errorTimestamp = System.currentTimeMillis()
    }

    /**
     * Mark as in progress
     */
    fun markAsInProgress() {
        syncStatus = SYNC_IN_PROGRESS
    }

    // ============================================================================
    // BATCH MANAGEMENT
    // ============================================================================

    /**
     * Assign to batch
     */
    fun assignToBatch(batchIdentifier: String, sequence: Int) {
        batchId = batchIdentifier
        batchSequence = sequence
    }

    /**
     * Record upload attempt
     */
    fun recordUploadAttempt() {
        uploadAttempts++
        lastUploadAttemptTime = System.currentTimeMillis()
    }

    // ============================================================================
    // UTILITY METHODS
    // ============================================================================

    /**
     * Get age in milliseconds
     */
    fun getAgeMs(): Long {
        return System.currentTimeMillis() - createdAt
    }

    /**
     * Get age in days
     */
    fun getAgeDays(): Int {
        val ageMs = getAgeMs()
        return (ageMs / (1000 * 60 * 60 * 24)).toInt()
    }

    /**
     * Check if location is fresh (< 5 minutes old)
     */
    fun isFresh(): Boolean {
        val ageMs = getAgeMs()
        return ageMs < (5 * 60 * 1000)  // 5 minutes
    }

    /**
     * Check if high accuracy (Excellent or Better)
     */
    fun isHighAccuracy(): Boolean {
        return accuracyLevel == ACCURACY_EXCELLENT || accuracyLevel == ACCURACY_BETTER
    }

    /**
     * Get retry tier name
     */
    fun getRetryTierName(): String {
        return when (currentRetryTier) {
            RETRY_TIER_1 -> "Tier 1 (1 min)"
            RETRY_TIER_2 -> "Tier 2 (5 min)"
            RETRY_TIER_3 -> "Tier 3 (10 min, unlimited)"
            else -> "Unknown"
        }
    }

    /**
     * Get human-readable status
     */
    override fun toString(): String {
        return "LocationData(lat=$latitude, lon=$longitude, " +
               "accuracy=$accuracyLevel, provider=$providerType, " +
               "mode=${if (emergencyMode) "Emergency" else if (realtimeMode) "Realtime" else "Normal"}, " +
               "sync=$syncStatus, tier=$currentRetryTier, priority=$priority)"
    }
}

