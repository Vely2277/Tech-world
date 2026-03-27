const express = require('express');
const { getFirebaseAdmin } = require('../config/firebase');
const { authenticateToken, optionalAuth } = require('../middlewares/authMiddleware');
const { jobSchema } = require('../models/schema');
const { sendPushNotification } = require('../controllers/notificationController');
const router = express.Router();

// POST /api/jobs - Create a new job (any authenticated user can post)
router.post('/', authenticateToken, async (req, res, next) => {
  try {
    // Get user profile
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(req.user.uid).get();
    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User profile not found'
      });
    }

    const userProfile = userDoc.data();

    // Create job document to match your database structure
    const jobData = {
      title: req.body.title,
      description: req.body.description,
      price: req.body.price,
      location: req.body.location,
      imageUrls: req.body.imageUrls || [],
      uid: req.user.uid, // Using UID to match your structure
      datePosted: admin.firestore.FieldValue.serverTimestamp()
    };

    const jobRef = await admin.firestore().collection('jobs').add(jobData);

    res.status(201).json({
      success: true,
      message: 'Job created successfully',
      data: {
        jobId: jobRef.id,
        ...jobData
      }
    });

  } catch (error) {
    next(error);
  }
});

// GET /api/jobs - Get all jobs with filtering
router.get('/', optionalAuth, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const snapshot = await admin.firestore().collection('jobs').get();
    const jobs = snapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    }));
    res.status(200).json({
      success: true,
      message: 'Jobs retrieved successfully',
      data: {
        jobs
      }
    });

  } catch (error) {
    next(error);
  }
});

// GET /api/jobs/:id - Get specific job details
router.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const jobDoc = await admin.firestore().collection('jobs').doc(req.params.id).get();

    if (!jobDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Job not found'
      });
    }

    const jobData = {
      id: jobDoc.id,
      ...jobDoc.data()
    };

    res.status(200).json({
      success: true,
      message: 'Job details retrieved successfully',
      data: jobData
    });

  } catch (error) {
    next(error);
  }
});

// PUT /api/jobs/:id - Update job (owner only)
router.put('/:id', authenticateToken, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const jobDoc = await admin.firestore().collection('jobs').doc(req.params.id).get();

    if (!jobDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Job not found'
      });
    }

    const jobData = jobDoc.data();

    // Check ownership using uid field
    if (jobData.uid !== req.user.uid) {
      return res.status(403).json({
        success: false,
        message: 'You can only update your own jobs'
      });
    }

    const allowedUpdates = ['title', 'description', 'price', 'location', 'imageUrls'];
    const updates = {};

    Object.keys(req.body).forEach(key => {
      if (allowedUpdates.includes(key) && req.body[key] !== undefined) {
        updates[key] = req.body[key];
      }
    });

    if (Object.keys(updates).length === 0) {
      return res.status(400).json({
        success: false,
        message: 'No valid fields to update'
      });
    }

    await admin.firestore().collection('jobs').doc(req.params.id).update(updates);

    res.status(200).json({
      success: true,
      message: 'Job updated successfully',
      data: updates
    });

  } catch (error) {
    next(error);
  }
});

// DELETE /api/jobs/:id - Delete job (owner only)
router.delete('/:id', authenticateToken, async (req, res, next) => {
  try {
    const admin = getFirebaseAdmin();
    const jobDoc = await admin.firestore().collection('jobs').doc(req.params.id).get();

    if (!jobDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Job not found'
      });
    }

    const jobData = jobDoc.data();

    // Check ownership using uid field
    if (jobData.uid !== req.user.uid) {
      return res.status(403).json({
        success: false,
        message: 'You can only delete your own jobs'
      });
    }

    await admin.firestore().collection('jobs').doc(req.params.id).delete();

    res.status(200).json({
      success: true,
      message: 'Job deleted successfully'
    });

  } catch (error) {
    next(error);
  }
});

// GET /api/jobs/user/:userId - Get all jobs created by a specific user
router.get('/user/:userId', authenticateToken, async (req, res, next) => {
  try {
    const { userId } = req.params;
    const admin = getFirebaseAdmin();

    console.log(`Fetching jobs for userId: ${userId}`);

    // Verify the user exists
    const userDoc = await admin.firestore().collection('users').doc(userId).get();
    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User not found'
      });
    }

    // Get all jobs created by this user using uid field
    const snapshot = await admin.firestore()
      .collection('jobs')
      .where('uid', '==', userId)
      .get();

    const jobs = snapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    }));

    console.log(`Found ${jobs.length} jobs for user ${userId}`);

    // Sort jobs by datePosted in JavaScript
    jobs.sort((a, b) => {
      const dateA = a.datePosted?._seconds || 0;
      const dateB = b.datePosted?._seconds || 0;
      return dateB - dateA; // Descending order (newest first)
    });

    res.status(200).json({
      success: true,
      message: 'User jobs retrieved successfully',
      data: {
        jobs,
        totalJobs: jobs.length
      }
    });

  } catch (error) {
    console.error('Error fetching user jobs:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch user jobs',
      error: error.message
    });
  }
});

// POST /api/jobs/:jobId/assign - Assign a job to a specific worker
router.post('/:jobId/assign', authenticateToken, async (req, res, next) => {
  try {
    const { jobId } = req.params;
    const { workerId, workerName } = req.body;
    const admin = getFirebaseAdmin();

    if (!workerId || !workerName) {
      return res.status(400).json({
        success: false,
        message: 'workerId and workerName are required'
      });
    }

    // Get the job
    const jobDoc = await admin.firestore().collection('jobs').doc(jobId).get();
    if (!jobDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Job not found'
      });
    }

    const jobData = jobDoc.data();

    // Check if the current user is the job owner using uid field
    if (jobData.uid !== req.user.uid) {
      return res.status(403).json({
        success: false,
        message: 'You can only assign your own jobs'
      });
    }

    // Verify worker exists
    const workerDoc = await admin.firestore().collection('users').doc(workerId).get();
    if (!workerDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Worker not found'
      });
    }

    // Create order to match your existing orders structure
    const orderData = {
      jobId: jobId,
      sellerId: workerId, // Keep sellerId to match your existing orders structure
      uid: req.user.uid, // Job poster's uid to match your existing orders structure
      price: jobData.price, // Use price field to match your existing structure
      status: 'active',
      paymentStatus: 'pending', // Add paymentStatus to match your structure
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };

    // Create the order
    const orderRef = await admin.firestore().collection('orders').add(orderData);

    // Get job poster's name for notification
    let posterName = 'Someone';
    try {
      const posterDoc = await admin.firestore().collection('users').doc(req.user.uid).get();
      if (posterDoc.exists) {
        const posterData = posterDoc.data();
        posterName = posterData.name ||
          `${posterData.firstName || ''} ${posterData.lastName || ''}`.trim() ||
          'Someone';
      }
    } catch (profileError) {
      console.error('Error fetching poster profile:', profileError);
    }

    // Send push notification to the worker (async, don't wait)
    setImmediate(async () => {
      try {
        const notificationData = {
          type: 'job_assigned',
          title: '🎉 New Job Assigned!',
          body: `${posterName} has assigned you the job: "${jobData.title}"`,
          senderName: posterName,
          senderId: req.user.uid,
          jobId: jobId,
          orderId: orderRef.id,
          jobTitle: jobData.title,
          jobPrice: jobData.price?.toString() || '',
          timestamp: new Date().toISOString()
        };

        await sendPushNotification(workerId, notificationData);
        console.log(`📬 Job assignment notification sent to worker: ${workerId}`);
      } catch (notifError) {
        console.error('Error sending job assignment notification:', notifError);
      }
    });

    res.status(201).json({
      success: true,
      message: 'Job assigned successfully and order created',
      data: {
        orderId: orderRef.id,
        jobId: jobId,
        workerId: workerId,
        workerName: workerName,
        status: 'active'
      }
    });

  } catch (error) {
    next(error);
  }
});


module.exports = router;
