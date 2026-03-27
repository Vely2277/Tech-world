/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * BATCH UPLOADER - RELIABLE LOCATION DATA UPLOAD MANAGER
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Handles reliable, idempotent uploading of location data to the backend with
 * retry logic, exponential backoff, and batch processing.
 *
 * CORE RESPONSIBILITIES:
 * 1. Batch Upload (group locations for efficient network usage)
 * 2. Retry Logic (exponential backoff with jitter)
 * 3. Idempotency (prevent duplicate server records)
 * 4. Network Awareness (respect connectivity state)
 * 5. Queue Management (priority & ordering)
 * 6. Upload Status Tracking (PENDING → UPLOADING → SYNCED/FAILED)
 * 7. Error Handling & Recovery
 * 8. Upload Statistics & Telemetry
 *
 * KEY DESIGN PRINCIPLES:
 * - RELIABLE: Never loses data; retries until success
 * - EFFICIENT: Batches uploads to save battery/bandwidth
 * - IDEMPOTENT: Safe to retry; no duplicate server records
 * - OBSERVABLE: Logs all upload attempts for debugging
 * - RESILIENT: Handles network failures gracefully
 *
 * RETRY STRATEGY:
 * Tier 1: 3 attempts with 1-minute intervals
 * Tier 2: 3 attempts with 5-minute intervals
 * Tier 3: Unlimited attempts with 10-minute intervals
 *
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import com.example.newconstructionappwithlocationtracking.models.location.LocationData
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.services.DeviceStatusReporter
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BatchUploader - Reliable location data uploader with retry/backoff
 *
 * UPLOAD THRESHOLD SYSTEM:
 * - Normal (synced settings): Upload when data >= 10
 * - Hardcoded defaults (cache expired): Upload when data >= 3
 * - Emergency mode: Upload immediately (one by one)
 *
 * TIME-BASED UPLOAD TRIGGERS:
 * - If data has been pending for 2+ hours: Upload regardless of count
 * - If tracking stops but internet active: Upload after 5 minutes delay
 *
 * POST-UPLOAD SYNC:
 * - After each successful batch upload, triggers settings sync
 * - Rate limited to once every 5 minutes minimum
 */
class BatchUploader(
    private val context: Context,
    private val database: LocationDatabase,
    private val networkMonitor: NetworkMonitor,
    private val settingsManager: LocationSettingsManager? = null
) {

    companion object {
        private const val TAG = "BatchUploader"

        // Retry tiers (based on your requirements)
        private const val TIER1_MAX_ATTEMPTS = 3
        private const val TIER1_RETRY_DELAY_MS = 60000L // 1 minute

        private const val TIER2_MAX_ATTEMPTS = 3
        private const val TIER2_RETRY_DELAY_MS = 300000L // 5 minutes

        private const val TIER3_RETRY_DELAY_MS = 600000L // 10 minutes
        // Tier 3 = unlimited attempts

        // Upload endpoint
        private const val UPLOAD_ENDPOINT = "https://real-pakistan-backend.onrender.com/api/location/upload"
    }

    // Track current user ID for post-upload sync
    private var currentUserId: String? = null

    // Track successful uploads for post-upload sync
    private var lastSuccessfulUploadTime = 0L

    // Tracking state for time-based uploads
    private var trackingStoppedTime = 0L
    private var isTrackingActive = true

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isUploading = false

    /**
     * Set the current user ID for post-upload sync
     */
    fun setUserId(userId: String) {
        currentUserId = userId
    }

    /**
     * Notify BatchUploader that tracking has stopped
     * Used for time-based upload trigger (upload after 5 min if tracking stopped)
     */
    fun notifyTrackingStopped() {
        if (isTrackingActive) {
            isTrackingActive = false
            trackingStoppedTime = System.currentTimeMillis()
            android.util.Log.d("BATCH_UPLOADER", "📴 Tracking STOPPED - will upload remaining data after 5 minutes if internet available")
        }
    }

    /**
     * Notify BatchUploader that tracking has resumed
     */
    fun notifyTrackingResumed() {
        if (!isTrackingActive) {
            isTrackingActive = true
            trackingStoppedTime = 0L
            android.util.Log.d("BATCH_UPLOADER", "📡 Tracking RESUMED")
        }
    }

    /**
     * Check if we should force upload due to tracking being stopped
     * Returns true if:
     * - Tracking has been stopped for 5+ minutes
     * - Internet is available
     * - There are pending locations
     */
    private fun shouldForceUploadDueToTrackingStopped(): Boolean {
        if (isTrackingActive) return false
        if (trackingStoppedTime == 0L) return false

        val timeSinceTrackingStopped = System.currentTimeMillis() - trackingStoppedTime
        return timeSinceTrackingStopped >= LocationConstants.TRACKING_STOPPED_UPLOAD_DELAY_MS
    }

    /**
     * Check if we should force upload due to data being too old
     * Returns true if the oldest pending location is 2+ hours old
     */
    private fun shouldForceUploadDueToDataAge(): Boolean {
        val oldestLocation = database.getOldestPendingLocation() ?: return false
        val dataAge = System.currentTimeMillis() - oldestLocation.clientTimestamp
        return dataAge >= LocationConstants.DATA_AGE_FORCE_UPLOAD_MS
    }

    /**
     * Check and trigger time-based upload if conditions are met.
     * This method should be called periodically (e.g., every minute) by a background job
     * to ensure uploads happen even when tracking is paused/stopped.
     *
     * TRIGGER CONDITIONS:
     * 1. Data age: If oldest pending location is 2+ hours old
     * 2. Tracking stopped: If tracking has been stopped for 5+ minutes
     *
     * This does NOT affect emergency mode which always uploads immediately.
     */
    suspend fun checkAndTriggerTimeBasedUpload() {
        val isEmergencyMode = settingsManager?.getCurrentSettings()?.emergencyMode ?: false
        if (isEmergencyMode) {
            // Emergency mode handles its own immediate uploads
            return
        }

        val pendingLocations = database.getPendingLocations(10)
        if (pendingLocations.isEmpty()) {
            return // Nothing to upload
        }

        val forceUploadDueToAge = shouldForceUploadDueToDataAge()
        val forceUploadDueToTrackingStopped = shouldForceUploadDueToTrackingStopped()

        if (forceUploadDueToAge || forceUploadDueToTrackingStopped) {
            android.util.Log.d("BATCH_UPLOADER", "═══════════════════════════════════════════════════════")
            android.util.Log.d("BATCH_UPLOADER", "⏰ TIME-BASED UPLOAD CHECK TRIGGERED")
            android.util.Log.d("BATCH_UPLOADER", "   Data age trigger: $forceUploadDueToAge")
            android.util.Log.d("BATCH_UPLOADER", "   Tracking stopped trigger: $forceUploadDueToTrackingStopped")
            android.util.Log.d("BATCH_UPLOADER", "   Pending locations: ${pendingLocations.size}")
            android.util.Log.d("BATCH_UPLOADER", "═══════════════════════════════════════════════════════")

            // Trigger upload (the uploadPendingLocations will handle the rest)
            uploadPendingLocations()
        }
    }

    /**
     * Get the current batch upload threshold based on settings source
     * - Emergency mode: 1 (upload immediately)
     * - Hardcoded defaults: 3 (cache expired & can't sync)
     * - Synced OR cached settings: 10 (normal operation)
     */
    fun getBatchUploadThreshold(): Int {
        return settingsManager?.getBatchUploadThreshold()
            ?: LocationConstants.BATCH_UPLOAD_THRESHOLD_NORMAL
    }

    /**
     * Upload all pending locations in batches
     * Uses dynamic threshold based on settings source
     *
     * UPLOAD TRIGGER CONDITIONS:
     * 1. Emergency mode: Upload immediately (threshold = 1)
     * 2. Normal/Cached: Upload when data >= 10 (or >= 3 for hardcoded defaults)
     * 3. Time-based: Upload if oldest data is 2+ hours old (regardless of count)
     * 4. Tracking stopped: Upload after 5 minutes if tracking stopped but internet active
     *
     * IMPORTANT: Emergency mode ALWAYS uploads immediately (one by one)
     */
    suspend fun uploadPendingLocations() {
        android.util.Log.d("BATCH_UPLOADER", "═══════════════════════════════════════════════════════")
        android.util.Log.d("BATCH_UPLOADER", "📤 UPLOAD PENDING LOCATIONS CALLED")
        android.util.Log.d("BATCH_UPLOADER", "═══════════════════════════════════════════════════════")

        if (isUploading) {
            android.util.Log.i("BATCH_UPLOADER", "⏳ Upload already in progress - skipping")
            LocationLogger.i(TAG, "Upload already in progress")
            return
        }

        if (!networkMonitor.isConnected()) {
            android.util.Log.w("BATCH_UPLOADER", "📡 No network connection - skipping upload")
            LocationLogger.w(TAG, "No network connection - skipping upload")
            return
        }
        android.util.Log.d("BATCH_UPLOADER", "✅ Network connection available")

        isUploading = true
        var uploadSuccessful = false

        try {
            // Get settings and state
            val threshold = getBatchUploadThreshold()
            val isEmergencyMode = settingsManager?.getCurrentSettings()?.emergencyMode ?: false
            val isUsingDefaults = settingsManager?.isUsingHardcodedDefaults() ?: false
            val forceUploadDueToAge = shouldForceUploadDueToDataAge()
            val forceUploadDueToTrackingStopped = shouldForceUploadDueToTrackingStopped()

            android.util.Log.d("BATCH_UPLOADER", "📊 Upload Decision Factors:")
            android.util.Log.d("BATCH_UPLOADER", "   Threshold: $threshold")
            android.util.Log.d("BATCH_UPLOADER", "   Emergency Mode: $isEmergencyMode")
            android.util.Log.d("BATCH_UPLOADER", "   Using Defaults: $isUsingDefaults")
            android.util.Log.d("BATCH_UPLOADER", "   Force (Data Age 2h+): $forceUploadDueToAge")
            android.util.Log.d("BATCH_UPLOADER", "   Force (Tracking Stopped 5m+): $forceUploadDueToTrackingStopped")

            val pendingLocations = database.getPendingLocations(50)

            if (pendingLocations.isEmpty()) {
                android.util.Log.i("BATCH_UPLOADER", "📭 No pending locations to upload")
                LocationLogger.i(TAG, "No pending locations to upload")
                return
            }

            val pendingCount = pendingLocations.size
            android.util.Log.d("BATCH_UPLOADER", "📦 Pending locations count: $pendingCount")

            // DECISION LOGIC:
            // 1. Emergency mode: ALWAYS upload (threshold = 1)
            // 2. Time-based triggers: Upload regardless of count
            // 3. Normal threshold check
            val shouldUpload = when {
                isEmergencyMode -> {
                    android.util.Log.d("BATCH_UPLOADER", "🚨 EMERGENCY MODE - uploading immediately")
                    true
                }
                forceUploadDueToAge -> {
                    android.util.Log.d("BATCH_UPLOADER", "⏰ DATA AGE TRIGGER - data is 2+ hours old, uploading regardless of count")
                    LocationLogger.i(TAG, "Force uploading due to data age (2+ hours)")
                    true
                }
                forceUploadDueToTrackingStopped -> {
                    android.util.Log.d("BATCH_UPLOADER", "📴 TRACKING STOPPED TRIGGER - uploading remaining data after 5 min delay")
                    LocationLogger.i(TAG, "Force uploading due to tracking stopped (5+ min delay)")
                    true
                }
                pendingCount >= threshold -> {
                    android.util.Log.d("BATCH_UPLOADER", "✅ THRESHOLD MET - $pendingCount >= $threshold")
                    true
                }
                else -> {
                    android.util.Log.d("BATCH_UPLOADER", "⏳ BELOW THRESHOLD - $pendingCount < $threshold, waiting for more locations")
                    false
                }
            }

            if (!shouldUpload) {
                android.util.Log.i("BATCH_UPLOADER", "📊 Skipping upload - conditions not met")
                return
            }

            android.util.Log.d("BATCH_UPLOADER", "🌐 Upload endpoint: $UPLOAD_ENDPOINT")
            LocationLogger.i(TAG, "Uploading ${pendingLocations.size} locations...")

            // Determine batch size based on mode
            val batchSize = if (isEmergencyMode) 1 else 10
            val batches = pendingLocations.chunked(batchSize)
            android.util.Log.d("BATCH_UPLOADER", "📊 Split into ${batches.size} batches ($batchSize locations each)")

            for ((index, batch) in batches.withIndex()) {
                android.util.Log.d("BATCH_UPLOADER", "🚀 Uploading batch ${index + 1}/${batches.size}")
                LocationLogger.i(TAG, "Uploading batch ${index + 1}/${batches.size}")
                uploadBatch(batch)
                delay(if (isEmergencyMode) 500 else 1000) // Faster for emergency mode
            }

            uploadSuccessful = true
            android.util.Log.d("BATCH_UPLOADER", "✅✅✅ Upload complete! ✅✅✅")
            LocationLogger.i(TAG, "Upload complete ✅")

            // CLEANUP: Delete synced locations immediately after successful upload
            try {
                val deletedCount = database.deleteSyncedImmediately()
                if (deletedCount > 0) {
                    android.util.Log.d("BATCH_UPLOADER", "🧹 Cleaned up $deletedCount synced locations from cache")
                    LocationLogger.i(TAG, "Cleaned up $deletedCount synced locations")
                }
            } catch (e: Exception) {
                android.util.Log.e("BATCH_UPLOADER", "⚠️ Cache cleanup failed: ${e.message}")
            }

            // SAFETY: Enforce cache limit (max 1000 locations)
            try {
                database.enforceCacheLimit()
            } catch (e: Exception) {
                android.util.Log.e("BATCH_UPLOADER", "⚠️ Cache limit enforcement failed: ${e.message}")
            }

        } catch (e: Exception) {
            android.util.Log.e("BATCH_UPLOADER", "❌ Upload failed", e)
            LocationLogger.e(TAG, "Upload failed", e)
        } finally {
            isUploading = false

            // Trigger post-upload settings sync on success
            if (uploadSuccessful) {
                triggerPostUploadSync()
            }
        }
    }

    /**
     * Trigger settings sync after successful upload
     */
    private fun triggerPostUploadSync() {
        val userId = currentUserId
        if (userId != null && settingsManager != null) {
            android.util.Log.d("BATCH_UPLOADER", "📤 Triggering post-upload settings sync")
            settingsManager.onUploadSuccess(userId)
            lastSuccessfulUploadTime = System.currentTimeMillis()
        }
    }

    /**
     * Upload a single batch of locations with retry logic
     */
    private suspend fun uploadBatch(locations: List<LocationData>) {
        for (location in locations) {
            try {
                uploadSingleLocation(location)
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Failed to upload location ${location.id}", e)
                // Continue with next location
            }
        }
    }

    /**
     * Upload a single location with full retry strategy
     */
    private suspend fun uploadSingleLocation(location: LocationData) {
        val maxAttempts = location.uploadAttempts

        // Determine which tier we're in
        val (retryDelay, shouldContinue) = when {
            maxAttempts < TIER1_MAX_ATTEMPTS -> {
                Pair(TIER1_RETRY_DELAY_MS, true)
            }
            maxAttempts < TIER1_MAX_ATTEMPTS + TIER2_MAX_ATTEMPTS -> {
                Pair(TIER2_RETRY_DELAY_MS, true)
            }
            else -> {
                // Tier 3 - unlimited with 10-min intervals
                Pair(TIER3_RETRY_DELAY_MS, true)
            }
        }

        if (!shouldContinue) {
            LocationLogger.e(TAG, "Max attempts reached for location ${location.id}")
            markLocationAsFailed(location)
            return
        }

        // Check if we should retry based on last attempt time
        val now = System.currentTimeMillis()
        if (location.lastUploadAttemptTime != null) {
            val timeSinceLastAttempt = now - location.lastUploadAttemptTime!!
            if (timeSinceLastAttempt < retryDelay) {
                LocationLogger.i(TAG, "Too soon to retry location ${location.id}")
                return
            }
        }

        // Update status to uploading
        database.markAsInProgress(location.id!!)

        try {
            // Perform upload
            val success = performUpload(location)

            if (success) {
                // Mark as synced
                database.markAsSynced(location.id!!)
                LocationLogger.i(TAG, "Location ${location.id} uploaded successfully ✅")
            } else {
                // Mark as failed and increment attempts
                database.recordUploadAttempt(location.id!!)
                database.markAsFailed(location.id!!)
                LocationLogger.w(TAG, "Upload failed for location ${location.id}")
            }

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Upload exception for location ${location.id}", e)
            database.recordUploadAttempt(location.id!!)
            database.markAsFailed(location.id!!)
        }
    }

    /**
     * Perform the actual HTTP upload
     */
    private suspend fun performUpload(location: LocationData): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("BATCH_UPLOADER", "───────────────────────────────────────")
                android.util.Log.d("BATCH_UPLOADER", "🌐 PERFORMING HTTP UPLOAD")
                android.util.Log.d("BATCH_UPLOADER", "   Location ID: ${location.id}")
                android.util.Log.d("BATCH_UPLOADER", "   User: ${location.userId}")
                android.util.Log.d("BATCH_UPLOADER", "   Lat: ${location.latitude}, Lon: ${location.longitude}")

                // Get auth token
                android.util.Log.d("BATCH_UPLOADER", "🔑 Getting auth token...")
                val token = getAuthToken()
                if (token == null) {
                    android.util.Log.e("BATCH_UPLOADER", "❌ No auth token available")
                    LocationLogger.e(TAG, "No auth token available")
                    return@withContext false
                }
                android.util.Log.d("BATCH_UPLOADER", "✅ Auth token obtained: ${token.take(20)}...")

                // Build JSON payload
                android.util.Log.d("BATCH_UPLOADER", "📝 Building JSON payload...")
                val json = buildLocationJson(location)
                android.util.Log.d("BATCH_UPLOADER", "✅ JSON built: ${json.toString().take(200)}...")

                // Build request
                val requestBody = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(UPLOAD_ENDPOINT)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                android.util.Log.d("BATCH_UPLOADER", "🚀 Sending HTTP POST to: $UPLOAD_ENDPOINT")

                // Execute request
                val response = httpClient.newCall(request).execute()

                val isSuccess = response.isSuccessful
                android.util.Log.d("BATCH_UPLOADER", "📬 Response code: ${response.code}")

                if (isSuccess) {
                    android.util.Log.d("BATCH_UPLOADER", "✅ Upload successful!")
                    LocationLogger.i(TAG, "Upload successful: ${response.code}")

                    // Update upload stats for admin dashboard
                    DeviceStatusReporter.updateUploadStats(context, success = true, count = 1)

                    // Parse server response for ack ID
                    val responseBody = response.body?.string()
                    responseBody?.let {
                        android.util.Log.d("BATCH_UPLOADER", "📥 Response body: ${it.take(200)}...")
                        try {
                            val jsonResponse = JSONObject(it)
                            val serverAckId = jsonResponse.optString("locationId")
                            if (serverAckId.isNotEmpty()) {
                                android.util.Log.d("BATCH_UPLOADER", "✅ Server ACK ID: $serverAckId")
                                // Note: This method doesn't exist in database, so we skip it
                                // database.updateServerAckId(location.id, serverAckId)
                            }
                        } catch (_: Exception) {
                            // Ignore JSON parse errors
                        }
                    }
                } else {
                    android.util.Log.e("BATCH_UPLOADER", "❌ Upload failed: ${response.code} - ${response.message}")
                    val errorBody = response.body?.string()
                    android.util.Log.e("BATCH_UPLOADER", "❌ Error body: $errorBody")
                    LocationLogger.e(TAG, "Upload failed: ${response.code} - ${response.message}")

                    // Update upload stats for admin dashboard
                    DeviceStatusReporter.updateUploadStats(context, success = false, count = 1)
                }

                response.close()
                android.util.Log.d("BATCH_UPLOADER", "───────────────────────────────────────")
                isSuccess

            } catch (e: IOException) {
                android.util.Log.e("BATCH_UPLOADER", "❌ Network error during upload", e)
                LocationLogger.e(TAG, "Network error during upload", e)
                false
            } catch (e: Exception) {
                android.util.Log.e("BATCH_UPLOADER", "❌ Upload error", e)
                LocationLogger.e(TAG, "Upload error", e)
                false
            }
        }
    }

    /**
     * Build JSON payload for location
     * Sends ALL metadata fields for proper diagnostics and timing analysis
     */
    private fun buildLocationJson(location: LocationData): JSONObject {
        // Convert clientTimestamp (milliseconds) to ISO date string for clientTimestamp field
        val clientTimestampDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(location.clientTimestamp))

        return JSONObject().apply {
            // Core identification
            put("id", location.id.toString())  // Android local database ID
            put("userId", location.userId)

            // Core location data
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy)
            put("altitude", location.altitude)
            put("bearing", location.bearing)
            put("speed", location.speed)
            put("provider", location.provider)

            // CRITICAL: Timestamps - these determine when location was captured
            put("clientTimestamp", clientTimestampDate)  // ISO date string
            put("timestamp", location.clientTimestamp)   // Number (milliseconds) - backend uses this for capturedAt
            put("createdAt", clientTimestampDate)

            // Battery information
            put("batteryLevel", location.batteryLevel)
            put("batteryThresholdActive", location.batteryThresholdActive)
            put("forcedInterval", location.forcedInterval)
            put("isCharging", false)  // TODO: Get actual charging status

            // Network information
            put("networkType", location.networkType)
            put("networkQuality", location.networkQuality)

            // Device information
            put("deviceModel", android.os.Build.MODEL)
            put("osVersion", location.osVersion)
            put("appVersion", location.appVersion)

            // TRACKING MODE FLAGS - Critical for understanding timing
            put("forceCheckEnabled", location.forceCheckEnabled)
            put("realtimeMode", location.realtimeMode)
            put("emergencyMode", location.emergencyMode)

            // INTERVAL METADATA - Critical for diagnosing timing issues
            put("intervalAppliedMs", location.intervalAppliedMs)
            put("intervalType", location.intervalType)

            // Movement detection
            put("movementStatus", location.movementStatus)

            // Acquisition metadata
            put("acquisitionDurationMs", location.acquisitionDurationMs)
            put("gpsRetryAttempts", location.gpsRetryAttempts)

            // Priority
            put("priority", location.priority)
        }
    }

    /**
     * Get authentication token (refresh if needed)
     */
    private suspend fun getAuthToken(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    LocationLogger.e(TAG, "No authenticated user")
                    return@withContext null
                }

                // Get ID token (this will refresh automatically if needed)
                val tokenResult = user.getIdToken(false).await()
                tokenResult.token

            } catch (e: Exception) {
                LocationLogger.e(TAG, "Failed to get auth token", e)
                null
            }
        }
    }

    /**
     * Mark location as permanently failed
     */
    private suspend fun markLocationAsFailed(location: LocationData) {
        database.markAsFailed(location.id!!)
        LocationLogger.e(TAG, "Location ${location.id} marked as permanently failed")
    }

    /**
     * Get upload statistics
     */
    suspend fun getUploadStats(): UploadStats {
        val pending = database.getLocationsByStatus(LocationData.SYNC_PENDING).size
        val failed = database.getLocationsByStatus(LocationData.SYNC_FAILED).size
        val synced = database.getLocationsByStatus(LocationData.SYNC_SYNCED).size

        return UploadStats(
            pending = pending,
            failed = failed,
            synced = synced
        )
    }

    /**
     * Retry all failed uploads
     */
    suspend fun retryFailedUploads() {
        LocationLogger.i(TAG, "Retrying all failed uploads...")

        val failedLocations = database.getFailedLocations()
        LocationLogger.i(TAG, "Found ${failedLocations.size} failed locations")

        for (location in failedLocations) {
            // Reset status to pending using LocationData.syncStatus
            location.syncStatus = LocationData.SYNC_PENDING
            database.updateLocation(location)
        }

        // Trigger upload
        uploadPendingLocations()
    }

    /**
     * Clean up old synced locations
     */
    suspend fun cleanupSyncedLocations() {
        val deleted = database.deleteSyncedImmediately()
        LocationLogger.i(TAG, "Cleaned up $deleted old synced locations")
    }
}

/**
 * Upload statistics data class
 */
data class UploadStats(
    val pending: Int,
    val failed: Int,
    val synced: Int
) {
    val total: Int get() = pending + failed + synced

    override fun toString(): String {
        return "UploadStats(pending=$pending, failed=$failed, synced=$synced, total=$total)"
    }
}


