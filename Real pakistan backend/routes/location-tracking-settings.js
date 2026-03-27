/**
 * ============================================================================
 * LOCATION TRACKING SETTINGS ROUTES
 * ============================================================================
 *
 * API endpoints for location tracking settings management.
 * Stored in Firebase collection: location-tracking-settings
 *
 * BASE PATH: /api/location-tracking-settings
 *
 * ============================================================================
 * USER ENDPOINTS (Authenticated)
 * ============================================================================
 *
 * GET    /                    - Get current user's settings
 * POST   /                    - Update current user's settings
 * POST   /enable-mode         - Enable force-check/realtime/emergency mode
 * POST   /disable-mode        - Disable a specific mode
 * POST   /reset               - Reset to default settings
 * GET    /history             - Get settings change history
 *
 * ============================================================================
 * PUBLIC ENDPOINTS (No auth required)
 * ============================================================================
 *
 * GET    /defaults            - Get default settings template
 * GET    /health              - Health check
 *
 * ============================================================================
 * ADMIN ENDPOINTS (Requires admin role - future)
 * ============================================================================
 *
 * GET    /admin/user/:userId  - Get specific user's settings
 * PUT    /admin/user/:userId  - Update specific user's settings
 * GET    /admin/all           - List all users' settings (paginated)
 * POST   /admin/broadcast     - Broadcast settings to all users
 *
 * ============================================================================
 */

const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middlewares/authMiddleware');
const controller = require('../controllers/locationTrackingSettingsController');

// ============================================================================
// PUBLIC ENDPOINTS (No authentication required)
// ============================================================================

/**
 * GET /api/location-tracking-settings/defaults
 * Get default settings template
 *
 * Response: {
 *   success: true,
 *   data: { ...default settings }
 * }
 */
router.get('/defaults', controller.getDefaults);

/**
 * GET /api/location-tracking-settings/health
 * Health check endpoint
 *
 * Response: {
 *   success: true,
 *   status: 'healthy',
 *   timestamp: '...'
 * }
 */
router.get('/health', controller.healthCheck);

// ============================================================================
// USER ENDPOINTS (Authentication required)
// ============================================================================

/**
 * GET /api/location-tracking-settings
 * Get current user's settings
 * Automatically handles mode expiration
 *
 * Headers: Authorization: Bearer <token>
 *
 * Response: {
 *   success: true,
 *   data: {
 *     updateInterval: 7200,
 *     realtimeInterval: 10,
 *     emergencyInterval: 30,
 *     forceCheckInterval: 300,
 *     trackingEnabled: true,
 *     forceCheckEnabled: false,
 *     realtimeModeEnabled: false,
 *     emergencyModeEnabled: false,
 *     effectiveInterval: 7200,
 *     activeMode: 'normal',
 *     ...
 *   }
 * }
 */
router.get('/', authenticateToken, controller.getSettings);

/**
 * POST /api/location-tracking-settings
 * Update current user's settings
 *
 * Headers: Authorization: Bearer <token>
 * Body: {
 *   updateInterval?: number,
 *   forceCheckEnabled?: boolean,
 *   realtimeModeEnabled?: boolean,
 *   emergencyModeEnabled?: boolean,
 *   ...any valid setting
 * }
 *
 * Response: {
 *   success: true,
 *   data: { ...updated settings }
 * }
 */
router.post('/', authenticateToken, controller.updateSettings);

/**
 * POST /api/location-tracking-settings/enable-mode
 * Quick enable a specific mode
 *
 * Headers: Authorization: Bearer <token>
 * Body: {
 *   mode: 'force-check' | 'realtime' | 'emergency',
 *   duration?: number (seconds, optional - uses default if not provided)
 * }
 *
 * Response: {
 *   success: true,
 *   data: {
 *     mode: 'force-check',
 *     enabled: true,
 *     expiresAt: '2024-...'
 *   }
 * }
 */
router.post('/enable-mode', authenticateToken, controller.enableMode);

/**
 * POST /api/location-tracking-settings/disable-mode
 * Quick disable a specific mode
 *
 * Headers: Authorization: Bearer <token>
 * Body: {
 *   mode: 'force-check' | 'realtime' | 'emergency'
 * }
 *
 * Response: {
 *   success: true,
 *   data: {
 *     mode: 'force-check',
 *     enabled: false
 *   }
 * }
 */
router.post('/disable-mode', authenticateToken, controller.disableMode);

/**
 * POST /api/location-tracking-settings/reset
 * Reset settings to defaults
 *
 * Headers: Authorization: Bearer <token>
 * Body: {
 *   keepHistory?: boolean (default: false)
 * }
 *
 * Response: {
 *   success: true,
 *   data: { ...default settings }
 * }
 */
router.post('/reset', authenticateToken, controller.resetSettings);

/**
 * GET /api/location-tracking-settings/history
 * Get settings change history
 *
 * Headers: Authorization: Bearer <token>
 * Query: ?limit=20 (max 50)
 *
 * Response: {
 *   success: true,
 *   data: {
 *     history: [...],
 *     total: 15
 *   }
 * }
 */
router.get('/history', authenticateToken, controller.getHistory);

// ============================================================================
// ADMIN ENDPOINTS (Authentication required - admin check in controller)
// ============================================================================

/**
 * GET /api/location-tracking-settings/admin/user/:userId
 * Get settings for a specific user (admin only)
 *
 * Headers: Authorization: Bearer <token>
 * Params: userId - Target user's Firebase UID
 *
 * Response: {
 *   success: true,
 *   data: { ...user settings }
 * }
 */
router.get('/admin/user/:userId', authenticateToken, controller.adminGetUserSettings);

/**
 * PUT /api/location-tracking-settings/admin/user/:userId
 * Update settings for a specific user (admin only)
 *
 * Headers: Authorization: Bearer <token>
 * Params: userId - Target user's Firebase UID
 * Body: { ...settings to update }
 *
 * Response: {
 *   success: true,
 *   data: { ...updated settings }
 * }
 */
router.put('/admin/user/:userId', authenticateToken, controller.adminUpdateUserSettings);

/**
 * GET /api/location-tracking-settings/admin/all
 * List all users' settings (admin only, paginated)
 *
 * Headers: Authorization: Bearer <token>
 * Query: ?limit=50&startAfter=<userId>
 *
 * Response: {
 *   success: true,
 *   data: {
 *     settings: [...],
 *     count: 50,
 *     hasMore: true
 *   }
 * }
 */
router.get('/admin/all', authenticateToken, controller.adminGetAllSettings);

/**
 * POST /api/location-tracking-settings/admin/broadcast
 * Broadcast settings to all users (admin only)
 *
 * Headers: Authorization: Bearer <token>
 * Body: {
 *   settings: { ...settings to apply to all users },
 *   reason?: string (audit log reason)
 * }
 *
 * Response: {
 *   success: true,
 *   data: {
 *     updatedCount: 150,
 *     settings: { ... }
 *   }
 * }
 */
router.post('/admin/broadcast', authenticateToken, controller.adminBroadcastSettings);

/**
 * POST /api/location-tracking-settings/admin/user/:userId/force-sync
 * Force sync settings to a specific user (admin only)
 * This bumps the version number to trigger immediate sync via realtime listener
 *
 * Headers: Authorization: Bearer <token>
 * Params: userId - Target user's Firebase UID
 *
 * Response: {
 *   success: true,
 *   message: 'Settings synced successfully',
 *   data: {
 *     userId: '...',
 *     version: 5,
 *     syncedAt: '...'
 *   }
 * }
 */
router.post('/admin/user/:userId/force-sync', authenticateToken, controller.adminForceSyncUserSettings);

// ============================================================================
// EXPORT
// ============================================================================

module.exports = router;

