require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { initializeFirebase, testFirebaseConnection } = require('./config/firebase');

// Initialize Firebase first
console.log('🚀 Starting Real Pakistan Backend API...');
console.log(`📅 ${new Date().toISOString()}`);
console.log(`🌍 Environment: ${process.env.NODE_ENV || 'development'}`);

try {
  initializeFirebase();
} catch (error) {
  console.error('❌ Failed to initialize Firebase:', error.message);
  process.exit(1);
}

const authRoutes = require('./routes/auth');
const profileRoutes = require('./routes/profile');
const jobsRoutes = require('./routes/jobs');
const locationRoutes = require('./routes/location');
const locationTrackingSettingsRoutes = require('./routes/location-tracking-settings');
const inboxRoutes = require('./routes/inbox');
const ordersRoutes = require('./routes/orders');
const earningsRoutes = require('./routes/earnings');
const reviewsRoutes = require('./routes/reviews');
const uploadRoutes = require('./routes/upload');
const withdrawalsRoutes = require('./routes/withdrawals');
const notificationsRoutes = require('./routes/notifications');
const emailRoutes = require('./routes/email');
const fcmRoutes = require('./routes/fcm');
const errorHandler = require('./middlewares/errorHandler');

// Import cron jobs service
const { initializeCronJobs } = require('./services/cronJobs');

// Import presence monitor for real-time online/offline detection
const { startPresenceMonitoring, shutdownPresenceMonitoring } = require('./services/presenceMonitor');

// Import recovery scheduler shutdown and restore (Firestore + in-memory timers, no Redis)
const { shutdown: shutdownRecoveryScheduler, restoreFromFirestore: restoreRecoveryJobs } = require('./services/recoveryScheduler');

const app = express();
const PORT = process.env.PORT || 5000;

// Security middleware
app.use(helmet({
  crossOriginResourcePolicy: { policy: "cross-origin" }
}));

// CORS configuration
app.use(cors({
  origin: process.env.ALLOWED_ORIGINS ? process.env.ALLOWED_ORIGINS.split(',') : ['http://localhost:3000', 'http://localhost:8080'],
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Requested-With']
}));

// Rate limiting middleware - adjusted for mobile apps
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 300, // Increased from 100 to 300 for mobile apps
  message: 'Too many requests from this IP, please try again later.',
  standardHeaders: true,
  legacyHeaders: false,
  skip: (req) => {
    // Skip rate limiting for admin routes
    return req.path.includes('/admin/');
  }
});
app.use(limiter);

// Body parsing middleware
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

// API Routes
app.use('/api/auth', authRoutes);
app.use('/api/profile', profileRoutes);
app.use('/api/jobs', jobsRoutes);
app.use('/api/location', locationRoutes);
app.use('/api/location-tracking-settings', locationTrackingSettingsRoutes);
app.use('/api/inbox', inboxRoutes);
app.use('/api/orders', ordersRoutes);
app.use('/api/earnings', earningsRoutes);
app.use('/api/reviews', reviewsRoutes);
app.use('/api/withdrawals', withdrawalsRoutes);
app.use('/api/notifications', notificationsRoutes);
app.use('/api/email', emailRoutes);
app.use('/api/fcm', fcmRoutes);
app.use('/api', uploadRoutes);

// Health check endpoint with Supabase ping to keep it alive
app.get('/health', async (req, res) => {
  try {
    console.log('🏥 Health check requested at:', new Date().toISOString());

    const healthResponse = {
      status: 'OK',
      message: 'Construction App API is running',
      timestamp: new Date().toISOString(),
      supabase: 'not_checked'
    };

    // Ping Supabase to keep it alive
    if (process.env.SUPABASE_URL && process.env.SUPABASE_KEY) {
      try {
        console.log('🔄 Attempting to ping Supabase...');
        console.log('🔗 Supabase URL:', process.env.SUPABASE_URL);

        const { createClient } = require('@supabase/supabase-js');
        const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY);

        // Ping database
        console.log('📊 Pinging Supabase database...');
        const dbStart = Date.now();
        const { data: dbData, error: dbError } = await supabase.from('users').select('uid').limit(1);
        const dbTime = Date.now() - dbStart;

        if (dbError) {
          console.error('❌ Database ping failed:', dbError.message);
        } else {
          console.log(`✅ Database ping successful in ${dbTime}ms, found ${dbData?.length || 0} records`);
        }

        // Ping storage
        console.log('💾 Pinging Supabase storage...');
        const storageStart = Date.now();
        const { data: buckets, error: storageError } = await supabase.storage.listBuckets();
        const storageTime = Date.now() - storageStart;

        if (storageError) {
          console.error('❌ Storage ping failed:', storageError.message);
        } else {
          console.log(`✅ Storage ping successful in ${storageTime}ms, found ${buckets?.length || 0} buckets`);
        }

        // Set response based on results
        if (dbError && storageError) {
          healthResponse.supabase = 'error';
          healthResponse.supabaseMessage = 'Both database and storage ping failed';
          healthResponse.dbError = dbError.message;
          healthResponse.storageError = storageError.message;
        } else if (dbError || storageError) {
          healthResponse.supabase = 'partial';
          healthResponse.supabaseMessage = 'One ping failed but service is running';
          if (dbError) healthResponse.dbError = dbError.message;
          if (storageError) healthResponse.storageError = storageError.message;
        } else {
          healthResponse.supabase = 'active';
          healthResponse.supabaseMessage = 'Database and storage pinged successfully';
          healthResponse.dbResponseTime = `${dbTime}ms`;
          healthResponse.storageResponseTime = `${storageTime}ms`;
          healthResponse.dbRecords = dbData?.length || 0;
          healthResponse.storageBuckets = buckets?.length || 0;
        }

        console.log('🏓 Supabase ping completed:', healthResponse.supabase);
      } catch (supabaseError) {
        console.error('❌ Supabase ping exception:', supabaseError.message);
        healthResponse.supabase = 'error';
        healthResponse.supabaseError = supabaseError.message;
      }
    } else {
      console.warn('⚠️ Supabase credentials not found in environment variables');
      healthResponse.supabase = 'not_configured';
      healthResponse.supabaseMessage = 'Supabase credentials not found';
    }

    console.log('🏥 Health check completed:', healthResponse.supabase);
    res.status(200).json(healthResponse);
  } catch (error) {
    console.error('❌ Health check error:', error);
    res.status(500).json({
      status: 'ERROR',
      message: 'Health check failed',
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

// Dedicated Supabase keep-alive endpoint
// Can be called by external monitoring services like UptimeRobot, cron-job.org, etc.
app.get('/keep-alive', async (req, res) => {
  console.log('🔄 Keep-alive endpoint called');

  try {
    const { keepSupabaseAlive } = require('./services/cronJobs');
    const result = await keepSupabaseAlive();

    res.status(200).json({
      status: 'OK',
      message: 'Keep-alive ping completed',
      supabase: result.success ? 'pinged' : 'error',
      timestamp: new Date().toISOString(),
      details: result
    });
  } catch (error) {
    console.error('❌ Keep-alive error:', error);
    res.status(500).json({
      status: 'ERROR',
      message: 'Keep-alive failed',
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

// 404 handler
app.use('*', (req, res) => {
  res.status(404).json({
    success: false,
    message: 'API endpoint not found'
  });
});

// Global error handler
app.use(errorHandler);

// Start server with proper error handling
const startServer = async () => {
  try {
    // Test Firebase connection before starting server
    const connectionTest = await testFirebaseConnection();
    if (!connectionTest) {
      console.warn('⚠️  Firebase connection test failed, but starting server anyway...');
    }

    app.listen(PORT, async () => {
      console.log(`✅ Server is running on port ${PORT}`);
      console.log(`📍 Health check: http://localhost:${PORT}/health`);
      console.log(`🔥 Firebase initialized successfully`);
      console.log(`💾 Supabase configured for file uploads`);
      console.log(`📧 Email service ready (Resend)`);
      console.log(`🌟 API is ready to accept requests!`);

      // Initialize cron jobs for automated emails
      try {
        initializeCronJobs();
        console.log(`⏰ Cron jobs initialized for email automation`);
      } catch (cronError) {
        console.error('⚠️ Failed to initialize cron jobs:', cronError.message);
      }

      // Restore any pending recovery timers from Firestore (on server restart)
      try {
        await restoreRecoveryJobs();
        console.log(`🔄 Recovery jobs restored from Firestore`);
      } catch (restoreError) {
        console.error('⚠️ Failed to restore recovery jobs:', restoreError.message);
      }

      // Start presence monitoring — real-time online/offline detection for all users
      try {
        await startPresenceMonitoring();
        console.log(`👁️ Presence monitoring started for all users`);
      } catch (presenceError) {
        console.error('⚠️ Failed to start presence monitoring:', presenceError.message);
      }
    });
  } catch (error) {
    console.error('❌ Failed to start server:', error.message);
    process.exit(1);
  }
};

startServer();

// Graceful shutdown — clean up presence monitor and recovery timers on server stop
const gracefulShutdown = async (signal) => {
  console.log(`\n📛 Received ${signal}. Shutting down gracefully...`);
  try {
    shutdownPresenceMonitoring();
    await shutdownRecoveryScheduler();
  } catch (err) {
    console.error('Error during shutdown:', err);
  }
  process.exit(0);
};

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

module.exports = app;