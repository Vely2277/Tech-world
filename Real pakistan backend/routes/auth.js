const express = require('express');
const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const router = express.Router();
const { userSchema } = require('../models/schema');

// Email validation function
function validateEmail(email) {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return email && emailRegex.test(email.trim());
}

// Password validation function
function validatePassword(password) {
  return password && password.length >= 6;
}

// POST /api/auth/signup - Register new user
router.post('/signup', async (req, res, next) => {
  try {
    console.log('=== SIGNUP REQUEST STARTED ===');
    console.log('Request body:', JSON.stringify(req.body, null, 2));

    const { firstName, lastName, email, password } = req.body;
    console.log('Extracted fields:', { firstName, lastName, email, hasPassword: !!password });

    // Validation (matching your Android validation)
    if (!firstName || firstName.trim().length === 0) {
      console.log('❌ Validation failed: First name is required');
      return res.status(422).json({
        success: false,
        message: 'First name is required'
      });
    }

    if (!lastName || lastName.trim().length === 0) {
      console.log('❌ Validation failed: Last name is required');
      return res.status(422).json({
        success: false,
        message: 'Last name is required'
      });
    }

    if (!validateEmail(email)) {
      console.log('❌ Validation failed: Invalid email');
      return res.status(422).json({
        success: false,
        message: 'Please enter a valid email address'
      });
    }

    if (!validatePassword(password)) {
      console.log('❌ Validation failed: Password too short');
      return res.status(422).json({
        success: false,
        message: 'Password must be at least 6 characters'
      });
    }

    console.log('✅ Validation passed');

    // Get Firebase Admin instance first
    console.log('📦 Getting Firebase Admin instance...');
    const admin = getFirebaseAdmin();
    console.log('✅ Firebase Admin instance obtained');

    // Check if user already exists
    console.log('🔍 Checking if user already exists...');
    try {
      await admin.auth().getUserByEmail(email.toLowerCase());
      console.log('❌ User already exists with this email');
      return res.status(409).json({
        success: false,
        message: 'Email already exists. Please use a different email or login.'
      });
    } catch (error) {
      // User doesn't exist, continue with registration
      if (error.code !== 'auth/user-not-found') {
        console.error('❌ Error checking user existence:', error);
        throw error;
      }
      console.log('✅ User does not exist, proceeding with registration');
    }

    // Create user in Firebase Auth
    console.log('👤 Creating user in Firebase Auth...');
    const userRecord = await admin.auth().createUser({
      email: email.toLowerCase(),
      password: password,
      displayName: `${firstName.trim()} ${lastName.trim()}`,
      emailVerified: false
    });
    console.log('✅ Firebase Auth user created:', userRecord.uid);

    // Store additional user data in Firestore
    console.log('💾 Preparing user profile data...');
    const userProfile = {
      uid: userRecord.uid,
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      email: email.toLowerCase(),
      role: 'seller',
      profilePicUrl: null,
      level: 1,
      successScore: 0,
      rating: 0.0,
      responseRate: 0,
      earnings: 0.0,
      totalEarned: 0,
      availableBalance: 0,
      totalReviews: 0,
      locationUpdateInterval: 300,

      // Add missing profile fields with defaults
      description: "",
      location: "",
      Languages: [],
      skills: [],
      ActiveStatus: "Active now",
      AverageResponseTime: "1",
      isVerified: false,
      emailVerified: false,
      notifications: [],
      withdrawalHistory: [],

      // Terms & Conditions acceptance
      termsAccepted: false,
      termsAcceptedTimestamp: null,
      termsVersion: 0,


      // Timestamps
      lastLogin: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    console.log('💾 Saving user profile to Firestore...');
    await admin.firestore().collection('users').doc(userRecord.uid).set(userProfile);
    console.log('✅ User profile saved to Firestore');

    // Generate custom token for immediate login
    console.log('🔑 Generating custom token...');
    const customToken = await admin.auth().createCustomToken(userRecord.uid);
    console.log('✅ Custom token generated');

    console.log('🎉 Signup completed successfully!');
    res.status(201).json({
      success: true,
      message: 'User registered successfully',
      user: {
        uid: userRecord.uid,
        email: userRecord.email,
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        role: 'seller'
      },
      token: customToken
    });

  } catch (error) {
    console.error('❌❌❌ SIGNUP ERROR ❌❌❌');
    console.error('Error name:', error.name);
    console.error('Error message:', error.message);
    console.error('Error code:', error.code);
    console.error('Error stack:', error.stack);
    console.error('Full error object:', JSON.stringify(error, null, 2));

    if (error.code === 'auth/email-already-exists') {
      return res.status(409).json({
        success: false,
        message: 'Email already exists. Please use a different email or login.'
      });
    }

    // Return detailed error for debugging
    res.status(500).json({
      success: false,
      message: 'Server error during signup',
      error: error.message,
      errorCode: error.code
    });
  }
});

// POST /api/auth/login - Login user
router.post('/login', async (req, res, next) => {
  try {
    const { email, password } = req.body;

    // Validation (matching your Android validation)
    if (!validateEmail(email)) {
      return res.status(422).json({
        success: false,
        message: 'Please enter a valid email address'
      });
    }

    if (!validatePassword(password)) {
      return res.status(422).json({
        success: false,
        message: 'Password must be at least 6 characters'
      });
    }

    // Get user by email to check if user exists
    const admin = getFirebaseAdmin();
    let userRecord;
    try {
      userRecord = await admin.auth().getUserByEmail(email.toLowerCase());
    } catch (error) {
      if (error.code === 'auth/user-not-found') {
        return res.status(404).json({
          success: false,
          message: 'User not found. Please check your email or sign up.'
        });
      }
      throw error;
    }

    // Get user profile from Firestore
    const userDoc = await admin.firestore().collection('users').doc(userRecord.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found. Please contact support.'
      });
    }

    const userProfile = userDoc.data();

    // Generate custom token for login
    const customToken = await admin.auth().createCustomToken(userRecord.uid);

    res.status(200).json({
      success: true,
      message: 'Login successful',
      user: {
        uid: userRecord.uid,
        email: userRecord.email,
        firstName: userProfile.firstName,
        lastName: userProfile.lastName,
        role: userProfile.role,
        level: userProfile.level,
        successScore: userProfile.successScore,
        rating: userProfile.rating,
        responseRate: userProfile.responseRate,
        earnings: userProfile.earnings
      },
      token: customToken
    });

  } catch (error) {
    console.error('Login error:', error);
    next(error);
  }
});

// POST /api/auth/forgot-password - Send password reset email
router.post('/forgot-password', async (req, res, next) => {
  try {
    const { email } = req.body;

    // Validation
    if (!validateEmail(email)) {
      return res.status(422).json({
        success: false,
        message: 'Please enter a valid email address'
      });
    }

    // Check if user exists
    const admin = getFirebaseAdmin();
    try {
      await admin.auth().getUserByEmail(email.toLowerCase());
    } catch (error) {
      if (error.code === 'auth/user-not-found') {
        return res.status(404).json({
          success: false,
          message: 'Email not found. Please check your email or sign up.'
        });
      }
      throw error;
    }

    // Generate password reset link
    const resetLink = await admin.auth().generatePasswordResetLink(email.toLowerCase());

    // In a real app, you would send this link via email
    // For now, we'll just return success
    res.status(200).json({
      success: true,
      message: 'Password reset instructions sent to your email',
      // Don't include the actual link in production
      resetLink: resetLink
    });

  } catch (error) {
    console.error('Forgot password error:', error);
    next(error);
  }
});

// POST /api/auth/verify-token - Verify Firebase ID token
router.post('/verify-token', async (req, res, next) => {
  try {
    const { idToken } = req.body;

    if (!idToken) {
      return res.status(400).json({
        success: false,
        message: 'ID token is required'
      });
    }

    // Verify the ID token
    const admin = getFirebaseAdmin();
    const decodedToken = await admin.auth().verifyIdToken(idToken);
    const uid = decodedToken.uid;

    // Get user profile
    const userDoc = await admin.firestore().collection('users').doc(uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const userProfile = userDoc.data();

    res.status(200).json({
      success: true,
      message: 'Token verified successfully',
      user: {
        uid: uid,
        email: decodedToken.email,
        firstName: userProfile.firstName,
        lastName: userProfile.lastName,
        role: userProfile.role
      }
    });

  } catch (error) {
    console.error('Token verification error:', error);
    if (error.code === 'auth/id-token-expired') {
      return res.status(401).json({
        success: false,
        message: 'Token expired. Please login again.'
      });
    }
    if (error.code === 'auth/invalid-id-token') {
      return res.status(401).json({
        success: false,
        message: 'Invalid token. Please login again.'
      });
    }
    next(error);
  }
});

module.exports = router;
