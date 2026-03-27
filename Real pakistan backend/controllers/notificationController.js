const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');

/**
 * Register FCM token for push notifications
 * POST /api/notifications/register-token
 */
exports.registerToken = async (req, res) => {
  try {
    const userId = req.user.uid;
    const { fcmToken, platform, deviceInfo } = req.body;

    if (!fcmToken) {
      return res.status(400).json({
        success: false,
        message: 'FCM token is required'
      });
    }

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    // Store the FCM token in the user's document
    const tokenData = {
      fcmToken,
      platform: platform || 'android',
      deviceInfo: deviceInfo || 'unknown',
      registeredAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      lastUpdated: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      isActive: true
    };

    // Update user's fcmTokens subcollection (supports multiple devices)
    const tokenDocRef = db.collection('users').doc(userId)
      .collection('fcmTokens').doc(fcmToken.substring(0, 50)); // Use part of token as ID

    await tokenDocRef.set(tokenData, { merge: true });

    // Also store in a dedicated collection for easy querying
    await db.collection('fcmTokens').doc(userId).set({
      userId,
      tokens: firebaseAdmin.firestore.FieldValue.arrayUnion({
        token: fcmToken,
        platform: platform || 'android',
        deviceInfo: deviceInfo || 'unknown',
        registeredAt: new Date().toISOString()
      }),
      lastUpdated: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    console.log(`✅ FCM token registered for user: ${userId}`);

    res.json({
      success: true,
      message: 'FCM token registered successfully'
    });

  } catch (error) {
    console.error('❌ Error registering FCM token:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to register FCM token',
      error: error.message
    });
  }
};

/**
 * Unregister FCM token (e.g., on logout)
 * POST /api/notifications/unregister-token
 */
exports.unregisterToken = async (req, res) => {
  try {
    const userId = req.user.uid;
    const { fcmToken } = req.body;

    if (!fcmToken) {
      return res.status(400).json({
        success: false,
        message: 'FCM token is required'
      });
    }

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    // Remove from user's fcmTokens subcollection
    const tokenDocRef = db.collection('users').doc(userId)
      .collection('fcmTokens').doc(fcmToken.substring(0, 50));

    await tokenDocRef.update({ isActive: false });

    console.log(`✅ FCM token unregistered for user: ${userId}`);

    res.json({
      success: true,
      message: 'FCM token unregistered successfully'
    });

  } catch (error) {
    console.error('❌ Error unregistering FCM token:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to unregister FCM token',
      error: error.message
    });
  }
};

/**
 * Send push notification to a specific user
 * Used internally by other controllers
 */
exports.sendPushNotification = async (recipientUserId, notificationData) => {
  try {
    const admin = getFirebaseAdmin();
    const db = admin.firestore();
    const messaging = admin.messaging();

    // Get recipient's FCM tokens
    const tokensDoc = await db.collection('fcmTokens').doc(recipientUserId).get();

    if (!tokensDoc.exists) {
      console.log(`⚠️ No FCM tokens found for user: ${recipientUserId}`);
      return { success: false, reason: 'No FCM tokens found' };
    }

    const tokenData = tokensDoc.data();
    const tokens = tokenData.tokens || [];

    if (tokens.length === 0) {
      console.log(`⚠️ No active FCM tokens for user: ${recipientUserId}`);
      return { success: false, reason: 'No active tokens' };
    }

    // Get unique tokens
    const uniqueTokens = [...new Set(tokens.map(t => t.token))];

    // Prepare the message
    const message = {
      notification: {
        title: notificationData.title || 'New Message',
        body: notificationData.body || 'You have a new message'
      },
      data: {
        type: notificationData.type || 'message',
        title: notificationData.title || 'New Message',
        body: notificationData.body || 'You have a new message',
        senderName: notificationData.senderName || 'Someone',
        senderId: notificationData.senderId || '',
        senderProfilePic: notificationData.senderProfilePic || '',
        chatId: notificationData.chatId || '',
        messageType: notificationData.messageType || 'text',
        imageUrl: notificationData.imageUrl || '',
        timestamp: new Date().toISOString(),
        click_action: 'FLUTTER_NOTIFICATION_CLICK'
      },
      android: {
        priority: 'high',
        notification: {
          channelId: 'message_notifications',
          priority: 'high',
          defaultSound: true,
          defaultVibrateTimings: true,
          clickAction: 'OPEN_CHAT'
        }
      },
      apns: {
        payload: {
          aps: {
            sound: 'default',
            badge: 1,
            'content-available': 1
          }
        }
      }
    };

    // Send to all tokens
    let successCount = 0;
    let failureCount = 0;
    const failedTokens = [];

    for (const token of uniqueTokens) {
      try {
        await messaging.send({
          ...message,
          token: token
        });
        successCount++;
        console.log(`✅ Notification sent successfully to token: ${token.substring(0, 20)}...`);
      } catch (tokenError) {
        failureCount++;
        failedTokens.push(token);
        console.error(`❌ Failed to send to token: ${token.substring(0, 20)}...`, tokenError.message);

        // Remove invalid tokens
        if (tokenError.code === 'messaging/invalid-registration-token' ||
            tokenError.code === 'messaging/registration-token-not-registered') {
          // Remove this token from the database
          await db.collection('fcmTokens').doc(recipientUserId).update({
            tokens: firebaseAdmin.firestore.FieldValue.arrayRemove(
              tokens.find(t => t.token === token)
            )
          });
          console.log(`🗑️ Removed invalid token for user: ${recipientUserId}`);
        }
      }
    }

    // Save notification to database for in-app history
    await db.collection('notifications').add({
      recipientId: recipientUserId,
      ...notificationData,
      sentAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      read: false,
      successCount,
      failureCount
    });

    console.log(`📬 Push notification result for ${recipientUserId}: ${successCount} success, ${failureCount} failed`);

    return {
      success: successCount > 0,
      successCount,
      failureCount,
      failedTokens
    };

  } catch (error) {
    console.error('❌ Error sending push notification:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Get user's notification history
 * GET /api/notifications
 */
exports.getNotifications = async (req, res) => {
  try {
    const userId = req.user.uid;
    const { limit = 50, unreadOnly = false } = req.query;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    let query = db.collection('notifications')
      .where('recipientId', '==', userId)
      .orderBy('sentAt', 'desc')
      .limit(parseInt(limit));

    if (unreadOnly === 'true') {
      query = query.where('read', '==', false);
    }

    const snapshot = await query.get();
    const notifications = snapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    }));

    res.json({
      success: true,
      notifications,
      count: notifications.length
    });

  } catch (error) {
    console.error('❌ Error fetching notifications:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch notifications',
      error: error.message
    });
  }
};

/**
 * Mark notification as read
 * PUT /api/notifications/:notificationId/read
 */
exports.markAsRead = async (req, res) => {
  try {
    const userId = req.user.uid;
    const { notificationId } = req.params;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const notificationRef = db.collection('notifications').doc(notificationId);
    const notificationDoc = await notificationRef.get();

    if (!notificationDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Notification not found'
      });
    }

    // Verify ownership
    if (notificationDoc.data().recipientId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Not authorized'
      });
    }

    await notificationRef.update({
      read: true,
      readAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    res.json({
      success: true,
      message: 'Notification marked as read'
    });

  } catch (error) {
    console.error('❌ Error marking notification as read:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to mark notification as read',
      error: error.message
    });
  }
};

/**
 * Mark all notifications as read
 * PUT /api/notifications/read-all
 */
exports.markAllAsRead = async (req, res) => {
  try {
    const userId = req.user.uid;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const snapshot = await db.collection('notifications')
      .where('recipientId', '==', userId)
      .where('read', '==', false)
      .get();

    const batch = db.batch();
    snapshot.docs.forEach(doc => {
      batch.update(doc.ref, {
        read: true,
        readAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });
    });

    await batch.commit();

    res.json({
      success: true,
      message: `${snapshot.size} notifications marked as read`
    });

  } catch (error) {
    console.error('❌ Error marking all notifications as read:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to mark notifications as read',
      error: error.message
    });
  }
};

/**
 * Get unread notification count
 * GET /api/notifications/unread-count
 */
exports.getUnreadCount = async (req, res) => {
  try {
    const userId = req.user.uid;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const snapshot = await db.collection('notifications')
      .where('recipientId', '==', userId)
      .where('read', '==', false)
      .count()
      .get();

    const count = snapshot.data().count;

    res.json({
      success: true,
      unreadCount: count
    });

  } catch (error) {
    console.error('❌ Error getting unread count:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get unread count',
      error: error.message
    });
  }
};

/**
 * Delete a notification
 * DELETE /api/notifications/:notificationId
 */
exports.deleteNotification = async (req, res) => {
  try {
    const userId = req.user.uid;
    const { notificationId } = req.params;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const notificationRef = db.collection('notifications').doc(notificationId);
    const notificationDoc = await notificationRef.get();

    if (!notificationDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Notification not found'
      });
    }

    // Verify ownership
    if (notificationDoc.data().recipientId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Not authorized'
      });
    }

    await notificationRef.delete();

    res.json({
      success: true,
      message: 'Notification deleted'
    });

  } catch (error) {
    console.error('❌ Error deleting notification:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to delete notification',
      error: error.message
    });
  }
};

/**
 * Clear all notifications for user
 * DELETE /api/notifications/clear-all
 */
exports.clearAll = async (req, res) => {
  try {
    const userId = req.user.uid;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const snapshot = await db.collection('notifications')
      .where('recipientId', '==', userId)
      .get();

    const batch = db.batch();
    snapshot.docs.forEach(doc => {
      batch.delete(doc.ref);
    });

    await batch.commit();

    res.json({
      success: true,
      message: `${snapshot.size} notifications deleted`
    });

  } catch (error) {
    console.error('❌ Error clearing notifications:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to clear notifications',
      error: error.message
    });
  }
};

/**
 * Update notification settings
 * PUT /api/notifications/settings
 */
exports.updateSettings = async (req, res) => {
  try {
    const userId = req.user.uid;
    const {
      messagesEnabled = true,
      ordersEnabled = true,
      jobsEnabled = true,
      soundEnabled = true,
      vibrationEnabled = true
    } = req.body;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    await db.collection('users').doc(userId).update({
      'notificationSettings': {
        messagesEnabled,
        ordersEnabled,
        jobsEnabled,
        soundEnabled,
        vibrationEnabled,
        updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      }
    });

    res.json({
      success: true,
      message: 'Notification settings updated'
    });

  } catch (error) {
    console.error('❌ Error updating notification settings:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to update settings',
      error: error.message
    });
  }
};

/**
 * Get notification settings
 * GET /api/notifications/settings
 */
exports.getSettings = async (req, res) => {
  try {
    const userId = req.user.uid;

    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    const userDoc = await db.collection('users').doc(userId).get();
    const settings = userDoc.data()?.notificationSettings || {
      messagesEnabled: true,
      ordersEnabled: true,
      jobsEnabled: true,
      soundEnabled: true,
      vibrationEnabled: true
    };

    res.json({
      success: true,
      settings
    });

  } catch (error) {
    console.error('❌ Error getting notification settings:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get settings',
      error: error.message
    });
  }
};

