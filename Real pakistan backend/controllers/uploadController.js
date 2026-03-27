const { createClient } = require('@supabase/supabase-js');
const { getFirebaseAdmin } = require('../config/firebase');
const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_KEY;
const supabase = createClient(supabaseUrl, supabaseKey);

exports.uploadImage = async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ success: false, message: 'No file uploaded' });
    }
    const file = req.file;
    const filePath = `user-images/${Date.now()}_${file.originalname}`;
    const { data, error } = await supabase.storage
      .from('user-images')
      .upload(filePath, file.buffer, { contentType: file.mimetype });

    if (error) {
      return res.status(500).json({ success: false, message: error.message });
    }

    const publicUrl = supabase.storage.from('user-images').getPublicUrl(filePath).data.publicUrl;

    // IMPORTANT: Do NOT save a Firestore message here.
    // The message is created when user presses send via /api/inbox.
    // Previously this created a duplicate image-only message.

    res.json({ success: true, imageUrl: publicUrl });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
};

// Profile picture upload - simplified version based on working chat upload
exports.uploadProfileImage = async (req, res) => {
  try {
    console.log('📷 Profile upload started');

    if (!req.file) {
      console.log('❌ No file uploaded');
      return res.status(400).json({ success: false, message: 'No file uploaded' });
    }

    const userId = req.user && req.user.uid ? req.user.uid : null;
    if (!userId) {
      console.log('❌ No user ID');
      return res.status(401).json({ success: false, message: 'Authentication required' });
    }

    console.log('✅ User authenticated:', userId);
    console.log('📁 File details:', {
      originalname: req.file.originalname,
      mimetype: req.file.mimetype,
      size: req.file.size
    });

    const file = req.file;
    const filePath = `profile-images/${userId}/${Date.now()}_${file.originalname}`;

    console.log('📤 Uploading to Supabase:', filePath);

    // Use EXACT same Supabase upload logic as chat
    const { data, error } = await supabase.storage
      .from('user-images')
      .upload(filePath, file.buffer, { contentType: file.mimetype });

    if (error) {
      console.error('❌ Supabase upload error:', error);
      return res.status(500).json({ success: false, message: error.message });
    }

    console.log('✅ Upload successful:', data);

    const publicUrl = supabase.storage.from('user-images').getPublicUrl(filePath).data.publicUrl;

    console.log('🔗 Public URL:', publicUrl);

    // Return same format as chat upload
    res.json({
      success: true,
      data: {
        imageUrl: publicUrl
      }
    });

  } catch (err) {
    console.error('❌ Profile upload error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

// NEW: Upload delivery files
exports.uploadDeliveryFile = async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ success: false, message: 'No file uploaded' });
    }

    const { orderId } = req.body;
    const userId = req.user && req.user.uid ? req.user.uid : null;

    if (!orderId || !userId) {
      return res.status(400).json({ success: false, message: 'orderId and authentication are required' });
    }

    // Verify user has permission to upload for this order
    const admin = getFirebaseAdmin();
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();
    if (!orderDoc.exists) {
      return res.status(404).json({ success: false, message: 'Order not found' });
    }

    const orderData = orderDoc.data();

    // Check if user is the assigned worker (sellerId) or the job owner (uid)
    const isAssignedWorker = orderData.sellerId === userId;
    const isJobOwner = orderData.uid === userId;

    if (!isAssignedWorker && !isJobOwner) {
      return res.status(403).json({
        success: false,
        message: 'Only the assigned worker or job owner can upload delivery files',
        debug: {
          userId: userId,
          orderSellerId: orderData.sellerId,
          orderUid: orderData.uid
        }
      });
    }

    const file = req.file;
    const filePath = `delivery-files/${orderId}/${Date.now()}_${file.originalname}`;

    const { data, error } = await supabase.storage
      .from('user-images') // Using same bucket but different folder
      .upload(filePath, file.buffer, { contentType: file.mimetype });

    if (error) {
      console.error('Supabase upload error:', error);
      return res.status(500).json({ success: false, message: error.message });
    }

    const publicUrl = supabase.storage.from('user-images').getPublicUrl(filePath).data.publicUrl;

    res.json({
      success: true,
      data: {
        fileUrl: publicUrl,
        fileName: file.originalname,
        orderId: orderId
      }
    });

  } catch (err) {
    console.error('Upload delivery file error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};
