// Get all messages for a specific chatId
exports.getChatMessages = async (req, res) => {
  try {
    const { chatId } = req.params;
    if (!chatId) {
      return res.status(400).json({ success: false, message: 'chatId is required' });
    }
    const admin = getFirebaseAdmin();
    const snapshot = await admin.firestore()
      .collection('messages')
      .where('chatId', '==', chatId)
      .orderBy('createdAt', 'asc')
      .get();
    const messages = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    res.json({ success: true, messages });
  } catch (error) {
    res.status(500).json({ success: false, message: 'Failed to fetch chat messages', error: error.message });
  }
};

const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { sendPushNotification } = require('./notificationController');

// Get all messages for the authenticated user (both sent and received)
exports.getInboxMessages = async (req, res) => {
  try {
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    // Get messages where user is either sender or recipient
    const [sentSnapshot, receivedSnapshot] = await Promise.all([
      db.collection('messages')
        .where('senderId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(100)
        .get(),
      db.collection('messages')
        .where('recipientId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(100)
        .get()
    ]);

    // Combine and deduplicate messages
    const messagesMap = new Map();

    sentSnapshot.docs.forEach(doc => {
      messagesMap.set(doc.id, { id: doc.id, ...doc.data() });
    });

    receivedSnapshot.docs.forEach(doc => {
      messagesMap.set(doc.id, { id: doc.id, ...doc.data() });
    });

    // Convert to array and sort by createdAt descending
    const messages = Array.from(messagesMap.values()).sort((a, b) => {
      const aTime = a.createdAt?._seconds || 0;
      const bTime = b.createdAt?._seconds || 0;
      return bTime - aTime;
    });

    res.json({ success: true, messages });
  } catch (error) {
    console.error('❌ Error fetching inbox messages:', error);
    res.status(500).json({ success: false, message: 'Failed to fetch messages', error: error.message });
  }
};

// Send a new message with push notification
exports.sendMessage = async (req, res) => {
  try {
    const { recipientId, content, jobDetails, imageUrl } = req.body;
    const senderId = req.user.uid;
    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    if (!recipientId || (!content && !imageUrl)) {
      return res.status(400).json({ success: false, message: 'recipientId and content are required' });
    }

    const chatId = [senderId, recipientId].sort().join('_'); // Consistent for both users

    const messageData = {
      chatId,
      senderId,
      recipientId,
      content: content || '',
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    // Add image URL if provided
    if (imageUrl) {
      messageData.imageUrl = imageUrl;
      messageData.messageType = 'image';
    } else {
      messageData.messageType = 'text';
    }

    // Add job details if provided
    if (jobDetails) {
      messageData.jobDetails = jobDetails;
    }

    const messageRef = await db.collection('messages').add(messageData);

    // Get sender's profile for notification
    let senderName = 'Someone';
    let senderProfilePic = '';
    try {
      const senderDoc = await db.collection('users').doc(senderId).get();
      if (senderDoc.exists) {
        const senderData = senderDoc.data();
        senderName = senderData.name ||
          `${senderData.firstName || ''} ${senderData.lastName || ''}`.trim() ||
          'Someone';
        senderProfilePic = senderData.profilePicUrl || '';
      }
    } catch (profileError) {
      console.error('Error fetching sender profile:', profileError);
    }

    // Check if recipient has notifications enabled
    let shouldSendNotification = true;
    try {
      const recipientDoc = await db.collection('users').doc(recipientId).get();
      if (recipientDoc.exists) {
        const settings = recipientDoc.data()?.notificationSettings;
        if (settings && settings.messagesEnabled === false) {
          shouldSendNotification = false;
        }
      }
    } catch (settingsError) {
      console.error('Error checking notification settings:', settingsError);
    }

    // Send push notification to recipient (async, don't wait)
    if (shouldSendNotification) {
      setImmediate(async () => {
        try {
          const notificationData = {
            type: 'message',
            title: `New message from ${senderName}`,
            body: imageUrl ? '📷 Sent you an image' : (content.length > 100 ? content.substring(0, 100) + '...' : content),
            senderName,
            senderId,
            senderProfilePic,
            chatId,
            messageType: imageUrl ? 'image' : 'text',
            imageUrl: imageUrl || ''
          };

          await sendPushNotification(recipientId, notificationData);
          console.log(`📬 Push notification sent for message to: ${recipientId}`);
        } catch (notifError) {
          console.error('Error sending push notification:', notifError);
        }
      });
    }

    res.status(201).json({
      success: true,
      message: {
        id: messageRef.id,
        ...messageData,
        createdAt: { _seconds: Math.floor(Date.now() / 1000) }
      }
    });
  } catch (error) {
    console.error('❌ Error sending message:', error);
    res.status(500).json({ success: false, message: 'Failed to send message', error: error.message });
  }
};

