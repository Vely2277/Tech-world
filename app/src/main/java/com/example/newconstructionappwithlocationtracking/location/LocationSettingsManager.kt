/*
 * ============================================================================
 * LOCATION SETTINGS MANAGER - HYBRID SYNC SYSTEM IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Complete settings synchronization system using HYBRID ARCHITECTURE:
 * - Backend (source of truth) validates and writes to Firestore
 * - Android app reads DIRECTLY from Firestore for instant real-time updates
 * - Local SharedPreferences caching for offline operation
 * - Automatic mode expiration (Emergency: 15min, ForceCheck: 30min)
 * - Comprehensive validation and error handling
 *
 * HYBRID SYNC STRATEGY:
 * 1. Sync every 10 minutes from last successful sync
 * 2. Also sync after each successful batch upload (rate limited: 5min min)
 * 3. On app restart: Use cached settings immediately, sync in background
 * 4. Realtime listener for instant admin changes (counts as sync)
 * 5. Auto-reset modes when expired (Emergency: 15min, ForceCheck: 30min)
 * 6. If cache > 1 hour old and can't sync: Use hardcoded defaults
 * 7. Network retry: If sync fails, retry every 5 minutes
 * 8. Auto-reset notifies backend to turn off modes there too
 *
 * BATCH UPLOAD THRESHOLDS:
 * - Using synced OR cached settings: Upload when data >= 10
 * - Using hardcoded defaults (cache expired & can't sync): Upload when data >= 3
 * - Emergency mode: Upload immediately (one by one)
 *
 * AUTO-RESET SYSTEM:
 * - Emergency mode: Auto-disabled after 15 minutes of being active
 * - ForceCheck mode: Auto-disabled after 30 minutes of being active
 * - Both reset on app side AND notified to backend
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.models.location.LocationSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.math.min

class LocationSettingsManager(private val context: Context) {

    private val firestore = FirebaseFirestore.getInstance()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var currentSettings: LocationSettings = LocationSettings.getDefault()
    private var settingsListener: ListenerRegistration? = null
    private var syncJob: Job? = null
    private var debounceJob: Job? = null
    private var autoResetJob: Job? = null
    private var retrySyncJob: Job? = null
    private val callbacks = mutableListOf<SettingsCallback>()

    // HTTP client for backend notifications
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Current user ID
    private var currentUserId: String? = null

    // Sync state
    private var isSyncing = false
    private var lastSyncTime = 0L
    private var lastPostUploadSyncTime = 0L
    private var lastSyncSuccess = false
    private var isUsingHardcodedDefaults = false
    private var pendingSyncRetry = false

    // Mode activation tracking
    private var emergencyModeActivatedAt = 0L
    private var forceCheckActivatedAt = 0L
    private var realtimeModeActivatedAt = 0L

    // Listener retry state
    private var retryAttempt = 0
    private var retryDelay = 1000L
    private val maxRetryDelay = 300000L  // 5 minutes

    // Retry sync interval
    private val RETRY_SYNC_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes

    companion object {
        private const val TAG = LocationConstants.TAG_SETTINGS

        // Firestore paths - use user-specific document
        private const val SETTINGS_COLLECTION = "location-tracking-settings"

        // Sync intervals
        private const val SYNC_INTERVAL_MS = LocationConstants.SETTINGS_SYNC_INTERVAL_MS  // 10 minutes
        private const val POST_UPLOAD_SYNC_MIN_INTERVAL = LocationConstants.POST_UPLOAD_SYNC_MIN_INTERVAL_MS  // 5 minutes
        private const val CACHE_MAX_AGE_MS = LocationConstants.SETTINGS_CACHE_MAX_AGE_MS  // 1 hour
        private const val DEBOUNCE_DELAY_MS = 30000L  // 30 seconds

        // Auto-reset durations
        private const val EMERGENCY_AUTO_RESET_MS = LocationConstants.EMERGENCY_MODE_AUTO_RESET_MS  // 15 minutes
        private const val FORCECHECK_AUTO_RESET_MS = LocationConstants.FORCECHECK_AUTO_RESET_MS  // 30 minutes
        private const val REALTIME_AUTO_RESET_MS = LocationConstants.REALTIME_MODE_AUTO_RESET_MS  // 30 minutes

        // SharedPreferences
        private const val PREFS_NAME = "location_settings_cache"
        private const val KEY_NORMAL_INTERVAL = "normalIntervalMs"
        private const val KEY_REALTIME_INTERVAL = "realtimeIntervalMs"
        private const val KEY_EMERGENCY_INTERVAL = "emergencyIntervalMs"
        private const val KEY_FORCECHECK_INTERVAL = "forceCheckIntervalMs"
        private const val KEY_FORCECHECK = "forceCheck"
        private const val KEY_REALTIME_MODE = "realtimeMode"
        private const val KEY_EMERGENCY_MODE = "emergencyMode"
        private const val KEY_TRACKING_ENABLED = "trackingEnabled"
        private const val KEY_LAST_UPDATED = "lastUpdated"
        private const val KEY_SOURCE = "source"
        private const val KEY_VERSION = "version"
        private const val KEY_CACHE_TIMESTAMP = "cacheTimestamp"
        private const val KEY_EMERGENCY_ACTIVATED_AT = "emergencyActivatedAt"
        private const val KEY_FORCECHECK_ACTIVATED_AT = "forceCheckActivatedAt"
        private const val KEY_REALTIME_ACTIVATED_AT = "realtimeActivatedAt"
        private const val KEY_LAST_SYNC_TIME = "lastSyncTime"
        private const val KEY_LAST_POST_UPLOAD_SYNC = "lastPostUploadSync"
        private const val KEY_LAST_ADMIN_CHANGE_TIME = "lastAdminChangeTime"
    }

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    /**
     * Start settings synchronization with hybrid approach
     */
    fun startSync(userId: String) {
        if (isSyncing) {
            LocationLogger.w(TAG, "Settings sync already running")
            android.util.Log.w("SETTINGS_SYNC", "⚠️ startSync() already running - skipping")
            return
        }

        currentUserId = userId

        LocationLogger.i(TAG, "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "🚀 Starting HYBRID settings synchronization")
        LocationLogger.i(TAG, "   User ID: $userId")
        LocationLogger.i(TAG, "═══════════════════════════════════════════════════════")

        android.util.Log.d("SETTINGS_SYNC", "═══════════════════════════════════════════════════════")
        android.util.Log.d("SETTINGS_SYNC", "🚀 HYBRID SYNC STARTED")
        android.util.Log.d("SETTINGS_SYNC", "   User: $userId")
        android.util.Log.d("SETTINGS_SYNC", "═══════════════════════════════════════════════════════")

        // Step 1: Load cached settings IMMEDIATELY (app restart fast path)
        android.util.Log.d("SETTINGS_SYNC", "📦 Step 1: Loading cached settings...")
        loadCachedSettings()

        // Step 2: Check if cache is expired and decide on hardcoded defaults
        android.util.Log.d("SETTINGS_SYNC", "🔍 Step 2: Checking cache validity...")
        checkCacheValidityAndApplyDefaults()

        // Step 3: Start real-time listener for instant admin changes
        android.util.Log.d("SETTINGS_SYNC", "🎧 Step 3: Starting realtime Firestore listener...")
        startRealtimeListener(userId)

        // Step 4: Start periodic refresh (every 10 minutes)
        android.util.Log.d("SETTINGS_SYNC", "⏰ Step 4: Starting periodic refresh (10 min)...")
        startPeriodicRefresh(userId)

        // Step 5: Start auto-reset monitor (checks every minute)
        android.util.Log.d("SETTINGS_SYNC", "🔄 Step 5: Starting auto-reset monitor...")
        startAutoResetMonitor()

        // Step 6: Sync immediately in background (don't block service start)
        android.util.Log.d("SETTINGS_SYNC", "🌐 Step 6: Initial background sync...")
        scope.launch {
            try {
                fetchSettingsFromFirebase(userId)
                android.util.Log.d("SETTINGS_SYNC", "✅ Initial sync completed!")
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Initial settings sync failed - using cached/defaults", e)
                android.util.Log.e("SETTINGS_SYNC", "❌ Initial sync FAILED: ${e.message}")
                // Start retry job if sync failed
                startRetrySyncJob(userId)
            }
        }

        isSyncing = true
        android.util.Log.d("SETTINGS_SYNC", "✅ All sync components started!")
    }

    /**
     * Stop settings synchronization
     */
    fun stopSync() {
        LocationLogger.i(TAG, "Stopping settings synchronization")
        android.util.Log.d("SETTINGS_SYNC", "🛑 STOPPING sync...")

        settingsListener?.remove()
        settingsListener = null

        syncJob?.cancel()
        syncJob = null

        debounceJob?.cancel()
        debounceJob = null

        autoResetJob?.cancel()
        autoResetJob = null

        retrySyncJob?.cancel()
        retrySyncJob = null

        isSyncing = false
        android.util.Log.d("SETTINGS_SYNC", "✅ Sync stopped")
    }

    /**
     * Fetch settings synchronously (Service calls this)
     * Returns current in-memory settings immediately
     */
    fun fetchSettings(): LocationSettings {
        // Check for auto-reset before returning
        checkAndApplyAutoReset()
        android.util.Log.d("SETTINGS_SYNC", "📋 fetchSettings() - interval: ${currentSettings.getCurrentInterval()}ms, source: ${currentSettings.source}")
        return currentSettings
    }

    /**
     * Get current settings
     */
    fun getCurrentSettings(): LocationSettings {
        return currentSettings
    }

    /**
     * Check if we're using hardcoded defaults (cache expired & can't sync)
     * Used by BatchUploader to determine upload threshold
     */
    fun isUsingHardcodedDefaults(): Boolean {
        return isUsingHardcodedDefaults
    }

    /**
     * Get the current batch upload threshold based on settings source
     * - Emergency mode: 1 (upload immediately)
     * - Hardcoded defaults: 3 (cache expired & can't sync)
     * - Synced OR cached settings: 10 (normal operation)
     */
    fun getBatchUploadThreshold(): Int {
        return when {
            currentSettings.emergencyMode -> LocationConstants.BATCH_UPLOAD_THRESHOLD_EMERGENCY
            isUsingHardcodedDefaults -> LocationConstants.BATCH_UPLOAD_THRESHOLD_HARDCODED
            else -> LocationConstants.BATCH_UPLOAD_THRESHOLD_NORMAL  // Both synced and cached use 10
        }
    }

    /**
     * Called by BatchUploader after successful upload
     * Triggers settings sync if rate limit allows (5 min minimum between syncs)
     */
    fun onUploadSuccess(userId: String) {
        val now = System.currentTimeMillis()
        val timeSinceLastPostUploadSync = now - lastPostUploadSyncTime

        android.util.Log.d("SETTINGS_SYNC", "📤 onUploadSuccess() - time since last sync: ${timeSinceLastPostUploadSync / 1000}s")

        if (timeSinceLastPostUploadSync >= POST_UPLOAD_SYNC_MIN_INTERVAL) {
            LocationLogger.i(TAG, "📤 Post-upload settings sync triggered")
            android.util.Log.d("SETTINGS_SYNC", "✅ Post-upload sync TRIGGERED")
            scope.launch {
                try {
                    fetchSettingsFromFirebase(userId)
                    lastPostUploadSyncTime = now
                    prefs.edit().putLong(KEY_LAST_POST_UPLOAD_SYNC, now).apply()
                    android.util.Log.d("SETTINGS_SYNC", "✅ Post-upload sync SUCCESS")
                } catch (e: Exception) {
                    LocationLogger.e(TAG, "Post-upload settings sync failed", e)
                    android.util.Log.e("SETTINGS_SYNC", "❌ Post-upload sync FAILED: ${e.message}")
                    // Start retry if failed
                    startRetrySyncJob(userId)
                }
            }
        } else {
            val nextIn = (POST_UPLOAD_SYNC_MIN_INTERVAL - timeSinceLastPostUploadSync) / 1000
            LocationLogger.d(TAG, "Post-upload sync skipped (rate limited). Next allowed in ${nextIn}s")
            android.util.Log.d("SETTINGS_SYNC", "⏳ Post-upload sync rate-limited. Next in ${nextIn}s")
        }
    }

    /**
     * Force immediate settings refresh
     */
    fun forceRefresh(userId: String) {
        scope.launch {
            try {
                fetchSettingsFromFirebase(userId)
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Force refresh failed", e)
            }
        }
    }

    /**
     * Register callback for settings changes
     */
    fun registerCallback(callback: SettingsCallback) {
        callbacks.add(callback)
    }

    /**
     * Unregister callback
     */
    fun unregisterCallback(callback: SettingsCallback) {
        callbacks.remove(callback)
    }

    /**
     * Unregister all callbacks
     */
    fun unregisterAllCallbacks() {
        callbacks.clear()
    }

    /**
     * Get sync status
     */
    fun getSyncStatus(): Map<String, Any> {
        return mapOf(
            "isSyncing" to isSyncing,
            "lastSyncTime" to lastSyncTime,
            "lastSyncSuccess" to lastSyncSuccess,
            "isUsingHardcodedDefaults" to isUsingHardcodedDefaults,
            "currentSource" to currentSettings.source,
            "settingsAge" to (System.currentTimeMillis() - currentSettings.lastUpdated),
            "isOutdated" to currentSettings.isOutdated(),
            "batchThreshold" to getBatchUploadThreshold(),
            "emergencyModeActive" to currentSettings.emergencyMode,
            "forceCheckActive" to currentSettings.forceCheck,
            "retryAttempt" to retryAttempt,
            "retryDelay" to retryDelay,
            "pendingSyncRetry" to pendingSyncRetry
        )
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopSync()
        scope.cancel()
        callbacks.clear()
    }

    // ============================================================================
    // CACHE VALIDITY CHECK & HARDCODED DEFAULTS
    // ============================================================================

    private fun checkCacheValidityAndApplyDefaults() {
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val lastAdminChangeTime = prefs.getLong(KEY_LAST_ADMIN_CHANGE_TIME, 0L)
        val cacheAge = System.currentTimeMillis() - cacheTimestamp
        val timeSinceAdminChange = System.currentTimeMillis() - lastAdminChangeTime

        android.util.Log.d("SETTINGS_SYNC", "🔍 Cache validity:")
        android.util.Log.d("SETTINGS_SYNC", "   Cache age: ${cacheAge / 60000}min (max: ${CACHE_MAX_AGE_MS / 60000}min)")
        android.util.Log.d("SETTINGS_SYNC", "   Time since admin change: ${timeSinceAdminChange / 60000}min")

        if (cacheTimestamp == 0L) {
            // No cache at all - use hardcoded defaults
            LocationLogger.w(TAG, "⚠️ No cache found - using hardcoded defaults (1 hour interval)")
            android.util.Log.w("SETTINGS_SYNC", "⚠️ NO CACHE - using hardcoded defaults")
            applyHardcodedDefaults()
        } else if (cacheAge > CACHE_MAX_AGE_MS && !lastSyncSuccess) {
            // Cache expired (> 1 hour) AND can't sync - switch to hardcoded defaults
            LocationLogger.w(TAG, "⚠️ Cache expired (${cacheAge / 60000}min old) & sync failed - using hardcoded defaults")
            android.util.Log.w("SETTINGS_SYNC", "⚠️ CACHE EXPIRED & sync failed - using hardcoded defaults")
            applyHardcodedDefaults()
        } else if (lastAdminChangeTime > 0 && timeSinceAdminChange > CACHE_MAX_AGE_MS) {
            // No admin changes in the last 1 hour - fall back to hardcoded defaults
            LocationLogger.w(TAG, "⚠️ No admin changes in ${timeSinceAdminChange / 60000}min - using hardcoded defaults")
            android.util.Log.w("SETTINGS_SYNC", "⚠️ No admin changes >1hr - using hardcoded defaults")
            applyHardcodedDefaults()
        } else {
            LocationLogger.i(TAG, "✅ Cache valid (${cacheAge / 60000}min old) - using cached settings")
            android.util.Log.d("SETTINGS_SYNC", "✅ Cache VALID - using cached settings")
            isUsingHardcodedDefaults = false
        }
    }

    private fun applyHardcodedDefaults() {
        isUsingHardcodedDefaults = true
        currentSettings = LocationSettings(
            normalIntervalMs = LocationConstants.HARDCODED_DEFAULT_INTERVAL_MS,  // 1 hour
            realtimeIntervalMs = LocationConstants.FALLBACK_REALTIME_INTERVAL_MS,
            emergencyIntervalMs = LocationConstants.FALLBACK_EMERGENCY_INTERVAL_MS,
            forceCheck = false,
            realtimeMode = false,
            emergencyMode = false,
            trackingEnabled = true,
            source = LocationSettings.SOURCE_DEFAULT,
            lastUpdated = System.currentTimeMillis()
        )
        LocationLogger.i(TAG, "📋 Hardcoded defaults applied: ${currentSettings.toSummaryString()}")
        android.util.Log.d("SETTINGS_SYNC", "📋 HARDCODED DEFAULTS: interval=${currentSettings.normalIntervalMs/1000}s, threshold=3")
    }

    // ============================================================================
    // NETWORK RETRY MECHANISM
    // ============================================================================

    private fun startRetrySyncJob(userId: String) {
        if (pendingSyncRetry) {
            LocationLogger.d(TAG, "Retry sync already scheduled")
            android.util.Log.d("SETTINGS_SYNC", "🔄 Retry already scheduled")
            return
        }

        pendingSyncRetry = true
        android.util.Log.d("SETTINGS_SYNC", "🔄 RETRY JOB started (every 5 min)")
        retrySyncJob?.cancel()
        retrySyncJob = scope.launch {
            while (isActive && pendingSyncRetry) {
                delay(RETRY_SYNC_INTERVAL_MS)  // Wait 5 minutes
                LocationLogger.i(TAG, "🔄 Retry sync attempt after network failure")
                android.util.Log.d("SETTINGS_SYNC", "🔄 Retry attempt...")
                try {
                    fetchSettingsFromFirebase(userId)
                    if (lastSyncSuccess) {
                        LocationLogger.i(TAG, "✅ Retry sync succeeded!")
                        android.util.Log.d("SETTINGS_SYNC", "✅ Retry SUCCESS!")
                        pendingSyncRetry = false
                        break
                    }
                } catch (e: Exception) {
                    LocationLogger.e(TAG, "Retry sync failed, will try again in 5 minutes", e)
                    android.util.Log.e("SETTINGS_SYNC", "❌ Retry failed: ${e.message}")
                }
            }
        }
        LocationLogger.i(TAG, "📅 Retry sync scheduled (every 5 minutes until success)")
    }

    private fun cancelRetrySyncJob() {
        pendingSyncRetry = false
        retrySyncJob?.cancel()
        retrySyncJob = null
    }

    // ============================================================================
    // AUTO-RESET MONITOR WITH BACKEND NOTIFICATION
    // ============================================================================

    private fun startAutoResetMonitor() {
        autoResetJob?.cancel()
        autoResetJob = scope.launch {
            while (isActive) {
                delay(60000) // Check every minute
                checkAndApplyAutoReset()
            }
        }
        LocationLogger.i(TAG, "🔄 Auto-reset monitor started (checks every 60s)")
        android.util.Log.d("SETTINGS_SYNC", "🔄 Auto-reset monitor STARTED (checks every 60s)")
    }

    private fun checkAndApplyAutoReset() {
        val now = System.currentTimeMillis()
        var settingsChanged = false
        val oldSettings = currentSettings
        var emergencyReset = false
        var forceCheckReset = false
        var realtimeReset = false

        // Check Emergency mode auto-reset (15 minutes)
        if (currentSettings.emergencyMode && emergencyModeActivatedAt > 0) {
            val elapsed = now - emergencyModeActivatedAt
            val remaining = (EMERGENCY_AUTO_RESET_MS - elapsed) / 60000
            android.util.Log.d("SETTINGS_SYNC", "🚨 Emergency mode check: elapsed=${elapsed/60000}min, remaining=${remaining}min")
            if (elapsed >= EMERGENCY_AUTO_RESET_MS) {
                LocationLogger.i(TAG, "⏰ Emergency mode AUTO-RESET triggered (active for ${elapsed / 60000}min)")
                android.util.Log.w("SETTINGS_SYNC", "⏰ EMERGENCY AUTO-RESET!")
                currentSettings = currentSettings.copy(emergencyMode = false)
                emergencyModeActivatedAt = 0
                prefs.edit().putLong(KEY_EMERGENCY_ACTIVATED_AT, 0).apply()
                settingsChanged = true
                emergencyReset = true
            }
        }

        // Check ForceCheck auto-reset (30 minutes)
        if (currentSettings.forceCheck && forceCheckActivatedAt > 0) {
            val elapsed = now - forceCheckActivatedAt
            val remaining = (FORCECHECK_AUTO_RESET_MS - elapsed) / 60000
            android.util.Log.d("SETTINGS_SYNC", "🔥 ForceCheck check: elapsed=${elapsed/60000}min, remaining=${remaining}min")
            if (elapsed >= FORCECHECK_AUTO_RESET_MS) {
                LocationLogger.i(TAG, "⏰ ForceCheck AUTO-RESET triggered (active for ${elapsed / 60000}min)")
                android.util.Log.w("SETTINGS_SYNC", "⏰ FORCECHECK AUTO-RESET!")
                currentSettings = currentSettings.copy(forceCheck = false)
                forceCheckActivatedAt = 0
                prefs.edit().putLong(KEY_FORCECHECK_ACTIVATED_AT, 0).apply()
                settingsChanged = true
                forceCheckReset = true
            }
        }

        // Check Realtime mode auto-reset (30 minutes)
        if (currentSettings.realtimeMode && realtimeModeActivatedAt > 0) {
            val elapsed = now - realtimeModeActivatedAt
            val remaining = (REALTIME_AUTO_RESET_MS - elapsed) / 60000
            android.util.Log.d("SETTINGS_SYNC", "⚡ Realtime mode check: elapsed=${elapsed/60000}min, remaining=${remaining}min")
            if (elapsed >= REALTIME_AUTO_RESET_MS) {
                LocationLogger.i(TAG, "⏰ Realtime mode AUTO-RESET triggered (active for ${elapsed / 60000}min)")
                android.util.Log.w("SETTINGS_SYNC", "⏰ REALTIME AUTO-RESET!")
                currentSettings = currentSettings.copy(realtimeMode = false)
                realtimeModeActivatedAt = 0
                prefs.edit().putLong(KEY_REALTIME_ACTIVATED_AT, 0).apply()
                settingsChanged = true
                realtimeReset = true
            }
        }

        if (settingsChanged) {
            // Save updated settings to cache
            cacheSettings(currentSettings)
            // Notify callbacks immediately (auto-reset bypasses debounce)
            notifyCallbacks(currentSettings, oldSettings)

            // Notify backend to turn off modes
            currentUserId?.let { userId ->
                scope.launch {
                    if (emergencyReset) {
                        android.util.Log.d("SETTINGS_SYNC", "📡 Notifying backend of emergency reset...")
                        notifyBackendModeReset(userId, "emergency")
                    }
                    if (forceCheckReset) {
                        android.util.Log.d("SETTINGS_SYNC", "📡 Notifying backend of forceCheck reset...")
                        notifyBackendModeReset(userId, "force")
                    }
                    if (realtimeReset) {
                        android.util.Log.d("SETTINGS_SYNC", "📡 Notifying backend of realtime reset...")
                        notifyBackendModeReset(userId, "realtime")
                    }
                }
            }
        }
    }

    /**
     * Notify backend to disable a mode after auto-reset
     */
    private suspend fun notifyBackendModeReset(userId: String, mode: String) {
        try {
            val backendUrl = getBackendUrlFromEnv()
            val url = "$backendUrl/api/location-tracking-settings/disable-mode"

            val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (token == null) {
                LocationLogger.e(TAG, "Cannot notify backend - no auth token")
                return
            }

            val jsonBody = JSONObject().apply {
                put("mode", mode)
                put("reason", "auto-reset")
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    LocationLogger.i(TAG, "✅ Backend notified of $mode auto-reset")
                } else {
                    LocationLogger.e(TAG, "❌ Failed to notify backend of $mode auto-reset: ${response.code}")
                }
                response.close()
            }
        } catch (e: Exception) {
            LocationLogger.e(TAG, "Error notifying backend of mode reset", e)
        }
    }

    private fun getBackendUrlFromEnv(): String {
        return try {
            val inputStream = context.assets.open(".env")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var backendUrl = "https://real-pakistan-backend.onrender.com"
            while (reader.readLine().also { line = it } != null) {
                if (line?.startsWith("BACKEND_URL=") == true) {
                    backendUrl = line!!.substringAfter("BACKEND_URL=").trim()
                    break
                }
            }
            reader.close()
            backendUrl
        } catch (e: Exception) {
            LocationLogger.w(TAG, ".env not found - using default backend URL")
            "https://real-pakistan-backend.onrender.com"
        }
    }

    // ============================================================================
    // LOAD CACHED SETTINGS FROM SHAREDPREFERENCES
    // ============================================================================

    private fun loadCachedSettings() {
        try {
            // Check if we have cached settings
            if (!prefs.contains(KEY_NORMAL_INTERVAL)) {
                LocationLogger.i(TAG, "No cached settings found, using defaults")
                android.util.Log.d("SETTINGS_SYNC", "📦 No cache found - using defaults")
                return
            }

            // Load all fields from SharedPreferences
            val normalMs = prefs.getLong(KEY_NORMAL_INTERVAL, LocationConstants.FALLBACK_UPDATE_INTERVAL_MS)
            val realtimeMs = prefs.getLong(KEY_REALTIME_INTERVAL, LocationConstants.FALLBACK_REALTIME_INTERVAL_MS)
            val emergencyMs = prefs.getLong(KEY_EMERGENCY_INTERVAL, LocationConstants.FALLBACK_EMERGENCY_INTERVAL_MS)
            val forceCheckMs = if (prefs.contains(KEY_FORCECHECK_INTERVAL)) {
                prefs.getLong(KEY_FORCECHECK_INTERVAL, 0L)
            } else null
            val forceCheck = prefs.getBoolean(KEY_FORCECHECK, false)
            val realtimeMode = prefs.getBoolean(KEY_REALTIME_MODE, false)
            val emergencyMode = prefs.getBoolean(KEY_EMERGENCY_MODE, false)
            val trackingEnabled = prefs.getBoolean(KEY_TRACKING_ENABLED, true)
            val lastUpdated = prefs.getLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            val source = prefs.getString(KEY_SOURCE, LocationSettings.SOURCE_LOCAL) ?: LocationSettings.SOURCE_LOCAL
            val version = prefs.getInt(KEY_VERSION, 1)

            // Load mode activation timestamps
            emergencyModeActivatedAt = prefs.getLong(KEY_EMERGENCY_ACTIVATED_AT, 0L)
            forceCheckActivatedAt = prefs.getLong(KEY_FORCECHECK_ACTIVATED_AT, 0L)
            realtimeModeActivatedAt = prefs.getLong(KEY_REALTIME_ACTIVATED_AT, 0L)
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
            lastPostUploadSyncTime = prefs.getLong(KEY_LAST_POST_UPLOAD_SYNC, 0L)

            // CRITICAL FIX: Check if modes have expired and reset them
            val now = System.currentTimeMillis()
            var emergencyModeAdjusted = emergencyMode
            var forceCheckAdjusted = forceCheck
            var realtimeModeAdjusted = realtimeMode

            // Auto-reset Emergency mode if > 15 minutes old
            if (emergencyMode && emergencyModeActivatedAt > 0) {
                val emergencyElapsed = now - emergencyModeActivatedAt
                if (emergencyElapsed >= EMERGENCY_AUTO_RESET_MS) {
                    android.util.Log.w("SETTINGS_SYNC", "🚨 Emergency mode EXPIRED (${emergencyElapsed/60000}min old) - resetting to OFF")
                    emergencyModeAdjusted = false
                    emergencyModeActivatedAt = 0
                    prefs.edit().remove(KEY_EMERGENCY_ACTIVATED_AT).apply()
                }
            }

            // Auto-reset ForceCheck if > 30 minutes old
            if (forceCheck && forceCheckActivatedAt > 0) {
                val forceCheckElapsed = now - forceCheckActivatedAt
                if (forceCheckElapsed >= FORCECHECK_AUTO_RESET_MS) {
                    android.util.Log.w("SETTINGS_SYNC", "🔥 ForceCheck EXPIRED (${forceCheckElapsed/60000}min old) - resetting to OFF")
                    forceCheckAdjusted = false
                    forceCheckActivatedAt = 0
                    prefs.edit().remove(KEY_FORCECHECK_ACTIVATED_AT).apply()
                }
            }

            // Auto-reset Realtime mode if > 30 minutes old
            if (realtimeMode && realtimeModeActivatedAt > 0) {
                val realtimeElapsed = now - realtimeModeActivatedAt
                if (realtimeElapsed >= REALTIME_AUTO_RESET_MS) {
                    android.util.Log.w("SETTINGS_SYNC", "⚡ Realtime mode EXPIRED (${realtimeElapsed/60000}min old) - resetting to OFF")
                    realtimeModeAdjusted = false
                    realtimeModeActivatedAt = 0
                    prefs.edit().remove(KEY_REALTIME_ACTIVATED_AT).apply()
                }
            }

            currentSettings = LocationSettings(
                normalIntervalMs = normalMs,
                realtimeIntervalMs = realtimeMs,
                emergencyIntervalMs = emergencyMs,
                forceCheckIntervalMs = forceCheckMs,
                forceCheck = forceCheckAdjusted,
                realtimeMode = realtimeModeAdjusted,
                emergencyMode = emergencyModeAdjusted,
                trackingEnabled = trackingEnabled,
                lastUpdated = lastUpdated,
                source = source,
                version = version
            )

            LocationLogger.i(TAG, "✅ Loaded cached settings: ${currentSettings.toSummaryString()}")
            android.util.Log.d("SETTINGS_SYNC", "📦 Cache loaded: interval=${normalMs/1000}s, forceCheck=$forceCheck, emergency=$emergencyMode")

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to load cached settings, using defaults", e)
            android.util.Log.e("SETTINGS_SYNC", "❌ Cache load FAILED: ${e.message}")
            currentSettings = LocationSettings.getDefault()
        }
    }

    // ============================================================================
    // REALTIME LISTENER WITH RETRY/BACKOFF (COUNTS AS SYNC)
    // ============================================================================

    private fun startRealtimeListener(userId: String) {
        try {
            LocationLogger.i(TAG, "🎧 Starting Firestore realtime listener for user: $userId")
            android.util.Log.d("SETTINGS_SYNC", "🎧 Firestore listener starting...")

            // Listen to user-specific settings document
            settingsListener = firestore.collection(SETTINGS_COLLECTION).document(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        val errorMessage = error.message ?: ""

                        // Check if it's a permission error
                        if (errorMessage.contains("PERMISSION_DENIED", ignoreCase = true)) {
                            LocationLogger.e(TAG, "🎧 Realtime listener PERMISSION_DENIED - Firebase Security Rules need to be updated!")
                            android.util.Log.e("SETTINGS_SYNC", "🎧 PERMISSION_DENIED - Update Firebase Security Rules to allow read!")
                            android.util.Log.e("SETTINGS_SYNC", "   Add rule: match /location-tracking-settings/{userId} { allow read: if request.auth.uid == userId; }")
                        }

                        LocationLogger.e(TAG, "Settings listener error: ${error.message}", error)
                        android.util.Log.e("SETTINGS_SYNC", "🎧 Listener ERROR: ${error.message}")
                        retryListenerWithBackoff(userId)
                        return@addSnapshotListener
                    }

                    // Success - reset retry backoff
                    retryAttempt = 0
                    retryDelay = 1000L

                    if (snapshot != null && snapshot.exists()) {
                        LocationLogger.d(TAG, "📡 Realtime update received from Firestore")
                        android.util.Log.d("SETTINGS_SYNC", "📡 REALTIME UPDATE received!")

                        // IMPORTANT: Realtime updates should ALWAYS be applied immediately
                        // This is how admin changes take effect instantly
                        val now = System.currentTimeMillis()
                        lastSyncTime = now
                        prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
                        lastSyncSuccess = true
                        isUsingHardcodedDefaults = false
                        cancelRetrySyncJob()  // Cancel retry since we got data

                        android.util.Log.d("SETTINGS_SYNC", "📡 Applying realtime settings IMMEDIATELY...")
                        scope.launch {
                            parseAndApplySettings(snapshot.data, userId, isFromRealtimeListener = true)
                        }
                    } else {
                        LocationLogger.w(TAG, "Settings document does not exist for user: $userId")
                        android.util.Log.w("SETTINGS_SYNC", "📡 No settings doc for user")
                    }
                }

            LocationLogger.i(TAG, "✅ Real-time settings listener started successfully")
            android.util.Log.d("SETTINGS_SYNC", "🎧 Listener ACTIVE")

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to start settings listener", e)
            android.util.Log.e("SETTINGS_SYNC", "🎧 Listener FAILED: ${e.message}")
            retryListenerWithBackoff(userId)
        }
    }

    private fun retryListenerWithBackoff(userId: String) {
        retryAttempt++
        retryDelay = min(retryDelay * 2, maxRetryDelay)

        LocationLogger.w(TAG, "🔄 Retrying listener in ${retryDelay}ms (attempt $retryAttempt)")

        handler.postDelayed({
            if (isSyncing) {
                startRealtimeListener(userId)
            }
        }, retryDelay)
    }

    // ============================================================================
    // PERIODIC REFRESH (Every 10 minutes)
    // ============================================================================

    private fun startPeriodicRefresh(userId: String) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive && isSyncing) {
                delay(SYNC_INTERVAL_MS)
                try {
                    LocationLogger.i(TAG, "⏰ Periodic settings sync (10 min interval)")
                    android.util.Log.d("SETTINGS_SYNC", "⏰ PERIODIC SYNC (10 min)...")
                    fetchSettingsFromFirebase(userId)
                } catch (e: Exception) {
                    LocationLogger.e(TAG, "Periodic refresh failed", e)
                    android.util.Log.e("SETTINGS_SYNC", "⏰ Periodic sync FAILED: ${e.message}")
                    // Start retry job on failure
                    startRetrySyncJob(userId)
                }
            }
        }
        LocationLogger.i(TAG, "📅 Periodic refresh scheduled (every ${SYNC_INTERVAL_MS / 60000}min)")
    }

    private suspend fun fetchSettingsFromFirebase(userId: String) {
        try {
            val startTime = System.currentTimeMillis()
            LocationLogger.d(TAG, "🌐 Fetching settings directly from Firestore for user: $userId")
            android.util.Log.d("SETTINGS_SYNC", "🌐 Fetching from Firebase...")

            // Check if user is authenticated
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                LocationLogger.e(TAG, "Cannot fetch settings - user not authenticated")
                android.util.Log.e("SETTINGS_SYNC", "🌐 User not authenticated")
                lastSyncSuccess = false
                checkCacheValidityAndApplyDefaults()
                return
            }

            // Fetch directly from Firestore (faster than going through backend)
            android.util.Log.d("SETTINGS_SYNC", "🌐 Reading from: $SETTINGS_COLLECTION/$userId")

            val snapshot = withContext(Dispatchers.IO) {
                firestore.collection(SETTINGS_COLLECTION)
                    .document(userId)
                    .get()
                    .await()
            }

            if (snapshot.exists()) {
                val data = snapshot.data
                if (data != null) {
                    parseAndApplySettings(data, userId, isFromRealtimeListener = false)
                    lastSyncSuccess = true
                    isUsingHardcodedDefaults = false
                    cancelRetrySyncJob()
                    android.util.Log.d("SETTINGS_SYNC", "🌐 Firebase fetch SUCCESS")
                } else {
                    LocationLogger.w(TAG, "Settings document exists but has no data")
                    android.util.Log.w("SETTINGS_SYNC", "🌐 Document exists but empty")
                    lastSyncSuccess = false
                }
            } else {
                LocationLogger.w(TAG, "No settings document found for user: $userId")
                android.util.Log.w("SETTINGS_SYNC", "🌐 No settings doc for user")
                // Not a failure - just no custom settings yet, use defaults
                lastSyncSuccess = true  // Mark as success since fetch worked
                isUsingHardcodedDefaults = true
            }

            lastSyncTime = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, lastSyncTime).apply()

            val duration = lastSyncTime - startTime
            LocationLogger.logPerformance(context, "settings_fetch", duration, lastSyncSuccess)
            android.util.Log.d("SETTINGS_SYNC", "🌐 Fetch took ${duration}ms")

            if (!lastSyncSuccess) {
                checkCacheValidityAndApplyDefaults()
            }

        } catch (e: Exception) {
            val errorMessage = e.message ?: ""

            // Check if it's a permission error
            if (errorMessage.contains("PERMISSION_DENIED", ignoreCase = true)) {
                LocationLogger.e(TAG, "🌐 Firebase fetch PERMISSION_DENIED - Check Firebase Security Rules!")
                android.util.Log.e("SETTINGS_SYNC", "🌐 PERMISSION_DENIED - Update Firebase Security Rules!")
                android.util.Log.e("SETTINGS_SYNC", "   Add: match /location-tracking-settings/{userId} { allow read: if request.auth.uid == userId; }")
            }

            LocationLogger.e(TAG, "Failed to fetch settings from Firebase", e)
            android.util.Log.e("SETTINGS_SYNC", "🌐 Firebase fetch FAILED: ${e.message}")
            lastSyncSuccess = false

            // Check if we should fall back to hardcoded defaults
            checkCacheValidityAndApplyDefaults()
        }
    }

    // ============================================================================
    // PARSE AND APPLY SETTINGS WITH MODE TRACKING
    // ============================================================================

    private fun parseAndApplySettings(data: Map<String, Any>?, userId: String, isFromRealtimeListener: Boolean = false) {
        if (data == null) {
            LocationLogger.w(TAG, "Received null settings data")
            android.util.Log.w("SETTINGS_SYNC", "⚙️ Received NULL data")
            return
        }

        try {
            val newSettings = LocationSettings.fromFirebase(data)

            android.util.Log.d("SETTINGS_SYNC", "═══════════════════════════════════════════════════════")
            android.util.Log.d("SETTINGS_SYNC", "⚙️ PARSING SETTINGS FROM ${if (isFromRealtimeListener) "REALTIME" else "FETCH"}")
            android.util.Log.d("SETTINGS_SYNC", "   normalIntervalMs: ${newSettings.normalIntervalMs}")
            android.util.Log.d("SETTINGS_SYNC", "   forceCheck: ${newSettings.forceCheck}")
            android.util.Log.d("SETTINGS_SYNC", "   forceCheckIntervalMs: ${newSettings.forceCheckIntervalMs}")
            android.util.Log.d("SETTINGS_SYNC", "   emergency: ${newSettings.emergencyMode}")
            android.util.Log.d("SETTINGS_SYNC", "   emergencyIntervalMs: ${newSettings.emergencyIntervalMs}")
            android.util.Log.d("SETTINGS_SYNC", "   version: ${newSettings.version}")
            android.util.Log.d("SETTINGS_SYNC", "   Current settings version: ${currentSettings.version}")
            android.util.Log.d("SETTINGS_SYNC", "═══════════════════════════════════════════════════════")

            // Validate settings
            if (!newSettings.isValid()) {
                LocationLogger.w(TAG, "Invalid settings received: ${newSettings.getValidationErrors()}")
                android.util.Log.w("SETTINGS_SYNC", "⚙️ INVALID settings: ${newSettings.getValidationErrors()}")
                return
            }

            // Version/timestamp conflict resolution - SKIP FOR REALTIME UPDATES
            // CRITICAL: Also skip if current settings are from cache/defaults
            // Backend/Realtime ALWAYS wins over cache/defaults!
            val currentIsFromBackend = currentSettings.source != LocationSettings.SOURCE_LOCAL &&
                                      currentSettings.source != LocationSettings.SOURCE_DEFAULT

            if (!isFromRealtimeListener && currentIsFromBackend) {
                // Only apply version check if current settings are ALSO from backend
                if (newSettings.version < currentSettings.version) {
                    LocationLogger.i(TAG, "Ignoring older version: ${newSettings.version} < ${currentSettings.version}")
                    android.util.Log.d("SETTINGS_SYNC", "⚙️ Ignoring older version")
                    return
                }

                if (newSettings.version == currentSettings.version &&
                    newSettings.lastUpdated <= currentSettings.lastUpdated) {
                    LocationLogger.d(TAG, "Ignoring stale settings (same or older timestamp)")
                    android.util.Log.d("SETTINGS_SYNC", "⚙️ Ignoring stale settings")
                    return
                }
            } else if (!currentIsFromBackend) {
                android.util.Log.w("SETTINGS_SYNC", "⚙️ Current settings are CACHE/DEFAULT (${currentSettings.source}) - backend OVERRIDES unconditionally!")
            } else {
                android.util.Log.d("SETTINGS_SYNC", "⚙️ REALTIME update - skipping version check")
            }

            val oldSettings = currentSettings

            // Track mode activation for auto-reset
            trackModeActivation(oldSettings, newSettings)

            // Check if meaningful changes occurred - BUT ALWAYS APPLY REALTIME UPDATES
            val hasDifferences = newSettings.hasMeaningfulDifferences(oldSettings)
            android.util.Log.d("SETTINGS_SYNC", "⚙️ hasMeaningfulDifferences: $hasDifferences")
            android.util.Log.d("SETTINGS_SYNC", "   Old interval: ${oldSettings.getCurrentInterval()}ms")
            android.util.Log.d("SETTINGS_SYNC", "   New interval: ${newSettings.getCurrentInterval()}ms")

            if (!hasDifferences && !isFromRealtimeListener) {
                LocationLogger.d(TAG, "No meaningful changes in settings")
                android.util.Log.d("SETTINGS_SYNC", "⚙️ No meaningful changes - skipping")
                return
            }

            if (!hasDifferences && isFromRealtimeListener) {
                android.util.Log.d("SETTINGS_SYNC", "⚙️ No meaningful changes but REALTIME - still notifying callbacks")
            }

            // Apply new settings
            currentSettings = newSettings
            isUsingHardcodedDefaults = false  // We got fresh settings

            // Record admin change time
            prefs.edit().putLong(KEY_LAST_ADMIN_CHANGE_TIME, System.currentTimeMillis()).apply()

            // Cache to SharedPreferences atomically
            cacheSettings(newSettings)

            val sourceInfo = if (isFromRealtimeListener) "(realtime)" else "(fetch)"
            LocationLogger.i(TAG, "✅ Settings updated $sourceInfo: ${newSettings.toSummaryString()}")
            android.util.Log.d("SETTINGS_SYNC", "✅ SETTINGS APPLIED $sourceInfo: interval=${newSettings.normalIntervalMs/1000}s")

            // Debounced notification (Emergency/ForceCheck bypass)
            notifyCallbacksDebounced(newSettings, oldSettings)

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to parse settings", e)
            android.util.Log.e("SETTINGS_SYNC", "⚙️ Parse FAILED: ${e.message}")
        }
    }

    /**
     * Track when modes are activated for auto-reset timing
     */
    private fun trackModeActivation(oldSettings: LocationSettings, newSettings: LocationSettings) {
        val now = System.currentTimeMillis()

        // Track Emergency mode activation
        if (newSettings.emergencyMode && !oldSettings.emergencyMode) {
            emergencyModeActivatedAt = now
            prefs.edit().putLong(KEY_EMERGENCY_ACTIVATED_AT, now).apply()
            LocationLogger.i(TAG, "🚨 Emergency mode ACTIVATED at ${now} - will auto-reset in 15min")
            android.util.Log.w("SETTINGS_SYNC", "🚨 EMERGENCY ACTIVATED - auto-reset in 15min")
        } else if (!newSettings.emergencyMode && oldSettings.emergencyMode) {
            emergencyModeActivatedAt = 0
            prefs.edit().putLong(KEY_EMERGENCY_ACTIVATED_AT, 0).apply()
            LocationLogger.i(TAG, "🚨 Emergency mode DEACTIVATED")
            android.util.Log.d("SETTINGS_SYNC", "🚨 Emergency DEACTIVATED")
        }

        // Track ForceCheck activation
        if (newSettings.forceCheck && !oldSettings.forceCheck) {
            forceCheckActivatedAt = now
            prefs.edit().putLong(KEY_FORCECHECK_ACTIVATED_AT, now).apply()
            LocationLogger.i(TAG, "🔥 ForceCheck ACTIVATED at ${now} - will auto-reset in 30min")
            android.util.Log.w("SETTINGS_SYNC", "🔥 FORCECHECK ACTIVATED - auto-reset in 30min")
        } else if (!newSettings.forceCheck && oldSettings.forceCheck) {
            forceCheckActivatedAt = 0
            prefs.edit().putLong(KEY_FORCECHECK_ACTIVATED_AT, 0).apply()
            LocationLogger.i(TAG, "🔥 ForceCheck DEACTIVATED")
            android.util.Log.d("SETTINGS_SYNC", "🔥 ForceCheck DEACTIVATED")
        }

        // Track Realtime mode activation
        if (newSettings.realtimeMode && !oldSettings.realtimeMode) {
            realtimeModeActivatedAt = now
            prefs.edit().putLong(KEY_REALTIME_ACTIVATED_AT, now).apply()
            LocationLogger.i(TAG, "⚡ Realtime mode ACTIVATED at ${now} - will auto-reset in 30min")
            android.util.Log.w("SETTINGS_SYNC", "⚡ REALTIME ACTIVATED - auto-reset in 30min")
        } else if (!newSettings.realtimeMode && oldSettings.realtimeMode) {
            realtimeModeActivatedAt = 0
            prefs.edit().putLong(KEY_REALTIME_ACTIVATED_AT, 0).apply()
            LocationLogger.i(TAG, "⚡ Realtime mode DEACTIVATED")
            android.util.Log.d("SETTINGS_SYNC", "⚡ Realtime DEACTIVATED")
        }
    }

    // ============================================================================
    // ATOMIC CACHING WITH MODE TIMESTAMPS
    // ============================================================================

    private fun cacheSettings(settings: LocationSettings) {
        try {
            val now = System.currentTimeMillis()

            prefs.edit().apply {
                // Core intervals
                putLong(KEY_NORMAL_INTERVAL, settings.normalIntervalMs)
                putLong(KEY_REALTIME_INTERVAL, settings.realtimeIntervalMs)
                putLong(KEY_EMERGENCY_INTERVAL, settings.emergencyIntervalMs)

                // Optional forceCheck interval
                settings.forceCheckIntervalMs?.let {
                    putLong(KEY_FORCECHECK_INTERVAL, it)
                } ?: remove(KEY_FORCECHECK_INTERVAL)

                // Mode flags
                putBoolean(KEY_FORCECHECK, settings.forceCheck)
                putBoolean(KEY_REALTIME_MODE, settings.realtimeMode)
                putBoolean(KEY_EMERGENCY_MODE, settings.emergencyMode)
                putBoolean(KEY_TRACKING_ENABLED, settings.trackingEnabled)

                // Metadata
                putLong(KEY_LAST_UPDATED, settings.lastUpdated)
                putString(KEY_SOURCE, settings.source)
                putInt(KEY_VERSION, settings.version)

                // Cache timestamp for validity checking
                putLong(KEY_CACHE_TIMESTAMP, now)

                // ForceCheck state for boot receiver
                putBoolean(LocationConstants.PREF_LAST_FORCECHECK_STATE, settings.forceCheck)
                putLong(LocationConstants.PREF_FORCECHECK_LAST_UPDATED, now)

                apply()
            }

            LocationLogger.d(TAG, "💾 Settings cached atomically to SharedPreferences")
            android.util.Log.d("SETTINGS_SYNC", "💾 Settings CACHED to SharedPreferences")

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to cache settings", e)
            android.util.Log.e("SETTINGS_SYNC", "💾 Cache FAILED: ${e.message}")
        }
    }

    // ============================================================================
    // DEBOUNCED NOTIFICATIONS (EMERGENCY/FORCECHECK OVERRIDE)
    // ============================================================================

    private fun notifyCallbacksDebounced(newSettings: LocationSettings, oldSettings: LocationSettings) {
        // Emergency or ForceCheck changed - apply immediately (bypass debounce)
        val isEmergencyChange = newSettings.emergencyMode != oldSettings.emergencyMode
        val isForceCheckChange = newSettings.forceCheck != oldSettings.forceCheck

        if (isEmergencyChange || isForceCheckChange) {
            LocationLogger.i(TAG, "🚀 Emergency/ForceCheck changed - applying immediately (bypass debounce)")
            notifyCallbacks(newSettings, oldSettings)
            return
        }

        // Debounce other changes (30 seconds)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            notifyCallbacks(newSettings, oldSettings)
        }

        LocationLogger.d(TAG, "⏳ Settings change debounced for 30 seconds")
    }

    private fun notifyCallbacks(newSettings: LocationSettings, oldSettings: LocationSettings) {
        LocationLogger.i(TAG, "📢 Notifying ${callbacks.size} callback(s) of settings change")
        android.util.Log.d("SETTINGS_SYNC", "📢 Notifying ${callbacks.size} callbacks")

        callbacks.forEach { callback ->
            try {
                callback.onSettingsChanged(newSettings, oldSettings)
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Error in settings callback", e)
                android.util.Log.e("SETTINGS_SYNC", "📢 Callback error: ${e.message}")
            }
        }
    }

    // ============================================================================
    // CALLBACK INTERFACE
    // ============================================================================

    interface SettingsCallback {
        fun onSettingsChanged(newSettings: LocationSettings, oldSettings: LocationSettings)
    }
}
