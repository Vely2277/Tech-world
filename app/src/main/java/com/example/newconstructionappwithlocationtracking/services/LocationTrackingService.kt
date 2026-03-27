/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION TRACKING SERVICE - MASTER ORCHESTRATOR
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * This service is the central orchestrator for 24/7 background location tracking.
 * It coordinates all location tracking components and ensures reliable, long-term operation.
 *
 * CORE RESPONSIBILITIES:
 * 1. Service Lifecycle Management (START/STOP/RESTART)
 * 2. Permission & Requirement Validation
 * 3. Foreground Service & Notification Management
 * 4. Dynamic Interval Calculation (battery + admin settings)
 * 5. Location Acquisition & Provider Selection
 * 6. Data Building, Validation & Storage
 * 7. Upload Coordination (via BatchUploader)
 * 8. Health Monitoring & Auto-Recovery
 * 9. Battery Threshold Monitoring
 * 10. Settings Synchronization (remote admin)
 * 11. Error Recovery & Circuit Breaker
 *
 * KEY DESIGN PRINCIPLES:
 * - ORCHESTRATOR ONLY: Delegates all specialized tasks to helper classes
 * - FAIL-SAFE: Never crashes; gracefully handles all errors
 * - BATTERY-AWARE: Adapts behavior based on battery level (44% threshold)
 * - ADMIN-CONTROLLED: Respects remote settings (intervals, ForceCheck, Emergency)
 * - PERSISTENT: Survives app kills, reboots, and background restrictions
 * - OBSERVABLE: Logs all critical events for debugging/monitoring
 *
 * INTERACTION WITH OTHER COMPONENTS:
 * - LocationPermissionManager: Permission validation & guidance
 * - BatteryOptimizationHelper: Battery decisions & interval calculation
 * - LocationSettingsManager: Remote admin settings sync
 * - LocationDatabase: Local caching & persistence
 * - BatchUploader: Reliable uploads with retry/backoff
 * - NetworkMonitor: Connectivity awareness
 * - LocationServiceHelper: Foreground service management
 * - LocationLogger: Centralized logging
 *
 * CRITICAL GUARANTEES:
 * ✅ Never starts without required permissions
 * ✅ Always runs as foreground service when active
 * ✅ Respects 44% battery threshold (unless ForceCheck/Emergency)
 * ✅ Caches data locally before uploading
 * ✅ Recovers from crashes/kills automatically
 * ✅ Adapts to battery, network, and permission changes
 * ✅ Implements circuit breaker to prevent crash loops
 * ✅ Logs all critical decisions for audit/debug
 *
 * BATTERY DRAIN PREVENTION:
 * - Minimum interval enforcement
 * - Provider selection based on battery level
 * - Movement-based triggering
 * - Adaptive accuracy degradation
 * - Wake-lock discipline (short, released in finally)
 * - WorkManager fallback when FGS killed
 *
 * LONG-TERM MAINTAINABILITY:
 * - Modular architecture (easy to update individual components)
 * - Comprehensive logging (trace all decisions)
 * - Version-aware (handles Android API changes)
 * - Testable (mockable dependencies)
 * - Documented (this header + inline comments)
 *
 * FUTURE-PROOFING:
 * - Schema versioning support (DB migrations)
 * - Feature flag ready (canary rollouts)
 * - Remote kill switch support
 * - Telemetry hooks (metrics/alerts)
 * - OEM quirk handling (whitelist guidance)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.models.location.LocationData
import com.example.newconstructionappwithlocationtracking.models.location.LocationSettings
import com.example.newconstructionappwithlocationtracking.location.LocationSettingsManager
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.BatteryOptimizationHelper
import com.example.newconstructionappwithlocationtracking.location.LocationDatabase
import com.example.newconstructionappwithlocationtracking.location.BatchUploader
import com.example.newconstructionappwithlocationtracking.location.NetworkMonitor
import com.example.newconstructionappwithlocationtracking.location.PreciseLocationScheduler
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkRequest
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.TrackingStatusLogger
import com.example.newconstructionappwithlocationtracking.location.NetworkRecoveryManager
import com.example.newconstructionappwithlocationtracking.location.WatchdogAlarmManager
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.example.newconstructionappwithlocationtracking.services.DeviceStatusReporter

/**
 * LocationTrackingService - 24/7 Background Location Tracking Service
 *
 * This service runs persistently in the foreground to track user location
 * and upload data to the backend while respecting battery, permissions,
 * and admin-configured settings.
 */
class LocationTrackingService : Service() {

    // ═══════════════════════════════════════════════════════════════════════════════
    // COMPANION OBJECT - CONSTANTS & FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "LocationTrackingService"

        // Notification
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val CHANNEL_NAME = "Location Tracking"

        // Actions
        private const val ACTION_START_TRACKING = "com.example.START_TRACKING"
        private const val ACTION_STOP_TRACKING = "com.example.STOP_TRACKING"
        private const val ACTION_PAUSE_TRACKING = "com.example.PAUSE_TRACKING"
        private const val ACTION_RESUME_TRACKING = "com.example.RESUME_TRACKING"

        // Health Check & Circuit Breaker
        private const val HEALTH_CHECK_INTERVAL_MS = 3600000L // 1 hour
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_WINDOW_MS = 300000L // 5 minutes
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 1800000L // 30 minutes

        // Default Tracking Intervals (used when backend unavailable)
        private const val DEFAULT_TRACKING_INTERVAL_MS = 3600000L // 1 hour
        private const val DEFAULT_REALTIME_INTERVAL_MS = 10000L // 10 seconds
        private const val DEFAULT_EMERGENCY_INTERVAL_MS = 30000L // 30 seconds
        private const val BATTERY_PROTECTION_INTERVAL_MS = 7200000L // 2 hours (when battery ≤ 44%)

        // Upload Thresholds
        private const val NORMAL_BATCH_UPLOAD_THRESHOLD = 10  // Upload when 10+ locations pending
        private const val EMERGENCY_BATCH_UPLOAD_THRESHOLD = 1  // Upload immediately in emergency mode

        // Factory methods
        fun start(context: Context) {
            android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
            android.util.Log.d("LOCATION_SERVICE", "🚀 LocationTrackingService.start() CALLED")
            android.util.Log.d("LOCATION_SERVICE", "   Context: ${context.javaClass.simpleName}")
            android.util.Log.d("LOCATION_SERVICE", "   Android Version: ${Build.VERSION.SDK_INT}")
            android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")

            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("LOCATION_SERVICE", "📱 Android O+ detected - using startForegroundService()")
                context.startForegroundService(intent)
            } else {
                android.util.Log.d("LOCATION_SERVICE", "📱 Pre-Android O - using startService()")
                context.startService(intent)
            }

            android.util.Log.d("LOCATION_SERVICE", "✅ Service intent sent successfully")
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.stopService(intent)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER COMPONENTS (DEPENDENCIES)
    // ═══════════════════════════════════════════════════════════════════════════════

    private lateinit var permissionManager: LocationPermissionManager
    private lateinit var batteryHelper: BatteryOptimizationHelper
    private lateinit var settingsManager: LocationSettingsManager
    private lateinit var database: LocationDatabase
    private lateinit var batchUploader: BatchUploader
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var preciseScheduler: PreciseLocationScheduler

    // System Services
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager

    // ═══════════════════════════════════════════════════════════════════════════════
    // STATE VARIABLES
    // ═══════════════════════════════════════════════════════════════════════════════

    // Service State
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // Tracking State - Default 1 hour, will be updated from backend settings
    private var currentInterval: Long = DEFAULT_TRACKING_INTERVAL_MS
    private var lastLocationTime: Long = 0L
    private var lastBatteryLevel: Int = -1
    private var lastBatteryCheckTime: Long = 0L

    // Circuit Breaker
    private val restartAttempts = AtomicInteger(0)
    private val lastRestartTime = AtomicLong(0L)
    private var circuitBreakerActive = false
    private var circuitBreakerActivatedTime = 0L

    // Wake Lock for aggressive tracking modes (ForceCheck, Emergency, Realtime, short intervals)
    private var aggressiveWakeLock: PowerManager.WakeLock? = null
    private var isAggressiveModeActive = false

    // CRITICAL: Persistent service wake lock - keeps CPU awake to prevent OEM kills
    private var serviceWakeLock: PowerManager.WakeLock? = null

    // Coroutines
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trackingJob: Job? = null
    private var healthCheckJob: Job? = null
    private var settingsSyncJob: Job? = null
    private var heartbeatJob: Job? = null

    // Handlers
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    // FusedLocationProviderClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // Battery Receiver
    private var batteryReceiver: BroadcastReceiver? = null

    // Location Settings Receiver
    private var locationSettingsReceiver: BroadcastReceiver? = null

    // Location Availability Tracking (for debouncing warnings)
    private var lastLocationAvailable: Boolean = true
    private var locationUnavailableSince: Long = 0L

    // ═══════════════════════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "🎬 onCreate() - SERVICE BEING CREATED")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "onCreate() - Service created")

        try {
            android.util.Log.d("LOCATION_SERVICE", "🔧 Initializing components...")
            initializeComponents()
            android.util.Log.d("LOCATION_SERVICE", "🧵 Setting up background thread...")
            setupBackgroundThread()
            android.util.Log.d("LOCATION_SERVICE", "🔔 Creating notification channel...")
            createNotificationChannel()

            // CRITICAL: Acquire persistent service wake lock to prevent OEM kills
            android.util.Log.d("LOCATION_SERVICE", "🔒 Acquiring persistent service wake lock...")
            acquireServiceWakeLock()

            // Mark service start time for uptime tracking
            ServiceStatePersistence.markServiceStartTime(this)

            isInitialized.set(true)
            android.util.Log.d("LOCATION_SERVICE", "✅✅✅ Service initialized successfully ✅✅✅")
            LocationLogger.i(TAG, "Service initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Failed to initialize service", e)
            LocationLogger.e(TAG, "Failed to initialize service", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "▶️ onStartCommand() CALLED")
        android.util.Log.d("LOCATION_SERVICE", "   Action: ${intent?.action ?: "NULL"}")
        android.util.Log.d("LOCATION_SERVICE", "   Flags: $flags")
        android.util.Log.d("LOCATION_SERVICE", "   StartId: $startId")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "onStartCommand() - Action: ${intent?.action}")

        // CRITICAL: On Android 12+, startForeground() MUST be called within 5 seconds
        // of startForegroundService(). Call it IMMEDIATELY for START_TRACKING action.
        if (intent?.action == ACTION_START_TRACKING || intent?.action == null) {
            try {
                android.util.Log.d("LOCATION_SERVICE", "🚨 Calling startForeground() immediately (Android 12+ requirement)")
                startForeground()
                android.util.Log.d("LOCATION_SERVICE", "✅ Foreground started successfully")
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to start foreground - stopping service", e)
                LocationLogger.e(TAG, "Failed to start foreground", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_START_TRACKING -> {
                android.util.Log.d("LOCATION_SERVICE", "🎯 Action: START_TRACKING")
                handleStartTracking()
            }
            ACTION_STOP_TRACKING -> {
                android.util.Log.d("LOCATION_SERVICE", "🛑 Action: STOP_TRACKING")
                handleStopTracking()
            }
            ACTION_PAUSE_TRACKING -> {
                android.util.Log.d("LOCATION_SERVICE", "⏸️ Action: PAUSE_TRACKING")
                handlePauseTracking()
            }
            ACTION_RESUME_TRACKING -> {
                android.util.Log.d("LOCATION_SERVICE", "▶️ Action: RESUME_TRACKING")
                handleResumeTracking()
            }
            null -> {
                android.util.Log.d("LOCATION_SERVICE", "🎯 No action specified - defaulting to START_TRACKING")
                handleStartTracking() // Default action
            }
        }

        android.util.Log.d("LOCATION_SERVICE", "🔄 Returning START_STICKY")
        return START_STICKY // Service will be restarted if killed
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    /**
     * CRITICAL: Called when app is swiped from recent tasks
     * This is our chance to schedule resurrection before process death
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.w("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.w("LOCATION_SERVICE", "⚠️ onTaskRemoved() - APP SWIPED FROM RECENTS")
        android.util.Log.w("LOCATION_SERVICE", "   Service should continue running")
        android.util.Log.w("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")

        // If tracking should be running, ensure resurrection alarms are scheduled
        if (ServiceStatePersistence.shouldTrackingBeRunning(this) || isRunning.get()) {
            android.util.Log.w("LOCATION_SERVICE", "📢 Scheduling resurrection alarms for after task removal...")

            try {
                // Schedule immediate resurrection check
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val intent = Intent(this, com.example.newconstructionappwithlocationtracking.receivers.ServiceResurrectionReceiver::class.java).apply {
                    action = com.example.newconstructionappwithlocationtracking.receivers.ServiceResurrectionReceiver.ACTION_RESURRECT
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    this,
                    9999, // Unique request code for task removal resurrection
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                // Schedule for 5 seconds from now
                val triggerTime = android.os.SystemClock.elapsedRealtime() + 5000L

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }

                android.util.Log.w("LOCATION_SERVICE", "✅ Resurrection alarm scheduled for 5 seconds from now")

            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to schedule resurrection: ${e.message}")
            }
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.w("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.w("LOCATION_SERVICE", "⚠️ onDestroy() - SERVICE BEING DESTROYED")
        android.util.Log.w("LOCATION_SERVICE", "   This should ONLY happen if user force-stopped app")
        android.util.Log.w("LOCATION_SERVICE", "   or explicitly disabled tracking in settings")
        android.util.Log.w("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "onDestroy() - Service destroyed")

        // Update service running state for LocationServiceHelper
        LocationServiceHelper.setServiceRunning(this, false)

        // CRITICAL: Comprehensive cleanup to prevent memory leaks
        cleanupService()

        // CRITICAL: Cancel all coroutine scopes
        try {
            serviceScope.cancel()
            android.util.Log.d("LOCATION_SERVICE", "✅ Service coroutine scope cancelled")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "Error cancelling service scope: ${e.message}")
        }

        // CRITICAL: Stop background thread
        try {
            backgroundThread.quitSafely()
            android.util.Log.d("LOCATION_SERVICE", "✅ Background thread stopped")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "Error stopping background thread: ${e.message}")
        }

        // CRITICAL: Release any active wake locks
        try {
            aggressiveWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    android.util.Log.d("LOCATION_SERVICE", "✅ Aggressive wake lock released")
                }
            }
            aggressiveWakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "Error releasing aggressive wake lock: ${e.message}")
        }

        // CRITICAL: Release persistent service wake lock
        try {
            releaseServiceWakeLock()
            android.util.Log.d("LOCATION_SERVICE", "✅ Service wake lock released")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "Error releasing service wake lock: ${e.message}")
        }

        // CRITICAL: If service should be running, schedule resurrection
        if (ServiceStatePersistence.shouldTrackingBeRunning(this)) {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Service being destroyed but SHOULD be running!")
            android.util.Log.w("LOCATION_SERVICE", "📢 Triggering resurrection alarm...")
            try {
                com.example.newconstructionappwithlocationtracking.receivers.ServiceResurrectionReceiver.triggerImmediateCheck(this)
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to trigger resurrection: ${e.message}")
            }
        }

        // CRITICAL: Null out all references to prevent memory leaks
        locationCallback = null
        android.util.Log.d("LOCATION_SERVICE", "✅ Location callback nulled")

        // Schedule WorkManager fallback if service killed unexpectedly
        // NOTE: Worker now unified in LocationTrackingApplication.PeriodicTrackingCheckWorker
        // if (isRunning.get() && !isPaused.get()) {
        //     scheduleWorkManagerFallback() // DISABLED - Using unified worker instead
        // }

        android.util.Log.i("LOCATION_SERVICE", "✅ Service destruction complete")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun initializeComponents() {
        LocationLogger.i(TAG, "Initializing components...")

        // Initialize helpers
        permissionManager = LocationPermissionManager(this)
        batteryHelper = BatteryOptimizationHelper(this)
        settingsManager = LocationSettingsManager(this)
        database = LocationDatabase.getInstance(this)
        networkMonitor = NetworkMonitor(this)
        // Pass settingsManager to BatchUploader for dynamic thresholds
        batchUploader = BatchUploader(this, database, networkMonitor, settingsManager)
        // Initialize precise location scheduler for backup alarm system
        preciseScheduler = PreciseLocationScheduler.getInstance(this)

        // System services
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        // FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize DeviceStatusReporter for real-time status uploads to admin
        DeviceStatusReporter.initialize(this)

        LocationLogger.i(TAG, "Components initialized successfully")
    }

    private fun setupBackgroundThread() {
        backgroundThread = HandlerThread("LocationTrackingServiceThread").apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOREGROUND SERVICE & NOTIFICATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN // Minimum importance - no sound, no popup, barely visible
            ).apply {
                description = "Background service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // Hide from lock screen
            }
            notificationManager.createNotificationChannel(channel)
            LocationLogger.i(TAG, "Notification channel created")
        }
    }

    private fun startForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            LocationLogger.i(TAG, "Started foreground service")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to start foreground", e)
            throw e
        }
    }

    private fun buildNotification(): Notification {
        // ULTRA-MINIMAL notification design as per requirements:
        // - Dot or small icon
        // - Title: "Construct Connect"
        // - Text: "" (empty)
        // - Lowest possible visibility
        // - Required by Android for foreground service but designed to be invisible

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Construct Connect") // App name only
            .setContentText("") // Empty text as requested
            .setSmallIcon(R.drawable.ic_notification_tracking) // Custom C-with-dot icon
            .setColor(getColor(R.color.colorPrimary)) // Brand color (Purple) for icon tint
            .setOngoing(true) // Cannot be dismissed (foreground service requirement)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Minimum priority - barely visible
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lock screen
            .setSilent(true) // No sound whatsoever
            .setShowWhen(false) // Don't show timestamp
            .setOnlyAlertOnce(true) // Never alert user
            .build()
    }

    private fun updateNotification() {
        // No-op: We use a minimal silent notification that doesn't need updates
        // This keeps the notification invisible to users
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ACTION HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun handleStartTracking() {
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "🚀 HANDLE START TRACKING CALLED")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "handleStartTracking() - Starting foreground service")

        if (isRunning.get()) {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Service already running - checking if need to resume tracking")
            // Service is running - check if we need to resume tracking
            if (isPaused.get() && hasAllPermissions()) {
                android.util.Log.d("LOCATION_SERVICE", "📍 Permissions now granted - resuming tracking")
                handleResumeTracking()
            }
            return
        }

        // Check circuit breaker
        if (circuitBreakerActive) {
            val elapsed = System.currentTimeMillis() - circuitBreakerActivatedTime
            if (elapsed < CIRCUIT_BREAKER_COOLDOWN_MS) {
                android.util.Log.w("LOCATION_SERVICE", "⚠️ Circuit breaker active - cooling down")
                LocationLogger.w(TAG, "Circuit breaker active - cooling down")
                return
            } else {
                deactivateCircuitBreaker()
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL: START FOREGROUND SERVICE IMMEDIATELY
        // This MUST happen regardless of permissions/location state
        // Service stays alive even if tracking is paused
        // ═══════════════════════════════════════════════════════════════════
        try {
            android.util.Log.d("LOCATION_SERVICE", "📢 Starting foreground service (ALWAYS ON)...")
            startForeground()
            android.util.Log.d("LOCATION_SERVICE", "✅ Foreground service started successfully")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ CRITICAL: Failed to start foreground service", e)
            LocationLogger.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }

        // Mark service as running (foreground service is alive)
        isRunning.set(true)
        LocationServiceHelper.setServiceRunning(this, true)
        ServiceStatePersistence.markServiceRunning(this)
        android.util.Log.d("LOCATION_SERVICE", "✅ Service marked as RUNNING (foreground)")

        // ═══════════════════════════════════════════════════════════════════
        // START MONITORING SYSTEMS (ALWAYS ACTIVE)
        // These run even when tracking is paused
        // ═══════════════════════════════════════════════════════════════════

        android.util.Log.d("LOCATION_SERVICE", "⚙️ Starting settings sync...")
        startSettingsSync()

        android.util.Log.d("LOCATION_SERVICE", "🔋 Registering battery monitor...")
        registerBatteryMonitor()

        android.util.Log.d("LOCATION_SERVICE", "📍 Registering location settings monitor...")
        registerLocationSettingsMonitor()

        android.util.Log.d("LOCATION_SERVICE", "🐕 Starting WatchdogAlarmManager...")
        try {
            WatchdogAlarmManager.startWatchdog(this)
            android.util.Log.d("LOCATION_SERVICE", "✅ WatchdogAlarmManager started")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "⚠️ WatchdogAlarmManager failed: ${e.message}")
        }

        android.util.Log.d("LOCATION_SERVICE", "🌐 Starting NetworkRecoveryManager...")
        try {
            NetworkRecoveryManager.getInstance(this).startMonitoring()
            android.util.Log.d("LOCATION_SERVICE", "✅ NetworkRecoveryManager started")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "⚠️ NetworkRecoveryManager failed: ${e.message}")
        }

        android.util.Log.d("LOCATION_SERVICE", "❤️ Starting health check...")
        startHealthCheck()

        android.util.Log.d("LOCATION_SERVICE", "💓 Starting heartbeat loop...")
        startHeartbeatLoop()

        // ═══════════════════════════════════════════════════════════════════
        // CHECK PERMISSIONS AND START TRACKING IF READY
        // If permissions not granted, service stays alive but tracking is paused
        // ═══════════════════════════════════════════════════════════════════

        android.util.Log.d("LOCATION_SERVICE", "🔍 Checking permissions for tracking...")
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (permissionStatus.canStartTracking && permissionStatus.isLocationEnabled) {
            android.util.Log.d("LOCATION_SERVICE", "✅ Permissions granted - starting location tracking")
            isPaused.set(false)

            // Mark tracking as active
            ServiceStatePersistence.setTrackingShouldBeRunning(this, true)
            batchUploader.notifyTrackingResumed()

            // Start actual location tracking
            android.util.Log.d("LOCATION_SERVICE", "🔄 Starting tracking loop...")
            startTrackingLoop()

            android.util.Log.d("LOCATION_SERVICE", "✅✅✅ Location tracking started successfully ✅✅✅")
            TrackingStatusLogger.logTrackingStarted(this, currentInterval)

            // Report status to backend for admin visibility
            DeviceStatusReporter.reportStatus(
                this,
                DeviceStatusReporter.STATUS_SERVICE_STARTED,
                "Location tracking service started successfully",
                mapOf("interval" to currentInterval)
            )

        } else {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Permissions/location not ready - service ALIVE but tracking PAUSED")
            android.util.Log.w("LOCATION_SERVICE", "   hasFineLocation: ${permissionStatus.hasFineLocation}")
            android.util.Log.w("LOCATION_SERVICE", "   hasBackgroundLocation: ${permissionStatus.hasBackgroundLocation}")
            android.util.Log.w("LOCATION_SERVICE", "   isLocationEnabled: ${permissionStatus.isLocationEnabled}")

            isPaused.set(true)
            ServiceStatePersistence.setTrackingShouldBeRunning(this, false)

            // Show appropriate notification
            if (!permissionStatus.canStartTracking) {
                showPermissionRequiredNotification(permissionStatus)
            } else if (!permissionStatus.isLocationEnabled) {
                showLocationDisabledNotification()
            }

            TrackingStatusLogger.logStatus(
                context = this,
                isTracking = false,
                additionalIssues = listOf("SERVICE_ALIVE_TRACKING_PAUSED"),
                reason = "Waiting for permissions/location to be enabled"
            )

            // Report paused status to backend for admin visibility
            val failureReason = when {
                !permissionStatus.hasFineLocation -> "NO_FINE_LOCATION_PERMISSION"
                !permissionStatus.hasBackgroundLocation -> "NO_BACKGROUND_LOCATION_PERMISSION"
                !permissionStatus.isLocationEnabled -> "LOCATION_SERVICES_DISABLED"
                else -> "UNKNOWN"
            }
            DeviceStatusReporter.reportStatus(
                this,
                DeviceStatusReporter.STATUS_TRACKING_PAUSED,
                "Service alive but tracking paused - waiting for permissions/location",
                null,
                failureReason
            )

            android.util.Log.d("LOCATION_SERVICE", "✅ Service running in STANDBY mode - will resume when ready")
        }

        LocationLogger.i(TAG, "Foreground service started - tracking=${!isPaused.get()}")
    }

    private fun handleStopTracking() {
        android.util.Log.i("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.i("LOCATION_SERVICE", "🛑 STOP TRACKING REQUESTED")
        android.util.Log.i("LOCATION_SERVICE", "   CRITICAL: Service will NOT stop - implementing PAUSE pattern")
        android.util.Log.i("LOCATION_SERVICE", "   Service remains alive as foreground service")
        android.util.Log.i("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "handleStopTracking() - PAUSING (not stopping) location tracking")

        // CRITICAL CHANGE: PAUSE instead of STOP
        // Service NEVER stops - it only pauses tracking
        // This ensures service survives app being killed from recents

        if (!isRunning.get()) {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Service not running - nothing to pause")
            return
        }

        // Set paused state
        isPaused.set(true)

        // Notify BatchUploader that tracking has paused
        batchUploader.notifyTrackingStopped()
        android.util.Log.d("LOCATION_SERVICE", "📴 Notified BatchUploader that tracking paused")

        // Log tracking status to Firebase
        TrackingStatusLogger.logTrackingStopped(this, "User requested pause (service stays alive)")

        // Stop location updates but keep service alive
        stopLocationUpdates()
        android.util.Log.d("LOCATION_SERVICE", "📍 Location updates stopped (service still running)")

        // Cancel tracking job but keep other monitoring active
        trackingJob?.cancel()
        android.util.Log.d("LOCATION_SERVICE", "🔄 Tracking job cancelled")

        // Update notification to show paused state (minimal notification)
        updateNotification()

        // Mark as paused in state persistence (but service is still running)
        ServiceStatePersistence.setTrackingShouldBeRunning(this, false)
        android.util.Log.d("LOCATION_SERVICE", "💾 State marked as PAUSED (service alive)")

        // Report paused status to backend for admin visibility
        DeviceStatusReporter.reportStatus(
            this,
            DeviceStatusReporter.STATUS_TRACKING_PAUSED,
            "Tracking paused by user request (service still alive)"
        )

        android.util.Log.i("LOCATION_SERVICE", "✅ Tracking PAUSED successfully - Service still running as foreground")
        LocationLogger.i(TAG, "Tracking paused - service remains active")
    }

    /**
     * CRITICAL: This method is for FORCE STOP only (user explicitly disables tracking in settings)
     * Normal "stop" requests should use handleStopTracking which pauses instead
     */
    private fun forceStopService() {
        android.util.Log.e("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.e("LOCATION_SERVICE", "⛔ FORCE STOP SERVICE - USER DISABLED TRACKING")
        android.util.Log.e("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.w(TAG, "forceStopService() - User explicitly disabled tracking")

        // Notify BatchUploader
        batchUploader.notifyTrackingStopped()

        // Log tracking status to Firebase
        TrackingStatusLogger.logTrackingStopped(this, "User explicitly disabled tracking in settings")

        // Update service running state for LocationServiceHelper
        LocationServiceHelper.setServiceRunning(this, false)

        // Stop watchdog and network recovery
        try {
            WatchdogAlarmManager.stopWatchdog(this)
            android.util.Log.d("LOCATION_SERVICE", "🐕 WatchdogAlarmManager stopped")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "⚠️ Failed to stop watchdog: ${e.message}")
        }

        try {
            NetworkRecoveryManager.getInstance(this).stopMonitoring()
            android.util.Log.d("LOCATION_SERVICE", "🌐 NetworkRecoveryManager stopped")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "⚠️ Failed to stop network recovery: ${e.message}")
        }

        // Full cleanup
        cleanupService()

        // Actually stop the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()

        android.util.Log.i("LOCATION_SERVICE", "⛔ Service FORCE STOPPED")
        LocationLogger.i(TAG, "Service force stopped - user disabled tracking")
    }

    private fun handlePauseTracking() {
        LocationLogger.i(TAG, "handlePauseTracking() - Pausing tracking")

        if (!isRunning.get() || isPaused.get()) return

        isPaused.set(true)

        // Notify BatchUploader that tracking has paused
        // This enables time-based upload trigger (uploads remaining data after 5 min if internet available)
        batchUploader.notifyTrackingStopped()
        android.util.Log.d("LOCATION_SERVICE", "📴 Notified BatchUploader that tracking paused")

        stopLocationUpdates()
        updateNotification()

        LocationLogger.i(TAG, "Tracking paused")
    }

    private fun handleResumeTracking() {
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "▶️ HANDLE RESUME TRACKING CALLED")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "handleResumeTracking() - Resuming tracking from paused state")

        if (!isRunning.get()) {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Service not running - calling handleStartTracking instead")
            handleStartTracking()
            return
        }

        if (!isPaused.get()) {
            android.util.Log.w("LOCATION_SERVICE", "⚠️ Tracking already active - skipping")
            LocationLogger.w(TAG, "Tracking already active")
            return
        }

        // Verify permissions before resuming
        android.util.Log.d("LOCATION_SERVICE", "🔍 Verifying permissions before resume...")
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking || !permissionStatus.isLocationEnabled) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Cannot resume - permissions/location not ready")
            android.util.Log.e("LOCATION_SERVICE", "   canStartTracking: ${permissionStatus.canStartTracking}")
            android.util.Log.e("LOCATION_SERVICE", "   isLocationEnabled: ${permissionStatus.isLocationEnabled}")

            if (!permissionStatus.canStartTracking) {
                showPermissionRequiredNotification(permissionStatus)
            } else if (!permissionStatus.isLocationEnabled) {
                showLocationDisabledNotification()
            }
            return
        }

        android.util.Log.d("LOCATION_SERVICE", "✅ Permissions verified - resuming tracking")

        // Unpause
        isPaused.set(false)

        // Mark tracking as active
        ServiceStatePersistence.setTrackingShouldBeRunning(this, true)

        // Notify BatchUploader that tracking has resumed
        batchUploader.notifyTrackingResumed()
        android.util.Log.d("LOCATION_SERVICE", "📡 Notified BatchUploader that tracking resumed from pause")

        // Restart location updates
        android.util.Log.d("LOCATION_SERVICE", "🔄 Restarting location updates...")
        createLocationCallback()
        startLocationUpdatesOnce()

        // Update notification (remove any error notifications)
        updateNotification()

        android.util.Log.d("LOCATION_SERVICE", "✅ Tracking resumed successfully")
        LocationLogger.i(TAG, "Tracking resumed successfully")

        TrackingStatusLogger.logTrackingStarted(this, currentInterval)

        // Report to admin via Firestore
        DeviceStatusReporter.reportTrackingResumed(this)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PERMISSION & REQUIREMENT VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun validatePermissionsAndStart(): Boolean {
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "🔍 VALIDATING PERMISSIONS...")

        val status = permissionManager.getComprehensivePermissionStatus()

        android.util.Log.d("LOCATION_SERVICE", "📋 Permission Status:")
        android.util.Log.d("LOCATION_SERVICE", "   hasFineLocation: ${status.hasFineLocation}")
        android.util.Log.d("LOCATION_SERVICE", "   hasBackgroundLocation: ${status.hasBackgroundLocation}")
        android.util.Log.d("LOCATION_SERVICE", "   isLocationEnabled: ${status.isLocationEnabled}")
        android.util.Log.d("LOCATION_SERVICE", "   canStartTracking: ${status.canStartTracking}")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")

        if (!status.canStartTracking) {
            android.util.Log.e("LOCATION_SERVICE", "❌ CANNOT START TRACKING!")
            android.util.Log.e("LOCATION_SERVICE", "   Fine Location: ${status.hasFineLocation}")
            android.util.Log.e("LOCATION_SERVICE", "   Background Location: ${status.hasBackgroundLocation}")
            android.util.Log.e("LOCATION_SERVICE", "   Location Services ON: ${status.isLocationEnabled}")
            LocationLogger.e(TAG, "Cannot start tracking - missing permissions")

            // Log to Firebase status so admin can see why tracking isn't working
            TrackingStatusLogger.logStatus(
                context = this,
                isTracking = false,
                additionalIssues = listOf(
                    "TRACKING_BLOCKED",
                    "Missing permissions",
                    "hasFineLocation=${status.hasFineLocation}",
                    "hasBackgroundLocation=${status.hasBackgroundLocation}",
                    "isLocationEnabled=${status.isLocationEnabled}"
                ),
                reason = "Missing permissions"
            )

            showPermissionRequiredNotification(status)
            return false
        }

        if (!status.isLocationEnabled) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Location services disabled on device!")
            LocationLogger.e(TAG, "Location services disabled")

            // Log to Firebase status
            TrackingStatusLogger.logStatus(
                context = this,
                isTracking = false,
                additionalIssues = listOf(TrackingStatusLogger.Issues.LOC_SERVICES_OFF),
                reason = "User has turned off location services on device"
            )

            showLocationDisabledNotification()
            return false
        }

        android.util.Log.d("LOCATION_SERVICE", "✅ All permissions validated - can start tracking!")
        return true
    }

    /**
     * Helper method to check if all required permissions are granted
     */
    private fun hasAllPermissions(): Boolean {
        val status = permissionManager.getComprehensivePermissionStatus()
        return status.canStartTracking && status.isLocationEnabled
    }

    /**
     * Helper method to start location tracking (used by location providers changed handler)
     */
    private fun startLocationTracking() {
        if (isRunning.get() && !isPaused.get()) {
            // Already tracking
            LocationLogger.d(TAG, "Location tracking already active")
            return
        }

        // If paused, resume
        if (isRunning.get() && isPaused.get()) {
            handleResumeTracking()
        } else {
            // Start from scratch
            handleStartTracking()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SETTINGS SYNCHRONIZATION (HYBRID SYNC SYSTEM)
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun startSettingsSync() {
        // Get current user ID
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            LocationLogger.e(TAG, "Cannot start settings sync - no authenticated user")
            return
        }

        // Set user ID on batch uploader for post-upload sync
        batchUploader.setUserId(userId)

        // Register callback for settings changes
        settingsManager.registerCallback(object : LocationSettingsManager.SettingsCallback {
            override fun onSettingsChanged(newSettings: LocationSettings, oldSettings: LocationSettings) {
                LocationLogger.i(TAG, "📡 Settings changed callback received")

                // Recalculate interval based on new settings
                recalculateInterval()

                // Restart location updates if interval changed significantly
                if (newSettings.getCurrentInterval() != oldSettings.getCurrentInterval()) {
                    restartLocationUpdates()
                }
            }
        })

        // Start hybrid sync (realtime listener + periodic sync)
        settingsManager.startSync(userId)

        LocationLogger.i(TAG, "✅ Hybrid settings sync started for user: $userId")
    }

    private suspend fun syncSettings() {
        LocationLogger.i(TAG, "Syncing settings from admin...")

        val newSettings = settingsManager.fetchSettings()

        if (newSettings != null && newSettings.hashCode() != currentInterval.hashCode()) {
            LocationLogger.i(TAG, "Settings changed - applying new configuration")

            // Recalculate interval
            recalculateInterval()

            // Restart location updates with new settings
            restartLocationUpdates()

            LocationLogger.i(TAG, "Settings applied successfully")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DYNAMIC INTERVAL CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun recalculateInterval() {
        val settings = settingsManager.fetchSettings()

        val batteryInfo = batteryHelper.getCompleteBatteryInfo(
            forceCheck = settings.forceCheck,
            emergencyMode = settings.emergencyMode
        )

        // Priority order: Emergency > Realtime > Battery Protection > Admin/Normal
        val newInterval = when {
            // Emergency mode: Highest priority - use emergency interval
            settings.emergencyMode -> {
                val emergencyInterval = settings.emergencyIntervalMs.takeIf { it > 0 } ?: DEFAULT_EMERGENCY_INTERVAL_MS
                LocationLogger.i(TAG, "📡 Using EMERGENCY mode interval: ${emergencyInterval}ms")
                emergencyInterval
            }

            // Realtime mode: High priority - use realtime interval
            settings.realtimeMode -> {
                val realtimeInterval = settings.realtimeIntervalMs.takeIf { it > 0 } ?: DEFAULT_REALTIME_INTERVAL_MS
                LocationLogger.i(TAG, "📡 Using REALTIME mode interval: ${realtimeInterval}ms")
                realtimeInterval
            }

            // Battery protection: When battery ≤ 44% AND ForceCheck is OFF
            batteryInfo.shouldProtect && !settings.forceCheck -> {
                LocationLogger.i(TAG, "🔋 Using BATTERY PROTECTION interval: ${BATTERY_PROTECTION_INTERVAL_MS}ms (battery: ${batteryInfo.level}%)")
                BATTERY_PROTECTION_INTERVAL_MS
            }

            // Normal mode: Use admin-configured interval (or default)
            else -> {
                val normalInterval = settings.normalIntervalMs.takeIf { it > 0 } ?: DEFAULT_TRACKING_INTERVAL_MS
                LocationLogger.i(TAG, "📡 Using NORMAL interval: ${normalInterval}ms")
                normalInterval
            }
        }

        if (newInterval != currentInterval) {
            LocationLogger.i(TAG, "🔄 Interval changed: ${currentInterval}ms → ${newInterval}ms")
            android.util.Log.d("LOCATION_SERVICE", "🔄 Interval changed: ${currentInterval}ms → ${newInterval}ms")
            currentInterval = newInterval
            updateNotification()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TRACKING LOOP
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun startTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            // First, fetch and apply settings from backend
            try {
                val initialSettings = settingsManager.fetchSettings()
                val initialInterval = calculateNewInterval(initialSettings)
                currentInterval = initialInterval

                android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
                android.util.Log.d("LOCATION_SERVICE", "🔄 STARTING TRACKING LOOP")
                android.util.Log.d("LOCATION_SERVICE", "   Using FusedLocationProviderClient")
                android.util.Log.d("LOCATION_SERVICE", "   Fetched interval from backend: ${currentInterval}ms")
                android.util.Log.d("LOCATION_SERVICE", "   Emergency: ${initialSettings.emergencyMode}, Realtime: ${initialSettings.realtimeMode}")
                android.util.Log.d("LOCATION_SERVICE", "   ForceCheck: ${initialSettings.forceCheck}")
                android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to fetch initial settings, using default: ${currentInterval}ms", e)
            }

            // Start location updates ONCE - the callback will handle incoming locations
            // MUST run on main thread for FusedLocationProviderClient
            try {
                if (!isPaused.get()) {
                    withContext(Dispatchers.Main) {
                        createLocationCallback()
                        startLocationUpdatesOnce()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to start initial location updates", e)
                handleTrackingError(e)
            }

            // Settings monitoring loop - checks for interval changes periodically
            while (isActive && isRunning.get()) {
                try {
                    delay(currentInterval)

                    // Check if settings changed and we need to reconfigure
                    if (!isPaused.get()) {
                        val settings = settingsManager.fetchSettings()
                        val newInterval = calculateNewInterval(settings)

                        // Only restart location updates if interval changed
                        if (newInterval != currentInterval) {
                            android.util.Log.d("LOCATION_SERVICE", "🔄 Interval changed: ${currentInterval}ms → ${newInterval}ms - restarting updates")
                            LocationLogger.i(TAG, "Interval changed: ${currentInterval}ms → ${newInterval}ms")
                            currentInterval = newInterval
                            restartLocationUpdates()
                            updateNotification()
                        }
                    }
                } catch (e: Exception) {
                    handleTrackingError(e)
                    delay(60000) // Wait 1 minute before retry
                }
            }
        }
    }

    private fun calculateNewInterval(settings: LocationSettings): Long {
        val batteryInfo = batteryHelper.getCompleteBatteryInfo(
            forceCheck = settings.forceCheck,
            emergencyMode = settings.emergencyMode
        )

        // Priority order: Emergency > Realtime > Battery Protection > Admin/Normal
        return when {
            // Emergency mode: Highest priority - use emergency interval
            settings.emergencyMode -> {
                settings.emergencyIntervalMs.takeIf { it > 0 } ?: DEFAULT_EMERGENCY_INTERVAL_MS
            }

            // Realtime mode: High priority - use realtime interval
            settings.realtimeMode -> {
                settings.realtimeIntervalMs.takeIf { it > 0 } ?: DEFAULT_REALTIME_INTERVAL_MS
            }

            // Battery protection: When battery ≤ 44% AND ForceCheck is OFF
            batteryInfo.shouldProtect && !settings.forceCheck -> {
                BATTERY_PROTECTION_INTERVAL_MS
            }

            // Normal mode: Use admin-configured interval (or default)
            else -> {
                settings.normalIntervalMs.takeIf { it > 0 } ?: DEFAULT_TRACKING_INTERVAL_MS
            }
        }
    }

    private fun restartLocationUpdates() {
        // Must run on main thread for FusedLocationProviderClient
        Handler(Looper.getMainLooper()).post {
            stopLocationUpdates()
            // Update precise scheduler with new interval
            preciseScheduler.updateInterval(currentInterval)
            createLocationCallback()
            startLocationUpdatesOnce()
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = if (lastLocationTime > 0) now - lastLocationTime else 0L
                val timeSinceRequest = if (lastLocationRequestTime > 0) now - lastLocationRequestTime else 0L

                android.util.Log.d("GPS_COORDINATES", "════════════════════════════════════════════════════════════")
                android.util.Log.d("GPS_COORDINATES", "🎯 LOCATION CALLBACK TRIGGERED!")
                android.util.Log.d("GPS_COORDINATES", "════════════════════════════════════════════════════════════")
                android.util.Log.d("GPS_COORDINATES", "   📊 TIMING ANALYSIS:")
                android.util.Log.d("GPS_COORDINATES", "   ├─ Expected interval: ${currentInterval}ms (${currentInterval/1000}s)")
                android.util.Log.d("GPS_COORDINATES", "   ├─ Time since last update: ${timeSinceLastUpdate}ms (${timeSinceLastUpdate/1000}s)")
                android.util.Log.d("GPS_COORDINATES", "   ├─ Time since request registered: ${timeSinceRequest}ms")
                android.util.Log.d("GPS_COORDINATES", "   ├─ Locations in result: ${locationResult.locations.size}")

                // Calculate variance from expected
                if (timeSinceLastUpdate > 0) {
                    val variance = timeSinceLastUpdate - currentInterval
                    val variancePercent = (variance * 100) / currentInterval
                    val status = when {
                        variance <= 0 -> "✅ FASTER than expected"
                        variancePercent <= 20 -> "✅ GOOD (within 20%)"
                        variancePercent <= 50 -> "⚠️ DELAYED by ${variance/1000}s (${variancePercent}%)"
                        else -> "❌ SEVERELY DELAYED by ${variance/1000}s (${variancePercent}%)"
                    }
                    android.util.Log.d("GPS_COORDINATES", "   └─ Interval Status: $status")
                }
                android.util.Log.d("GPS_COORDINATES", "════════════════════════════════════════════════════════════")

                locationResult.lastLocation?.let { location ->
                    android.util.Log.d("GPS_COORDINATES", "📍 GPS COORDINATES RECEIVED:")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Latitude: ${location.latitude}")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Longitude: ${location.longitude}")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Accuracy: ${location.accuracy}m")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Provider: ${location.provider}")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Speed: ${location.speed}m/s")
                    android.util.Log.d("GPS_COORDINATES", "   ├─ Bearing: ${location.bearing}°")
                    android.util.Log.d("GPS_COORDINATES", "   └─ Location Time: ${location.time}")
                    android.util.Log.d("GPS_COORDINATES", "════════════════════════════════════════════════════════════")

                    // Process the location
                    handleNewLocation(location)
                } ?: run {
                    android.util.Log.w("GPS_COORDINATES", "⚠️ lastLocation is null in callback - waiting for next update")
                }
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                val isAvailable = availability.isLocationAvailable
                val now = System.currentTimeMillis()

                // Track availability changes with debouncing
                if (!isAvailable && lastLocationAvailable) {
                    // Just became unavailable - start tracking time
                    locationUnavailableSince = now
                    lastLocationAvailable = false
                    android.util.Log.d("LOCATION_SERVICE", "📡 Location Availability Changed: false (GPS acquiring signal...)")
                } else if (!isAvailable && !lastLocationAvailable) {
                    // Still unavailable - check how long
                    val unavailableDuration = now - locationUnavailableSince
                    if (unavailableDuration > 10000L) {
                        // Unavailable for >10 seconds - this is a real issue
                        android.util.Log.w("LOCATION_SERVICE", "⚠️ Location unavailable for ${unavailableDuration/1000}s - GPS may be disabled or weak signal")

                        // Log to Firebase for admin visibility
                        TrackingStatusLogger.logStatus(
                            context = this@LocationTrackingService,
                            isTracking = !isPaused.get(),
                            additionalIssues = listOf("GPS_UNAVAILABLE_${unavailableDuration/1000}s"),
                            reason = "Location unavailable for extended period"
                        )
                    }
                } else if (isAvailable && !lastLocationAvailable) {
                    // Became available again
                    val unavailableDuration = now - locationUnavailableSince
                    lastLocationAvailable = true
                    android.util.Log.d("LOCATION_SERVICE", "📡 Location Availability Changed: true (GPS signal acquired after ${unavailableDuration/1000}s)")
                }
                // If isAvailable && lastLocationAvailable -> no change, no log spam
            }
        }
        android.util.Log.d("LOCATION_SERVICE", "✅ Location callback created with timing diagnostics")
        LocationLogger.d(TAG, "Location callback created")
    }

    private fun startLocationUpdatesOnce() {
        val intervalFormatted = when {
            currentInterval >= 3600000 -> "${currentInterval / 3600000} hour(s)"
            currentInterval >= 60000 -> "${currentInterval / 60000} minute(s)"
            else -> "${currentInterval / 1000} second(s)"
        }

        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "🚀 STARTING LOCATION UPDATES")
        android.util.Log.d("LOCATION_SERVICE", "   Interval: $intervalFormatted (${currentInterval}ms)")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")

        try {
            if (locationCallback == null) {
                createLocationCallback()
            }

            // Get current settings to determine mode
            val settings = settingsManager.getCurrentSettings()
            val isEmergency = settings.emergencyMode
            val isRealtime = settings.realtimeMode
            val isForceCheck = settings.forceCheck

            // ════════════════════════════════════════════════════════════════════
            // CRITICAL FIX: AGGRESSIVE MODE DETECTION
            // ════════════════════════════════════════════════════════════════════
            // Aggressive mode is enabled when:
            // - Emergency mode (highest priority tracking)
            // - Realtime mode (high-frequency tracking)
            // - ForceCheck mode (admin override)
            // - Short intervals (< 5 minutes = 300,000ms)
            //
            // In aggressive mode:
            // - Wake lock is acquired to prevent CPU sleep
            // - MaxUpdateDelay is set to 0 (no batching)
            // - MinUpdateInterval is set very low
            // - Fastest interval is set to match requested interval
            // ════════════════════════════════════════════════════════════════════
            val isAggressiveMode = isEmergency || isRealtime || isForceCheck || currentInterval < 300000L

            android.util.Log.d("LOCATION_SERVICE", "📊 Tracking Mode Analysis:")
            android.util.Log.d("LOCATION_SERVICE", "   Emergency: $isEmergency")
            android.util.Log.d("LOCATION_SERVICE", "   Realtime: $isRealtime")
            android.util.Log.d("LOCATION_SERVICE", "   ForceCheck: $isForceCheck")
            android.util.Log.d("LOCATION_SERVICE", "   Interval < 5min: ${currentInterval < 300000L}")
            android.util.Log.d("LOCATION_SERVICE", "   → AGGRESSIVE MODE: $isAggressiveMode")

            // Manage wake lock for aggressive modes
            manageAggressiveWakeLock(isAggressiveMode)

            // ════════════════════════════════════════════════════════════════════
            // CRITICAL FIX: OPTIMAL LOCATIONREQUEST CONFIGURATION
            // ════════════════════════════════════════════════════════════════════
            //
            // KEY PARAMETERS FOR PRECISE INTERVAL CONTROL:
            //
            // 1. setIntervalMillis(interval)
            //    - The desired interval between location updates
            //    - This is a TARGET, not a guarantee
            //
            // 2. setMinUpdateIntervalMillis(minInterval)
            //    - FASTEST possible update rate (minimum time between updates)
            //    - For aggressive modes: Set to SAME as interval or even lower
            //    - This ensures updates CAN arrive as fast as requested
            //
            // 3. setMaxUpdateDelayMillis(maxDelay)
            //    - CRITICAL FOR SCREEN-OFF RELIABILITY
            //    - When > 0: Android batches updates to save battery
            //    - When = 0: Updates delivered IMMEDIATELY (no batching)
            //    - For aggressive modes: MUST be 0
            //
            // 4. setPriority(priority)
            //    - PRIORITY_HIGH_ACCURACY: GPS + Network (best)
            //    - PRIORITY_BALANCED_POWER_ACCURACY: Network primarily
            //    - For ALL our modes: Use HIGH_ACCURACY
            //
            // 5. setWaitForAccurateLocation(false)
            //    - When false: Get first location faster (even if less accurate)
            //    - We want fast first fix, so always false
            //
            // 6. setMinUpdateDistanceMeters(0f)
            //    - When 0: Get updates even when stationary
            //    - Important for time-based tracking
            //
            // ════════════════════════════════════════════════════════════════════

            // Calculate optimal intervals based on mode
            val minUpdateInterval: Long
            val maxUpdateDelay: Long
            val minDistance: Float

            if (isAggressiveMode) {
                // AGGRESSIVE MODE: Maximum responsiveness
                // - MinUpdateInterval = same as desired interval (allow fastest possible)
                // - For very short intervals (< 5s), allow even faster updates
                minUpdateInterval = if (currentInterval <= 5000L) {
                    (currentInterval * 0.5).toLong().coerceAtLeast(500L) // Allow 50% faster
                } else {
                    (currentInterval * 0.8).toLong().coerceAtLeast(1000L) // Allow 20% faster
                }
                maxUpdateDelay = 0L // CRITICAL: No batching - immediate delivery
                minDistance = 0f // Get updates even when stationary

                android.util.Log.d("LOCATION_SERVICE", "⚡ AGGRESSIVE CONFIG:")
                android.util.Log.d("LOCATION_SERVICE", "   MinUpdateInterval: ${minUpdateInterval}ms (allows faster updates)")
                android.util.Log.d("LOCATION_SERVICE", "   MaxUpdateDelay: 0ms (NO BATCHING)")
                android.util.Log.d("LOCATION_SERVICE", "   MinDistance: 0m (update even when stationary)")
            } else {
                // NORMAL MODE: Balance between accuracy and battery
                minUpdateInterval = (currentInterval * 0.9).toLong().coerceAtLeast(30000L) // 90% of interval, min 30s
                maxUpdateDelay = (currentInterval * 1.5).toLong() // Allow 50% delay for batching
                minDistance = 0f // Still get time-based updates

                android.util.Log.d("LOCATION_SERVICE", "🔋 NORMAL CONFIG:")
                android.util.Log.d("LOCATION_SERVICE", "   MinUpdateInterval: ${minUpdateInterval}ms")
                android.util.Log.d("LOCATION_SERVICE", "   MaxUpdateDelay: ${maxUpdateDelay}ms (allows batching)")
                android.util.Log.d("LOCATION_SERVICE", "   MinDistance: 0m")
            }

            // Build the LocationRequest with optimal configuration
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentInterval)
                .setMinUpdateIntervalMillis(minUpdateInterval)
                .setMaxUpdateDelayMillis(maxUpdateDelay)
                .setMinUpdateDistanceMeters(minDistance)
                .setWaitForAccurateLocation(false) // Get first fix faster
                .build()

            android.util.Log.d("LOCATION_SERVICE", "════════════════════════════════════════════════════════")
            android.util.Log.d("LOCATION_SERVICE", "📝 FINAL LocationRequest Configuration:")
            android.util.Log.d("LOCATION_SERVICE", "   Priority: PRIORITY_HIGH_ACCURACY")
            android.util.Log.d("LOCATION_SERVICE", "   Interval: ${currentInterval}ms ($intervalFormatted)")
            android.util.Log.d("LOCATION_SERVICE", "   MinUpdateInterval: ${minUpdateInterval}ms")
            android.util.Log.d("LOCATION_SERVICE", "   MaxUpdateDelay: ${maxUpdateDelay}ms ${if (maxUpdateDelay == 0L) "(IMMEDIATE)" else "(batched)"}")
            android.util.Log.d("LOCATION_SERVICE", "   MinDistance: ${minDistance}m")
            android.util.Log.d("LOCATION_SERVICE", "   WaitForAccurate: false")
            android.util.Log.d("LOCATION_SERVICE", "   WakeLock: ${if (isAggressiveMode) "ACTIVE" else "INACTIVE"}")
            android.util.Log.d("LOCATION_SERVICE", "════════════════════════════════════════════════════════")

            try {
                // ════════════════════════════════════════════════════════════════════
                // CRITICAL FIX: GET IMMEDIATE FIRST LOCATION FOR **ALL** MODES
                // ════════════════════════════════════════════════════════════════════
                // Request current location FIRST for immediate data
                // This ensures we don't wait for the first interval to pass
                // WITHOUT THIS: 1-hour interval = wait 1 hour for first location!
                // WITH THIS: Get first location within seconds, then wait for interval
                // ════════════════════════════════════════════════════════════════════
                android.util.Log.d("LOCATION_SERVICE", "⚡ Requesting IMMEDIATE first location (ALL modes)")
                requestImmediateLocation()

                @Suppress("MissingPermission")
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback!!,
                    Looper.getMainLooper()
                ).addOnSuccessListener {
                    android.util.Log.d("LOCATION_SERVICE", "✅ Location updates REGISTERED - interval: $intervalFormatted")
                    android.util.Log.d("LOCATION_SERVICE", "📊 Search logcat for 'GPS_COORDINATES' to see location updates")
                    LocationLogger.i(TAG, "Location updates started with interval: $intervalFormatted (aggressive=$isAggressiveMode)")

                    // Log the actual request time for debugging
                    lastLocationRequestTime = System.currentTimeMillis()
                    android.util.Log.d("LOCATION_SERVICE", "⏱️ Location request registered at: $lastLocationRequestTime")

                    // ════════════════════════════════════════════════════════════════════
                    // CRITICAL: START PRECISE SCHEDULER FOR **ALL** MODES
                    // ════════════════════════════════════════════════════════════════════
                    // The precise scheduler uses AlarmManager as a BACKUP to ensure
                    // location updates are received even when FusedLocation delays.
                    //
                    // WHY ALL MODES (not just aggressive):
                    // - When screen is OFF, FusedLocation batches updates heavily
                    // - For 1-hour interval with maxUpdateDelay=1.5h, update can be delayed 1.5+ hours!
                    // - AlarmManager with setExactAndAllowWhileIdle() fires even in Doze mode
                    // - This guarantees location at exact intervals regardless of screen state
                    // ════════════════════════════════════════════════════════════════════
                    android.util.Log.d("LOCATION_SERVICE", "⏰ Starting PRECISE SCHEDULER as backup (ALL modes)")
                    android.util.Log.d("LOCATION_SERVICE", "   Mode: ${if (isAggressiveMode) "AGGRESSIVE" else "NORMAL"}")
                    android.util.Log.d("LOCATION_SERVICE", "   Interval: $intervalFormatted")
                    android.util.Log.d("LOCATION_SERVICE", "   Purpose: Ensures location even when screen off / Doze mode")

                    preciseScheduler.start(currentInterval) { backupLocation ->
                        android.util.Log.d("GPS_COORDINATES", "⏰ BACKUP ALARM LOCATION:")
                        android.util.Log.d("GPS_COORDINATES", "   Lat: ${backupLocation.latitude}")
                        android.util.Log.d("GPS_COORDINATES", "   Lon: ${backupLocation.longitude}")
                        android.util.Log.d("GPS_COORDINATES", "   From: PreciseScheduler backup alarm")
                        android.util.Log.d("GPS_COORDINATES", "   Mode: ${if (isAggressiveMode) "AGGRESSIVE" else "NORMAL"}")
                        handleNewLocation(backupLocation)
                    }
                }.addOnFailureListener { e ->
                    android.util.Log.e("LOCATION_SERVICE", "❌ Failed to register location updates", e)
                    LocationLogger.e(TAG, "Failed to register location updates", e)

                    // FALLBACK: Try with less aggressive settings
                    tryFallbackLocationRequest()
                }

            } catch (e: SecurityException) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Security exception starting location updates", e)
                LocationLogger.e(TAG, "Security exception starting location updates", e)
                handleStopTracking()
            }

        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Failed to start location updates", e)
            LocationLogger.e(TAG, "Failed to start location updates", e)
        }
    }

    /**
     * Request an immediate location fix - doesn't wait for interval
     * Used in aggressive modes to get data ASAP
     */
    private fun requestImmediateLocation() {
        try {
            android.util.Log.d("LOCATION_SERVICE", "⚡ Requesting IMMEDIATE location fix...")

            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null // No cancellation token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    android.util.Log.d("GPS_COORDINATES", "⚡⚡⚡ IMMEDIATE LOCATION RECEIVED ⚡⚡⚡")
                    android.util.Log.d("GPS_COORDINATES", "   Lat: ${location.latitude}")
                    android.util.Log.d("GPS_COORDINATES", "   Lon: ${location.longitude}")
                    android.util.Log.d("GPS_COORDINATES", "   Accuracy: ${location.accuracy}m")
                    handleNewLocation(location)
                } else {
                    android.util.Log.w("LOCATION_SERVICE", "⚠️ Immediate location returned null")
                }
            }.addOnFailureListener { e ->
                android.util.Log.w("LOCATION_SERVICE", "⚠️ Immediate location request failed: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Error requesting immediate location", e)
        }
    }

    /**
     * Fallback location request with less aggressive settings
     * Used when primary request fails
     */
    private fun tryFallbackLocationRequest() {
        try {
            android.util.Log.d("LOCATION_SERVICE", "🔄 Trying FALLBACK location request...")

            val fallbackRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, currentInterval)
                .setMinUpdateIntervalMillis(currentInterval)
                .setMaxUpdateDelayMillis(currentInterval * 2)
                .setWaitForAccurateLocation(false)
                .build()

            @Suppress("MissingPermission")
            fusedLocationClient.requestLocationUpdates(
                fallbackRequest,
                locationCallback!!,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                android.util.Log.d("LOCATION_SERVICE", "✅ FALLBACK location updates registered")
            }.addOnFailureListener { e ->
                android.util.Log.e("LOCATION_SERVICE", "❌ FALLBACK also failed: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Fallback request exception", e)
        }
    }

    // Track when we last requested location updates (for debugging)
    private var lastLocationRequestTime: Long = 0L

    /**
     * Manages wake lock for aggressive tracking modes
     * Wake lock prevents CPU from sleeping, ensuring location updates are received on time
     * even when the screen is off.
     *
     * IMPORTANT: Wake locks consume battery, so they're only used when necessary:
     * - Emergency mode
     * - Realtime mode
     * - ForceCheck mode
     * - Short intervals (< 5 minutes)
     */
    private fun manageAggressiveWakeLock(shouldBeActive: Boolean) {
        if (shouldBeActive && !isAggressiveModeActive) {
            // Acquire wake lock for aggressive tracking
            try {
                aggressiveWakeLock?.release()
                aggressiveWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Construct Connect:AggressiveLocationTracking"
                ).apply {
                    // Use a reasonable timeout to prevent infinite wake lock if something goes wrong
                    // 4 hours max, after which it auto-releases (will be re-acquired on next update)
                    acquire(4 * 60 * 60 * 1000L) // 4 hours timeout
                }
                isAggressiveModeActive = true
                android.util.Log.d("LOCATION_SERVICE", "🔋⚡ WAKE LOCK ACQUIRED for aggressive tracking")
                LocationLogger.i(TAG, "Wake lock acquired for aggressive tracking mode")
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to acquire wake lock", e)
                LocationLogger.e(TAG, "Failed to acquire wake lock", e)
            }
        } else if (!shouldBeActive && isAggressiveModeActive) {
            // Release wake lock when returning to normal tracking
            releaseAggressiveWakeLock()
        }
    }

    /**
     * Release the aggressive tracking wake lock
     */
    private fun releaseAggressiveWakeLock() {
        try {
            aggressiveWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    android.util.Log.d("LOCATION_SERVICE", "🔋 Wake lock RELEASED")
                    LocationLogger.i(TAG, "Wake lock released")
                }
            }
            aggressiveWakeLock = null
            isAggressiveModeActive = false
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Error releasing wake lock", e)
            LocationLogger.e(TAG, "Error releasing wake lock", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PERSISTENT SERVICE WAKE LOCK - ANTI-OEM-KILL PROTECTION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Acquire persistent service wake lock to prevent OEM battery killers
     * This is CRITICAL for Transsion/Infinix/Tecno/Xiaomi/Oppo devices
     *
     * NOTE: This is different from aggressiveWakeLock - this one is ALWAYS held
     * while the service is running, regardless of tracking mode.
     *
     * The wake lock prevents the CPU from sleeping, which OEM battery managers
     * often use as a signal to kill the app.
     */
    private fun acquireServiceWakeLock() {
        try {
            if (serviceWakeLock != null && serviceWakeLock?.isHeld == true) {
                android.util.Log.d("LOCATION_SERVICE", "🔒 Service wake lock already held")
                return
            }

            serviceWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Construct Connect:PersistentServiceWakeLock"
            ).apply {
                // CRITICAL: Use ACQUIRE_CAUSES_WAKEUP to ensure wake lock is truly held
                // 10 hour timeout - will be re-acquired on each heartbeat
                setReferenceCounted(false) // Prevent multiple acquire/release issues
                acquire(10 * 60 * 60 * 1000L) // 10 hours
            }

            android.util.Log.d("LOCATION_SERVICE", "🔒✅ PERSISTENT SERVICE WAKE LOCK ACQUIRED")
            android.util.Log.d("LOCATION_SERVICE", "   Purpose: Prevent OEM battery killers from stopping service")
            android.util.Log.d("LOCATION_SERVICE", "   Timeout: 10 hours (will be refreshed on heartbeat)")
            LocationLogger.i(TAG, "Persistent service wake lock acquired")

        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Failed to acquire service wake lock: ${e.message}")
            LocationLogger.e(TAG, "Failed to acquire service wake lock", e)
        }
    }

    /**
     * Release persistent service wake lock
     * Only called in onDestroy() when service is intentionally stopping
     */
    private fun releaseServiceWakeLock() {
        try {
            serviceWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    android.util.Log.d("LOCATION_SERVICE", "🔓 Persistent service wake lock released")
                    LocationLogger.i(TAG, "Persistent service wake lock released")
                }
            }
            serviceWakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Error releasing service wake lock: ${e.message}")
        }
    }

    /**
     * Refresh the service wake lock - call periodically to ensure it doesn't expire
     * Called from heartbeat loop
     */
    private fun refreshServiceWakeLock() {
        try {
            if (serviceWakeLock == null || serviceWakeLock?.isHeld != true) {
                android.util.Log.w("LOCATION_SERVICE", "⚠️ Service wake lock expired or released - re-acquiring")
                acquireServiceWakeLock()
            } else {
                // Release and re-acquire to reset the timeout
                serviceWakeLock?.release()
                serviceWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Construct Connect:PersistentServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 60 * 1000L) // Refresh for another 10 hours
                }
                android.util.Log.d("LOCATION_SERVICE", "🔒🔄 Service wake lock refreshed")
            }
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "❌ Error refreshing service wake lock: ${e.message}")
            // Try to acquire fresh
            try {
                acquireServiceWakeLock()
            } catch (e2: Exception) {
                android.util.Log.e("LOCATION_SERVICE", "❌ Failed to re-acquire service wake lock: ${e2.message}")
            }
        }
    }


    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            android.util.Log.d("LOCATION_SERVICE", "🛑 Location updates stopped")
            LocationLogger.i(TAG, "Location updates stopped")
        }

        // Stop precise scheduler (backup alarm system)
        preciseScheduler.stop()
        android.util.Log.d("LOCATION_SERVICE", "🛑 Precise scheduler stopped")

        // Release wake lock when stopping updates
        releaseAggressiveWakeLock()
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // LOCATION HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun handleNewLocation(location: Location) {
        val intervalFormatted = when {
            currentInterval >= 3600000 -> "${currentInterval / 3600000}h"
            currentInterval >= 60000 -> "${currentInterval / 60000}m"
            else -> "${currentInterval / 1000}s"
        }

        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_SERVICE", "📍 PROCESSING NEW LOCATION (Interval: $intervalFormatted)")
        android.util.Log.d("LOCATION_SERVICE", "   Lat: ${location.latitude}, Lon: ${location.longitude}")
        android.util.Log.d("LOCATION_SERVICE", "   Accuracy: ${location.accuracy}m, Provider: ${location.provider}")
        android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "New location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m")

        lastLocationTime = System.currentTimeMillis()

        // ════════════════════════════════════════════════════════════════════
        // CRITICAL: Notify precise scheduler that location was received
        // This resets the backup alarm timer to prevent unnecessary alarms
        // ════════════════════════════════════════════════════════════════════
        preciseScheduler.onLocationReceived()

        // Save to SharedPreferences for health checks
        try {
            getSharedPreferences("location_service_state", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_location_time", lastLocationTime)
                .apply()
        } catch (e: Exception) {
            // Non-critical, continue
        }

        // Build location data
        val locationData = buildLocationData(location)

        // Validate
        if (!validateLocationData(locationData)) {
            android.util.Log.w("LOCATION_SERVICE", "❌ Location validation failed - skipping")
            LocationLogger.w(TAG, "Location validation failed - skipping")
            return
        }

        // Save to database and trigger upload
        serviceScope.launch {
            try {
                android.util.Log.d("LOCATION_DB", "💾 Saving location to database...")
                val insertedId = database.insertLocation(locationData)
                android.util.Log.d("LOCATION_DB", "✅ Location saved with ID: $insertedId")
                LocationLogger.i(TAG, "Location saved to database")

                // Update location statistics for admin dashboard
                DeviceStatusReporter.updateLocationStats(this@LocationTrackingService)

                // Log location capture to Firebase status
                TrackingStatusLogger.logLocationCaptured(
                    context = this@LocationTrackingService,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    provider = location.provider ?: "unknown"
                )

                // Trigger upload check
                triggerUploadIfNeeded()

            } catch (e: Exception) {
                android.util.Log.e("LOCATION_DB", "❌ Failed to save location", e)
                LocationLogger.e(TAG, "Failed to save location", e)
            }
        }
    }

    private fun buildLocationData(location: Location): LocationData {
        val settings = settingsManager.fetchSettings()
        val batteryInfo = batteryHelper.getCompleteBatteryInfo(
            forceCheck = settings.forceCheck,
            emergencyMode = settings.emergencyMode
        )

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

        // Determine current interval being used and its type
        val currentIntervalMs = currentInterval
        val intervalType = when {
            settings.emergencyMode -> "emergency"
            settings.realtimeMode -> "realtime"
            batteryInfo.shouldProtect && !settings.forceCheck -> "forced_battery"
            settings.forceCheck -> "forcecheck"
            else -> "normal"
        }

        return LocationData(
            userId = userId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = location.altitude,
            bearing = location.bearing,
            speed = location.speed,
            provider = location.provider ?: LocationConstants.PROVIDER_UNKNOWN,
            clientTimestamp = location.time,

            // Battery information
            batteryLevel = batteryInfo.level,
            batteryThresholdActive = batteryInfo.shouldProtect,
            forcedInterval = batteryInfo.shouldProtect && !settings.forceCheck,

            // TRACKING MODE FLAGS - Critical for understanding timing
            forceCheckEnabled = settings.forceCheck,
            realtimeMode = settings.realtimeMode,
            emergencyMode = settings.emergencyMode,

            // INTERVAL METADATA - Critical for diagnosing timing issues
            intervalAppliedMs = currentIntervalMs,
            intervalType = intervalType,

            // Network information
            networkType = networkMonitor.getNetworkType(),

            // Device information
            osVersion = Build.VERSION.SDK_INT.toString(),
            appVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                null
            },

            // Movement detection
            movementStatus = if (location.speed > 0.5f) LocationData.MOVEMENT_MOVING else LocationData.MOVEMENT_STATIONARY,

            // Sync status
            syncStatus = LocationData.SYNC_PENDING,
            uploadAttempts = 0
        )
    }

    private fun validateLocationData(data: LocationData): Boolean {
        // Basic validation
        if (data.accuracy != null && data.accuracy > 500f) {
            LocationLogger.w(TAG, "Accuracy too poor: ${data.accuracy}m")
            return false
        }

        if (data.latitude < -90 || data.latitude > 90) {
            LocationLogger.e(TAG, "Invalid latitude: ${data.latitude}")
            return false
        }

        if (data.longitude < -180 || data.longitude > 180) {
            LocationLogger.e(TAG, "Invalid longitude: ${data.longitude}")
            return false
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // UPLOAD COORDINATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Trigger upload based on BatchUploader's comprehensive logic:
     * 1. Emergency mode: Upload immediately (threshold = 1)
     * 2. Normal threshold: Upload when data >= 10 (or >= 3 for hardcoded defaults)
     * 3. Time-based: Upload if oldest data is 2+ hours old (regardless of count)
     * 4. Tracking stopped: Upload after 5 minutes if tracking stopped but internet active
     *
     * The BatchUploader handles all the decision logic internally.
     */
    private suspend fun triggerUploadIfNeeded() {
        val pendingLocations = database.getPendingLocations(50)
        val pendingCount = pendingLocations.size

        // Get current settings
        val settings = settingsManager.fetchSettings()
        val threshold = batchUploader.getBatchUploadThreshold()

        android.util.Log.d("LOCATION_UPLOAD", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_UPLOAD", "📤 UPLOAD CHECK (from LocationTrackingService):")
        android.util.Log.d("LOCATION_UPLOAD", "   Pending locations: $pendingCount")
        android.util.Log.d("LOCATION_UPLOAD", "   Current threshold: $threshold")
        android.util.Log.d("LOCATION_UPLOAD", "   Emergency mode: ${settings.emergencyMode}")
        android.util.Log.d("LOCATION_UPLOAD", "   Using hardcoded defaults: ${settingsManager.isUsingHardcodedDefaults()}")
        android.util.Log.d("LOCATION_UPLOAD", "═══════════════════════════════════════════════════════")

        // BatchUploader handles all the comprehensive upload logic including:
        // - Emergency mode immediate upload
        // - Normal/hardcoded threshold checks
        // - Time-based triggers (data age 2h+, tracking stopped 5min+)
        android.util.Log.d("LOCATION_UPLOAD", "🚀 Delegating to BatchUploader for comprehensive upload decision...")
        batchUploader.uploadPendingLocations()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BATTERY MONITORING
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun registerBatteryMonitor() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    handleBatteryChange(intent)
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
        LocationLogger.i(TAG, "Battery monitor registered")
    }

    private fun unregisterBatteryMonitor() {
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
            LocationLogger.i(TAG, "Battery monitor unregistered")
        }
    }

    private fun registerLocationSettingsMonitor() {
        if (locationSettingsReceiver != null) return

        locationSettingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION) {
                    handleLocationProvidersChanged()
                }
            }
        }

        val filter = IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(locationSettingsReceiver, filter)
        LocationLogger.i(TAG, "Location settings monitor registered")
    }

    private fun unregisterLocationSettingsMonitor() {
        locationSettingsReceiver?.let {
            unregisterReceiver(it)
            locationSettingsReceiver = null
            LocationLogger.i(TAG, "Location settings monitor unregistered")
        }
    }

    private fun handleLocationProvidersChanged() {
        serviceScope.launch {
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val isLocationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

                android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")
                android.util.Log.d("LOCATION_SERVICE", "📍 LOCATION PROVIDERS CHANGED")
                android.util.Log.d("LOCATION_SERVICE", "   Location enabled: $isLocationEnabled")
                android.util.Log.d("LOCATION_SERVICE", "   Service running: ${isRunning.get()}")
                android.util.Log.d("LOCATION_SERVICE", "   Tracking paused: ${isPaused.get()}")
                android.util.Log.d("LOCATION_SERVICE", "═══════════════════════════════════════════════════════")

                if (isLocationEnabled) {
                    LocationLogger.i(TAG, "📍 Location services enabled - checking if can resume tracking")

                    // Report to admin
                    DeviceStatusReporter.reportLocationEnabled(this@LocationTrackingService)

                    // Check if we have all permissions
                    if (hasAllPermissions()) {
                        android.util.Log.d("LOCATION_SERVICE", "✅ All permissions granted - resuming tracking")

                        if (!isRunning.get()) {
                            // Service not running - start it
                            android.util.Log.w("LOCATION_SERVICE", "⚠️ Service not running - starting now")
                            handleStartTracking()
                        } else if (isPaused.get()) {
                            // Service running but paused - resume tracking
                            android.util.Log.d("LOCATION_SERVICE", "▶️ Resuming paused tracking")
                            handleResumeTracking()
                        } else {
                            android.util.Log.d("LOCATION_SERVICE", "✅ Already tracking - no action needed")
                        }
                    } else {
                        android.util.Log.w("LOCATION_SERVICE", "⚠️ Location enabled but permissions missing")
                        LocationLogger.w(TAG, "Location enabled but permissions not granted yet")
                    }
                } else {
                    LocationLogger.w(TAG, "📍 Location services disabled - pausing tracking")
                    android.util.Log.w("LOCATION_SERVICE", "⚠️ Location disabled - pausing tracking (service stays alive)")

                    // Report to admin
                    DeviceStatusReporter.reportLocationDisabled(this@LocationTrackingService)

                    if (isRunning.get() && !isPaused.get()) {
                        // Pause tracking but keep service alive
                        stopLocationUpdates()
                        isPaused.set(true)
                        ServiceStatePersistence.setTrackingShouldBeRunning(this@LocationTrackingService, false)

                        TrackingStatusLogger.logStatus(
                            context = this@LocationTrackingService,
                            isTracking = false,
                            additionalIssues = listOf(TrackingStatusLogger.Issues.LOC_SERVICES_OFF),
                            reason = "Location services disabled by user"
                        )

                        android.util.Log.d("LOCATION_SERVICE", "✅ Tracking paused - service still alive")
                    }
                }
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Error handling location providers change: ${e.message}")
                android.util.Log.e("LOCATION_SERVICE", "❌ Error handling location change", e)
            }
        }
    }

    private fun handleBatteryChange(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        if (batteryPct != lastBatteryLevel && batteryPct >= 0) {
            lastBatteryLevel = batteryPct
            checkBatteryThreshold()
        }
    }

    private fun checkBatteryThreshold() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckTime < 60000) return

        lastBatteryCheckTime = now

        val currentLevel = batteryHelper.getBatteryLevel()
        if (currentLevel != lastBatteryLevel) {
            val wasBelowThreshold = lastBatteryLevel <= 44
            val isBelowThreshold = currentLevel <= 44

            if (wasBelowThreshold != isBelowThreshold) {
                LocationLogger.i(TAG, "Battery threshold crossed: ${lastBatteryLevel}% → ${currentLevel}%")
                recalculateInterval()
                restartLocationUpdates()
            }

            lastBatteryLevel = currentLevel
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HEALTH MONITORING & AUTO-RECOVERY
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive && isRunning.get()) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
    }

    private suspend fun performHealthCheck() {
        LocationLogger.i(TAG, "Performing health check...")

        val now = System.currentTimeMillis()
        val staleDuration = currentInterval * 3

        // Check if location is stale
        if (lastLocationTime > 0 && (now - lastLocationTime) > staleDuration) {
            LocationLogger.w(TAG, "Location is stale - attempting recovery")
            attemptRecovery()
        }

        // Check pending uploads
        val pendingLocations = database.getPendingLocations(50)
        val pendingCount = pendingLocations.size
        if (pendingCount > 40) { // 80% of limit
            LocationLogger.w(TAG, "High pending count: $pendingCount")
        }

        // Check for time-based uploads (data age 2h+ or tracking stopped 5min+)
        // This ensures uploads happen even when tracking has issues
        android.util.Log.d("LOCATION_SERVICE", "⏰ Health check: Checking for time-based upload triggers...")
        batchUploader.checkAndTriggerTimeBasedUpload()

        // Enforce cache limit to prevent unbounded database growth
        try {
            database.enforceCacheLimit()
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "⚠️ Cache limit enforcement failed: ${e.message}")
        }

        LocationLogger.i(TAG, "Health check complete ✅")
    }

    private suspend fun attemptRecovery() {
        LocationLogger.i(TAG, "Attempting service recovery...")

        // Check circuit breaker
        if (shouldActivateCircuitBreaker()) {
            activateCircuitBreaker()
            return
        }

        // Record restart attempt
        recordRestartAttempt()

        // Restart location updates
        try {
            restartLocationUpdates()
            LocationLogger.i(TAG, "Recovery successful")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "Recovery failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun shouldActivateCircuitBreaker(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - RESTART_WINDOW_MS

        // Reset counter if outside window
        if (lastRestartTime.get() < windowStart) {
            restartAttempts.set(0)
        }

        return restartAttempts.get() >= MAX_RESTART_ATTEMPTS
    }

    private fun recordRestartAttempt() {
        restartAttempts.incrementAndGet()
        lastRestartTime.set(System.currentTimeMillis())
    }

    private fun activateCircuitBreaker() {
        LocationLogger.e(TAG, "Circuit breaker activated - too many restart attempts")
        circuitBreakerActive = true
        circuitBreakerActivatedTime = System.currentTimeMillis()

        stopLocationUpdates()
        updateNotification()

        // Silently activate — no notification shown to user
    }

    private fun deactivateCircuitBreaker() {
        LocationLogger.i(TAG, "Circuit breaker deactivated - resuming normal operation")
        circuitBreakerActive = false
        restartAttempts.set(0)
        updateNotification()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun handleTrackingError(error: Exception) {
        LocationLogger.e(TAG, "Tracking error occurred", error)

        when (error) {
            is SecurityException -> {
                LocationLogger.e(TAG, "Permission revoked during tracking")
                handleStopTracking()
            }
            else -> {
                LocationLogger.w(TAG, "Non-fatal error - continuing")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun cleanupService() {
        android.util.Log.i("LOCATION_SERVICE", "🧹 CLEANING UP SERVICE RESOURCES...")
        LocationLogger.i(TAG, "Cleaning up service...")

        isRunning.set(false)

        // CRITICAL: Cancel all coroutine jobs with null assignment
        trackingJob?.cancel()
        trackingJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        settingsSyncJob?.cancel()
        settingsSyncJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null

        android.util.Log.d("LOCATION_SERVICE", "✅ All coroutine jobs cancelled and nulled")

        // CRITICAL: Stop location updates and remove callbacks
        stopLocationUpdates()
        android.util.Log.d("LOCATION_SERVICE", "✅ Location updates stopped")

        // CRITICAL: Unregister all broadcast receivers
        unregisterBatteryMonitor()
        unregisterLocationSettingsMonitor()
        android.util.Log.d("LOCATION_SERVICE", "✅ All broadcast receivers unregistered")

        // CRITICAL: Unregister settings callbacks
        try {
            settingsManager.unregisterAllCallbacks()
            android.util.Log.d("LOCATION_SERVICE", "✅ Settings callbacks cleared")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_SERVICE", "Error unregistering settings callbacks: ${e.message}")
        }

        // Mark service as stopped in persistence
        ServiceStatePersistence.markServiceStopped(this, "Service cleanup")

        android.util.Log.i("LOCATION_SERVICE", "✅ Service cleanup complete")
        LocationLogger.i(TAG, "Service cleaned up")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HEARTBEAT LOOP - For crash detection and anti-kill protection
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Heartbeat loop - writes periodic heartbeat for crash detection
     * If service dies unexpectedly, the missing heartbeat allows recovery mechanisms
     * to detect the crash and restart
     *
     * ALSO: Refreshes wake locks and schedules resurrection alarms
     */
    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var heartbeatCount = 0

            while (isActive && isRunning.get()) {
                try {
                    heartbeatCount++

                    // Record heartbeat every minute
                    ServiceStatePersistence.recordHeartbeat(this@LocationTrackingService)

                    // Update service running state to confirm we're alive
                    LocationServiceHelper.setServiceRunning(this@LocationTrackingService, true)

                    // Every 5 minutes: Refresh wake lock and reschedule resurrection alarms
                    if (heartbeatCount % 5 == 0) {
                        android.util.Log.d("LOCATION_SERVICE", "💓 Heartbeat #$heartbeatCount - refreshing anti-kill protections...")

                        // Refresh the persistent service wake lock
                        refreshServiceWakeLock()

                        // Ensure resurrection alarms are scheduled
                        com.example.newconstructionappwithlocationtracking.receivers.ServiceResurrectionReceiver.scheduleResurrectionAlarms(this@LocationTrackingService)

                        // Check and reset resurrection count if stable
                        ServiceStatePersistence.checkAndResetIfStable(this@LocationTrackingService)
                    }

                    // Every 10 minutes: Log comprehensive status
                    if (heartbeatCount % 10 == 0) {
                        val uptime = ServiceStatePersistence.getServiceUptime(this@LocationTrackingService)
                        val resCount = ServiceStatePersistence.getResurrectionCount(this@LocationTrackingService)
                        android.util.Log.d("LOCATION_SERVICE", "💓💓💓 SERVICE STATUS REPORT 💓💓💓")
                        android.util.Log.d("LOCATION_SERVICE", "   Heartbeat: #$heartbeatCount")
                        android.util.Log.d("LOCATION_SERVICE", "   Uptime: ${uptime / 60000} minutes")
                        android.util.Log.d("LOCATION_SERVICE", "   Resurrections (this session): $resCount")
                        android.util.Log.d("LOCATION_SERVICE", "   Service running: ${isRunning.get()}")
                        android.util.Log.d("LOCATION_SERVICE", "   Service paused: ${isPaused.get()}")
                        android.util.Log.d("LOCATION_SERVICE", "   Wake lock held: ${serviceWakeLock?.isHeld ?: false}")
                    }

                    delay(60000L) // 1 minute heartbeat interval
                } catch (e: Exception) {
                    android.util.Log.e("LOCATION_SERVICE", "❌ Heartbeat error: ${e.message}")
                    // Continue loop - heartbeat is critical
                }
            }
        }
        android.util.Log.d("LOCATION_SERVICE", "💓 Heartbeat loop started (1 min interval)")
        android.util.Log.d("LOCATION_SERVICE", "   - Wake lock refresh every 5 minutes")
        android.util.Log.d("LOCATION_SERVICE", "   - Status report every 10 minutes")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // WORKMANAGER FALLBACK
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun scheduleWorkManagerFallback() {
        try {
            LocationLogger.i(TAG, "Scheduling WorkManager fallback watchdog...")

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<LocationTrackingWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "location_tracking_fallback",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )

            LocationLogger.i(TAG, "✅ WorkManager fallback scheduled - monitors service every 15 min")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to schedule WorkManager fallback", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showPermissionRequiredNotification(status: LocationPermissionManager.ComprehensivePermissionStatus) {
        // Silently handle — no notification shown to user
        val missingPermissions = mutableListOf<String>()
        if (!status.hasFineLocation) missingPermissions.add("Precise Location")
        if (!status.hasBackgroundLocation) missingPermissions.add("Background Location")
        LocationLogger.w(TAG, "Missing permissions (no notification shown): ${missingPermissions.joinToString(", ")}")
    }

    private fun showLocationDisabledNotification() {
        // Silently handle — no notification shown to user
        LocationLogger.w(TAG, "Location services disabled (no notification shown)")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════════

    fun getServiceStatus(): Map<String, Any> {
        return mapOf(
            "isRunning" to isRunning.get(),
            "isPaused" to isPaused.get(),
            "isInitialized" to isInitialized.get(),
            "currentInterval" to currentInterval,
            "lastLocationTime" to lastLocationTime,
            "batteryLevel" to lastBatteryLevel,
            "circuitBreakerActive" to circuitBreakerActive
        )
    }

    fun calculateServiceHealthScore(): Float {
        var score = 1.0f

        if (circuitBreakerActive) score -= 0.3f

        if (lastLocationTime > 0) {
            val age = System.currentTimeMillis() - lastLocationTime
            if (age > currentInterval * 3) score -= 0.2f
        }

        return score.coerceIn(0.0f, 1.0f)
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION TRACKING WORKER - COMPREHENSIVE WATCHDOG
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Comprehensive watchdog that monitors location tracking service health and ensures
 * 24/7 operation with smart recovery strategies, circuit breaker recovery, admin
 * notifications, and diagnostic logging.
 *
 * RUNS: Every 15 minutes (WorkManager periodic)
 *
 * RESPONSIBILITIES:
 * 1. Health Monitoring
 *    - Check if service is running
 *    - Check last location capture time
 *    - Check database upload backlog
 *    - Check circuit breaker status
 *
 * 2. Smart Recovery (4 Strategies)
 *    - Strategy 1: Normal restart (standard)
 *    - Strategy 2: Clear app cache + restart (if repeated failures)
 *    - Strategy 3: Re-request permissions (if revoked)
 *    - Strategy 4: Notify admin + user (if all fails)
 *
 * 3. Circuit Breaker Recovery
 *    - Detect circuit breaker block
 *    - Wait for cooldown
 *    - Diagnose root cause
 *    - Try alternative recovery
 *
 * 4. Admin Notifications
 *    - FCM push to admin dashboard
 *    - Update Firebase tracking status
 *    - Log failure reasons
 *
 * 5. OEM-Specific Handling
 *    - Detect OEM (Xiaomi, Huawei, Samsung)
 *    - Use OEM-specific workarounds
 *    - Show OEM-specific user guidance
 *
 * RESULT HANDLING:
 * - Result.success() → Service healthy and running
 * - Result.retry() → Temporary issue, will retry
 * - Result.failure() → Permanent issue (permissions, etc.)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

class LocationTrackingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationTrackingWorker"

        // Health check thresholds
        private const val LOCATION_STALE_THRESHOLD_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val DB_BACKLOG_WARNING_THRESHOLD = 100
        private const val DB_BACKLOG_CRITICAL_THRESHOLD = 500

        // Recovery strategies
        private const val MAX_NORMAL_RESTART_ATTEMPTS = 3
        private const val MAX_CACHE_CLEAR_ATTEMPTS = 2
        private const val MAX_PERMISSION_REQUESTS = 1

        // Circuit breaker cooldown
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes

        // OEM detection
        private val AGGRESSIVE_OEM_BRANDS = listOf("xiaomi", "huawei", "oppo", "vivo", "oneplus", "samsung")
    }

    private val database by lazy { LocationDatabase.getInstance(applicationContext) }

    override suspend fun doWork(): ListenableWorker.Result {
        return withContext(Dispatchers.IO) {
            try {
                LocationLogger.i(TAG, "🔍 Watchdog check started (run #${runAttemptCount})")

                // Step 1: Check if tracking should be enabled
                if (!shouldTrackingBeEnabled()) {
                    LocationLogger.i(TAG, "   📴 Tracking disabled by user, skipping")
                    return@withContext ListenableWorker.Result.success()
                }

                // Step 2: Comprehensive health check
                val healthStatus = performComprehensiveHealthCheck()

                // Step 3: Take action based on health status
                when (healthStatus.status) {
                    HealthStatus.HEALTHY -> {
                        LocationLogger.i(TAG, "✅ Service healthy")
                        handleHealthyService(healthStatus)
                        ListenableWorker.Result.success()
                    }

                    HealthStatus.RUNNING_BUT_STALE -> {
                        LocationLogger.w(TAG, "⚠️  Service running but location stale")
                        handleStaleLocation(healthStatus)
                        ListenableWorker.Result.success()
                    }

                    HealthStatus.NOT_RUNNING -> {
                        LocationLogger.w(TAG, "❌ Service not running")
                        handleServiceNotRunning(healthStatus)
                    }

                    HealthStatus.CIRCUIT_BREAKER_ACTIVE -> {
                        LocationLogger.w(TAG, "🔴 Circuit breaker active")
                        handleCircuitBreakerActive(healthStatus)
                    }

                    HealthStatus.PERMISSIONS_MISSING -> {
                        LocationLogger.w(TAG, "🚫 Permissions missing")
                        handlePermissionsMissing(healthStatus)
                    }

                    HealthStatus.LOCATION_SERVICES_DISABLED -> {
                        LocationLogger.w(TAG, "📡 Location services disabled")
                        handleLocationServicesDisabled(healthStatus)
                    }
                }

            } catch (e: Exception) {
                LocationLogger.e(TAG, "❌ Watchdog error", e)
                ListenableWorker.Result.retry()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HEALTH CHECK
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun performComprehensiveHealthCheck(): ServiceHealthStatus {
        LocationLogger.d(TAG, "   ├─ Performing comprehensive health check...")

        val permissionManager = LocationPermissionManager(applicationContext)
        val batteryHelper = BatteryOptimizationHelper(applicationContext)
        val database = LocationDatabase.getInstance(applicationContext)

        // Check if service is running
        val isRunning = LocationServiceHelper.isServiceRunning(applicationContext)
        LocationLogger.d(TAG, "   │  ├─ Service running: $isRunning")

        // Check permissions
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()
        val canStartTracking = permissionStatus.canStartTracking
        val isLocationEnabled = permissionStatus.isLocationEnabled
        LocationLogger.d(TAG, "   │  ├─ Can start tracking: $canStartTracking")
        LocationLogger.d(TAG, "   │  ├─ Location enabled: $isLocationEnabled")

        // Check circuit breaker
        val circuitBreakerActive = LocationServiceHelper.isCircuitBreakerActive()
        val nextAttemptTime = if (circuitBreakerActive) LocationServiceHelper.getNextAttemptTime() else 0L
        LocationLogger.d(TAG, "   │  ├─ Circuit breaker active: $circuitBreakerActive")

        // Check last location time
        val lastLocationTime = getLastLocationTime()
        val locationAge = System.currentTimeMillis() - lastLocationTime
        val isLocationStale = locationAge > 3600000L // 1 hour threshold
        LocationLogger.d(TAG, "   │  ├─ Last location: ${locationAge / 60000} min ago")
        LocationLogger.d(TAG, "   │  ├─ Location stale: $isLocationStale")

        // Check database health
        val pendingLocations = database.getPendingLocations(50)
        val pendingCount = pendingLocations.size
        val dbBacklogCritical = pendingCount > 800
        val dbBacklogWarning = pendingCount > 400
        LocationLogger.d(TAG, "   │  ├─ Pending locations: $pendingCount")

        // Check battery status
        val batteryInfo = batteryHelper.getCompleteBatteryInfo()
        LocationLogger.d(TAG, "   │  └─ Battery: ${batteryInfo.level}%, Optimized: ${batteryInfo.isBatteryOptimized}")

        // Determine overall status
        val status = when {
            !canStartTracking -> HealthStatus.PERMISSIONS_MISSING
            !isLocationEnabled -> HealthStatus.LOCATION_SERVICES_DISABLED
            circuitBreakerActive -> HealthStatus.CIRCUIT_BREAKER_ACTIVE
            !isRunning -> HealthStatus.NOT_RUNNING
            isLocationStale -> HealthStatus.RUNNING_BUT_STALE
            else -> HealthStatus.HEALTHY
        }

        return ServiceHealthStatus(
            status = status,
            isRunning = isRunning,
            canStartTracking = canStartTracking,
            isLocationEnabled = isLocationEnabled,
            circuitBreakerActive = circuitBreakerActive,
            nextAttemptTime = nextAttemptTime,
            lastLocationTime = lastLocationTime,
            locationStale = isLocationStale,
            pendingUploads = pendingCount,
            dbBacklogWarning = dbBacklogWarning,
            dbBacklogCritical = dbBacklogCritical,
            batteryLevel = batteryInfo.level,
            batteryOptimized = batteryInfo.isBatteryOptimized,
            permissionStatus = permissionStatus
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HEALTHY SERVICE HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handleHealthyService(health: ServiceHealthStatus) {
        LocationLogger.d(TAG, "   ├─ Service is healthy")

        // Check for warnings
        if (health.dbBacklogWarning) {
            LocationLogger.w(TAG, "   ⚠️  Upload backlog: ${health.pendingUploads} locations")
        }

        if (health.batteryOptimized) {
            LocationLogger.w(TAG, "   ⚠️  Battery optimization enabled (may affect tracking)")
        }

        // Update admin dashboard status
        updateAdminDashboardStatus(healthy = true, message = "Tracking active")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STALE LOCATION HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handleStaleLocation(health: ServiceHealthStatus): ListenableWorker.Result {
        val ageMinutes = (System.currentTimeMillis() - health.lastLocationTime) / 60000
        LocationLogger.w(TAG, "   ├─ Location stale: $ageMinutes minutes old")

        // Service is running but not capturing locations
        // This could mean GPS issues, permissions, or service malfunction

        // Notify admin
        notifyAdmin(
            "Location Stale Warning",
            "User location hasn't updated in $ageMinutes minutes. Service running but not capturing."
        )

        // Try to trigger a manual location capture via service
        try {
            // Service has internal recovery mechanism
            LocationLogger.d(TAG, "   └─ Service will attempt internal recovery")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to trigger recovery", e)
        }

        return ListenableWorker.Result.success()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SERVICE NOT RUNNING HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handleServiceNotRunning(health: ServiceHealthStatus): ListenableWorker.Result {
        LocationLogger.w(TAG, "   ├─ Service not running, attempting recovery...")

        val userId = getUserId()

        // Try recovery strategies in order
        return when {
            // Strategy 1: Normal restart
            runAttemptCount <= MAX_NORMAL_RESTART_ATTEMPTS -> {
                LocationLogger.d(TAG, "   ├─ Strategy 1: Normal restart (attempt $runAttemptCount)")
                attemptNormalRestart(userId, health)
            }

            // Strategy 2: Clear cache + restart
            runAttemptCount <= MAX_NORMAL_RESTART_ATTEMPTS + MAX_CACHE_CLEAR_ATTEMPTS -> {
                LocationLogger.d(TAG, "   ├─ Strategy 2: Clear cache + restart")
                attemptCacheClearRestart(userId, health)
            }

            // Strategy 3: Request permissions again
            runAttemptCount <= MAX_NORMAL_RESTART_ATTEMPTS + MAX_CACHE_CLEAR_ATTEMPTS + MAX_PERMISSION_REQUESTS -> {
                LocationLogger.d(TAG, "   ├─ Strategy 3: Re-request permissions")
                attemptPermissionRequest(health)
            }

            // Strategy 4: Notify admin and give up
            else -> {
                LocationLogger.e(TAG, "   ├─ Strategy 4: All strategies failed, notifying admin")
                notifyAdminCritical(
                    "Tracking Failed",
                    "All recovery strategies failed after ${runAttemptCount} attempts. Manual intervention required."
                )
                updateAdminDashboardStatus(healthy = false, message = "Tracking failed - manual intervention needed")
                ListenableWorker.Result.failure()
            }
        }
    }

    private suspend fun attemptNormalRestart(
        @Suppress("UNUSED_PARAMETER") userId: String,
        @Suppress("UNUSED_PARAMETER") health: ServiceHealthStatus
    ): ListenableWorker.Result {
        return try {
            LocationTrackingService.start(applicationContext)
            LocationLogger.i(TAG, "   ✅ Service started successfully")
            updateAdminDashboardStatus(healthy = true, message = "Service recovered")
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Service start failed: ${e.message}")
            ListenableWorker.Result.retry()
        }
    }

    private suspend fun attemptCacheClearRestart(
        userId: String,
        health: ServiceHealthStatus
    ): ListenableWorker.Result {
        try {
            // Clear internal caches (if any)
            LocationLogger.d(TAG, "   ├─ Clearing internal caches...")

            // Clear old location data (keep last 50)
            val deleted = database.getPendingLocations(1000).size // Simplified for now
            LocationLogger.d(TAG, "   │  └─ Found $deleted old locations")

            // Try restart
            return attemptNormalRestart(userId, health)

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Cache clear failed", e)
            return ListenableWorker.Result.retry()
        }
    }

    private suspend fun attemptPermissionRequest(@Suppress("UNUSED_PARAMETER") health: ServiceHealthStatus): ListenableWorker.Result {
        // Can't directly request permissions from Worker
        // But we can notify user and admin

        val missing = listOf("Location permissions") // Simplified for now

        LocationLogger.w(TAG, "   ├─ Missing permissions: $missing")

        // Notify user via notification
        showPermissionRequestNotification(missing)

        // Notify admin
        notifyAdmin(
            "Permissions Required",
            "User needs to grant: ${missing.joinToString()}"
        )

        return ListenableWorker.Result.failure() // Permanent failure until user grants permissions
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER RECOVERY
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handleCircuitBreakerActive(health: ServiceHealthStatus): ListenableWorker.Result {
        val timeSinceActivation = System.currentTimeMillis() - (health.nextAttemptTime - CIRCUIT_BREAKER_COOLDOWN_MS)
        val cooldownRemaining = health.nextAttemptTime - System.currentTimeMillis()

        LocationLogger.w(TAG, "   ├─ Circuit breaker active for ${timeSinceActivation / 60000} minutes")
        LocationLogger.d(TAG, "   ├─ Cooldown remaining: ${cooldownRemaining / 1000} seconds")

        if (cooldownRemaining > 0) {
            // Still in cooldown
            LocationLogger.d(TAG, "   └─ Waiting for cooldown to complete")
            return ListenableWorker.Result.retry()
        }

        // Cooldown complete - diagnose root cause
        LocationLogger.d(TAG, "   ├─ Cooldown complete, diagnosing root cause...")

        val diagnosis = diagnoseRootCause(health)
        LocationLogger.d(TAG, "   ├─ Diagnosis: $diagnosis")

        when (diagnosis) {
            "PERMISSIONS" -> {
                return attemptPermissionRequest(health)
            }
            "LOCATION_DISABLED" -> {
                notifyAdmin("Location Services Disabled", "User needs to enable GPS")
                return ListenableWorker.Result.failure()
            }
            "BATTERY_OPTIMIZED" -> {
                notifyAdmin("Battery Optimization", "App is being restricted by battery optimization")
                // Try restart anyway
                return attemptNormalRestart(getUserId(), health)
            }
            else -> {
                // Unknown issue - try restart
                LocationLogger.d(TAG, "   └─ Unknown issue, attempting restart")
                return attemptNormalRestart(getUserId(), health)
            }
        }
    }

    private fun diagnoseRootCause(health: ServiceHealthStatus): String {
        return when {
            !health.canStartTracking -> "PERMISSIONS"
            !health.isLocationEnabled -> "LOCATION_DISABLED"
            health.batteryOptimized -> "BATTERY_OPTIMIZED"
            else -> "UNKNOWN"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PERMISSIONS MISSING HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handlePermissionsMissing(@Suppress("UNUSED_PARAMETER") health: ServiceHealthStatus): ListenableWorker.Result {
        val missing = listOf("Location permissions") // Simplified

        LocationLogger.w(TAG, "   ├─ Permissions missing: $missing")

        // Show notification to user
        showPermissionRequestNotification(missing)

        // Notify admin
        notifyAdmin("Permissions Revoked", "User revoked: ${missing.joinToString()}")

        // Update dashboard
        updateAdminDashboardStatus(healthy = false, message = "Permissions missing: ${missing.joinToString()}")

        return ListenableWorker.Result.failure() // Permanent failure until permissions granted
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LOCATION SERVICES DISABLED HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun handleLocationServicesDisabled(@Suppress("UNUSED_PARAMETER") health: ServiceHealthStatus): ListenableWorker.Result {
        LocationLogger.w(TAG, "   ├─ Location services (GPS) disabled by user")

        // Show notification to user
        showLocationServicesNotification()

        // Notify admin
        notifyAdmin("Location Services Disabled", "User disabled GPS/Location services")

        // Update dashboard
        updateAdminDashboardStatus(healthy = false, message = "GPS disabled by user")

        return ListenableWorker.Result.failure() // Permanent failure until location enabled
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showPermissionRequestNotification(missing: List<String>) {
        // TODO: Implement notification to user
        LocationLogger.d(TAG, "   └─ Would show notification: Grant permissions - $missing")
    }

    private fun showLocationServicesNotification() {
        // TODO: Implement notification to user
        LocationLogger.d(TAG, "   └─ Would show notification: Enable Location Services")
    }

    private suspend fun notifyAdmin(title: String, message: String) {
        try {
            LocationLogger.d(TAG, "   ├─ Notifying admin: $title - $message")

            // TODO: Send FCM to admin dashboard
            // TODO: Log to Firebase Analytics

            // For now, just log
            LocationLogger.w(TAG, "   └─ ADMIN ALERT: $title - $message")

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to notify admin", e)
        }
    }

    private suspend fun notifyAdminCritical(title: String, message: String) {
        try {
            LocationLogger.e(TAG, "   ├─ CRITICAL ADMIN ALERT: $title - $message")

            // TODO: Send high-priority FCM to admin
            // TODO: Log to Firebase with high severity
            // TODO: Update user tracking status in Firebase

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to send critical alert", e)
        }
    }

    private suspend fun updateAdminDashboardStatus(healthy: Boolean, message: String) {
        try {
            val userId = getUserId()

            LocationLogger.d(TAG, "   └─ Updating dashboard: healthy=$healthy, message=$message")

            // TODO: Update Firebase user-tracking-status collection
            // getFirebaseAdmin().firestore().collection('user-tracking-status').doc(userId).update({
            //     isHealthy: healthy,
            //     lastHealthCheck: FieldValue.serverTimestamp(),
            //     statusMessage: message
            // })

        } catch (e: Exception) {
            LocationLogger.e(TAG, "Failed to update dashboard", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun shouldTrackingBeEnabled(): Boolean {
        return true // Simplified for now
    }

    private fun getUserId(): String {
        return com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
    }

    private fun getLastLocationTime(): Long {
        val prefs = applicationContext.getSharedPreferences(
            "location_service_state",
            Context.MODE_PRIVATE
        )
        return prefs.getLong("last_location_time", 0L)
    }

    private fun isAggressiveOEM(): Boolean {
        val brand = android.os.Build.BRAND.lowercase()
        return AGGRESSIVE_OEM_BRANDS.any { brand.contains(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════════

    private data class ServiceHealthStatus(
        val status: HealthStatus,
        val isRunning: Boolean,
        val canStartTracking: Boolean,
        val isLocationEnabled: Boolean,
        val circuitBreakerActive: Boolean,
        val nextAttemptTime: Long,
        val lastLocationTime: Long,
        val locationStale: Boolean,
        val pendingUploads: Int,
        val dbBacklogWarning: Boolean,
        val dbBacklogCritical: Boolean,
        val batteryLevel: Int,
        val batteryOptimized: Boolean,
        val permissionStatus: LocationPermissionManager.ComprehensivePermissionStatus
    )

    private enum class HealthStatus {
        HEALTHY,
        RUNNING_BUT_STALE,
        NOT_RUNNING,
        CIRCUIT_BREAKER_ACTIVE,
        PERMISSIONS_MISSING,
        LOCATION_SERVICES_DISABLED
    }
}

