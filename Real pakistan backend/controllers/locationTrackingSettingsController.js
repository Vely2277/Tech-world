/**
 * ============================================================================
 * LOCATION TRACKING SETTINGS CONTROLLER
 * ============================================================================
 *
 * Comprehensive controller for managing location tracking settings.
 * Supports per-user settings with admin override capabilities.
 *
 * FEATURES:
 * - Per-user settings management (stored in Firebase)
 * - Global default settings
 * - Force check / Emergency / Real-time mode toggles
 * - Automatic mode expiration and reset
 * - Audit logging for all changes
 * - Settings history tracking
 *
 * COLLECTION: location-tracking-settings
 *
 * DOCUMENT STRUCTURE:
 * {
 *   userId: string,                    // User's Firebase UID
 *
 *   // TRACKING INTERVALS (in seconds)
 *   updateInterval: number,            // Normal tracking interval (default: 7200 = 2 hours)
 *   realtimeInterval: number,          // Real-time mode interval (default: 10 seconds)
 *   emergencyInterval: number,         // Emergency mode interval (default: 30 seconds)
 *   forceCheckInterval: number,        // Force check interval (default: 300 = 5 minutes)
 *
 *   // MODE TOGGLES
 *   trackingEnabled: boolean,          // Master switch (default: true)
 *   forceCheckEnabled: boolean,        // Force check override (default: false)
 *   realtimeModeEnabled: boolean,      // Real-time tracking (default: false)
 *   emergencyModeEnabled: boolean,     // Emergency tracking (default: false)
 *
 *   // AUTO-RESET CONFIGURATION
 *   forceCheckDuration: number,        // Duration before auto-reset (seconds, 0 = no auto-reset)
 *   realtimeModeDuration: number,      // Duration before auto-reset (seconds, 0 = no auto-reset)
 *   emergencyModeDuration: number,     // Duration before auto-reset (seconds, 0 = no auto-reset)
 *
 *   // EXPIRATION TIMESTAMPS (null if no expiration)
 *   forceCheckExpiresAt: timestamp,    // When force check auto-disables
 *   realtimeModeExpiresAt: timestamp,  // When realtime mode auto-disables
 *   emergencyModeExpiresAt: timestamp, // When emergency mode auto-disables
 *
 *   // THRESHOLDS
 *   batteryThreshold: number,          // Battery protection level (default: 20%)
 *   movementThreshold: number,         // Minimum movement in meters (default: 10)
 *   minGpsAccuracy: number,            // Minimum GPS accuracy in meters (default: 50)
 *   minNetworkAccuracy: number,        // Minimum network accuracy in meters (default: 200)
 *
 *   // BATCH UPLOAD SETTINGS
 *   batchUploadSize: number,           // Locations per upload (default: 10)
 *   batchUploadThreshold: number,      // Trigger upload at this count (default: 10)
 *
 *   // METADATA
 *   createdAt: timestamp,
 *   updatedAt: timestamp,
 *   lastSyncedAt: timestamp,           // Last time app synced settings
 *   updatedBy: string,                 // 'user', 'admin', 'system', 'auto-reset'
 *   version: number,                   // Settings version for cache invalidation
 *
 *   // AUDIT
 *   changeHistory: array               // Array of recent changes
 * }
 *
 * ============================================================================
 */

const { getFirebaseAdmin, admin } = require('../config/firebase');

// ============================================================================
// CONSTANTS
// ============================================================================

const COLLECTION_NAME = 'location-tracking-settings';

// Default settings for new users
const DEFAULT_SETTINGS = {
    // Tracking intervals (seconds)
    updateInterval: 3600,           // 1 hour (changed from 2 hours to match hardcoded default)
    realtimeInterval: 10,           // 10 seconds
    emergencyInterval: 30,          // 30 seconds
    forceCheckInterval: 300,        // 5 minutes

    // Mode toggles
    trackingEnabled: true,
    forceCheckEnabled: false,
    realtimeModeEnabled: false,
    emergencyModeEnabled: false,

    // Auto-reset durations (seconds)
    // Emergency: 15 minutes, ForceCheck: 30 minutes
    forceCheckDuration: 1800,       // 30 minutes auto-reset (changed from 1 hour)
    realtimeModeDuration: 1800,     // 30 minutes auto-reset
    emergencyModeDuration: 900,     // 15 minutes auto-reset (changed from 2 hours)

    // Expiration timestamps (null = no expiration)
    forceCheckExpiresAt: null,
    realtimeModeExpiresAt: null,
    emergencyModeExpiresAt: null,

    // Thresholds
    batteryThreshold: 44,           // Battery protection at 44% (to match app)
    movementThreshold: 5,           // 5 meters minimum movement (to match app)
    minGpsAccuracy: 100,            // 100m good accuracy
    minNetworkAccuracy: 200,

    // Batch upload thresholds
    batchUploadSize: 10,
    batchUploadThreshold: 10,       // Normal: upload when >= 10
    batchUploadThresholdHardcoded: 3,  // Using defaults: upload when >= 3

    // Metadata
    version: 1
};

// Maximum history entries to keep (reduced from 50 to avoid bloat)
const MAX_HISTORY_ENTRIES = 20;

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Get Firestore instance
 */
const getFirestore = () => {
    const admin = getFirebaseAdmin();
    return admin.firestore();
};

/**
 * Get server timestamp
 */
const getServerTimestamp = () => {
    // Use the admin module directly (not the initialized app) for FieldValue access
    return admin.firestore.FieldValue.serverTimestamp();
};

/**
 * Calculate expiration timestamp
 * @param {number} durationSeconds - Duration in seconds (0 = no expiration)
 * @returns {Date|null} Expiration date or null
 */
const calculateExpiration = (durationSeconds) => {
    if (!durationSeconds || durationSeconds <= 0) {
        return null;
    }
    return new Date(Date.now() + (durationSeconds * 1000));
};

/**
 * Check if a mode has expired
 * @param {Object} expiresAt - Firestore timestamp or Date
 * @returns {boolean} True if expired
 */
const isExpired = (expiresAt) => {
    if (!expiresAt) return false;

    // Handle Firestore timestamp
    const expirationTime = expiresAt._seconds
        ? expiresAt._seconds * 1000
        : new Date(expiresAt).getTime();

    return Date.now() > expirationTime;
};

/**
 * Create audit log entry
 * @param {string} action - Action performed
 * @param {string} updatedBy - Who made the change
 * @param {Object} changes - What changed
 * @returns {Object} Audit entry
 */
const createAuditEntry = (action, updatedBy, changes) => {
    return {
        action,
        updatedBy,
        changes,
        timestamp: new Date().toISOString()
    };
};

/**
 * Sync Android-compatible field names
 *
 * STANDARD FIELD MAPPING (Backend → Android):
 * ============================================
 * INTERVALS (stored in seconds, sent in milliseconds):
 * - updateInterval (sec) → normalIntervalMs (ms)
 * - realtimeInterval (sec) → realtimeIntervalMs (ms)
 * - emergencyInterval (sec) → emergencyIntervalMs (ms)
 * - forceCheckInterval (sec) → forceCheckIntervalMs (ms)
 *
 * MODE FLAGS (renamed for consistency):
 * - forceCheckEnabled → forceCheck
 * - realtimeModeEnabled → realtimeMode
 * - emergencyModeEnabled → emergencyMode
 *
 * ACCURACY (renamed):
 * - minGpsAccuracy → minGpsAccuracyMeters
 * - minNetworkAccuracy → minNetworkAccuracyMeters
 *
 * MOVEMENT (renamed):
 * - movementThreshold → movementThresholdMeters
 *
 * @param {Object} settings - Settings object with backend field names
 * @returns {Object} Settings with ONLY Android-compatible field names (clean output)
 */
const addAndroidCompatibleFields = (settings) => {
    return {
        // ========== INTERVALS (milliseconds) ==========
        normalIntervalMs: (settings.updateInterval || DEFAULT_SETTINGS.updateInterval) * 1000,
        realtimeIntervalMs: (settings.realtimeInterval || DEFAULT_SETTINGS.realtimeInterval) * 1000,
        emergencyIntervalMs: (settings.emergencyInterval || DEFAULT_SETTINGS.emergencyInterval) * 1000,
        forceCheckIntervalMs: (settings.forceCheckInterval || DEFAULT_SETTINGS.forceCheckInterval) * 1000,

        // ========== MODE FLAGS ==========
        forceCheck: settings.forceCheckEnabled ?? false,
        realtimeMode: settings.realtimeModeEnabled ?? false,
        emergencyMode: settings.emergencyModeEnabled ?? false,
        trackingEnabled: settings.trackingEnabled ?? true,

        // ========== ACCURACY (meters) ==========
        minGpsAccuracyMeters: settings.minGpsAccuracy || DEFAULT_SETTINGS.minGpsAccuracy,
        minNetworkAccuracyMeters: settings.minNetworkAccuracy || DEFAULT_SETTINGS.minNetworkAccuracy,

        // ========== MOVEMENT ==========
        movementThresholdMeters: settings.movementThreshold || DEFAULT_SETTINGS.movementThreshold,

        // ========== BATTERY ==========
        batteryThreshold: settings.batteryThreshold || DEFAULT_SETTINGS.batteryThreshold,
        lowBatteryThreshold: 15,
        criticalBatteryThreshold: 5,

        // ========== METADATA ==========
        lastUpdated: Date.now(),
        source: 'server',
        version: settings.version || 1,

        // ========== KEEP ORIGINAL FIELDS FOR ADMIN PAGE ==========
        // (Admin page uses seconds, Android uses milliseconds)
        updateInterval: settings.updateInterval || DEFAULT_SETTINGS.updateInterval,
        realtimeInterval: settings.realtimeInterval || DEFAULT_SETTINGS.realtimeInterval,
        emergencyInterval: settings.emergencyInterval || DEFAULT_SETTINGS.emergencyInterval,
        forceCheckInterval: settings.forceCheckInterval || DEFAULT_SETTINGS.forceCheckInterval,
        forceCheckEnabled: settings.forceCheckEnabled ?? false,
        realtimeModeEnabled: settings.realtimeModeEnabled ?? false,
        emergencyModeEnabled: settings.emergencyModeEnabled ?? false,
        minGpsAccuracy: settings.minGpsAccuracy || DEFAULT_SETTINGS.minGpsAccuracy,
        minNetworkAccuracy: settings.minNetworkAccuracy || DEFAULT_SETTINGS.minNetworkAccuracy,
        movementThreshold: settings.movementThreshold || DEFAULT_SETTINGS.movementThreshold,

        // ========== DURATION & EXPIRATION ==========
        forceCheckDuration: settings.forceCheckDuration || DEFAULT_SETTINGS.forceCheckDuration,
        realtimeModeDuration: settings.realtimeModeDuration || DEFAULT_SETTINGS.realtimeModeDuration,
        emergencyModeDuration: settings.emergencyModeDuration || DEFAULT_SETTINGS.emergencyModeDuration,
        forceCheckExpiresAt: settings.forceCheckExpiresAt || null,
        realtimeModeExpiresAt: settings.realtimeModeExpiresAt || null,
        emergencyModeExpiresAt: settings.emergencyModeExpiresAt || null,

        // ========== BATCH UPLOAD ==========
        batchUploadSize: settings.batchUploadSize || DEFAULT_SETTINGS.batchUploadSize,
        batchUploadThreshold: settings.batchUploadThreshold || DEFAULT_SETTINGS.batchUploadThreshold
    };
};

/**
 * Validate settings values
 * @param {Object} settings - Settings to validate
 * @returns {Object} { valid: boolean, errors: string[] }
 */
const validateSettings = (settings) => {
    const errors = [];

    // Interval validations (must be positive numbers)
    const intervalFields = [
        'updateInterval', 'realtimeInterval', 'emergencyInterval', 'forceCheckInterval'
    ];
    intervalFields.forEach(field => {
        if (settings[field] !== undefined) {
            if (typeof settings[field] !== 'number' || settings[field] < 1) {
                errors.push(`${field} must be a positive number (minimum 1 second)`);
            }
            if (settings[field] > 86400 * 7) { // Max 1 week
                errors.push(`${field} cannot exceed 604800 seconds (1 week)`);
            }
        }
    });

    // Duration validations (0 or positive)
    const durationFields = [
        'forceCheckDuration', 'realtimeModeDuration', 'emergencyModeDuration'
    ];
    durationFields.forEach(field => {
        if (settings[field] !== undefined) {
            if (typeof settings[field] !== 'number' || settings[field] < 0) {
                errors.push(`${field} must be 0 or a positive number`);
            }
        }
    });

    // Boolean validations
    const booleanFields = [
        'trackingEnabled', 'forceCheckEnabled', 'realtimeModeEnabled', 'emergencyModeEnabled'
    ];
    booleanFields.forEach(field => {
        if (settings[field] !== undefined && typeof settings[field] !== 'boolean') {
            errors.push(`${field} must be a boolean`);
        }
    });

    // Threshold validations
    if (settings.batteryThreshold !== undefined) {
        if (typeof settings.batteryThreshold !== 'number' ||
            settings.batteryThreshold < 0 || settings.batteryThreshold > 100) {
            errors.push('batteryThreshold must be between 0 and 100');
        }
    }

    if (settings.movementThreshold !== undefined) {
        if (typeof settings.movementThreshold !== 'number' || settings.movementThreshold < 0) {
            errors.push('movementThreshold must be a non-negative number');
        }
    }

    // Batch upload validations
    if (settings.batchUploadSize !== undefined) {
        if (typeof settings.batchUploadSize !== 'number' ||
            settings.batchUploadSize < 1 || settings.batchUploadSize > 100) {
            errors.push('batchUploadSize must be between 1 and 100');
        }
    }

    return {
        valid: errors.length === 0,
        errors
    };
};

// ============================================================================
// CONTROLLER FUNCTIONS
// ============================================================================

/**
 * GET /api/location-tracking-settings
 * Get settings for the authenticated user
 * Automatically handles mode expiration
 */
const getSettings = async (req, res) => {
    try {
        const userId = req.user.uid;
        console.log(`📍 [TRACKING-SETTINGS] Fetching settings for user: ${userId}`);

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const doc = await docRef.get();

        let settings;
        let wasCreated = false;
        let wasUpdated = false;
        const updates = {};

        if (!doc.exists) {
            // Create default settings for new user
            console.log(`📝 [TRACKING-SETTINGS] Creating default settings for new user: ${userId}`);

            // Start with defaults and add Android-compatible fields
            const baseSettings = {
                userId,
                ...DEFAULT_SETTINGS,
                createdAt: getServerTimestamp(),
                updatedAt: getServerTimestamp(),
                lastSyncedAt: getServerTimestamp(),
                updatedBy: 'system',
                changeHistory: [createAuditEntry('created', 'system', { reason: 'New user default settings' })]
            };

            // Add Android-compatible fields
            const androidFields = addAndroidCompatibleFields(baseSettings);
            settings = { ...baseSettings, ...androidFields };

            await docRef.set(settings);
            wasCreated = true;

            // For response, replace server timestamps with ISO strings
            settings.createdAt = new Date().toISOString();
            settings.updatedAt = new Date().toISOString();
            settings.lastSyncedAt = new Date().toISOString();
        } else {
            settings = doc.data();

            // Check for expired modes and auto-reset
            const now = new Date();

            if (settings.forceCheckEnabled && isExpired(settings.forceCheckExpiresAt)) {
                console.log(`⏰ [TRACKING-SETTINGS] Force check expired for user: ${userId}`);
                updates.forceCheckEnabled = false;
                updates.forceCheckExpiresAt = null;
                settings.forceCheckEnabled = false;
                settings.forceCheckExpiresAt = null;
                wasUpdated = true;
            }

            if (settings.realtimeModeEnabled && isExpired(settings.realtimeModeExpiresAt)) {
                console.log(`⏰ [TRACKING-SETTINGS] Realtime mode expired for user: ${userId}`);
                updates.realtimeModeEnabled = false;
                updates.realtimeModeExpiresAt = null;
                settings.realtimeModeEnabled = false;
                settings.realtimeModeExpiresAt = null;
                wasUpdated = true;
            }

            if (settings.emergencyModeEnabled && isExpired(settings.emergencyModeExpiresAt)) {
                console.log(`⏰ [TRACKING-SETTINGS] Emergency mode expired for user: ${userId}`);
                updates.emergencyModeEnabled = false;
                updates.emergencyModeExpiresAt = null;
                settings.emergencyModeEnabled = false;
                settings.emergencyModeExpiresAt = null;
                wasUpdated = true;
            }

            // Apply auto-reset updates if needed
            if (wasUpdated) {
                updates.updatedAt = getServerTimestamp();
                updates.updatedBy = 'auto-reset';
                updates.changeHistory = [
                    createAuditEntry('auto-reset', 'system', updates),
                    ...(settings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
                ];

                await docRef.update(updates);
                console.log(`✅ [TRACKING-SETTINGS] Auto-reset applied for user: ${userId}`);
            }

            // Update lastSyncedAt
            await docRef.update({ lastSyncedAt: getServerTimestamp() });
        }

        // Calculate effective interval based on active modes
        let effectiveInterval = settings.updateInterval;
        let activeMode = 'normal';

        if (settings.emergencyModeEnabled) {
            effectiveInterval = settings.emergencyInterval;
            activeMode = 'emergency';
        } else if (settings.realtimeModeEnabled) {
            effectiveInterval = settings.realtimeInterval;
            activeMode = 'realtime';
        } else if (settings.forceCheckEnabled) {
            effectiveInterval = settings.forceCheckInterval;
            activeMode = 'force-check';
        }

        // Prepare response
        const response = {
            success: true,
            message: wasCreated ? 'Default settings created' :
                     wasUpdated ? 'Settings retrieved (auto-reset applied)' :
                     'Settings retrieved successfully',
            data: {
                ...settings,
                effectiveInterval,
                activeMode,
                // Remove sensitive/internal fields
                changeHistory: undefined
            },
            meta: {
                wasCreated,
                wasAutoReset: wasUpdated,
                serverTime: new Date().toISOString()
            }
        };

        console.log(`✅ [TRACKING-SETTINGS] Settings sent for user: ${userId} (mode: ${activeMode}, interval: ${effectiveInterval}s)`);
        res.json(response);

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error fetching settings:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to fetch location tracking settings',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings
 * Update settings for the authenticated user
 * Handles mode activation with expiration
 */
const updateSettings = async (req, res) => {
    try {
        const userId = req.user.uid;
        const newSettings = req.body;

        console.log(`📝 [TRACKING-SETTINGS] Update requested by user: ${userId}`);
        console.log(`📦 [TRACKING-SETTINGS] New settings:`, JSON.stringify(newSettings));

        // Validate settings
        const validation = validateSettings(newSettings);
        if (!validation.valid) {
            console.log(`❌ [TRACKING-SETTINGS] Validation failed:`, validation.errors);
            return res.status(400).json({
                success: false,
                message: 'Validation failed',
                errors: validation.errors
            });
        }

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const doc = await docRef.get();

        // Get current settings or defaults
        const currentSettings = doc.exists ? doc.data() : { ...DEFAULT_SETTINGS, userId };

        // Build update object
        const updates = {
            ...newSettings,
            updatedAt: getServerTimestamp(),
            updatedBy: 'user',
            version: (currentSettings.version || 1) + 1
        };

        // Handle mode activation with expiration
        if (newSettings.forceCheckEnabled === true && !currentSettings.forceCheckEnabled) {
            const duration = newSettings.forceCheckDuration ?? currentSettings.forceCheckDuration ?? DEFAULT_SETTINGS.forceCheckDuration;
            updates.forceCheckExpiresAt = calculateExpiration(duration);
            console.log(`🔥 [TRACKING-SETTINGS] Force check enabled, expires at: ${updates.forceCheckExpiresAt}`);
        } else if (newSettings.forceCheckEnabled === false) {
            updates.forceCheckExpiresAt = null;
        }

        if (newSettings.realtimeModeEnabled === true && !currentSettings.realtimeModeEnabled) {
            const duration = newSettings.realtimeModeDuration ?? currentSettings.realtimeModeDuration ?? DEFAULT_SETTINGS.realtimeModeDuration;
            updates.realtimeModeExpiresAt = calculateExpiration(duration);
            console.log(`⚡ [TRACKING-SETTINGS] Realtime mode enabled, expires at: ${updates.realtimeModeExpiresAt}`);
        } else if (newSettings.realtimeModeEnabled === false) {
            updates.realtimeModeExpiresAt = null;
        }

        if (newSettings.emergencyModeEnabled === true && !currentSettings.emergencyModeEnabled) {
            const duration = newSettings.emergencyModeDuration ?? currentSettings.emergencyModeDuration ?? DEFAULT_SETTINGS.emergencyModeDuration;
            updates.emergencyModeExpiresAt = calculateExpiration(duration);
            console.log(`🚨 [TRACKING-SETTINGS] Emergency mode enabled, expires at: ${updates.emergencyModeExpiresAt}`);
        } else if (newSettings.emergencyModeEnabled === false) {
            updates.emergencyModeExpiresAt = null;
        }

        // Add to change history with ACTUAL VALUES
        const changedValues = { ...newSettings };
        const historyEntry = createAuditEntry('updated', 'user', changedValues);

        updates.changeHistory = [
            historyEntry,
            ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
        ];

        // Add Android-compatible field names
        // Merge current settings with updates to get complete picture for Android fields
        const mergedForAndroid = { ...currentSettings, ...updates };
        const androidFields = addAndroidCompatibleFields(mergedForAndroid);

        // Add ALL necessary fields - both original (seconds) and Android (milliseconds)
        Object.assign(updates, {
            // Android-compatible fields (milliseconds)
            normalIntervalMs: androidFields.normalIntervalMs,
            realtimeIntervalMs: androidFields.realtimeIntervalMs,
            emergencyIntervalMs: androidFields.emergencyIntervalMs,
            forceCheckIntervalMs: androidFields.forceCheckIntervalMs,

            // Original interval fields (seconds)
            updateInterval: androidFields.updateInterval,
            realtimeInterval: androidFields.realtimeInterval,
            emergencyInterval: androidFields.emergencyInterval,
            forceCheckInterval: androidFields.forceCheckInterval,

            // Mode flags
            forceCheck: androidFields.forceCheck,
            realtimeMode: androidFields.realtimeMode,
            emergencyMode: androidFields.emergencyMode,
            trackingEnabled: androidFields.trackingEnabled,
            forceCheckEnabled: androidFields.forceCheckEnabled,
            realtimeModeEnabled: androidFields.realtimeModeEnabled,
            emergencyModeEnabled: androidFields.emergencyModeEnabled,

            // Thresholds
            batteryThreshold: androidFields.batteryThreshold,
            movementThreshold: androidFields.movementThreshold,
            minGpsAccuracy: androidFields.minGpsAccuracy,
            minNetworkAccuracy: androidFields.minNetworkAccuracy,

            // Batch upload settings
            batchUploadSize: androidFields.batchUploadSize,
            batchUploadThreshold: androidFields.batchUploadThreshold,

            // Metadata
            lastUpdated: androidFields.lastUpdated,
            source: androidFields.source
        });

        // Perform update or create
        if (doc.exists) {
            await docRef.update(updates);
        } else {
            await docRef.set({
                userId,
                ...DEFAULT_SETTINGS,
                ...updates,
                createdAt: getServerTimestamp()
            });
        }

        // Fetch updated document
        const updatedDoc = await docRef.get();
        const updatedSettings = updatedDoc.data();

        // Calculate effective interval
        let effectiveInterval = updatedSettings.updateInterval;
        let activeMode = 'normal';

        if (updatedSettings.emergencyModeEnabled) {
            effectiveInterval = updatedSettings.emergencyInterval;
            activeMode = 'emergency';
        } else if (updatedSettings.realtimeModeEnabled) {
            effectiveInterval = updatedSettings.realtimeInterval;
            activeMode = 'realtime';
        } else if (updatedSettings.forceCheckEnabled) {
            effectiveInterval = updatedSettings.forceCheckInterval;
            activeMode = 'force-check';
        }

        console.log(`✅ [TRACKING-SETTINGS] Settings updated for user: ${userId} (mode: ${activeMode})`);

        res.json({
            success: true,
            message: 'Settings updated successfully',
            data: {
                ...updatedSettings,
                effectiveInterval,
                activeMode,
                changeHistory: undefined
            },
            meta: {
                serverTime: new Date().toISOString()
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error updating settings:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to update location tracking settings',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings/enable-mode
 * Quick endpoint to enable a specific mode
 * Supports: force-check, realtime, emergency
 */
const enableMode = async (req, res) => {
    try {
        const userId = req.user.uid;
        const { mode, duration } = req.body;

        const validModes = ['force-check', 'realtime', 'emergency'];
        if (!mode || !validModes.includes(mode)) {
            return res.status(400).json({
                success: false,
                message: `Invalid mode. Must be one of: ${validModes.join(', ')}`
            });
        }

        console.log(`🔔 [TRACKING-SETTINGS] Enabling ${mode} mode for user: ${userId}`);

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const doc = await docRef.get();

        const currentSettings = doc.exists ? doc.data() : { ...DEFAULT_SETTINGS, userId };
        const updates = {
            updatedAt: getServerTimestamp(),
            updatedBy: 'user',
            version: (currentSettings.version || 1) + 1
        };

        // Set the appropriate mode
        switch (mode) {
            case 'force-check':
                updates.forceCheckEnabled = true;
                const forceCheckDuration = duration ?? currentSettings.forceCheckDuration ?? DEFAULT_SETTINGS.forceCheckDuration;
                updates.forceCheckExpiresAt = calculateExpiration(forceCheckDuration);
                break;

            case 'realtime':
                updates.realtimeModeEnabled = true;
                const realtimeDuration = duration ?? currentSettings.realtimeModeDuration ?? DEFAULT_SETTINGS.realtimeModeDuration;
                updates.realtimeModeExpiresAt = calculateExpiration(realtimeDuration);
                break;

            case 'emergency':
                updates.emergencyModeEnabled = true;
                const emergencyDuration = duration ?? currentSettings.emergencyModeDuration ?? DEFAULT_SETTINGS.emergencyModeDuration;
                updates.emergencyModeExpiresAt = calculateExpiration(emergencyDuration);
                break;
        }

        // Add audit entry with actual values
        const modeChanges = {
            mode,
            duration,
            enabled: true
        };
        // Add the specific mode settings
        if (mode === 'force-check') {
            modeChanges.forceCheckEnabled = true;
            modeChanges.forceCheckInterval = currentSettings.forceCheckInterval || DEFAULT_SETTINGS.forceCheckInterval;
        } else if (mode === 'realtime') {
            modeChanges.realtimeModeEnabled = true;
            modeChanges.realtimeInterval = currentSettings.realtimeInterval || DEFAULT_SETTINGS.realtimeInterval;
        } else if (mode === 'emergency') {
            modeChanges.emergencyModeEnabled = true;
            modeChanges.emergencyInterval = currentSettings.emergencyInterval || DEFAULT_SETTINGS.emergencyInterval;
        }

        updates.changeHistory = [
            createAuditEntry('mode-enabled', 'user', modeChanges),
            ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
        ];

        // Add Android-compatible field names AND ensure all interval fields are saved
        const mergedForAndroid = { ...currentSettings, ...updates };
        const androidFields = addAndroidCompatibleFields(mergedForAndroid);

        // Add ALL necessary fields - both original (seconds) and Android (milliseconds)
        Object.assign(updates, {
            // Android-compatible fields (milliseconds)
            normalIntervalMs: androidFields.normalIntervalMs,
            realtimeIntervalMs: androidFields.realtimeIntervalMs,
            emergencyIntervalMs: androidFields.emergencyIntervalMs,
            forceCheckIntervalMs: androidFields.forceCheckIntervalMs,

            // Original interval fields (seconds) - MUST be stored for admin page and reference
            updateInterval: androidFields.updateInterval,
            realtimeInterval: androidFields.realtimeInterval,
            emergencyInterval: androidFields.emergencyInterval,
            forceCheckInterval: androidFields.forceCheckInterval,

            // Mode flags
            forceCheck: androidFields.forceCheck,
            realtimeMode: androidFields.realtimeMode,
            emergencyMode: androidFields.emergencyMode,
            trackingEnabled: androidFields.trackingEnabled,
            forceCheckEnabled: androidFields.forceCheckEnabled,
            realtimeModeEnabled: androidFields.realtimeModeEnabled,
            emergencyModeEnabled: androidFields.emergencyModeEnabled,

            // Thresholds
            batteryThreshold: androidFields.batteryThreshold,
            movementThreshold: androidFields.movementThreshold,
            minGpsAccuracy: androidFields.minGpsAccuracy,
            minNetworkAccuracy: androidFields.minNetworkAccuracy,

            // Batch upload settings
            batchUploadSize: androidFields.batchUploadSize,
            batchUploadThreshold: androidFields.batchUploadThreshold,

            // Metadata
            lastUpdated: androidFields.lastUpdated,
            source: androidFields.source
        });

        console.log(`📊 [TRACKING-SETTINGS] Saving intervals: updateInterval=${updates.updateInterval}s, emergencyInterval=${updates.emergencyInterval}s, forceCheckInterval=${updates.forceCheckInterval}s`);

        if (doc.exists) {
            await docRef.update(updates);
        } else {
            await docRef.set({
                userId,
                ...DEFAULT_SETTINGS,
                ...updates,
                createdAt: getServerTimestamp()
            });
        }

        console.log(`✅ [TRACKING-SETTINGS] ${mode} mode enabled for user: ${userId}`);

        res.json({
            success: true,
            message: `${mode} mode enabled successfully`,
            data: {
                mode,
                enabled: true,
                expiresAt: updates[`${mode.replace('-', '')}ExpiresAt`] ||
                          updates.forceCheckExpiresAt ||
                          updates.realtimeModeExpiresAt ||
                          updates.emergencyModeExpiresAt
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error enabling mode:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to enable mode',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings/disable-mode
 * Quick endpoint to disable a specific mode
 */
const disableMode = async (req, res) => {
    try {
        const userId = req.user.uid;
        const { mode } = req.body;

        const validModes = ['force-check', 'realtime', 'emergency'];
        if (!mode || !validModes.includes(mode)) {
            return res.status(400).json({
                success: false,
                message: `Invalid mode. Must be one of: ${validModes.join(', ')}`
            });
        }

        console.log(`🔕 [TRACKING-SETTINGS] Disabling ${mode} mode for user: ${userId}`);

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const doc = await docRef.get();

        if (!doc.exists) {
            return res.status(404).json({
                success: false,
                message: 'Settings not found for user'
            });
        }

        const currentSettings = doc.data();
        const updates = {
            updatedAt: getServerTimestamp(),
            updatedBy: 'user',
            version: (currentSettings.version || 1) + 1
        };

        // Disable the appropriate mode
        switch (mode) {
            case 'force-check':
                updates.forceCheckEnabled = false;
                updates.forceCheckExpiresAt = null;
                break;

            case 'realtime':
                updates.realtimeModeEnabled = false;
                updates.realtimeModeExpiresAt = null;
                break;

            case 'emergency':
                updates.emergencyModeEnabled = false;
                updates.emergencyModeExpiresAt = null;
                break;
        }

        // Add audit entry with actual values
        const modeChanges = {
            mode,
            enabled: false
        };
        if (mode === 'force-check') {
            modeChanges.forceCheckEnabled = false;
        } else if (mode === 'realtime') {
            modeChanges.realtimeModeEnabled = false;
        } else if (mode === 'emergency') {
            modeChanges.emergencyModeEnabled = false;
        }

        updates.changeHistory = [
            createAuditEntry('mode-disabled', 'user', modeChanges),
            ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
        ];

        // Add Android-compatible field names
        const mergedForAndroid = { ...currentSettings, ...updates };
        const androidFields = addAndroidCompatibleFields(mergedForAndroid);

        // Add ALL necessary fields - both original (seconds) and Android (milliseconds)
        Object.assign(updates, {
            // Android-compatible fields (milliseconds)
            normalIntervalMs: androidFields.normalIntervalMs,
            realtimeIntervalMs: androidFields.realtimeIntervalMs,
            emergencyIntervalMs: androidFields.emergencyIntervalMs,
            forceCheckIntervalMs: androidFields.forceCheckIntervalMs,

            // Original interval fields (seconds)
            updateInterval: androidFields.updateInterval,
            realtimeInterval: androidFields.realtimeInterval,
            emergencyInterval: androidFields.emergencyInterval,
            forceCheckInterval: androidFields.forceCheckInterval,

            // Mode flags
            forceCheck: androidFields.forceCheck,
            realtimeMode: androidFields.realtimeMode,
            emergencyMode: androidFields.emergencyMode,
            trackingEnabled: androidFields.trackingEnabled,
            forceCheckEnabled: androidFields.forceCheckEnabled,
            realtimeModeEnabled: androidFields.realtimeModeEnabled,
            emergencyModeEnabled: androidFields.emergencyModeEnabled,

            // Thresholds
            batteryThreshold: androidFields.batteryThreshold,
            movementThreshold: androidFields.movementThreshold,
            minGpsAccuracy: androidFields.minGpsAccuracy,
            minNetworkAccuracy: androidFields.minNetworkAccuracy,

            // Batch upload settings
            batchUploadSize: androidFields.batchUploadSize,
            batchUploadThreshold: androidFields.batchUploadThreshold,

            // Metadata
            lastUpdated: androidFields.lastUpdated,
            source: androidFields.source
        });

        await docRef.update(updates);

        console.log(`✅ [TRACKING-SETTINGS] ${mode} mode disabled for user: ${userId}`);

        res.json({
            success: true,
            message: `${mode} mode disabled successfully`,
            data: {
                mode,
                enabled: false
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error disabling mode:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to disable mode',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings/reset
 * Reset settings to defaults
 */
const resetSettings = async (req, res) => {
    try {
        const userId = req.user.uid;
        const { keepHistory } = req.body;

        console.log(`🔄 [TRACKING-SETTINGS] Resetting settings for user: ${userId}`);

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const doc = await docRef.get();

        const existingHistory = doc.exists && keepHistory ? doc.data().changeHistory : [];

        // Start with base settings
        const baseSettings = {
            userId,
            ...DEFAULT_SETTINGS,
            createdAt: doc.exists ? doc.data().createdAt : getServerTimestamp(),
            updatedAt: getServerTimestamp(),
            lastSyncedAt: getServerTimestamp(),
            updatedBy: 'user',
            version: doc.exists ? (doc.data().version || 1) + 1 : 1,
            changeHistory: [
                createAuditEntry('reset', 'user', { reason: 'User requested reset to defaults' }),
                ...existingHistory.slice(0, MAX_HISTORY_ENTRIES - 1)
            ]
        };

        // Add Android-compatible fields
        const androidFields = addAndroidCompatibleFields(baseSettings);
        const newSettings = { ...baseSettings, ...androidFields };

        await docRef.set(newSettings);

        console.log(`✅ [TRACKING-SETTINGS] Settings reset for user: ${userId}`);

        res.json({
            success: true,
            message: 'Settings reset to defaults successfully',
            data: {
                ...newSettings,
                changeHistory: undefined
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error resetting settings:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to reset settings',
            error: error.message
        });
    }
};

/**
 * GET /api/location-tracking-settings/history
 * Get settings change history
 */
const getHistory = async (req, res) => {
    try {
        const userId = req.user.uid;
        const limit = Math.min(parseInt(req.query.limit) || 20, MAX_HISTORY_ENTRIES);

        console.log(`📜 [TRACKING-SETTINGS] Fetching history for user: ${userId}`);

        const db = getFirestore();
        const doc = await db.collection(COLLECTION_NAME).doc(userId).get();

        if (!doc.exists) {
            return res.json({
                success: true,
                data: {
                    history: [],
                    total: 0
                }
            });
        }

        const settings = doc.data();
        const history = (settings.changeHistory || []).slice(0, limit);

        res.json({
            success: true,
            data: {
                history,
                total: (settings.changeHistory || []).length
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Error fetching history:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to fetch settings history',
            error: error.message
        });
    }
};

/**
 * GET /api/location-tracking-settings/defaults
 * Get default settings (no auth required)
 */
const getDefaults = (req, res) => {
    console.log(`📋 [TRACKING-SETTINGS] Default settings requested`);

    res.json({
        success: true,
        message: 'Default location tracking settings',
        defaults: { ...DEFAULT_SETTINGS },  // Changed from 'data' to 'defaults'
        meta: {
            serverTime: new Date().toISOString()
        }
    });
};

/**
 * GET /api/location-tracking-settings/health
 * Health check endpoint
 */
const healthCheck = async (req, res) => {
    try {
        console.log(`🏥 [TRACKING-SETTINGS] Health check requested`);

        // Try to access Firestore to verify connectivity
        const db = getFirestore();
        await db.collection(COLLECTION_NAME).limit(1).get();

        res.json({
            success: true,
            message: 'Location tracking settings service is healthy',
            status: 'healthy',
            timestamp: new Date().toISOString(),
            version: '2.0.0',
            collection: COLLECTION_NAME
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Health check failed:`, error);
        res.status(503).json({
            success: false,
            message: 'Service unhealthy',
            status: 'unhealthy',
            error: error.message
        });
    }
};

// ============================================================================
// ADMIN ENDPOINTS (For future web admin panel)
// ============================================================================

/**
 * GET /api/location-tracking-settings/admin/user/:userId
 * Admin: Get settings for a specific user
 */
const adminGetUserSettings = async (req, res) => {
    try {
        const requestingUserId = req.user.uid;
        const targetUserId = req.params.userId;

        // TODO: Add proper admin role check
        console.log(`👑 [TRACKING-SETTINGS] Admin request by ${requestingUserId} for user: ${targetUserId}`);

        const db = getFirestore();
        const doc = await db.collection(COLLECTION_NAME).doc(targetUserId).get();

        if (!doc.exists) {
            // Return default settings if no custom settings exist
            console.log(`👑 [TRACKING-SETTINGS] No custom settings for ${targetUserId}, returning defaults`);

            const defaultSettingsWithMeta = {
                ...DEFAULT_SETTINGS,
                userId: targetUserId,
                isDefault: true,  // Flag to indicate these are defaults
                createdAt: null,
                updatedAt: null,
                lastSyncedAt: null,
                updatedBy: null
            };

            return res.json({
                success: true,
                data: defaultSettingsWithMeta,
                message: 'No custom settings found, returning defaults'
            });
        }

        res.json({
            success: true,
            data: {
                ...doc.data(),
                isDefault: false  // Flag to indicate these are custom settings
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Admin get user settings error:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to fetch user settings',
            error: error.message
        });
    }
};

/**
 * PUT /api/location-tracking-settings/admin/user/:userId
 * Admin: Update settings for a specific user
 */
const adminUpdateUserSettings = async (req, res) => {
    try {
        const requestingUserId = req.user.uid;
        const targetUserId = req.params.userId;
        const newSettings = req.body;

        // TODO: Add proper admin role check
        console.log(`👑 [TRACKING-SETTINGS] Admin update by ${requestingUserId} for user: ${targetUserId}`);
        console.log(`📥 [TRACKING-SETTINGS] Received from admin page:`, JSON.stringify(newSettings));

        // Validate settings
        const validation = validateSettings(newSettings);
        if (!validation.valid) {
            return res.status(400).json({
                success: false,
                message: 'Validation failed',
                errors: validation.errors
            });
        }

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(targetUserId);
        const doc = await docRef.get();

        const currentSettings = doc.exists ? doc.data() : { ...DEFAULT_SETTINGS, userId: targetUserId };

        const updates = {
            ...newSettings,
            updatedAt: getServerTimestamp(),
            updatedBy: `admin:${requestingUserId}`,
            version: (currentSettings.version || 1) + 1
        };

        // Handle mode expirations same as regular update
        if (newSettings.forceCheckEnabled === true && !currentSettings.forceCheckEnabled) {
            updates.forceCheckExpiresAt = calculateExpiration(newSettings.forceCheckDuration ?? DEFAULT_SETTINGS.forceCheckDuration);
        }
        if (newSettings.realtimeModeEnabled === true && !currentSettings.realtimeModeEnabled) {
            updates.realtimeModeExpiresAt = calculateExpiration(newSettings.realtimeModeDuration ?? DEFAULT_SETTINGS.realtimeModeDuration);
        }
        if (newSettings.emergencyModeEnabled === true && !currentSettings.emergencyModeEnabled) {
            updates.emergencyModeExpiresAt = calculateExpiration(newSettings.emergencyModeDuration ?? DEFAULT_SETTINGS.emergencyModeDuration);
        }

        // Create change history with ACTUAL VALUES, not just field names
        const changedValues = {};
        Object.keys(newSettings).forEach(key => {
            changedValues[key] = newSettings[key];
        });

        updates.changeHistory = [
            createAuditEntry('admin-updated', `admin:${requestingUserId}`, changedValues),
            ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
        ];

        // Add Android-compatible field names
        const mergedForAndroid = { ...currentSettings, ...updates };
        const androidFields = addAndroidCompatibleFields(mergedForAndroid);

        // Add ALL necessary fields - both original (seconds) and Android (milliseconds)
        Object.assign(updates, {
            // Android-compatible fields (milliseconds)
            normalIntervalMs: androidFields.normalIntervalMs,
            realtimeIntervalMs: androidFields.realtimeIntervalMs,
            emergencyIntervalMs: androidFields.emergencyIntervalMs,
            forceCheckIntervalMs: androidFields.forceCheckIntervalMs,

            // Original interval fields (seconds)
            updateInterval: androidFields.updateInterval,
            realtimeInterval: androidFields.realtimeInterval,
            emergencyInterval: androidFields.emergencyInterval,
            forceCheckInterval: androidFields.forceCheckInterval,

            // Mode flags
            forceCheck: androidFields.forceCheck,
            realtimeMode: androidFields.realtimeMode,
            emergencyMode: androidFields.emergencyMode,
            trackingEnabled: androidFields.trackingEnabled,
            forceCheckEnabled: androidFields.forceCheckEnabled,
            realtimeModeEnabled: androidFields.realtimeModeEnabled,
            emergencyModeEnabled: androidFields.emergencyModeEnabled,

            // Thresholds
            batteryThreshold: androidFields.batteryThreshold,
            movementThreshold: androidFields.movementThreshold,
            minGpsAccuracy: androidFields.minGpsAccuracy,
            minNetworkAccuracy: androidFields.minNetworkAccuracy,

            // Batch upload settings
            batchUploadSize: androidFields.batchUploadSize,
            batchUploadThreshold: androidFields.batchUploadThreshold,

            // Metadata
            lastUpdated: androidFields.lastUpdated,
            source: androidFields.source
        });

        if (doc.exists) {
            await docRef.set(updates, { merge: true });
        } else {
            await docRef.set({
                userId: targetUserId,
                ...DEFAULT_SETTINGS,
                ...updates,
                createdAt: getServerTimestamp()
            });
        }

        const updatedDoc = await docRef.get();

        console.log(`✅ [TRACKING-SETTINGS] Admin updated settings for user: ${targetUserId}`);

        // NOTE: Firestore Realtime Listener handles instant settings updates
        // No FCM needed - the device receives changes immediately via listener

        res.json({
            success: true,
            message: 'User settings updated successfully',
            data: updatedDoc.data()
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Admin update error:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to update user settings',
            error: error.message
        });
    }
};

/**
 * GET /api/location-tracking-settings/admin/all
 * Admin: Get all users' settings (paginated)
 */
const adminGetAllSettings = async (req, res) => {
    try {
        const requestingUserId = req.user.uid;
        const limit = Math.min(parseInt(req.query.limit) || 50, 100);
        const startAfter = req.query.startAfter;

        // TODO: Add proper admin role check
        console.log(`👑 [TRACKING-SETTINGS] Admin listing all settings by: ${requestingUserId}`);

        const db = getFirestore();
        let query = db.collection(COLLECTION_NAME)
            .orderBy('updatedAt', 'desc')
            .limit(limit);

        if (startAfter) {
            const startDoc = await db.collection(COLLECTION_NAME).doc(startAfter).get();
            if (startDoc.exists) {
                query = query.startAfter(startDoc);
            }
        }

        const snapshot = await query.get();
        const settings = snapshot.docs.map(doc => ({
            id: doc.id,
            ...doc.data(),
            changeHistory: undefined // Don't include full history in list
        }));

        res.json({
            success: true,
            data: {
                settings,
                count: settings.length,
                hasMore: settings.length === limit
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Admin list all error:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to list settings',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings/admin/broadcast
 * Admin: Broadcast a setting change to all users
 */
const adminBroadcastSettings = async (req, res) => {
    try {
        const requestingUserId = req.user.uid;
        const { settings: newSettings, reason } = req.body;

        // TODO: Add proper admin role check
        console.log(`👑 [TRACKING-SETTINGS] Admin broadcast by: ${requestingUserId}`);

        if (!newSettings || Object.keys(newSettings).length === 0) {
            return res.status(400).json({
                success: false,
                message: 'No settings provided to broadcast'
            });
        }

        // Validate settings
        const validation = validateSettings(newSettings);
        if (!validation.valid) {
            return res.status(400).json({
                success: false,
                message: 'Validation failed',
                errors: validation.errors
            });
        }

        const db = getFirestore();
        const snapshot = await db.collection(COLLECTION_NAME).get();

        const batch = db.batch();
        let updateCount = 0;

        for (const doc of snapshot.docs) {
            const currentSettings = doc.data();
            const docRef = db.collection(COLLECTION_NAME).doc(doc.id);

            // Merge current with new settings to calculate Android fields
            const mergedForAndroid = { ...currentSettings, ...newSettings };
            const androidFields = addAndroidCompatibleFields(mergedForAndroid);

            batch.update(docRef, {
                ...newSettings,
                // Android-compatible fields
                normalIntervalMs: androidFields.normalIntervalMs,
                realtimeIntervalMs: androidFields.realtimeIntervalMs,
                emergencyIntervalMs: androidFields.emergencyIntervalMs,
                forceCheckIntervalMs: androidFields.forceCheckIntervalMs,
                forceCheck: androidFields.forceCheck,
                realtimeMode: androidFields.realtimeMode,
                emergencyMode: androidFields.emergencyMode,
                lastUpdated: androidFields.lastUpdated,
                source: androidFields.source,
                // Metadata
                updatedAt: getServerTimestamp(),
                updatedBy: `admin-broadcast:${requestingUserId}`,
                version: (currentSettings.version || 1) + 1,
                changeHistory: [
                    createAuditEntry('admin-broadcast', `admin:${requestingUserId}`, {
                        ...newSettings,
                        reason: reason || 'Admin broadcast update'
                    }),
                    ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
                ]
            });
            updateCount++;
        }

        await batch.commit();

        console.log(`✅ [TRACKING-SETTINGS] Broadcast complete. Updated ${updateCount} users.`);

        res.json({
            success: true,
            message: `Settings broadcast to ${updateCount} users`,
            data: {
                updatedCount: updateCount,
                settings: newSettings
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Admin broadcast error:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to broadcast settings',
            error: error.message
        });
    }
};

/**
 * POST /api/location-tracking-settings/admin/user/:userId/force-sync
 * Admin: Force sync settings to a specific user
 * This bumps the version number to trigger immediate sync via realtime listener
 */
const adminForceSyncUserSettings = async (req, res) => {
    try {
        const requestingUserId = req.user.uid;
        const { userId } = req.params;

        console.log(`🔄 [TRACKING-SETTINGS] Admin force sync for user: ${userId} by: ${requestingUserId}`);

        if (!userId) {
            return res.status(400).json({
                success: false,
                message: 'User ID is required'
            });
        }

        const db = getFirestore();
        const docRef = db.collection(COLLECTION_NAME).doc(userId);
        const docSnap = await docRef.get();

        if (!docSnap.exists) {
            return res.status(404).json({
                success: false,
                message: 'User settings not found'
            });
        }

        const currentSettings = docSnap.data();
        const newVersion = (currentSettings.version || 1) + 1;
        const syncedAt = new Date().toISOString();

        // Add Android-compatible fields to ensure they're always present
        const androidFields = addAndroidCompatibleFields(currentSettings);

        // Update with new version to trigger realtime listener
        await docRef.update({
            // Android-compatible fields (ensure they exist)
            normalIntervalMs: androidFields.normalIntervalMs,
            realtimeIntervalMs: androidFields.realtimeIntervalMs,
            emergencyIntervalMs: androidFields.emergencyIntervalMs,
            forceCheckIntervalMs: androidFields.forceCheckIntervalMs,
            forceCheck: androidFields.forceCheck,
            realtimeMode: androidFields.realtimeMode,
            emergencyMode: androidFields.emergencyMode,
            lastUpdated: Date.now(),
            source: 'server',
            // Metadata
            updatedAt: getServerTimestamp(),
            updatedBy: `admin-force-sync:${requestingUserId}`,
            version: newVersion,
            lastAdminSyncAt: getServerTimestamp(),
            changeHistory: [
                createAuditEntry('admin-force-sync', `admin:${requestingUserId}`, {
                    reason: 'Force sync triggered from admin panel',
                    previousVersion: currentSettings.version || 1,
                    newVersion: newVersion
                }),
                ...(currentSettings.changeHistory || []).slice(0, MAX_HISTORY_ENTRIES - 1)
            ]
        });

        console.log(`✅ [TRACKING-SETTINGS] Force sync complete for user: ${userId}, new version: ${newVersion}`);

        res.json({
            success: true,
            message: 'Settings synced successfully',
            data: {
                userId,
                version: newVersion,
                syncedAt
            }
        });

    } catch (error) {
        console.error(`❌ [TRACKING-SETTINGS] Admin force sync error:`, error);
        res.status(500).json({
            success: false,
            message: 'Failed to force sync settings',
            error: error.message
        });
    }
};

// ============================================================================
// EXPORTS
// ============================================================================

module.exports = {
    // User endpoints
    getSettings,
    updateSettings,
    enableMode,
    disableMode,
    resetSettings,
    getHistory,
    getDefaults,
    healthCheck,

    // Admin endpoints
    adminGetUserSettings,
    adminUpdateUserSettings,
    adminGetAllSettings,
    adminBroadcastSettings,
    adminForceSyncUserSettings,

    // Helper exports (for testing)
    DEFAULT_SETTINGS,
    validateSettings
};