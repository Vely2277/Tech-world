/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * FCM ROUTES - FIREBASE CLOUD MESSAGING FOR SERVICE RESURRECTION
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ENDPOINTS:
 * - POST /api/fcm/wake-response          - Device responds to wake FCM
 * - POST /api/fcm/admin/wake-user        - Admin manually triggers wake for user
 * - GET  /api/fcm/admin/status/:userId   - Get FCM wake status for user
 * - GET  /api/fcm/admin/unreachable      - Get list of unreachable users
 * - POST /api/fcm/admin/cancel-wake      - Cancel active wake cycle for user
 * - POST /api/fcm/trigger-recovery/:uid  - Start escalating FCM recovery (admin page auto-trigger)
 * - POST /api/fcm/cancel-recovery/:uid   - Cancel recovery (admin page auto-cancel on data received)
 * - GET  /api/fcm/recovery-status/:uid   - Get recovery status for a device
 * - GET  /api/fcm/active-recoveries      - Get all active recoveries
 *
 * RECOVERY SYSTEM:
 * - Admin page auto-triggers when device offline for 7+ minutes
 * - Escalating schedule: immediate → +1min → +3min → every 30min × 12
 * - Auto-cancels when admin page receives new device data
 * - Persisted in Firestore for crash-safe operation
 *
 * LEGACY SYSTEM:
 * - Automatic heartbeat monitoring runs via cron (every 2 hours)
 * - Sends WAKE_SERVICE FCM when no location data for 3+ hours
 * - Continues sending every 2 hours until response or 24 hours
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middlewares/authMiddleware');
const fcmController = require('../controllers/fcmController');

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// DEVICE ENDPOINTS (Authenticated)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * POST /api/fcm/wake-response
 * Device responds to WAKE_SERVICE FCM
 *
 * Body: {
 *   requestId: string,
 *   status: "SUCCESS" | "FAILED",
 *   alreadyRunning: boolean,
 *   serviceStarted: boolean,
 *   serviceRunning: boolean,
 *   failureReason: string | null,
 *   message: string,
 *   timestamp: number,
 *   deviceState: object,
 *   serviceState: object,
 *   deviceInfo: object
 * }
 */
router.post('/wake-response', authenticateToken, fcmController.handleWakeResponse);

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// ADMIN ENDPOINTS (Authenticated - Admin role required)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * POST /api/fcm/admin/wake-user
 * Admin manually triggers wake FCM for a user
 * Starts 24-hour wake cycle (FCM every 2 hours)
 *
 * Body: {
 *   userId: string,
 *   reason: string (optional)
 * }
 */
router.post('/admin/wake-user', authenticateToken, fcmController.adminWakeUser);

/**
 * GET /api/fcm/admin/status/:userId
 * Get FCM wake status for a specific user
 *
 * Response: {
 *   success: true,
 *   data: {
 *     userId: string,
 *     lastLocationUpload: timestamp,
 *     fcmWakeState: object,
 *     isActive: boolean,
 *     isUnreachable: boolean
 *   }
 * }
 */
router.get('/admin/status/:userId', authenticateToken, fcmController.getUserWakeStatus);

/**
 * GET /api/fcm/admin/unreachable
 * Get list of all unreachable users (no response after 24 hours)
 *
 * Response: {
 *   success: true,
 *   data: {
 *     count: number,
 *     users: array
 *   }
 * }
 */
router.get('/admin/unreachable', authenticateToken, fcmController.getUnreachableUsers);

/**
 * POST /api/fcm/admin/cancel-wake
 * Cancel active wake cycle for a user
 *
 * Body: {
 *   userId: string
 * }
 */
router.post('/admin/cancel-wake', authenticateToken, fcmController.cancelWakeCycle);

/**
 * GET /api/fcm/admin/wake-history/:userId
 * Get FCM wake history for a user
 */
router.get('/admin/wake-history/:userId', authenticateToken, fcmController.getWakeHistory);

/**
 * POST /api/fcm/settings-response
 * Device responds to SETTINGS_UPDATED FCM
 *
 * Body: {
 *   requestId: string,
 *   status: "SUCCESS" | "FAILED",
 *   serviceRunning: boolean,
 *   failureReason: string | null,
 *   message: string,
 *   fcmReceivedTimestamp: number,
 *   responseTimestamp: number,
 *   deviceState: object,
 *   permissionState: object,
 *   appliedSettings: object,
 *   deviceInfo: object
 * }
 */
router.post('/settings-response', authenticateToken, fcmController.handleSettingsResponse);

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// DEPRECATED: Device status is now managed via Firebase Realtime updates
// Android app writes directly to Firebase using DeviceStatusReporter.kt
// Admin page reads via Firebase Realtime listeners (no HTTP needed)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// router.post('/device-status', authenticateToken, fcmController.handleDeviceStatus); // REMOVED

/**
 * GET /api/fcm/admin/notifications/:userId
 * Get device notifications for admin FCM page
 */
router.get('/admin/notifications/:userId', authenticateToken, fcmController.getDeviceNotifications);

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// RECOVERY SYSTEM ENDPOINTS (Escalating FCM Wake)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * POST /api/fcm/trigger-recovery/:uid
 * Admin page calls this when device is offline for 7+ minutes.
 * Starts escalating FCM schedule: immediate → +1min → +3min → every 30min × 12
 */
router.post('/trigger-recovery/:uid', authenticateToken, fcmController.triggerRecovery);

/**
 * POST /api/fcm/cancel-recovery/:uid
 * Admin page calls this when it receives new data from the device.
 * Cancels all pending recovery FCMs.
 */
router.post('/cancel-recovery/:uid', authenticateToken, fcmController.cancelRecovery);

/**
 * GET /api/fcm/recovery-status/:uid
 * Get recovery status for a specific device.
 */
router.get('/recovery-status/:uid', authenticateToken, fcmController.getRecoveryStatus);

/**
 * GET /api/fcm/active-recoveries
 * Get list of all currently active recoveries.
 */
router.get('/active-recoveries', authenticateToken, fcmController.getActiveRecoveries);

/**
 * GET /api/fcm/health
 * Health check for FCM system
 */
router.get('/health', fcmController.healthCheck);

module.exports = router;
