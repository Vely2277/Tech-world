/*
 * ============================================================================
 * LOCATION TRACKING CONSTANTS - UPDATED FOR HYBRID SYNC SYSTEM
 * ============================================================================
 *
 * PURPOSE:
 * Centralized constant definitions for the entire location tracking system.
 * Provides default values, configuration parameters, and error codes.
 *
 * KEY FEATURES:
 * - Service configuration constants (admin-controlled via Firebase)
 * - Location accuracy thresholds (4-tier system: Excellent/Better/Good/Okay)
 * - Battery management (44% threshold with ForceCheck override)
 * - Network quality indicators
 * - Smart retry logic (3-tier: 1min → 5min → 10min unlimited)
 * - Batch upload system with checkpoints
 * - Error codes and messages
 *
 * NEW HYBRID SYNC SYSTEM:
 * - Settings sync every 10 minutes from last sync
 * - Also sync after each successful upload (rate limited to once per 5 min)
 * - On app restart: Use cached settings immediately, sync in background
 * - Realtime listener for immediate admin changes
 * - ForceCheck auto-reset after 30 minutes
 * - EmergencyMode auto-reset after 15 minutes
 * - Cached settings fallback to hardcoded defaults after 1 hour
 * - When using hardcoded defaults: upload when data >= 3
 * - When using synced settings: upload when data >= 10
 *
 * ============================================================================
 * IMPLEMENTATION REQUIREMENTS FOR OTHER FILES
 * ============================================================================
 *
 * 1. LOCATIONTRACKINGSERVICE.KT - Tracking interval logic:
 *    - Fetch admin interval from Firebase (normal, real-time, and emergency modes)
 *    - Get current battery level
 *    - Get ForceCheck and EmergencyMode states from Firebase
 *    - Calculate interval (priority order):
 *      IF EmergencyMode ON: Use emergency interval (overrides everything)
 *      ELSE IF battery > 44%: Use admin interval (from Firebase)
 *      ELSE IF battery ≤ 44% AND ForceCheck OFF: Use 2-hour interval
 *      ELSE IF battery ≤ 44% AND ForceCheck ON: Use admin interval (ignore battery)
 *    - Upload error handling:
 *      IF upload fails: Mark as PENDING in database, continue tracking
 *      DON'T stop tracking when upload fails!
 *      BatchUploader retries failed uploads when internet returns
 *
 * 2. LOCATIONSETTINGSMANAGER.KT - Settings sync:
 *    - Sync from Firebase every 10 minutes
 *    - Fetch: updateInterval, realtimeInterval, emergencyInterval, forceCheck, realtimeMode, emergencyMode
 *    - Cache in SharedPreferences
 *    - Save ForceCheck state to PREF_LAST_FORCECHECK_STATE immediately when changed
 *    - All intervals (normal, real-time, emergency) are fetched from Firebase
 *
 * 3. LOCATIONBOOTRECEIVER.KT - Smart boot delay:
 *    - Read PREF_LAST_FORCECHECK_STATE from SharedPreferences
 *    - IF lastForceCheck == true: Wait 10 minutes before starting service
 *    - IF lastForceCheck == false: Wait 30 minutes before starting service
 *
 * 4. BATCH UPLOADER (NEW FILE NEEDED) - Upload logic:
 *    - Upload 10 locations at once
 *    - Wait 2 seconds between batches
 *    - Use checkpoints: Save last uploaded ID
 *    - On failure: Resume from last checkpoint
 *    - 3-Tier retry: 1min (3x) → 5min (3x) → 10min (unlimited)
 *
 * 5. LOCATIONDATABASE.KT - Data retention:
 *    - Delete SYNCED locations immediately after upload
 *    - Delete PENDING locations after 3 days
 *    - Keep max 1000 locations, delete oldest SYNCED when limit reached
 *
 * 6. GPS RETRY LOGIC (IN LOCATIONTRACKINGSERVICE):
 *    - Try GPS for 30 seconds
 *    - If fails: Retry 10 times total
 *    - After 10 failures: Wait 10 minutes, then retry cycle again
 *    - Fallback to Network after 3 failed cycles
 *
 * 7. ACCURACY LEVEL FUNCTION (LocationUtils.kt):
 *    fun getAccuracyLevel(accuracy: Float): String {
 *        return when {
 *            accuracy <= 20f -> "Excellent"  // 0-20m
 *            accuracy <= 50f -> "Better"     // 20-50m
 *            accuracy <= 100f -> "Good"      // 50-100m
 *            else -> "Okay"                  // >100m
 *        }
 *    }
 *
 * 8. NETWORK MONITOR - Offline detection:
 *    - When offline: Don't waste battery retrying uploads
 *    - Cache all locations locally
 *    - When online: Trigger batch upload immediately
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location.utils

object LocationConstants {

    // ============================================================================
    // SERVICE CONFIGURATION
    // ============================================================================

    // Default update interval - FALLBACK/HARDCODED DEFAULT (1 hour)
    // Used when cache is expired (>1 hour old) and cannot sync with server
    const val HARDCODED_DEFAULT_INTERVAL_MS = 3600000L // 1 hour (hardcoded default)

    // Legacy fallback (2 hours) - kept for backwards compatibility
    const val FALLBACK_UPDATE_INTERVAL_MS = 3600000L // 1 hour (changed from 2 hours)

    // ============================================================================
    // MINIMUM INTERVALS - CRITICAL FOR PRECISE TIMING
    // ============================================================================
    // These define the ABSOLUTE minimum intervals for each mode.
    // Admin can set intervals as low as these values.
    //
    // IMPORTANT: For very short intervals (< 10s), ensure device:
    // - Has good GPS signal
    // - Battery > 20% (or ForceCheck enabled)
    // - Is not in Doze mode
    // ============================================================================

    const val MIN_UPDATE_INTERVAL_MS = 60000L // 1 minute (normal mode - prevents battery drain)
    const val MIN_REALTIME_INTERVAL_MS = 1000L // 1 second (realtime mode - admin can set as low as 1s)
    const val MIN_EMERGENCY_INTERVAL_MS = 1000L // 1 second (emergency mode - admin can set as low as 1s)
    const val MIN_FORCECHECK_INTERVAL_MS = 1000L // 1 second (forceCheck mode - admin can set as low as 1s)

    // ============================================================================
    // ABSOLUTE MINIMUM INTERVALS (SYSTEM LIMITS)
    // ============================================================================
    // These are the absolute minimum values that should NEVER be exceeded
    // even if admin tries to set lower. Prevents system instability.
    // ============================================================================

    const val ABSOLUTE_MIN_INTERVAL_MS = 500L // 0.5 seconds - absolute minimum
    const val AGGRESSIVE_MODE_THRESHOLD_MS = 300000L // 5 minutes - below this = aggressive mode

    // Real-time mode interval - FALLBACK ONLY (normally fetched from Firebase)
    const val FALLBACK_REALTIME_INTERVAL_MS = 10000L // 10 seconds (if Firebase unreachable)

    // Emergency mode interval - FALLBACK ONLY (normally fetched from Firebase)
    const val FALLBACK_EMERGENCY_INTERVAL_MS = 30000L // 30 seconds (if Firebase unreachable)

    // Maximum interval
    const val MAX_UPDATE_INTERVAL_MS = 172800000L // 48 hours

    // System monitoring intervals
    const val WATCHDOG_CHECK_INTERVAL_MS = 3600000L // 1 hour

    // ============================================================================
    // HYBRID SYNC SYSTEM INTERVALS
    // ============================================================================

    // Periodic settings sync (every 10 minutes)
    const val SETTINGS_SYNC_INTERVAL_MS = 600000L // 10 minutes

    // Minimum time between post-upload syncs (rate limiting)
    const val POST_UPLOAD_SYNC_MIN_INTERVAL_MS = 300000L // 5 minutes

    // Cache validity duration (when cache expires, use hardcoded defaults)
    const val SETTINGS_CACHE_MAX_AGE_MS = 3600000L // 1 hour

    // ============================================================================
    // AUTO-RESET DURATIONS (MODE EXPIRATION)
    // =========================================================================

    // Emergency tracking auto-disable after 15 minutes
    const val EMERGENCY_MODE_AUTO_RESET_MS = 900000L // 15 minutes

    // ForceCheck auto-disable after 30 minutes
    const val FORCECHECK_AUTO_RESET_MS = 1800000L // 30 minutes

    // Realtime mode auto-disable after 30 minutes
    const val REALTIME_MODE_AUTO_RESET_MS = 1800000L // 30 minutes

    // ============================================================================
    // BATCH UPLOAD THRESHOLDS
    // =========================================================================

    // Normal threshold: upload when data >= 10
    const val BATCH_UPLOAD_THRESHOLD_NORMAL = 10

    // Hardcoded default threshold: upload when data >= 3
    const val BATCH_UPLOAD_THRESHOLD_HARDCODED = 3

    // Emergency mode: upload immediately (one by one)
    const val BATCH_UPLOAD_THRESHOLD_EMERGENCY = 1

    // ============================================================================
    // TIME-BASED UPLOAD TRIGGERS
    // =========================================================================

    // Maximum time data can exist before forced upload (2 hours)
    // If data has been pending for this long, upload regardless of count
    const val DATA_AGE_FORCE_UPLOAD_MS = 7200000L // 2 hours

    // Time to wait after tracking stops before uploading remaining data (5 minutes)
    // If tracking stops but internet is active, upload after this delay
    const val TRACKING_STOPPED_UPLOAD_DELAY_MS = 300000L // 5 minutes

    // ============================================================================
    // GPS and location request settings
    // =========================================================================

    const val LOCATION_REQUEST_TIMEOUT_MS = 30000L // 30 seconds
    const val GPS_RETRY_ATTEMPTS = 10 // Try 10 times before giving up
    const val GPS_RETRY_CYCLE_DELAY_MS = 600000L // 10 minutes between retry cycles
    const val FASTEST_LOCATION_INTERVAL_MS = 10000L // 10 seconds

    // ============================================================================
    // LOCATION ACCURACY THRESHOLDS (4-TIER SYSTEM)
    // ============================================================================

    const val GPS_EXCELLENT_ACCURACY = 20f // 0-20 meters = Excellent
    const val GPS_BETTER_ACCURACY = 50f // 20-50 meters = Better
    const val GPS_GOOD_ACCURACY = 100f // 50-100 meters = Good
    const val GPS_OKAY_ACCURACY = 100f // Above 100 meters = Okay

    const val NETWORK_GOOD_ACCURACY = 100f // meters
    const val NETWORK_ACCEPTABLE_ACCURACY = 500f // meters

    // ============================================================================
    // MOVEMENT DETECTION
    // ============================================================================

    const val MOVEMENT_THRESHOLD_METERS = 5f
    const val STATIONARY_TIMEOUT_MS = 1800000L // 30 minutes
    const val HIGH_SPEED_THRESHOLD_MPS = 10f // ~36 km/h

    const val LOCATION_CLUSTERING_RADIUS = 5f // meters

    // ============================================================================
    // BATTERY MANAGEMENT (44% THRESHOLD SYSTEM)
    // ============================================================================

    const val BATTERY_PROTECTION_THRESHOLD = 44
    const val BATTERY_LOW_FORCED_INTERVAL_MS = 7200000L // 2 hours when battery low

    const val SETTING_FORCE_CHECK_ENABLED = "force_check_enabled"
    const val SETTING_EMERGENCY_MODE_ENABLED = "emergency_mode_enabled"

    const val BATTERY_OPTIMIZATION_WHITELIST_REQUEST_CODE = 1001

    // ============================================================================
    // NETWORK SETTINGS (3-TIER UNLIMITED RETRY SYSTEM)
    // ============================================================================

    const val TIER1_RETRY_INTERVAL_MS = 60000L // 1 minute
    const val TIER1_MAX_ATTEMPTS = 3

    const val TIER2_RETRY_INTERVAL_MS = 300000L // 5 minutes
    const val TIER2_MAX_ATTEMPTS = 5

    const val TIER3_RETRY_INTERVAL_MS = 600000L // 10 minutes
    const val TIER3_MAX_ATTEMPTS = -1 // Unlimited

    const val BATCH_UPLOAD_SIZE = 10
    const val BATCH_UPLOAD_DELAY_MS = 2000L // 2 seconds between batches
    const val BATCH_UPLOAD_CHECKPOINT_ENABLED = true

    const val MAX_CACHE_SIZE = 1000

    const val SYNCED_DATA_RETENTION_DAYS = 0
    const val PENDING_DATA_RETENTION_DAYS = 3

    // ============================================================================
    // DATABASE SETTINGS
    // ============================================================================

    const val DATABASE_NAME = "location_tracking.db"
    const val DATABASE_VERSION = 1

    const val TABLE_LOCATIONS = "locations_cache"
    const val TABLE_SETTINGS = "settings_cache"
    const val TABLE_LOGS = "service_logs"

    // ============================================================================
    // SHARED PREFERENCES
    // ============================================================================

    const val PREFS_NAME = "location_tracking_prefs"
    const val PREF_USER_ID = "user_id"
    const val PREF_LAST_LOCATION_TIME = "last_location_time"
    const val PREF_UPDATE_INTERVAL = "update_interval"
    const val PREF_TRACKING_ENABLED = "tracking_enabled"
    const val PREF_SERVICE_RESTART_COUNT = "restart_count"
    const val PREF_BATTERY_OPTIMIZED = "battery_optimized"

    // ForceCheck state caching (for smart boot delay)
    const val PREF_LAST_FORCECHECK_STATE = "last_forcecheck_state"
    const val PREF_FORCECHECK_LAST_UPDATED = "forcecheck_last_updated"
    const val PREF_BOOT_COUNT = "boot_count"

    // NEW: Settings sync state tracking
    const val PREF_LAST_SETTINGS_SYNC_TIME = "last_settings_sync_time"
    const val PREF_LAST_POST_UPLOAD_SYNC_TIME = "last_post_upload_sync_time"
    const val PREF_SETTINGS_CACHE_TIMESTAMP = "settings_cache_timestamp"
    const val PREF_USING_HARDCODED_DEFAULTS = "using_hardcoded_defaults"

    // NEW: Mode activation timestamps for auto-reset
    const val PREF_EMERGENCY_MODE_ACTIVATED_AT = "emergency_mode_activated_at"
    const val PREF_FORCECHECK_ACTIVATED_AT = "forcecheck_activated_at"

    // ============================================================================
    // RECOVERY LEVELS
    // ============================================================================

    const val RECOVERY_NONE = 0
    const val RECOVERY_GENTLE_RESTART = 1
    const val RECOVERY_FORCE_RESTART = 2
    const val RECOVERY_FULL_RESET = 3
    const val RECOVERY_EMERGENCY_MODE = 4

    // ============================================================================
    // ERROR CODES
    // ============================================================================

    const val ERROR_NO_PERMISSION = 1001
    const val ERROR_LOCATION_DISABLED = 1002
    const val ERROR_PROVIDER_UNAVAILABLE = 1003
    const val ERROR_TIMEOUT = 1004
    const val ERROR_NETWORK_FAILURE = 1005
    const val ERROR_DATABASE_ERROR = 1006
    const val ERROR_SERVICE_CRASHED = 1007
    const val ERROR_BATTERY_OPTIMIZED = 1008
    const val ERROR_SETTINGS_SYNC_FAILED = 1009
    const val ERROR_UNKNOWN = 9999

    // ============================================================================
    // ERROR MESSAGES
    // ============================================================================

    const val MSG_NO_PERMISSION = "Location permission not granted"
    const val MSG_LOCATION_DISABLED = "Location services disabled"
    const val MSG_PROVIDER_UNAVAILABLE = "Location provider unavailable"
    const val MSG_TIMEOUT = "Location request timeout"
    const val MSG_NETWORK_FAILURE = "Network connection failed"
    const val MSG_DATABASE_ERROR = "Database operation failed"
    const val MSG_SERVICE_CRASHED = "Service crashed unexpectedly"
    const val MSG_BATTERY_OPTIMIZED = "App is battery optimized"
    const val MSG_SETTINGS_SYNC_FAILED = "Failed to sync settings"

    // ============================================================================
    // LOCATION PROVIDERS
    // ============================================================================

    const val PROVIDER_GPS = "gps"
    const val PROVIDER_NETWORK = "network"
    const val PROVIDER_PASSIVE = "passive"
    const val PROVIDER_FUSED = "fused"
    const val PROVIDER_UNKNOWN = "unknown"

    // ============================================================================
    // SYNC STATUS
    // ============================================================================

    const val SYNC_PENDING = "pending"
    const val SYNC_SYNCED = "synced"
    const val SYNC_FAILED = "failed"
    const val SYNC_IN_PROGRESS = "in_progress"

    // ============================================================================
    // MOVEMENT STATUS
    // ============================================================================

    const val MOVEMENT_UNKNOWN = "unknown"
    const val MOVEMENT_STATIONARY = "stationary"
    const val MOVEMENT_MOVING = "moving"
    const val MOVEMENT_HIGH_SPEED = "high_speed"

    // ============================================================================
    // NETWORK QUALITY
    // ============================================================================

    const val NETWORK_EXCELLENT = "excellent"
    const val NETWORK_GOOD = "good"
    const val NETWORK_POOR = "poor"
    const val NETWORK_OFFLINE = "offline"

    // ============================================================================
    // SERVICE NAMES
    // ============================================================================

    const val SERVICE_LOCATION_TRACKING = "LocationTrackingService"
    const val SERVICE_WATCHDOG = "LocationWatchdogService"
    const val SERVICE_SYNC = "LocationSyncService"
    const val SERVICE_BOOT_RECEIVER = "LocationBootReceiver"

    // ============================================================================
    // NOTIFICATION IDS
    // ============================================================================

    const val NOTIFICATION_ID_TRACKING = 10001
    const val NOTIFICATION_ID_WATCHDOG = 10002
    const val NOTIFICATION_ID_SYNC = 10003

    const val NOTIFICATION_CHANNEL_ID = "location_tracking_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Location Tracking"

    // ============================================================================
    // LOGGING TAGS
    // ============================================================================

    const val TAG_SERVICE = "LocationService"
    const val TAG_WATCHDOG = "LocationWatchdog"
    const val TAG_SYNC = "LocationSync"
    const val TAG_DATABASE = "LocationDB"
    const val TAG_SETTINGS = "LocationSettings"
    const val TAG_BATTERY = "BatteryOptimization"
    const val TAG_NETWORK = "NetworkMonitor"
    const val TAG_PERMISSION = "LocationPermission"
    const val TAG_BOOT = "LocationBoot"
    const val TAG_MANAGER = "LocationManager"

    // ============================================================================
    // SERVICE ACTIONS
    // ============================================================================

    const val ACTION_START_TRACKING = "com.example.newconstructionappwithlocationtracking.ACTION_START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.example.newconstructionappwithlocationtracking.ACTION_STOP_TRACKING"
    const val ACTION_PAUSE_TRACKING = "com.example.newconstructionappwithlocationtracking.ACTION_PAUSE_TRACKING"
    const val ACTION_RESUME_TRACKING = "com.example.newconstructionappwithlocationtracking.ACTION_RESUME_TRACKING"

    // ============================================================================
    // BROADCAST ACTIONS
    // ============================================================================

    const val ACTION_LOCATION_UPDATE = "com.example.newconstructionappwithlocationtracking.LOCATION_UPDATE"
    const val ACTION_SETTINGS_CHANGED = "com.example.newconstructionappwithlocationtracking.SETTINGS_CHANGED"
    const val ACTION_SERVICE_RESTART = "com.example.newconstructionappwithlocationtracking.SERVICE_RESTART"
    const val ACTION_EMERGENCY_MODE = "com.example.newconstructionappwithlocationtracking.EMERGENCY_MODE"

    // ============================================================================
    // INTENT EXTRAS
    // ============================================================================

    const val EXTRA_LOCATION_DATA = "location_data"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_ERROR_CODE = "error_code"
    const val EXTRA_ERROR_MESSAGE = "error_message"
    const val EXTRA_RECOVERY_LEVEL = "recovery_level"

    // ============================================================================
    // API ENDPOINTS
    // ============================================================================

    const val ENDPOINT_LOCATION_UPDATE = "/api/location"
    const val ENDPOINT_LOCATION_INTERVAL = "/api/location/interval"
    const val ENDPOINT_LOCATION_HISTORY = "/api/location/history"
    const val ENDPOINT_LOCATION_BULK = "/api/location/bulk"

    // ============================================================================
    // FIREBASE COLLECTIONS
    // ============================================================================

    const val COLLECTION_LOCATIONS = "locations"
    const val COLLECTION_LOCATION_SETTINGS = "location-tracking-settings"
    const val COLLECTION_USER_STATUS = "user-tracking-status"
    const val COLLECTION_LOCATION_CACHE = "location-cache"
    const val COLLECTION_LOCATION_LOGS = "location-logs"

    // ============================================================================
    // SHAREDPREFERENCES KEYS - SERVICE STATE
    // ============================================================================

    const val PREFS_NAME_SERVICE_STATE = "location_service_state"
    const val KEY_SERVICE_RUNNING = "service_running"
    const val KEY_TRACKING_ENABLED = "tracking_enabled"
    const val KEY_TRACKING_TOGGLED_AT = "tracking_toggled_at"

    // ============================================================================
    // TIMEOUTS AND DELAYS
    // ============================================================================

    const val BOOT_STARTUP_DELAY_NORMAL_MS = 1800000L // 30 minutes (ForceCheck OFF)
    const val BOOT_STARTUP_DELAY_FORCECHECK_MS = 600000L // 10 minutes (ForceCheck ON)

    const val SERVICE_RESTART_DELAY_MS = 300000L // 5 minutes after crash

    const val EMERGENCY_MODE_CHECK_INTERVAL_MS = 600000L // 10 minutes

    const val SETTINGS_FETCH_TIMEOUT_MS = 30000L // 30 seconds

    const val UPLOAD_TIMEOUT_MS = 30000L // 30 seconds for single location

    const val BATCH_UPLOAD_TIMEOUT_MS = 600000L // 10 minutes for batch
}
