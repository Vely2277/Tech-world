/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * FCM CONTROLLER - SERVICE RESURRECTION & WAKE MANAGEMENT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Manages FCM-based service resurrection for location tracking:
 * - Monitors user activity (location uploads)
 * - Sends WAKE_SERVICE FCM when device goes silent
 * - Tracks wake attempts and responses
 * - Provides admin controls for manual intervention
 *
 * TIMING PARAMETERS:
 * - SILENCE_THRESHOLD: 3 hours - Time without data before first FCM
 * - WAKE_INTERVAL: 2 hours - Time between FCM attempts
 * - MAX_ATTEMPTS: 12 - Maximum FCM attempts (24 hours)
 * - UNREACHABLE_AFTER: 24 hours - Mark as unreachable if no response
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * DEVICE STATUS REPORTING ARCHITECTURE (IMPORTANT!)
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Device status is reported via FIREBASE REALTIME LISTENER ONLY:
 *
 * 1. ANDROID APP (DeviceStatusReporter.kt):
 *    - Writes directly to Firestore collections:
 *      • deviceStatus/{userId} - Dedicated status document
 *      • locationTrackingSettings/{userId} - Settings + status (PRIMARY)
 *      • deviceNotifications - Event log for notifications
 *    - Updates happen in real-time (2-5 seconds latency)
 *
 * 2. ADMIN PAGE (main.js + firebase.js):
 *    - Listens to Firebase Realtime updates via Firebase SDK
 *    - Subscribes to: locationTrackingSettings/{userId}
 *    - Updates UI instantly when status changes
 *    - NO HTTP requests needed for status
 *
 * 3. THIS BACKEND:
 *    - Does NOT handle device status updates
 *    - handleDeviceStatus endpoint is DEPRECATED
 *    - Only manages FCM wake cycles and notifications
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const SILENCE_THRESHOLD_MS = 3 * 60 * 60 * 1000;      // 3 hours
const WAKE_INTERVAL_MS = 2 * 60 * 60 * 1000;          // 2 hours between attempts
const MAX_WAKE_ATTEMPTS = 12;                          // 12 attempts = 24 hours
const UNREACHABLE_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 hours

// Collection names
const COLLECTION_LOCATION_TRACKING = 'locationTracking';
const COLLECTION_FCM_WAKE_HISTORY = 'fcmWakeHistory';
const COLLECTION_FCM_TOKENS = 'fcmTokens';
const COLLECTION_USERS = 'users';

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Get Firestore instance
 */
const getFirestore = () => {
  const admin = getFirebaseAdmin();
  return admin.firestore();
};

/**
 * Get Firebase Messaging instance
 */
const getMessaging = () => {
  const admin = getFirebaseAdmin();
  return admin.messaging();
};

/**
 * Generate unique request ID
 */
const generateRequestId = (userId, reason) => {
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 8);
  return `wake_${userId.substring(0, 8)}_${timestamp}_${random}`;
};

/**
 * Get user's FCM tokens
 */
const getUserFCMTokens = async (userId) => {
  const db = getFirestore();

  try {
    const tokensDoc = await db.collection(COLLECTION_FCM_TOKENS).doc(userId).get();

    if (!tokensDoc.exists) {
      console.log(`⚠️ [FCM] No FCM tokens found for user: ${userId}`);
      return [];
    }

    const data = tokensDoc.data();
    const tokens = data.tokens || [];

    // Return unique, active tokens
    const uniqueTokens = [...new Set(tokens.map(t => t.token))];
    console.log(`📱 [FCM] Found ${uniqueTokens.length} FCM tokens for user: ${userId}`);

    return uniqueTokens;
  } catch (error) {
    console.error(`❌ [FCM] Error getting FCM tokens for user ${userId}:`, error);
    return [];
  }
};

/**
 * Send WAKE_SERVICE FCM to user
 */
const sendWakeFCM = async (userId, reason, requestId) => {
  const messaging = getMessaging();
  const tokens = await getUserFCMTokens(userId);

  if (tokens.length === 0) {
    console.log(`⚠️ [FCM] Cannot send wake FCM - no tokens for user: ${userId}`);
    return { success: false, reason: 'NO_TOKENS' };
  }

  const message = {
    data: {
      type: 'WAKE_SERVICE',
      action: 'CHECK_STATUS',
      requestId: requestId,
      reason: reason,
      timestamp: Date.now().toString(),
      priority: 'high'
    },
    android: {
      priority: 'high',
      ttl: 86400000, // 24 hours TTL
    }
  };

  let successCount = 0;
  let failureCount = 0;
  const failedTokens = [];

  for (const token of tokens) {
    try {
      await messaging.send({
        ...message,
        token: token
      });
      successCount++;
      console.log(`✅ [FCM] Wake FCM sent to token: ${token.substring(0, 20)}...`);
    } catch (error) {
      failureCount++;
      failedTokens.push(token);
      console.error(`❌ [FCM] Failed to send to token: ${token.substring(0, 20)}...`, error.message);

      // Remove invalid tokens
      if (error.code === 'messaging/invalid-registration-token' ||
          error.code === 'messaging/registration-token-not-registered') {
        await removeInvalidToken(userId, token);
      }
    }
  }

  return {
    success: successCount > 0,
    successCount,
    failureCount,
    failedTokens
  };
};

/**
 * Remove invalid FCM token from database
 */
const removeInvalidToken = async (userId, token) => {
  const db = getFirestore();

  try {
    const tokensDoc = await db.collection(COLLECTION_FCM_TOKENS).doc(userId).get();
    if (tokensDoc.exists) {
      const data = tokensDoc.data();
      const tokens = data.tokens || [];
      const tokenToRemove = tokens.find(t => t.token === token);

      if (tokenToRemove) {
        await db.collection(COLLECTION_FCM_TOKENS).doc(userId).update({
          tokens: firebaseAdmin.firestore.FieldValue.arrayRemove(tokenToRemove)
        });
        console.log(`🗑️ [FCM] Removed invalid token for user: ${userId}`);
      }
    }
  } catch (error) {
    console.error(`❌ [FCM] Error removing invalid token:`, error);
  }
};

/**
 * Get or create user's wake state document
 */
const getUserWakeState = async (userId) => {
  const db = getFirestore();
  const docRef = db.collection(COLLECTION_LOCATION_TRACKING).doc(userId);
  const doc = await docRef.get();

  if (!doc.exists) {
    return {
      userId,
      lastLocationUploadTime: null,
      fcmWakeState: {
        isActive: false,
        startedAt: null,
        attemptCount: 0,
        lastAttemptTime: null,
        lastResponseTime: null,
        status: 'idle', // idle | sending | responded | unreachable
        lastRequestId: null
      }
    };
  }

  const data = doc.data();
  return {
    userId,
    lastLocationUploadTime: data.lastLocationUploadTime || null,
    fcmWakeState: data.fcmWakeState || {
      isActive: false,
      startedAt: null,
      attemptCount: 0,
      lastAttemptTime: null,
      lastResponseTime: null,
      status: 'idle',
      lastRequestId: null
    }
  };
};

/**
 * Update user's wake state
 */
const updateUserWakeState = async (userId, wakeState) => {
  const db = getFirestore();
  const docRef = db.collection(COLLECTION_LOCATION_TRACKING).doc(userId);

  await docRef.set({
    userId,
    fcmWakeState: wakeState,
    updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
  }, { merge: true });
};

/**
 * Record wake attempt in history
 */
const recordWakeAttempt = async (userId, requestId, reason, fcmResult) => {
  const db = getFirestore();

  await db.collection(COLLECTION_FCM_WAKE_HISTORY).add({
    userId,
    requestId,
    reason,
    sentAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
    fcmResult: {
      success: fcmResult.success,
      successCount: fcmResult.successCount,
      failureCount: fcmResult.failureCount
    },
    status: 'pending', // pending | success | failed | no_response
    responseReceivedAt: null,
    response: null
  });
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// CONTROLLER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * POST /api/fcm/wake-response
 * Handle device response to WAKE_SERVICE FCM
 */
exports.handleWakeResponse = async (req, res) => {
  try {
    const userId = req.user.uid;
    const {
      requestId,
      status,
      alreadyRunning,
      serviceStarted,
      serviceRunning,
      failureReason,
      message,
      timestamp,
      fcmReceivedTimestamp,
      responseTimestamp,
      deviceState,
      permissionState,
      serviceState,
      deviceInfo,
      wasQueued,
      queuedAt,
      sentAt
    } = req.body;

    console.log('═══════════════════════════════════════════════════════');
    console.log('📥 [FCM] WAKE RESPONSE RECEIVED');
    console.log(`   User: ${userId}`);
    console.log(`   Request ID: ${requestId}`);
    console.log(`   Status: ${status}`);
    console.log(`   Already Running: ${alreadyRunning}`);
    console.log(`   Service Started: ${serviceStarted}`);
    console.log(`   Service Running: ${serviceRunning}`);
    console.log(`   Failure Reason: ${failureReason}`);
    console.log(`   Message: ${message}`);
    console.log(`   Was Queued: ${wasQueued || false}`);
    if (permissionState) {
      console.log(`   Permission State:`, permissionState);
    }
    console.log('═══════════════════════════════════════════════════════');

    const db = getFirestore();

    // Update wake history record
    const historyQuery = await db.collection(COLLECTION_FCM_WAKE_HISTORY)
      .where('requestId', '==', requestId)
      .limit(1)
      .get();

    if (!historyQuery.empty) {
      const historyDoc = historyQuery.docs[0];
      await historyDoc.ref.update({
        status: status === 'SUCCESS' ? 'success' : 'failed',
        responseReceivedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
        response: {
          alreadyRunning: alreadyRunning || false,
          serviceStarted: serviceStarted || false,
          serviceRunning: serviceRunning || false,
          failureReason: failureReason || null,
          message: message || null,
          timestamp: timestamp || null,
          fcmReceivedTimestamp: fcmReceivedTimestamp || null,
          responseTimestamp: responseTimestamp || null,
          deviceState: deviceState || null,
          permissionState: permissionState || null,
          serviceState: serviceState || null,
          deviceInfo: deviceInfo || null,
          wasQueued: wasQueued || false
        }
      });
    }

    // Also store in fcmResponseLog for unified log viewing
    await db.collection('fcmResponseLog').add({
      userId,
      requestId: requestId || null,
      type: 'WAKE_RESPONSE',
      status: status || null,
      alreadyRunning: alreadyRunning || false,
      serviceStarted: serviceStarted || false,
      serviceRunning: serviceRunning || false,
      failureReason: failureReason || null,
      message: message || null,
      fcmReceivedTimestamp: fcmReceivedTimestamp || null,
      responseTimestamp: responseTimestamp || null,
      deviceState: deviceState || null,
      permissionState: permissionState || null,
      serviceState: serviceState || null,
      deviceInfo: deviceInfo || null,
      wasQueued: wasQueued || false,
      queuedAt: queuedAt || null,
      sentAt: sentAt || responseTimestamp || null,
      receivedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Update user's wake state — record the response but do NOT stop recovery.
    // Recovery is managed exclusively by the presence monitor (online/offline detection).
    // A wake response means the device received the FCM (has network), but recovery
    // should only cancel when presence flips to online — not on every FCM response.
    const userState = await getUserWakeState(userId);
    userState.fcmWakeState.lastResponseTime = Date.now();
    userState.fcmWakeState.lastResponseStatus = status || 'unknown';

    await updateUserWakeState(userId, userState.fcmWakeState);

    // Also store the last device state and FCM response for easy access
    await db.collection(COLLECTION_LOCATION_TRACKING).doc(userId).set({
      lastDeviceState: deviceState || null,
      lastPermissionState: permissionState || null,
      lastFcmResponse: {
        type: 'WAKE_RESPONSE',
        requestId: requestId || null,
        status: status || null,
        serviceRunning: serviceRunning || false,
        failureReason: failureReason || null,
        message: message || null,
        timestamp: Date.now(),
        deviceInfo: deviceInfo || null,
        permissionState: permissionState || null
      },
      lastDeviceInfo: deviceInfo || null,
      lastResponseTime: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    // Determine if this is a real failure or just an informational non-critical issue
    const isServiceActuallyRunning = serviceRunning === true || alreadyRunning === true;
    const isNonCriticalIssue = isServiceActuallyRunning && failureReason && [
      'LOCATION_SERVICES_DISABLED',
    ].includes(failureReason);

    // Add notification for admin visibility
    let notificationMessage;
    let notificationType;

    if (status === 'SUCCESS') {
      notificationMessage = `Device responded: ${alreadyRunning ? 'Service already running' : 'Service started successfully'}`;
      notificationType = 'FCM_WAKE_SUCCESS';
    } else if (isNonCriticalIssue) {
      notificationMessage = `Device responded: Service is running but ${failureReason === 'LOCATION_SERVICES_DISABLED' ? 'GPS is off (waiting for user to enable)' : failureReason}`;
      notificationType = 'FCM_WAKE_SUCCESS'; // Treat as success since service IS running
    } else {
      notificationMessage = `Device responded with error: ${failureReason || message}`;
      notificationType = 'FCM_WAKE_FAILED';
    }

    await db.collection('deviceNotifications').add({
      userId,
      type: notificationType,
      message: notificationMessage,
      timestamp: Date.now(),
      requestId: requestId || null,
      status: status || null,
      serviceRunning: serviceRunning || false,
      failureReason: failureReason || null,
      deviceInfo: deviceInfo || null,
      permissionState: permissionState || null,
      read: false,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    console.log(`📥 [FCM] Wake response recorded for ${userId} (status: ${status}, service running: ${serviceRunning})`);

    res.json({
      success: true,
      message: 'Wake response recorded',
      data: {
        requestId,
        wakeCycleStopped: false // Recovery is controlled by presence monitor, not wake responses
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error handling wake response:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to process wake response',
      error: error.message
    });
  }
};

/**
 * POST /api/fcm/settings-response
 * Handle device response to SETTINGS_UPDATED FCM
 */
exports.handleSettingsResponse = async (req, res) => {
  try {
    const userId = req.user.uid;
    const {
      requestId,
      type,
      status,
      serviceRunning,
      failureReason,
      message,
      fcmReceivedTimestamp,
      responseTimestamp,
      deviceState,
      permissionState,
      appliedSettings,
      deviceInfo,
      wasQueued,
      queuedAt,
      sentAt
    } = req.body;

    console.log('═══════════════════════════════════════════════════════');
    console.log('📥 [FCM] SETTINGS RESPONSE RECEIVED');
    console.log(`   User: ${userId}`);
    console.log(`   Request ID: ${requestId}`);
    console.log(`   Type: ${type}`);
    console.log(`   Status: ${status}`);
    console.log(`   Service Running: ${serviceRunning}`);
    console.log(`   Failure Reason: ${failureReason}`);
    console.log(`   Message: ${message}`);
    console.log(`   Was Queued: ${wasQueued || false}`);
    console.log(`   Applied Settings:`, appliedSettings);
    console.log('═══════════════════════════════════════════════════════');

    const db = getFirestore();

    // Store the response in fcmResponseLog collection
    await db.collection('fcmResponseLog').add({
      userId,
      requestId: requestId || null,
      type: type || 'SETTINGS_RESPONSE',
      status: status || null,
      serviceRunning: serviceRunning || false,
      failureReason: failureReason || null,
      message: message || null,
      fcmReceivedTimestamp: fcmReceivedTimestamp || null,
      responseTimestamp: responseTimestamp || null,
      deviceState: deviceState || null,
      permissionState: permissionState || null,
      appliedSettings: appliedSettings || null,
      deviceInfo: deviceInfo || null,
      wasQueued: wasQueued || false,
      queuedAt: queuedAt || null,
      sentAt: sentAt || responseTimestamp || null,
      receivedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Update user's location tracking document with latest state
    await db.collection(COLLECTION_LOCATION_TRACKING).doc(userId).set({
      lastDeviceState: deviceState || null,
      lastPermissionState: permissionState || null,
      lastFcmResponse: {
        type: type || 'SETTINGS_RESPONSE',
        requestId: requestId || null,
        status: status || null,
        serviceRunning: serviceRunning || false,
        failureReason: failureReason || null,
        message: message || null,
        appliedSettings: appliedSettings || null,
        timestamp: Date.now(),
        deviceInfo: deviceInfo || null
      },
      lastDeviceInfo: deviceInfo || null,
      lastResponseTime: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    // Add notification for admin visibility
    const notificationMessage = status === 'SUCCESS'
      ? `Settings applied successfully${appliedSettings?.interval ? ` (interval: ${appliedSettings.interval}ms)` : ''}`
      : `Settings failed: ${failureReason || message}`;

    await db.collection('deviceNotifications').add({
      userId,
      type: 'SETTINGS_RESPONSE',
      message: notificationMessage,
      timestamp: Date.now(),
      requestId: requestId || null,
      status: status || null,
      serviceRunning: serviceRunning || false,
      failureReason: failureReason || null,
      appliedSettings: appliedSettings || null,
      deviceInfo: deviceInfo || null,
      permissionState: permissionState || null,
      read: false,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Record settings response time (do NOT stop recovery — presence monitor handles that)
    const userState = await getUserWakeState(userId);
    userState.fcmWakeState.lastResponseTime = Date.now();
    userState.fcmWakeState.lastResponseStatus = status || 'unknown';
    await updateUserWakeState(userId, userState.fcmWakeState);

    console.log(`✅ [FCM] Settings response recorded for user ${userId}`);

    res.json({
      success: true,
      message: 'Settings response recorded',
      data: {
        requestId,
        status,
        serviceRunning
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error handling settings response:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to process settings response',
      error: error.message
    });
  }
};

/**
 * POST /api/fcm/admin/wake-user
 * Admin manually triggers wake FCM for a user
 */
exports.adminWakeUser = async (req, res) => {
  try {
    const { userId, reason = 'admin_manual_wake', startCycle = false } = req.body;
    const db = getFirestore();

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'userId is required'
      });
    }

    console.log('═══════════════════════════════════════════════════════');
    console.log('🚨 [FCM] ADMIN WAKE USER TRIGGERED');
    console.log(`   Target User: ${userId}`);
    console.log(`   Reason: ${reason}`);
    console.log(`   Start Cycle: ${startCycle}`);
    console.log(`   Triggered By: ${req.user.uid}`);
    console.log('═══════════════════════════════════════════════════════');

    // Get current wake state
    const userState = await getUserWakeState(userId);

    // Check if wake cycle already active (only if we're trying to start a cycle)
    if (startCycle && userState.fcmWakeState.isActive) {
      const timeSinceStart = Date.now() - (userState.fcmWakeState.startedAt || 0);
      const attemptsRemaining = MAX_WAKE_ATTEMPTS - userState.fcmWakeState.attemptCount;

      console.log(`⚠️ [FCM] Wake cycle already active for user ${userId}`);

      return res.json({
        success: true,
        message: 'Wake cycle already active',
        data: {
          userId,
          alreadyActive: true,
          startedAt: userState.fcmWakeState.startedAt,
          attemptCount: userState.fcmWakeState.attemptCount,
          attemptsRemaining,
          timeSinceStartMs: timeSinceStart,
          lastAttemptTime: userState.fcmWakeState.lastAttemptTime,
          status: userState.fcmWakeState.status
        }
      });
    }

    // Generate request ID
    const requestId = generateRequestId(userId, reason);

    // Send FCM
    const fcmResult = await sendWakeFCM(userId, reason, requestId);

    if (!fcmResult.success) {
      console.log(`❌ [FCM] Failed to send wake FCM to user ${userId}`);
      return res.status(500).json({
        success: false,
        message: 'Failed to send wake FCM',
        reason: fcmResult.reason || 'SEND_FAILED'
      });
    }

    const now = Date.now();

    // Only update wake state and start cycle if startCycle is true
    if (startCycle) {
      // Update wake state - START cycle
      userState.fcmWakeState = {
        isActive: true,
        startedAt: now,
        attemptCount: 1,
        lastAttemptTime: now,
        lastResponseTime: null,
        status: 'sending',
        lastRequestId: requestId,
        triggeredBy: req.user.uid,
        reason: reason
      };

      await updateUserWakeState(userId, userState.fcmWakeState);

      console.log(`✅ [FCM] Wake cycle started for user ${userId}`);
    } else {
      // Single FCM mode - do NOT start a cycle
      // Only update lastRequestId and lastAttemptTime without activating cycle
      userState.fcmWakeState.lastRequestId = requestId;
      userState.fcmWakeState.lastAttemptTime = now;
      // Keep isActive as false (no cycle)

      await updateUserWakeState(userId, userState.fcmWakeState);

      console.log(`✅ [FCM] Single wake FCM sent to user ${userId} (no cycle started)`);
    }

    // Record in history
    await recordWakeAttempt(userId, requestId, reason, fcmResult);

    // Store notification for admin visibility
    await db.collection('deviceNotifications').add({
      userId,
      type: startCycle ? 'FCM_CYCLE_STARTED' : 'FCM_SENT',
      message: startCycle
        ? `Wake cycle started (FCM every 2 hours, max ${MAX_WAKE_ATTEMPTS} attempts)`
        : `Single wake FCM sent to device`,
      timestamp: now,
      requestId,
      reason,
      fcmSentCount: fcmResult.successCount,
      triggeredBy: req.user.uid,
      cycleMode: startCycle,
      read: false,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    res.json({
      success: true,
      message: startCycle ? 'Wake cycle started successfully' : 'Single wake FCM sent successfully',
      data: {
        userId,
        requestId,
        fcmSentCount: fcmResult.successCount,
        wakeCycleStarted: startCycle,
        maxAttempts: startCycle ? MAX_WAKE_ATTEMPTS : 1,
        intervalMs: startCycle ? WAKE_INTERVAL_MS : null,
        singleMode: !startCycle
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error in admin wake user:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to trigger wake',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/admin/status/:userId
 * Get FCM wake status for a user
 */
exports.getUserWakeStatus = async (req, res) => {
  try {
    const { userId } = req.params;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'userId is required'
      });
    }

    const userState = await getUserWakeState(userId);
    const db = getFirestore();

    // Get last location upload time from locations collection
    const locationsQuery = await db.collection('locations')
      .where('userId', '==', userId)
      .orderBy('serverTimestamp', 'desc')
      .limit(1)
      .get();

    let lastLocationTime = null;
    let lastDeviceInfoFromLocation = null;
    let lastLocation = null;
    if (!locationsQuery.empty) {
      const lastLoc = locationsQuery.docs[0].data();
      lastLocationTime = lastLoc.serverTimestamp?.toDate?.() || lastLoc.serverTimestamp;
      // Extract device info from last location if available
      lastDeviceInfoFromLocation = {
        batteryLevel: lastLoc.batteryLevel,
        networkType: lastLoc.networkType || (lastLoc.provider === 'network' ? 'Network' : 'GPS'),
        accuracy: lastLoc.accuracy,
        provider: lastLoc.provider
      };
      // Extract coordinates for map view
      lastLocation = {
        latitude: lastLoc.latitude,
        longitude: lastLoc.longitude,
        accuracy: lastLoc.accuracy,
        timestamp: lastLocationTime
      };
    }

    // Get user profile for FCM token info
    let userProfile = null;
    let lastFcmTokenTime = null;
    try {
      const profileDoc = await db.collection('users').doc(userId).get();
      if (profileDoc.exists) {
        userProfile = profileDoc.data();
        lastFcmTokenTime = userProfile.fcmTokenUpdatedAt?.toDate?.() || userProfile.fcmTokenUpdatedAt;
      }
    } catch (e) {
      console.log('Could not fetch user profile:', e.message);
    }

    // Calculate silence duration
    const now = Date.now();
    const silenceDuration = lastLocationTime ? now - new Date(lastLocationTime).getTime() : null;

    // Determine if user is active (received data within last hour)
    const isActiveTracking = silenceDuration !== null && silenceDuration < (60 * 60 * 1000); // 1 hour

    // Get FCM stats from wake history (uses index: userId + sentAt)
    const historyQuery = await db.collection(COLLECTION_FCM_WAKE_HISTORY)
      .where('userId', '==', userId)
      .orderBy('sentAt', 'desc')
      .limit(50)
      .get();

    let fcmStats = {
      totalSent: 0,
      totalResponded: 0,
      successRate: 0
    };

    const wakeHistory = historyQuery.docs.map(d => {
      const data = d.data();
      return {
        ...data,
        sentAt: data.sentAt?.toDate?.() || data.sentAt
      };
    });

    if (wakeHistory.length > 0) {
      fcmStats.totalSent = wakeHistory.length;
      fcmStats.totalResponded = wakeHistory.filter(d =>
        d.status === 'success' || d.response?.status === 'SUCCESS'
      ).length;
      fcmStats.successRate = fcmStats.totalSent > 0
        ? Math.round((fcmStats.totalResponded / fcmStats.totalSent) * 100)
        : 0;
    }

    // Get recent FCM response logs (uses index: userId + receivedAt)
    let responseLogs = [];
    try {
      const responseLogsQuery = await db.collection('fcmResponseLog')
        .where('userId', '==', userId)
        .orderBy('receivedAt', 'desc')
        .limit(20)
        .get();

      responseLogs = responseLogsQuery.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          ...data,
          receivedAt: data.receivedAt?.toDate?.() || data.receivedAt,
          sentAt: data.sentAt?.toDate?.() || data.sentAt,
          timestamp: data.timestamp
        };
      });

      // Re-calculate success rate including response logs
      if (responseLogs.length > 0) {
        const successfulResponses = responseLogs.filter(r =>
          r.status === 'SUCCESS' || r.serviceRunning === true
        ).length;
        if (fcmStats.totalResponded < successfulResponses) {
          fcmStats.totalResponded = successfulResponses;
          fcmStats.successRate = fcmStats.totalSent > 0
            ? Math.round((fcmStats.totalResponded / fcmStats.totalSent) * 100)
            : 0;
        }
      }
    } catch (e) {
      console.log('Could not fetch response logs:', e.message);
    }

    // Get device notifications for admin page (uses index: userId + createdAt)
    let notifications = [];
    try {
      const notificationsQuery = await db.collection('deviceNotifications')
        .where('userId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(30)
        .get();

      notifications = notificationsQuery.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          ...data,
          createdAt: data.createdAt?.toDate?.() || data.createdAt,
          timestamp: data.timestamp
        };
      });
    } catch (e) {
      console.log('Could not fetch notifications:', e.message);
    }

    // Get device status logs for more detail (uses index: userId + receivedAt)
    let statusLogs = [];
    try {
      const statusLogsQuery = await db.collection('deviceStatusLog')
        .where('userId', '==', userId)
        .orderBy('receivedAt', 'desc')
        .limit(20)
        .get();

      statusLogs = statusLogsQuery.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          ...data,
          receivedAt: data.receivedAt?.toDate?.() || data.receivedAt
        };
      });
    } catch (e) {
      console.log('Could not fetch status logs:', e.message);
    }

    // Get user's last FCM response from settings doc
    let lastFcmResponse = null;
    let lastDeviceState = null;
    let lastDeviceInfo = null;
    let lastPermissionState = null;
    let lastServiceState = null;
    let lastStatusUpdate = null;
    try {
      const settingsDoc = await db.collection(COLLECTION_LOCATION_TRACKING).doc(userId).get();
      if (settingsDoc.exists) {
        const settingsData = settingsDoc.data();
        lastFcmResponse = settingsData.lastFcmResponse;
        lastDeviceState = settingsData.lastDeviceState;
        lastDeviceInfo = settingsData.lastDeviceInfo;
        lastPermissionState = settingsData.lastPermissionState;
        lastServiceState = settingsData.lastServiceState;
        lastStatusUpdate = settingsData.lastStatusUpdate?.toDate?.() || settingsData.lastStatusUpdate;
      }
    } catch (e) {
      console.log('Could not fetch settings doc:', e.message);
    }

    // Calculate last response time - from multiple sources
    let lastResponseTime = null;
    if (responseLogs.length > 0) {
      lastResponseTime = responseLogs[0].receivedAt;
    }
    if (lastStatusUpdate && (!lastResponseTime || new Date(lastStatusUpdate) > new Date(lastResponseTime))) {
      lastResponseTime = lastStatusUpdate;
    }

    res.json({
      success: true,
      data: {
        userId,
        lastLocationUpload: lastLocationTime,
        lastLocation: lastLocation,
        silenceDurationMs: silenceDuration,
        silenceDurationHours: silenceDuration ? (silenceDuration / (60 * 60 * 1000)).toFixed(2) : null,
        fcmWakeState: userState.fcmWakeState,
        isActive: isActiveTracking,
        isUnreachable: userState.fcmWakeState.status === 'unreachable',
        deviceInfo: lastDeviceInfoFromLocation,
        lastDeviceInfo: lastDeviceInfo,
        lastPermissionState: lastPermissionState,
        lastServiceState: lastServiceState,
        lastFcmTokenTime: lastFcmTokenTime,
        lastResponseTime: lastResponseTime,
        lastStatusUpdate: lastStatusUpdate,
        fcmStats: fcmStats,
        responseLogs: responseLogs,
        notifications: notifications,
        statusLogs: statusLogs,
        lastFcmResponse: lastFcmResponse,
        lastDeviceState: lastDeviceState,
        thresholds: {
          silenceThresholdMs: SILENCE_THRESHOLD_MS,
          silenceThresholdHours: SILENCE_THRESHOLD_MS / (60 * 60 * 1000),
          wakeIntervalMs: WAKE_INTERVAL_MS,
          wakeIntervalHours: WAKE_INTERVAL_MS / (60 * 60 * 1000),
          maxAttempts: MAX_WAKE_ATTEMPTS
        }
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error getting user wake status:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get wake status',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/admin/unreachable
 * Get list of unreachable users
 */
exports.getUnreachableUsers = async (req, res) => {
  try {
    const db = getFirestore();

    const unreachableQuery = await db.collection(COLLECTION_LOCATION_TRACKING)
      .where('fcmWakeState.status', '==', 'unreachable')
      .get();

    const users = [];
    for (const doc of unreachableQuery.docs) {
      const data = doc.data();
      users.push({
        userId: data.userId,
        fcmWakeState: data.fcmWakeState,
        markedUnreachableAt: data.fcmWakeState?.lastAttemptTime
      });
    }

    res.json({
      success: true,
      data: {
        count: users.length,
        users
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error getting unreachable users:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get unreachable users',
      error: error.message
    });
  }
};

/**
 * POST /api/fcm/admin/cancel-wake
 * Cancel active wake cycle for a user
 */
exports.cancelWakeCycle = async (req, res) => {
  try {
    const { userId } = req.body;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'userId is required'
      });
    }

    console.log(`🛑 [FCM] Cancelling wake cycle for user: ${userId}`);

    const userState = await getUserWakeState(userId);

    if (!userState.fcmWakeState.isActive) {
      return res.json({
        success: true,
        message: 'No active wake cycle to cancel',
        data: { userId, wasActive: false }
      });
    }

    // Cancel the wake cycle
    userState.fcmWakeState.isActive = false;
    userState.fcmWakeState.status = 'cancelled';
    userState.fcmWakeState.cancelledAt = Date.now();
    userState.fcmWakeState.cancelledBy = req.user.uid;

    await updateUserWakeState(userId, userState.fcmWakeState);

    res.json({
      success: true,
      message: 'Wake cycle cancelled',
      data: {
        userId,
        wasActive: true,
        attemptsMade: userState.fcmWakeState.attemptCount
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error cancelling wake cycle:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to cancel wake cycle',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/admin/wake-history/:userId
 * Get FCM wake history for a user
 */
exports.getWakeHistory = async (req, res) => {
  try {
    const { userId } = req.params;
    const { limit = 20 } = req.query;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'userId is required'
      });
    }

    const db = getFirestore();

    const historyQuery = await db.collection(COLLECTION_FCM_WAKE_HISTORY)
      .where('userId', '==', userId)
      .orderBy('sentAt', 'desc')
      .limit(parseInt(limit))
      .get();

    const history = historyQuery.docs.map(doc => ({
      id: doc.id,
      ...doc.data(),
      sentAt: doc.data().sentAt?.toDate?.() || doc.data().sentAt,
      responseReceivedAt: doc.data().responseReceivedAt?.toDate?.() || doc.data().responseReceivedAt
    }));

    res.json({
      success: true,
      data: {
        userId,
        count: history.length,
        history
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error getting wake history:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get wake history',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/health
 * Health check for FCM system
 */
exports.healthCheck = async (req, res) => {
  res.json({
    success: true,
    status: 'healthy',
    timestamp: new Date().toISOString(),
    config: {
      silenceThresholdHours: SILENCE_THRESHOLD_MS / (60 * 60 * 1000),
      wakeIntervalHours: WAKE_INTERVAL_MS / (60 * 60 * 1000),
      maxAttempts: MAX_WAKE_ATTEMPTS,
      unreachableAfterHours: UNREACHABLE_THRESHOLD_MS / (60 * 60 * 1000)
    }
  });
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// HEARTBEAT MONITORING SYSTEM (Called by cron job)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Check all users for silence and send wake FCMs
 * Called every 2 hours by cron job in cronJobs.js
 */
exports.checkSilentUsersAndWake = async () => {
  console.log('═══════════════════════════════════════════════════════');
  console.log('🔍 [FCM] CHECKING SILENT USERS FOR WAKE');
  console.log(`   Time: ${new Date().toISOString()}`);
  console.log('═══════════════════════════════════════════════════════');

  const db = getFirestore();
  const now = Date.now();
  const silenceThreshold = now - SILENCE_THRESHOLD_MS;

  try {
    // Get all users with tracking enabled
    const usersQuery = await db.collection(COLLECTION_USERS).get();

    let checkedCount = 0;
    let silentCount = 0;
    let wakeSentCount = 0;
    let alreadyActiveCount = 0;
    let unreachableCount = 0;

    for (const userDoc of usersQuery.docs) {
      const userId = userDoc.id;
      checkedCount++;

      // Get user's location tracking state
      const userState = await getUserWakeState(userId);

      // Skip if user already responded recently (within last 2 hours)
      if (userState.fcmWakeState.lastResponseTime &&
          now - userState.fcmWakeState.lastResponseTime < WAKE_INTERVAL_MS) {
        continue;
      }

      // Get last location time
      const locationsQuery = await db.collection('locations')
        .where('userId', '==', userId)
        .orderBy('serverTimestamp', 'desc')
        .limit(1)
        .get();

      let lastLocationTime = null;
      if (!locationsQuery.empty) {
        const lastLoc = locationsQuery.docs[0].data();
        const timestamp = lastLoc.serverTimestamp;
        lastLocationTime = timestamp?.toDate?.()?.getTime() ||
                          (typeof timestamp === 'number' ? timestamp : new Date(timestamp).getTime());
      }

      // Check if silent (no data for 3+ hours)
      if (!lastLocationTime || lastLocationTime < silenceThreshold) {
        silentCount++;

        // Check if wake cycle already active
        if (userState.fcmWakeState.isActive) {
          // Check if should send next attempt
          const timeSinceLastAttempt = now - (userState.fcmWakeState.lastAttemptTime || 0);

          if (timeSinceLastAttempt >= WAKE_INTERVAL_MS) {
            // Check if max attempts reached
            if (userState.fcmWakeState.attemptCount >= MAX_WAKE_ATTEMPTS) {
              // Mark as unreachable
              userState.fcmWakeState.status = 'unreachable';
              userState.fcmWakeState.isActive = false;
              await updateUserWakeState(userId, userState.fcmWakeState);
              unreachableCount++;
              console.log(`⚠️ [FCM] User ${userId} marked as UNREACHABLE after ${MAX_WAKE_ATTEMPTS} attempts`);
              continue;
            }

            // Send next FCM attempt
            const requestId = generateRequestId(userId, 'auto_silence_check');
            const fcmResult = await sendWakeFCM(userId, 'auto_silence_check', requestId);

            if (fcmResult.success) {
              userState.fcmWakeState.attemptCount++;
              userState.fcmWakeState.lastAttemptTime = now;
              userState.fcmWakeState.lastRequestId = requestId;
              await updateUserWakeState(userId, userState.fcmWakeState);
              await recordWakeAttempt(userId, requestId, 'auto_silence_check', fcmResult);
              wakeSentCount++;
              console.log(`📤 [FCM] Wake FCM #${userState.fcmWakeState.attemptCount} sent to user ${userId}`);
            }
          } else {
            alreadyActiveCount++;
          }
        } else {
          // Start new wake cycle
          const requestId = generateRequestId(userId, 'auto_silence_check');
          const fcmResult = await sendWakeFCM(userId, 'auto_silence_check', requestId);

          if (fcmResult.success) {
            userState.fcmWakeState = {
              isActive: true,
              startedAt: now,
              attemptCount: 1,
              lastAttemptTime: now,
              lastResponseTime: null,
              status: 'sending',
              lastRequestId: requestId,
              triggeredBy: 'system',
              reason: 'auto_silence_check'
            };
            await updateUserWakeState(userId, userState.fcmWakeState);
            await recordWakeAttempt(userId, requestId, 'auto_silence_check', fcmResult);
            wakeSentCount++;
            console.log(`📤 [FCM] NEW wake cycle started for silent user ${userId}`);
          }
        }
      }
    }

    console.log('═══════════════════════════════════════════════════════');
    console.log('📊 [FCM] SILENT USER CHECK COMPLETE');
    console.log(`   Users checked: ${checkedCount}`);
    console.log(`   Silent users: ${silentCount}`);
    console.log(`   Wake FCMs sent: ${wakeSentCount}`);
    console.log(`   Already active: ${alreadyActiveCount}`);
    console.log(`   Marked unreachable: ${unreachableCount}`);
    console.log('═══════════════════════════════════════════════════════');

    return {
      success: true,
      checkedCount,
      silentCount,
      wakeSentCount,
      alreadyActiveCount,
      unreachableCount
    };

  } catch (error) {
    console.error('❌ [FCM] Error checking silent users:', error);
    return { success: false, error: error.message };
  }
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// DEVICE STATUS REPORTING - REAL-TIME STATUS UPLOADS FROM DEVICE
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * POST /api/fcm/device-status
 * Receive real-time device status updates
 */
/**
 * ⚠️ DEPRECATED - NO LONGER USED
 *
 * This endpoint was previously used for FCM-based status updates.
 * Now we use Firebase Realtime Listener (deviceStatus collection) instead.
 * The Android app reports status directly to Firestore via DeviceStatusReporter,
 * and the admin page listens via Firebase SDK (not HTTP).
 *
 * Keeping this endpoint for backwards compatibility but it does nothing.
 */
exports.handleDeviceStatus = async (req, res) => {
  try {
    console.log('⚠️ [FCM] handleDeviceStatus called but DEPRECATED - use Firebase Realtime Listener instead');

    res.json({
      success: true,
      message: 'Status endpoint deprecated - use Firebase Realtime Listener'
    });

  } catch (error) {
    console.error('❌ [FCM] Error in deprecated handleDeviceStatus:', error);
    res.status(500).json({
      success: false,
      message: 'Deprecated endpoint',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/admin/notifications/:userId
 * Get device notifications for admin FCM page
 *
 * This ONLY reads from deviceNotifications collection which is written directly
 * by the Android app's DeviceStatusReporter to Firestore.
 * The admin page also listens to this collection via Firebase Realtime Listener.
 */
exports.getDeviceNotifications = async (req, res) => {
  try {
    const { userId } = req.params;
    const { limit = 50 } = req.query;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'userId is required'
      });
    }

    const db = getFirestore();

    // Get notifications from deviceNotifications collection (written by DeviceStatusReporter)
    const notificationsQuery = await db.collection('deviceNotifications')
      .where('userId', '==', userId)
      .orderBy('createdAt', 'desc')
      .limit(parseInt(limit))
      .get();

    const notifications = notificationsQuery.docs.map(doc => ({
      id: doc.id,
      ...doc.data(),
      createdAt: doc.data().createdAt?.toDate?.() || doc.data().createdAt
    }));

    res.json({
      success: true,
      data: {
        notifications
      }
    });

  } catch (error) {
    console.error('❌ [FCM] Error getting notifications:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get notifications',
      error: error.message
    });
  }
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// RECOVERY SYSTEM - ESCALATING FCM WAKE (Called by Admin Page)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const recoveryScheduler = require('../services/recoveryScheduler');

/**
 * POST /api/fcm/trigger-recovery/:uid
 * Admin page calls this when a device is detected as offline (7+ minutes no data).
 * Starts the escalating FCM recovery schedule.
 */
exports.triggerRecovery = async (req, res) => {
  try {
    const { uid } = req.params;
    const triggeredBy = req.user?.uid || 'admin_page';

    if (!uid) {
      return res.status(400).json({
        success: false,
        message: 'User ID (uid) is required'
      });
    }

    console.log('═══════════════════════════════════════════════════════');
    console.log('🚨 [Recovery] TRIGGER RECOVERY REQUESTED');
    console.log(`   Target User: ${uid}`);
    console.log(`   Triggered By: ${triggeredBy}`);
    console.log('═══════════════════════════════════════════════════════');

    const result = await recoveryScheduler.startRecovery(uid, triggeredBy);

    res.json({
      success: result.success,
      message: result.message,
      data: {
        userId: uid,
        alreadyActive: result.alreadyActive || false,
        state: result.state || null
      }
    });

  } catch (error) {
    console.error('❌ [Recovery] Error triggering recovery:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to trigger recovery',
      error: error.message
    });
  }
};

/**
 * POST /api/fcm/cancel-recovery/:uid
 * Admin page calls this when it receives new data from the device (device back online).
 * Cancels all pending recovery FCMs for the device.
 */
exports.cancelRecovery = async (req, res) => {
  try {
    const { uid } = req.params;

    if (!uid) {
      return res.status(400).json({
        success: false,
        message: 'User ID (uid) is required'
      });
    }

    console.log(`✅ [Recovery] Cancel recovery requested for: ${uid}`);

    const result = await recoveryScheduler.cancelRecovery(uid);

    res.json({
      success: result.success,
      message: result.message,
      data: {
        userId: uid,
        wasActive: result.wasActive || false,
        totalFcmsSent: result.totalFcmsSent || 0
      }
    });

  } catch (error) {
    console.error('❌ [Recovery] Error cancelling recovery:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to cancel recovery',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/recovery-status/:uid
 * Get recovery status for a device
 */
exports.getRecoveryStatus = async (req, res) => {
  try {
    const { uid } = req.params;

    if (!uid) {
      return res.status(400).json({
        success: false,
        message: 'User ID (uid) is required'
      });
    }

    const result = await recoveryScheduler.getRecoveryStatus(uid);

    res.json({
      success: true,
      data: result
    });

  } catch (error) {
    console.error('❌ [Recovery] Error getting recovery status:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get recovery status',
      error: error.message
    });
  }
};

/**
 * GET /api/fcm/active-recoveries
 * Get all currently active recoveries (for admin monitoring)
 */
exports.getActiveRecoveries = async (req, res) => {
  try {
    const activeRecoveries = await recoveryScheduler.getActiveRecoveries();

    res.json({
      success: true,
      data: {
        count: activeRecoveries.length,
        recoveries: activeRecoveries
      }
    });

  } catch (error) {
    console.error('❌ [Recovery] Error getting active recoveries:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get active recoveries',
      error: error.message
    });
  }
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Calculate FCM success rate for a user
 */
const calculateFcmSuccessRate = (wakeHistory, responseLogs) => {
  // Count total FCMs sent
  const totalSent = wakeHistory?.length || 0;
  if (totalSent === 0) return { sent: 0, responded: 0, successRate: 0 };

  // Count successful responses
  const responded = wakeHistory?.filter(h =>
    h.status === 'success' || h.response?.status === 'SUCCESS'
  ).length || 0;

  // Also check fcmResponseLog
  const responseCount = responseLogs?.filter(r =>
    r.status === 'SUCCESS' || r.type === 'WAKE_RESPONSE'
  ).length || 0;

  const totalResponded = Math.max(responded, responseCount);
  const successRate = totalSent > 0 ? Math.round((totalResponded / totalSent) * 100) : 0;

  return {
    sent: totalSent,
    responded: totalResponded,
    successRate
  };
};

