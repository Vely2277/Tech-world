/**
 * ============================================================================
 * LOCATION SETTINGS CONFIGURATION (HARDCODED FOR DEVELOPMENT)
 * ============================================================================
 *
 * This file contains hardcoded location tracking settings.
 * Later, these will be stored in Firebase and editable via admin dashboard.
 *
 * For now, the backend reads from here and returns to the mobile app.
 *
 * SETTINGS EXPLANATION:
 * - updateInterval: Normal tracking interval (seconds)
 * - realtimeInterval: Real-time mode tracking interval (seconds)
 * - emergencyInterval: Emergency mode tracking interval (seconds)
 * - forceCheck: Override battery protection (true/false)
 * - realtimeMode: Enable real-time tracking (true/false)
 * - emergencyMode: Enable emergency tracking (true/false)
 * - batteryThreshold: Battery level where protection kicks in (%)
 * - movementThreshold: Minimum movement to log new location (meters)
 * - trackingEnabled: Master on/off switch (true/false)
 *
 * ============================================================================
 */

const LOCATION_SETTINGS = {
    // ============================================================================
    // TRACKING INTERVALS (in seconds)
    // ============================================================================

    updateInterval: 7200,           // 2 hours = 7200 seconds (normal tracking)
    realtimeInterval: 10,            // 10 seconds (when real-time mode enabled)
    emergencyInterval: 30,           // 30 seconds (when emergency mode enabled)

    // ============================================================================
    // MODE TOGGLES
    // ============================================================================

    forceCheck: false,               // Battery override - OFF by default
    realtimeMode: false,             // Real-time tracking - OFF by default
    emergencyMode: false,            // Emergency tracking - OFF by default
    trackingEnabled: true,           // Master switch - ON by default

    // ============================================================================
    // THRESHOLDS
    // ============================================================================

    batteryThreshold: 44,            // Battery protection at 44%
    movementThreshold: 5,            // Movement detection: 5 meters

    // ============================================================================
    // ACCURACY SETTINGS
    // ============================================================================

    minGpsAccuracy: 50,              // Minimum GPS accuracy (meters)
    minNetworkAccuracy: 200,         // Minimum Network accuracy (meters)

    // ============================================================================
    // RETRY SETTINGS
    // ============================================================================

    maxRetryAttempts: -1,            // Unlimited retries (-1)
    retryTier1Interval: 60,          // Tier 1: 1 minute (seconds)
    retryTier1Attempts: 3,           // Tier 1: 3 attempts
    retryTier2Interval: 300,         // Tier 2: 5 minutes (seconds)
    retryTier2Attempts: 3,           // Tier 2: 3 attempts
    retryTier3Interval: 600,         // Tier 3: 10 minutes (seconds) - unlimited

    // ============================================================================
    // BATCH UPLOAD SETTINGS
    // ============================================================================

    batchUploadSize: 10,             // Upload 10 locations at once
    batchUploadDelay: 2,             // 2 seconds between batches
    maxCacheSize: 1000,              // Maximum 1000 locations cached

    // ============================================================================
    // DATA RETENTION
    // ============================================================================

    syncedDataRetentionDays: 0,      // Delete synced data immediately
    pendingDataRetentionDays: 3,     // Keep pending data for 3 days

    // ============================================================================
    // HEALTH CHECK
    // ============================================================================

    watchdogCheckInterval: 3600,     // Check service health every 1 hour (seconds)
    settingsSyncInterval: 600,       // Sync settings every 10 minutes (seconds)

    // ============================================================================
    // METADATA
    // ============================================================================

    version: 1,                      // Settings version
    lastUpdated: new Date().toISOString(),
    source: 'hardcoded'              // Source: 'hardcoded', 'firebase', or 'admin'
};

/**
 * Get current location settings
 * @returns {Object} Current location settings
 */
function getLocationSettings() {
    return {
        ...LOCATION_SETTINGS,
        lastUpdated: new Date().toISOString() // Update timestamp on each request
    };
}

/**
 * Get settings formatted for API response
 * @returns {Object} Settings in API response format
 */
function getSettingsForAPI() {
    return {
        success: true,
        settings: getLocationSettings(),
        message: 'Location settings retrieved successfully'
    };
}

/**
 * Update settings (for future admin functionality)
 * @param {Object} newSettings - New settings to merge
 * @returns {Object} Updated settings
 */
function updateSettings(newSettings) {
    // TODO: Later, this will update Firebase
    // For now, just log that update was attempted
    console.log('⚠️  Settings update attempted (hardcoded mode):', newSettings);
    console.log('💡 To enable settings updates, implement Firebase integration');

    return {
        success: false,
        message: 'Settings are currently hardcoded. Admin dashboard coming soon.',
        currentSettings: LOCATION_SETTINGS
    };
}

/**
 * Validate settings
 * @param {Object} settings - Settings to validate
 * @returns {Object} Validation result
 */
function validateSettings(settings) {
    const errors = [];

    // Validate intervals
    if (settings.updateInterval < 60) {
        errors.push('updateInterval must be at least 60 seconds (1 minute)');
    }
    if (settings.updateInterval > 172800) {
        errors.push('updateInterval must not exceed 172800 seconds (48 hours)');
    }

    // Validate battery threshold
    if (settings.batteryThreshold < 0 || settings.batteryThreshold > 100) {
        errors.push('batteryThreshold must be between 0 and 100');
    }

    // Validate movement threshold
    if (settings.movementThreshold < 0) {
        errors.push('movementThreshold must be positive');
    }

    return {
        valid: errors.length === 0,
        errors: errors
    };
}

module.exports = {
    getLocationSettings,
    getSettingsForAPI,
    updateSettings,
    validateSettings,
    LOCATION_SETTINGS
};

