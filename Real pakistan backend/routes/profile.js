
const express = require('express');
const multer = require('multer');
const { createClient } = require('@supabase/supabase-js');
const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { authenticateToken } = require('../middlewares/authMiddleware');
const { userSchema } = require('../models/schema');

const router = express.Router();
const upload = multer();

// Supabase client for file uploads
const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_KEY;
const supabase = createClient(supabaseUrl, supabaseKey);

// POST /api/profile/upload-profile-image - Upload profile picture
router.post('/upload-profile-image', authenticateToken, upload.single('image'), async (req, res, next) => {
  try {
    console.log('📷 Profile image upload request received');

    if (!req.file) {
      console.log('❌ No file uploaded');
      return res.status(400).json({ success: false, message: 'No file uploaded' });
    }

    const userId = req.user && req.user.uid ? req.user.uid : null;
    if (!userId) {
      console.log('❌ No user ID');
      return res.status(401).json({ success: false, message: 'Authentication required' });
    }

    console.log('👤 User ID:', userId);
    console.log('📁 File info:', {
      originalname: req.file.originalname,
      mimetype: req.file.mimetype,
      size: req.file.size
    });

    const file = req.file;
    const filePath = `profile-images/${userId}/${Date.now()}_${file.originalname}`;

    console.log('📤 Uploading to Supabase:', filePath);

    const { data, error } = await supabase.storage
      .from('user-images')
      .upload(filePath, file.buffer, { contentType: file.mimetype });

    if (error) {
      console.error('❌ Supabase upload error:', error);
      return res.status(500).json({ success: false, message: error.message });
    }

    console.log('✅ Supabase upload successful:', data);

    const publicUrl = supabase.storage.from('user-images').getPublicUrl(filePath).data.publicUrl;

    console.log('🔗 Public URL:', publicUrl);

    res.json({
      success: true,
      data: {
        imageUrl: publicUrl
      }
    });

  } catch (err) {
    console.error('❌ Upload profile image error:', err);
    next(err);
  }
});

// GET /api/profile/:userId - Get public profile for any user
router.get('/:userId', async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(req.params.userId).get();
    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }
    const profile = userDoc.data();
    res.status(200).json({
      success: true,
      message: 'Profile retrieved successfully',
      data: {
        ...profile,
        name: `${profile.firstName || ''} ${profile.lastName || ''}`.trim()
      }
    });
  } catch (error) {
    next(error);
  }
});

// GET /api/profile - Get current user profile
router.get('/', authenticateToken, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(req.user.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const profile = userDoc.data();

    res.status(200).json({
      success: true,
      message: 'Profile retrieved successfully',
      data: {
        ...profile,
        name: `${profile.firstName || ''} ${profile.lastName || ''}`.trim()
      }
    });

  } catch (error) {
    next(error);
  }
});


// POST /api/profile/accept-terms - Accept terms and conditions
router.post('/accept-terms', authenticateToken, async (req, res, next) => {
  try {
    console.log('📋 Terms acceptance request received');
    const admin = getFirebaseAdmin();
    const { version } = req.body;

    if (!version || typeof version !== 'number') {
      return res.status(400).json({
        success: false,
        message: 'Terms version is required'
      });
    }

    const termsData = {
      termsAccepted: true,
      termsAcceptedTimestamp: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      termsVersion: version,
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    console.log('✅ Updating terms acceptance for user:', req.user.uid);
    await admin.firestore().collection('users').doc(req.user.uid).update(termsData);

    console.log('🎉 Terms accepted successfully');
    res.status(200).json({
      success: true,
      message: 'Terms accepted successfully',
      data: {
        termsAccepted: true,
        termsVersion: version,
        timestamp: new Date().toISOString()
      }
    });

  } catch (error) {
    console.error('❌ Accept terms error:', error);
    next(error);
  }
});

// GET /api/profile/terms-status - Check terms acceptance status
router.get('/terms-status', authenticateToken, async (req, res, next) => {
  try {
    console.log('📋 Terms status check for user:', req.user.uid);
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(req.user.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User not found'
      });
    }

    const userData = userDoc.data();
    const currentTermsVersion = 1; // Update this when terms are updated

    res.status(200).json({
      success: true,
      data: {
        termsAccepted: userData.termsAccepted || false,
        termsVersion: userData.termsVersion || 0,
        termsAcceptedTimestamp: userData.termsAcceptedTimestamp,
        needsToAccept: !userData.termsAccepted || userData.termsVersion < currentTermsVersion,
        currentTermsVersion: currentTermsVersion
      }
    });

  } catch (error) {
    console.error('❌ Terms status check error:', error);
    next(error);
  }
});

// POST /api/profile/complete-onboarding - Mark onboarding as completed
router.post('/complete-onboarding', authenticateToken, async (req, res, next) => {
  try {
    console.log('🎯 Onboarding completion request received');
    const admin = getFirebaseAdmin();

    const onboardingData = {
      onboardingCompleted: true,
      onboardingCompletedTimestamp: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    console.log('✅ Updating onboarding completion for user:', req.user.uid);
    await admin.firestore().collection('users').doc(req.user.uid).update(onboardingData);

    console.log('🎉 Onboarding marked as completed');
    res.status(200).json({
      success: true,
      message: 'Onboarding completed successfully',
      data: {
        onboardingCompleted: true,
        timestamp: new Date().toISOString()
      }
    });

  } catch (error) {
    console.error('❌ Complete onboarding error:', error);
    next(error);
  }
});

// PUT /api/profile - Update user profile
router.put('/', authenticateToken, async (req, res, next) => {
  try {
    console.log('📤 Profile update request received:', JSON.stringify(req.body, null, 2));
    const admin = getFirebaseAdmin();

    // Allow updates to these specific fields only
    const allowedUpdates = ['firstName', 'lastName', 'description', 'location', 'profilePicUrl', 'Languages', 'skills'];
    const updates = {};
    Object.keys(req.body).forEach(key => {
      if (allowedUpdates.includes(key) && req.body[key] !== undefined) {
        updates[key] = req.body[key];
      }
    });

    console.log('🔍 Filtered updates:', JSON.stringify(updates, null, 2));

    if (Object.keys(updates).length === 0) {
      return res.status(400).json({
        success: false,
        message: 'No valid fields to update'
      });
    }

    // Validate updates - BE MORE FLEXIBLE
    if (updates.firstName !== undefined && (typeof updates.firstName !== 'string' || updates.firstName.trim().length < 2)) {
      return res.status(400).json({ success: false, message: 'First name must be at least 2 characters' });
    }

    // Allow empty lastName - only validate if it's provided and non-empty
    if (updates.lastName !== undefined && typeof updates.lastName !== 'string') {
      return res.status(400).json({ success: false, message: 'Last name must be a string' });
    }

    if (updates.lastName && updates.lastName.trim().length > 0 && updates.lastName.trim().length < 2) {
      return res.status(400).json({ success: false, message: 'Last name must be at least 2 characters when provided' });
    }

    // Allow empty description
    if (updates.description !== undefined && typeof updates.description !== 'string') {
      return res.status(400).json({ success: false, message: 'Description must be a string' });
    }

    // Allow empty location
    if (updates.location !== undefined && typeof updates.location !== 'string') {
      return res.status(400).json({ success: false, message: 'Location must be a string' });
    }

    if (updates.profilePicUrl !== undefined && typeof updates.profilePicUrl !== 'string') {
      return res.status(400).json({ success: false, message: 'Profile picture URL must be a string' });
    }

    if (updates.Languages !== undefined && (!Array.isArray(updates.Languages) || !updates.Languages.every(lang => typeof lang === 'string'))) {
      return res.status(400).json({ success: false, message: 'Languages must be an array of strings' });
    }

    if (updates.skills !== undefined && (!Array.isArray(updates.skills) || !updates.skills.every(skill => typeof skill === 'string'))) {
      return res.status(400).json({ success: false, message: 'Skills must be an array of strings' });
    }

    updates.updatedAt = firebaseAdmin.firestore.FieldValue.serverTimestamp();

    console.log('✅ Validation passed, updating Firestore with:', JSON.stringify(updates, null, 2));

    await admin.firestore().collection('users').doc(req.user.uid).update(updates);

    console.log('🎉 Profile updated successfully for user:', req.user.uid);

    res.status(200).json({
      success: true,
      message: 'Profile updated successfully',
      data: updates
    });

  } catch (error) {
    console.error('❌ Profile update error:', error);
    console.error('Error details:', error.message);
    console.error('Stack trace:', error.stack);
    next(error);
  }
});

// GET /api/profile/stats - Get user statistics
router.get('/stats', authenticateToken, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(req.user.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const profile = userDoc.data();

    // Get additional stats from other collections
    const jobsSnapshot = await admin.firestore()
      .collection('jobs')
      .where('createdBy', '==', req.user.uid)
      .get();

    const ordersSnapshot = await admin.firestore()
      .collection('orders')
      .where('sellerId', '==', req.user.uid)
      .get();

    const completedOrders = ordersSnapshot.docs.filter(doc =>
      doc.data().status === 'completed'
    ).length;

    const uniqueClients = new Set(ordersSnapshot.docs.map(doc =>
      doc.data().buyerId
    )).size;

    res.status(200).json({
      success: true,
      message: 'User stats retrieved successfully',
      data: {
        level: profile.level || 1,
        successScore: profile.successScore || 0,
        rating: profile.rating || 0,
        responseRate: profile.responseRate || 0,
        totalJobs: jobsSnapshot.size,
        completedOrders: completedOrders,
        uniqueClients: uniqueClients,
        earnings: profile.earnings || 0
      }
    });

  } catch (error) {
    next(error);
  }
});

module.exports = router;
