const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middlewares/authMiddleware');
const notificationController = require('../controllers/notificationController');

/**
 * Notification Routes
 * All routes require authentication
 */

// FCM Token management
router.post('/register-token', authenticateToken, notificationController.registerToken);
router.post('/unregister-token', authenticateToken, notificationController.unregisterToken);

// Notification history
router.get('/', authenticateToken, notificationController.getNotifications);
router.get('/unread-count', authenticateToken, notificationController.getUnreadCount);

// Read/unread management
router.put('/read-all', authenticateToken, notificationController.markAllAsRead);
router.put('/:notificationId/read', authenticateToken, notificationController.markAsRead);

// Delete notifications
router.delete('/clear-all', authenticateToken, notificationController.clearAll);
router.delete('/:notificationId', authenticateToken, notificationController.deleteNotification);

// Notification settings
router.get('/settings', authenticateToken, notificationController.getSettings);
router.put('/settings', authenticateToken, notificationController.updateSettings);

module.exports = router;

