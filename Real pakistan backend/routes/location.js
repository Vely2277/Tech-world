/*
 * ============================================================================
 * LOCATION ROUTES - ENHANCED
 * ============================================================================
 *
 * PURPOSE:
 * Handle all location tracking API endpoints including single updates,
 * bulk uploads, history retrieval, and admin interval management.
 *
 * ENDPOINTS:
 * - POST   /api/location              - Single location update
 * - POST   /api/location/bulk         - Bulk location upload (offline sync)
 * - GET    /api/location/interval     - Get user's update interval
 * - PUT    /api/location/interval     - Update user's interval (admin)
 * - GET    /api/location/history      - Get location history
 * - GET    /api/location/latest       - Get latest location
 * - DELETE /api/location/old          - Clean old locations
 *
 * ============================================================================
 */

const express = require('express');
const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { authenticateToken } = require('../middlewares/authMiddleware');
const { locationSchema } = require('../models/schema');
const router = express.Router();

// POST /api/location/upload - Upload location (Android BatchUploader endpoint)
router.post('/upload', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();

    // Joi validation
    const { error, value } = locationSchema.validate({
      ...req.body,
      userId: req.user.uid,
      serverTimestamp: new Date().toISOString(),
      createdAt: new Date().toISOString()
    }, { abortEarly: false });

    if (error) {
      console.log('Validation error:', error.details);
      return res.status(400).json({
        success: false,
        message: 'Validation failed',
        errors: error.details.map(d => d.message)
      });
    }

    // Create comprehensive location record with ALL Android fields
    const locationData = {
      // Core location
      userId: req.user.uid,
      latitude: parseFloat(req.body.latitude),
      longitude: parseFloat(req.body.longitude),
      accuracy: req.body.accuracy || null,
      altitude: req.body.altitude || null,
      bearing: req.body.bearing || null,
      speed: req.body.speed || null,
      provider: req.body.provider || 'unknown',

      // Timestamps - CRITICAL: Use client capture time, NOT server time!
      timestamp: req.body.timestamp || null,  // Original timestamp in milliseconds from device
      clientTimestamp: req.body.clientTimestamp || null,  // ISO date string from client
      capturedAt: req.body.timestamp ? firebaseAdmin.firestore.Timestamp.fromDate(new Date(req.body.timestamp)) : firebaseAdmin.firestore.FieldValue.serverTimestamp(),  // When GPS was captured on device
      createdAt: req.body.timestamp ? firebaseAdmin.firestore.Timestamp.fromDate(new Date(req.body.timestamp)) : firebaseAdmin.firestore.FieldValue.serverTimestamp(),  // When location was created (device time)
      uploadedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),  // When uploaded to server (for batch tracking)
      serverTimestamp: firebaseAdmin.firestore.FieldValue.serverTimestamp(),  // Actual server receive time (for sync diagnostics)

      // Battery & Power
      batteryLevel: req.body.batteryLevel || null,
      batteryThresholdActive: req.body.batteryThresholdActive || null,
      forcedInterval: req.body.forcedInterval || null,
      isCharging: req.body.isCharging || null,

      // Network
      networkType: req.body.networkType || null,
      networkQuality: req.body.networkQuality || null,

      // Device Info
      deviceModel: req.body.deviceModel || null,
      osVersion: req.body.osVersion || null,
      appVersion: req.body.appVersion || null,

      // Tracking Modes
      forceCheckEnabled: req.body.forceCheckEnabled || null,
      realtimeMode: req.body.realtimeMode || null,
      emergencyMode: req.body.emergencyMode || null,

      // Movement
      movementStatus: req.body.movementStatus || 'unknown',

      // Metadata
      androidLocalId: req.body.id || null,
      metadata: req.body.metadata || null,

      // Acquisition Info
      acquisitionDurationMs: req.body.acquisitionDurationMs || null,
      gpsRetryAttempts: req.body.gpsRetryAttempts || null,

      // Priority & Interval
      priority: req.body.priority || null,
      intervalAppliedMs: req.body.intervalAppliedMs || null,
      intervalType: req.body.intervalType || null
    };

    // Store location and update user profile in parallel
    console.log('📍 Saving location to Firestore...');
    console.log('   User ID:', req.user.uid);
    console.log('   Lat:', locationData.latitude, 'Lon:', locationData.longitude);

    const locationRef = await app.firestore().collection('locations').add(locationData);

    console.log('✅ Location saved with ID:', locationRef.id);

    await Promise.all([
      // Update user current location
      app.firestore().collection('users').doc(req.user.uid).update({
        currentLocation: {
          latitude: parseFloat(req.body.latitude),
          longitude: parseFloat(req.body.longitude),
          lastUpdated: firebaseAdmin.firestore.FieldValue.serverTimestamp()
        }
      }).catch(err => console.log('User update error:', err)),


      // Update location tracking state for FCM resurrection system
      // This is critical for detecting silent users
      // NOTE: Do NOT set fcmWakeState here - realtime status comes from device_status collection
      app.firestore().collection('locationTracking').doc(req.user.uid).set({
        userId: req.user.uid,
        lastLocationUploadTime: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
        lastLocationId: locationRef.id,
        lastLatitude: parseFloat(req.body.latitude),
        lastLongitude: parseFloat(req.body.longitude)
      }, { merge: true }).catch(err => console.log('Location tracking state update error:', err))
    ]);

    // Return response with locationId (Android expects this)
    res.status(200).json({
      success: true,
      message: 'Location uploaded successfully',
      locationId: locationRef.id,
      timestamp: Date.now()
    });

  } catch (error) {
    console.error('Location upload error:', error);
    next(error);
  }
});

// POST /api/location - Legacy endpoint (keep for backward compatibility)
router.post('/', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();

    // Joi validation
    const { error, value } = locationSchema.validate({
      ...req.body,
      userId: req.user.uid,
      serverTimestamp: new Date().toISOString(),
      createdAt: new Date().toISOString()
    }, { presence: 'required', abortEarly: true });
    if (error) {
      return res.status(400).json({ success: false, message: error.details[0].message });
    }

    // Create location record with enhanced metadata
    const locationData = {
      userId: req.user.uid,
      latitude: parseFloat(req.body.latitude),
      longitude: parseFloat(req.body.longitude),
      accuracy: req.body.accuracy || null,
      provider: req.body.provider || 'unknown',
      batteryLevel: req.body.batteryLevel || null,
      networkQuality: req.body.networkQuality || null,
      movementStatus: req.body.movementStatus || 'unknown',
      clientTimestamp: req.body.timestamp ? new Date(req.body.timestamp) : null,
      serverTimestamp: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    // Store location and update user profile in parallel for better performance
    await Promise.all([
      app.firestore().collection('locations').add(locationData),
      app.firestore().collection('users').doc(req.user.uid).update({
        currentLocation: {
          latitude: parseFloat(req.body.latitude),
          longitude: parseFloat(req.body.longitude),
          lastUpdated: firebaseAdmin.firestore.FieldValue.serverTimestamp()
        }
      })
    ]);

    res.status(200).json({
      success: true,
      message: 'Location updated successfully'
    });

  } catch (error) {
    next(error);
  }
});

// POST /api/location/bulk - Bulk location upload (for offline sync)
router.post('/bulk', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { locations } = req.body;

    if (!Array.isArray(locations) || locations.length === 0) {
      return res.status(400).json({
        success: false,
        message: 'Locations array is required and must not be empty'
      });
    }

    if (locations.length > 100) {
      return res.status(400).json({
        success: false,
        message: 'Cannot upload more than 100 locations at once'
      });
    }

    // Process locations in batch
    const batch = app.firestore().batch();
    const locationsRef = app.firestore().collection('locations');
    let successCount = 0;
    let latestLocation = null;

    for (const loc of locations) {
      // Validate each location
      if (!loc.latitude || !loc.longitude) {
        continue;
      }

      const locationData = {
        userId: req.user.uid,
        latitude: parseFloat(loc.latitude),
        longitude: parseFloat(loc.longitude),
        accuracy: loc.accuracy || null,
        provider: loc.provider || 'unknown',
        batteryLevel: loc.batteryLevel || null,
        networkQuality: loc.networkQuality || null,
        movementStatus: loc.movementStatus || 'unknown',
        clientTimestamp: loc.timestamp ? new Date(loc.timestamp) : null,
        serverTimestamp: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
        createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      };

      const newDocRef = locationsRef.doc();
      batch.set(newDocRef, locationData);
      successCount++;

      // Track latest location
      if (!latestLocation || (loc.timestamp && loc.timestamp > (latestLocation.timestamp || 0))) {
        latestLocation = loc;
      }
    }

    // Update user's current location with the latest
    if (latestLocation) {
      const userRef = app.firestore().collection('users').doc(req.user.uid);
      batch.update(userRef, {
        currentLocation: {
          latitude: parseFloat(latestLocation.latitude),
          longitude: parseFloat(latestLocation.longitude),
          lastUpdated: firebaseAdmin.firestore.FieldValue.serverTimestamp()
        }
      });
    }

    // Commit batch
    await batch.commit();

    res.status(200).json({
      success: true,
      message: `Successfully uploaded ${successCount} location(s)`,
      data: {
        uploaded: successCount,
        total: locations.length
      }
    });

  } catch (error) {
    console.error('Bulk upload error:', error);
    next(error);
  }
});

// GET /api/location/interval - --Get location update interval for current user
router.get('/interval', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const userDoc = await app.firestore().collection('users').doc(req.user.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const profile = userDoc.data();
    const interval = profile.locationUpdateInterval || 300; // Default 5 minutes

    res.status(200).json({
      success: true,
      message: 'Location update interval retrieved successfully',
      data: {
        intervalSeconds: interval,
        intervalMinutes: Math.floor(interval / 60)
      }
    });

  } catch (error) {
    next(error);
  }
});

// PUT /api/location/interval - Update location interval (admin use)
router.put('/interval', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { intervalSeconds } = req.body;

    if (!intervalSeconds || intervalSeconds < 60) {
      return res.status(400).json({
        success: false,
        message: 'Interval must be at least 60 seconds'
      });
    }

    if (intervalSeconds > 3600) {
      return res.status(400).json({
        success: false,
        message: 'Interval cannot exceed 1 hour (3600 seconds)'
      });
    }

    await app.firestore().collection('users').doc(req.user.uid).update({
      locationUpdateInterval: parseInt(intervalSeconds),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    res.status(200).json({
      success: true,
      message: 'Location update interval updated successfully',
      data: {
        intervalSeconds: parseInt(intervalSeconds),
        intervalMinutes: Math.floor(intervalSeconds / 60)
      }
    });

  } catch (error) {
    next(error);
  }
});

// GET /api/location/history - Get user's location history
router.get('/history', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { limit = 50, startDate, endDate } = req.query;

    let query = app.firestore()
      .collection('locations')
      .where('userId', '==', req.user.uid)
      .orderBy('serverTimestamp', 'desc')
      .limit(Math.min(parseInt(limit), 100));

    // Add date filters if provided
    if (startDate) {
      query = query.where('serverTimestamp', '>=', new Date(startDate));
    }

    if (endDate) {
      query = query.where('serverTimestamp', '<=', new Date(endDate));
    }

    const snapshot = await query.get();
    
    const locations = snapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    }));

    res.status(200).json({
      success: true,
      message: 'Location history retrieved successfully',
      data: {
        locations,
        count: locations.length
      }
    });

  } catch (error) {
    next(error);
  }
});

// GET /api/location/current - Get current location of user
router.get('/current', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const userDoc = await app.firestore().collection('users').doc(req.user.uid).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const profile = userDoc.data();
    const currentLocation = profile.currentLocation;

    if (!currentLocation) {
      return res.status(404).json({
        success: false,
        message: 'No location data available'
      });
    }

    res.status(200).json({
      success: true,
      message: 'Current location retrieved successfully',
      data: currentLocation
    });

  } catch (error) {
    next(error);
  }
});

// POST /api/location/batch - Upload multiple location updates
router.post('/batch', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { locations } = req.body;

    if (!Array.isArray(locations) || locations.length === 0) {
      return res.status(400).json({
        success: false,
        message: 'Locations array is required and cannot be empty'
      });
    }

    if (locations.length > 50) {
      return res.status(400).json({
        success: false,
        message: 'Cannot upload more than 50 locations at once'
      });
    }

    const batch = app.firestore().batch();
    const timestamp = firebaseAdmin.firestore.FieldValue.serverTimestamp();

    let lastLocation = null;

    locations.forEach((location, index) => {
      if (!location.latitude || !location.longitude) {
        throw new Error(`Location ${index + 1}: Latitude and longitude are required`);
      }

      const locationData = {
        userId: req.user.uid,
        latitude: parseFloat(location.latitude),
        longitude: parseFloat(location.longitude),
        accuracy: location.accuracy || null,
        provider: location.provider || 'unknown',
        clientTimestamp: location.timestamp ? new Date(location.timestamp) : null,
        serverTimestamp: timestamp,
        createdAt: timestamp
      };

      const locationRef = app.firestore().collection('locations').doc();
      batch.set(locationRef, locationData);

      // Keep track of the most recent location
      if (!lastLocation || (location.timestamp && location.timestamp > lastLocation.timestamp)) {
        lastLocation = {
          latitude: locationData.latitude,
          longitude: locationData.longitude,
          lastUpdated: timestamp
        };
      }
    });

    // Update user's current location with the most recent one
    if (lastLocation) {
      const userRef = app.firestore().collection('users').doc(req.user.uid);
      batch.update(userRef, {
        currentLocation: lastLocation,
        updatedAt: timestamp
      });
    }

    await batch.commit();

    res.status(201).json({
      success: true,
      message: `Successfully uploaded ${locations.length} location updates`,
      data: {
        uploadedCount: locations.length,
        lastLocation: lastLocation
      }
    });

  } catch (error) {
    next(error);
  }
});

// ============================================================================
// ADMIN ENDPOINTS FOR LOCATION VIEWING
// ============================================================================

// GET /api/location/user/:userId - Get location history for a specific user (Admin)
router.get('/user/:userId', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { userId } = req.params;
    const { limit = 100 } = req.query;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'User ID is required'
      });
    }

    const snapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .orderBy('createdAt', 'desc')
      .limit(Math.min(parseInt(limit), 500))
      .get();

    const locations = snapshot.docs.map(doc => {
      const data = doc.data();
      return {
        id: doc.id,
        latitude: data.latitude,
        longitude: data.longitude,
        accuracy: data.accuracy,
        provider: data.provider,
        batteryLevel: data.batteryLevel,
        speed: data.speed,
        timestamp: data.timestamp,
        clientTimestamp: data.clientTimestamp,
        createdAt: data.createdAt,
        uploadedAt: data.uploadedAt
      };
    });

    res.status(200).json({
      success: true,
      message: 'User locations retrieved successfully',
      locations,
      count: locations.length
    });

  } catch (error) {
    console.error('Get user locations error:', error);
    next(error);
  }
});

// GET /api/location/user/:userId/range - Get user locations within date range (Admin)
router.get('/user/:userId/range', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { userId } = req.params;
    const { startDate, endDate, limit = 500 } = req.query;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'User ID is required'
      });
    }

    console.log(`[Location Range] User: ${userId}, Start: ${startDate}, End: ${endDate}`);

    // Use Firestore query to filter by timestamp directly
    const startMs = startDate ? new Date(startDate).getTime() : 0;
    const endMs = endDate ? new Date(endDate).getTime() : Date.now() + 86400000;

    console.log(`[Location Range] User: ${userId}, Start: ${startDate}, End: ${endDate}`);
    console.log(`[Location Range] Querying Firestore for timestamp: ${startMs} to ${endMs}`);

    let query = app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .where('timestamp', '>=', startMs)
      .where('timestamp', '<=', endMs)
      .orderBy('timestamp', 'asc');

    const snapshot = await query.get();
    console.log(`[Location Range] Firestore returned: ${snapshot.docs.length} locations`);

    const locations = snapshot.docs.map(doc => {
      const data = doc.data();
      return {
        id: doc.id,
        latitude: data.latitude,
        longitude: data.longitude,
        accuracy: data.accuracy,
        provider: data.provider,
        batteryLevel: data.batteryLevel,
        speed: data.speed,
        altitude: data.altitude,
        bearing: data.bearing,
        timestamp: data.timestamp,
        clientTimestamp: data.clientTimestamp,
        createdAt: data.createdAt,
        uploadedAt: data.uploadedAt
      };
    });

    res.status(200).json({
      success: true,
      message: 'User locations retrieved successfully',
      data: { locations },
      count: locations.length,
      dateRange: {
        start: startDate || 'not specified',
        end: endDate || 'not specified'
      }
    });

  } catch (error) {
    console.error('Get user locations by range error:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch locations',
      error: error.message
    });
  }
});

// GET /api/location/user/:userId/latest - Get latest location for a user (Admin)
router.get('/user/:userId/latest', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { userId } = req.params;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'User ID is required'
      });
    }

    // First try to get from user profile
    const userDoc = await app.firestore().collection('users').doc(userId).get();

    if (userDoc.exists && userDoc.data().currentLocation) {
      return res.status(200).json({
        success: true,
        message: 'Latest location retrieved from profile',
        location: userDoc.data().currentLocation,
        source: 'profile'
      });
    }

    // Fallback to latest from locations collection
    const snapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .orderBy('createdAt', 'desc')
      .limit(1)
      .get();

    if (snapshot.empty) {
      return res.status(404).json({
        success: false,
        message: 'No location found for this user'
      });
    }

    const doc = snapshot.docs[0];
    const data = doc.data();

    res.status(200).json({
      success: true,
      message: 'Latest location retrieved',
      location: {
        id: doc.id,
        latitude: data.latitude,
        longitude: data.longitude,
        accuracy: data.accuracy,
        provider: data.provider,
        createdAt: data.createdAt,
        uploadedAt: data.uploadedAt
      },
      source: 'locations'
    });

  } catch (error) {
    console.error('Get latest location error:', error);
    next(error);
  }
});

// GET /api/location/user/:userId/stats - Get location statistics for a user (Admin)
router.get('/user/:userId/stats', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { userId } = req.params;

    if (!userId) {
      return res.status(400).json({
        success: false,
        message: 'User ID is required'
      });
    }

    // Get total count
    const totalSnapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .count()
      .get();

    const totalCount = totalSnapshot.data().count;

    // Get last 24 hours count
    const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000);
    const last24hSnapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .where('createdAt', '>=', oneDayAgo)
      .count()
      .get();

    const last24hCount = last24hSnapshot.data().count;

    // Get latest and earliest
    const latestSnapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .orderBy('createdAt', 'desc')
      .limit(1)
      .get();

    const earliestSnapshot = await app.firestore()
      .collection('locations')
      .where('userId', '==', userId)
      .orderBy('createdAt', 'asc')
      .limit(1)
      .get();

    const latestLocation = latestSnapshot.empty ? null : latestSnapshot.docs[0].data();
    const earliestLocation = earliestSnapshot.empty ? null : earliestSnapshot.docs[0].data();

    res.status(200).json({
      success: true,
      message: 'Location statistics retrieved',
      stats: {
        totalLocations: totalCount,
        last24Hours: last24hCount,
        latestTimestamp: latestLocation?.createdAt || null,
        earliestTimestamp: earliestLocation?.createdAt || null,
        latestCoords: latestLocation ? {
          lat: latestLocation.latitude,
          lng: latestLocation.longitude
        } : null
      }
    });

  } catch (error) {
    console.error('Get location stats error:', error);
    next(error);
  }
});

// POST /api/location/admin/multiple - Get locations for multiple users (Admin)
router.post('/admin/multiple', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { userIds, limit = 50 } = req.body;

    if (!Array.isArray(userIds) || userIds.length === 0) {
      return res.status(400).json({
        success: false,
        message: 'userIds array is required'
      });
    }

    if (userIds.length > 20) {
      return res.status(400).json({
        success: false,
        message: 'Cannot query more than 20 users at once'
      });
    }

    const results = {};

    await Promise.all(userIds.map(async (userId) => {
      const snapshot = await app.firestore()
        .collection('locations')
        .where('userId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(parseInt(limit))
        .get();

      results[userId] = snapshot.docs.map(doc => ({
        id: doc.id,
        latitude: doc.data().latitude,
        longitude: doc.data().longitude,
        createdAt: doc.data().createdAt
      }));
    }));

    res.status(200).json({
      success: true,
      message: 'Multiple user locations retrieved',
      results
    });

  } catch (error) {
    console.error('Get multiple users locations error:', error);
    next(error);
  }
});

// GET /api/location/admin/recent - Get all recent locations (Admin)
router.get('/admin/recent', authenticateToken, async (req, res, next) => {
  try {
    const app = getFirebaseAdmin();
    const { limit = 100, hoursAgo = 24 } = req.query;

    const cutoffTime = new Date(Date.now() - parseInt(hoursAgo) * 60 * 60 * 1000);

    const snapshot = await app.firestore()
      .collection('locations')
      .where('createdAt', '>=', cutoffTime)
      .orderBy('createdAt', 'desc')
      .limit(Math.min(parseInt(limit), 500))
      .get();

    const locations = snapshot.docs.map(doc => ({
      id: doc.id,
      userId: doc.data().userId,
      latitude: doc.data().latitude,
      longitude: doc.data().longitude,
      createdAt: doc.data().createdAt
    }));

    res.status(200).json({
      success: true,
      message: 'Recent locations retrieved',
      locations,
      count: locations.length,
      hoursAgo: parseInt(hoursAgo)
    });

  } catch (error) {
    console.error('Get recent locations error:', error);
    next(error);
  }
});

module.exports = router;