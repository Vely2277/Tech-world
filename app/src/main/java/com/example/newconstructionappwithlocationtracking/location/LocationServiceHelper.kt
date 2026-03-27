/*
 * ============================================================================
 * LOCATION SERVICE HELPER - ULTRA-ROBUST AUTONOMOUS IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Provides a bulletproof entry point to start, stop, and manage the location
 * tracking service with 100% autonomous operation. Ensures tracking resumes
 * automatically under any condition without user intervention.
 *
 * AUTONOMOUS GUARANTEES (24/7 RELIABILITY):
 * ✅ Resumes after device reboot (via LocationBootReceiver)
 * ✅ Resumes when location permission enabled (via settings change)
 * ✅ Resumes when network becomes available (via NetworkMonitor)
 * ✅ Resumes after power saver mode ends (via battery broadcast)
 * ✅ Resumes after doze mode ends (via alarm manager)
 * ✅ Resumes after app update (via package replaced broadcast)
 * ✅ Resumes after service killed (via START_STICKY + WorkManager fallback)
 * ✅ Resumes when permission granted in Settings (via permission listener)
 * ✅ Resumes after low battery recovery (via battery broadcast)
 *
 * CORE RULE:
 * IF all permissions granted AND tracking enabled → TRACKING MUST RUN
 * No exceptions. No excuses. Always running.
 *
 * KEY FEATURES:
 * - System broadcast registration for all recovery scenarios
 * - WorkManager periodic safety net (checks every 15 minutes)
 * - Smart circuit breaker (prevents crash loops but keeps trying)
 * - SMART FAILURE RECOVERY: If the specific failure condition is fixed,
 *   bypass circuit breaker and resume immediately
 * - Comprehensive permission validation
 * - Battery and power state monitoring
 * - Health verification with auto-recovery
 * - Complete logging for debugging
 *
 * CIRCUIT BREAKER LOGIC (SMART RECOVERY):
 * - Allows max 3 restart attempts per 10-minute window
 * - After 3 attempts, waits 10 minutes then CONTINUES trying
 * - HOWEVER: If the specific failure reason is resolved (e.g., location
 *   turned on, permission granted), immediately resets and retries
 * - Never permanently stops (only delays)
 * - Resets on successful start
 *
 * FAILURE TRACKING:
 * - Tracks the SPECIFIC reason for each failure
 * - When that specific condition is fixed, bypasses cooldown
 * - Examples:
 *   - Failed due to LOCATION_SERVICES_OFF → Location turned on → Immediate retry
 *   - Failed due to PERMISSION_MISSING → Permission granted → Immediate retry
 *   - Failed due to BATTERY_LOW → Battery charged → Immediate retry
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.models.location.ServiceStatus
import com.example.newconstructionappwithlocationtracking.services.LocationTrackingService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LocationServiceHelper {

    private const val TAG = LocationConstants.TAG_SERVICE

    // Circuit breaker configuration
    private const val MAX_RESTART_ATTEMPTS = 3
    private const val RESTART_WINDOW_MS = 600000L  // 10 minutes
    private const val RETRY_DELAY_MS = 600000L // 10 minutes delay after circuit breaker

    // WorkManager safety net
    private const val WORK_NAME_SAFETY_NET = "location_tracking_safety_net"
    private const val SAFETY_NET_INTERVAL_MINUTES = 15L

    // Tracking state
    private val restartAttempts = mutableListOf<Long>()
    private var lastHealthCheckTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // System monitor state
    private val isSystemMonitorRegistered = AtomicBoolean(false)
    private var systemMonitorReceiver: BroadcastReceiver? = null
    private var locationSettingsReceiver: BroadcastReceiver? = null

    // ============================================================================
    // FAILURE REASON TRACKING SYSTEM
    // ============================================================================

    /**
     * Enum of all possible failure reasons for tracking
     * Used to track WHY tracking failed so we can immediately resume
     * when that specific condition is fixed
     */
    enum class FailureReason {
        NONE,                           // No failure
        LOCATION_SERVICES_DISABLED,     // GPS/Location is turned off
        FINE_LOCATION_PERMISSION,       // Fine location permission not granted
        BACKGROUND_LOCATION_PERMISSION, // Background location permission not granted
        NOTIFICATION_PERMISSION,        // Notification permission not granted (Android 13+)
        BATTERY_TOO_LOW,               // Battery level too low
        POWER_SAVE_MODE,               // Device in power save mode
        DOZE_MODE,                     // Device in doze mode
        NO_USER_SESSION,               // No logged in user
        SERVICE_CRASH,                 // Service crashed unexpectedly
        UNKNOWN                        // Unknown/generic failure
    }

    /**
     * Data class to track failure attempts with their reasons
     */
    data class FailureAttempt(
        val timestamp: Long,
        val reason: FailureReason
    )

    // Track failure attempts with reasons
    private val failureAttempts = mutableListOf<FailureAttempt>()

    // Track the most recent/primary failure reason
    private var primaryFailureReason: FailureReason = FailureReason.NONE

    // Track if we're in circuit breaker cooldown
    private var circuitBreakerCooldownEndTime: Long = 0L

    // Pending retry runnable (so we can cancel if condition fixed)
    private var pendingRetryRunnable: Runnable? = null

    // ============================================================================
    // SERVICE START RESULT
    // ============================================================================

    sealed class ServiceStartResult {
        object Success : ServiceStartResult()
        object AlreadyRunning : ServiceStartResult()
        data class PermissionsMissing(val missing: List<String>) : ServiceStartResult()
        object LocationServicesDisabled : ServiceStartResult()
        data class CircuitBreakerActive(val nextAttemptIn: Long, val reason: FailureReason) : ServiceStartResult()
        object BatteryTooLow : ServiceStartResult()
        object PowerSaveModeActive : ServiceStartResult()
        object NoUserSession : ServiceStartResult()
        data class Failed(val reason: String, val exception: Exception? = null, val failureReason: FailureReason = FailureReason.UNKNOWN) : ServiceStartResult()
    }

    sealed class ServiceStopResult {
        object Success : ServiceStopResult()
        object NotRunning : ServiceStopResult()
        data class Failed(val reason: String, val exception: Exception? = null) : ServiceStopResult()
    }

    // ============================================================================
    // MAIN ENTRY POINT: START SERVICE WITH FULL AUTONOMOUS SETUP
    // ============================================================================

    /**
     * Start location tracking service with comprehensive validation
     * Also sets up autonomous monitoring to ensure tracking never stops
     *
     * @param context Application context
     * @param userId User ID for settings sync
     * @return ServiceStartResult with detailed status
     */
    fun startLocationService(context: Context, userId: String): ServiceStartResult {
        return try {
            LocationLogger.i(TAG, "🚀 Starting location service for user: $userId")

            // Check circuit breaker first - but with smart bypass
            if (isCircuitBreakerActive()) {
                val nextAttempt = getNextAttemptTime()
                LocationLogger.w(TAG, "   🔴 Circuit breaker active (reason: $primaryFailureReason), next attempt in ${nextAttempt / 1000}s")

                // Schedule automatic retry after cooldown
                scheduleRetryAfterCooldown(context, userId, nextAttempt)

                return ServiceStartResult.CircuitBreakerActive(nextAttempt, primaryFailureReason)
            }

            // Check user session
            LocationLogger.d(TAG, "   ├─ Checking user session...")
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null && userId == "unknown") {
                LocationLogger.w(TAG, "   ❌ Cannot start: No user session")
                recordFailureAttempt(FailureReason.NO_USER_SESSION)
                return ServiceStartResult.NoUserSession
            }

            // Comprehensive permission validation
            LocationLogger.d(TAG, "   ├─ Checking permissions...")
            val permissionManager = LocationPermissionManager(context)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()

            // Check specific permission failures
            if (!permissionStatus.hasFineLocation) {
                LocationLogger.w(TAG, "   ❌ Cannot start: Fine location permission missing")
                recordFailureAttempt(FailureReason.FINE_LOCATION_PERMISSION)
                registerSystemMonitor(context.applicationContext)
                return ServiceStartResult.PermissionsMissing(listOf("FINE_LOCATION"))
            }

            if (!permissionStatus.hasBackgroundLocation) {
                LocationLogger.w(TAG, "   ❌ Cannot start: Background location permission missing")
                recordFailureAttempt(FailureReason.BACKGROUND_LOCATION_PERMISSION)
                registerSystemMonitor(context.applicationContext)
                return ServiceStartResult.PermissionsMissing(listOf("BACKGROUND_LOCATION"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionStatus.hasNotificationPermission) {
                LocationLogger.w(TAG, "   ⚠️ Notification permission missing (Android 13+)")
                // Don't fail completely, but track it
                // recordFailureAttempt(FailureReason.NOTIFICATION_PERMISSION)
            }

            if (!permissionStatus.canStartTracking) {
                val missing = permissionStatus.getMissingPermissions()
                LocationLogger.w(TAG, "   ❌ Cannot start: Missing permissions - $missing")

                // Determine primary missing permission
                val failureReason = when {
                    !permissionStatus.hasFineLocation -> FailureReason.FINE_LOCATION_PERMISSION
                    !permissionStatus.hasBackgroundLocation -> FailureReason.BACKGROUND_LOCATION_PERMISSION
                    else -> FailureReason.UNKNOWN
                }
                recordFailureAttempt(failureReason)
                registerSystemMonitor(context.applicationContext)
                return ServiceStartResult.PermissionsMissing(missing)
            }

            // Check location services enabled
            if (!permissionStatus.isLocationEnabled) {
                LocationLogger.w(TAG, "   ❌ Cannot start: Location services disabled")
                recordFailureAttempt(FailureReason.LOCATION_SERVICES_DISABLED)
                registerLocationSettingsListener(context.applicationContext)
                return ServiceStartResult.LocationServicesDisabled
            }

            LocationLogger.d(TAG, "   ✅ Permissions: OK")

            // Check if already running
            LocationLogger.d(TAG, "   ├─ Checking if already running...")
            if (isServiceRunning(context)) {
                LocationLogger.d(TAG, "   ✅ Service already running")
                clearFailureTracking() // Clear any previous failures
                return ServiceStartResult.AlreadyRunning
            }

            // Battery and power state checks
            LocationLogger.d(TAG, "   ├─ Checking battery and power status...")
            val batteryHelper = BatteryOptimizationHelper(context)
            val batteryLevel = batteryHelper.getBatteryLevel()
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            // Check battery level (only block if critically low AND not charging)
            if (batteryLevel < 5 && !batteryHelper.isCharging()) {
                LocationLogger.w(TAG, "   ❌ Cannot start: Battery critically low ($batteryLevel%)")
                recordFailureAttempt(FailureReason.BATTERY_TOO_LOW)
                registerSystemMonitor(context.applicationContext)
                return ServiceStartResult.BatteryTooLow
            }

            // Check power save mode (warn but don't block)
            if (powerManager.isPowerSaveMode) {
                LocationLogger.w(TAG, "   ⚠️ Device in power save mode - tracking may be affected")
                // Don't block, just warn - but track if it causes issues
            }

            // Check doze mode (warn but don't block for initial start)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isDeviceIdleMode) {
                LocationLogger.w(TAG, "   ⚠️ Device in doze mode - tracking may be affected")
            }

            if (batteryHelper.isBatteryOptimized()) {
                LocationLogger.w(TAG, "   ⚠️ App is battery optimized - may affect reliability")
            }

            LocationLogger.d(TAG, "   ✅ Battery level: $batteryLevel%")

            // Settings pre-warming
            LocationLogger.d(TAG, "   ├─ Pre-warming settings...")
            val settingsManager = LocationSettingsManager(context)
            val currentSettings = settingsManager.getCurrentSettings()

            if (!currentSettings.isFromServer()) {
                LocationLogger.i(TAG, "   ⚠️ Settings not synced, triggering sync")
                settingsManager.startSync(userId)
            } else {
                LocationLogger.d(TAG, "   ✅ Settings: ${currentSettings.toSummaryString()}")
            }

            // Start service
            LocationLogger.d(TAG, "   ├─ Starting foreground service...")
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationConstants.ACTION_START_TRACKING
                putExtra("userId", userId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            // Save preference
            setTrackingEnabled(context, true)
            saveUserId(context, userId)

            // Setup autonomous monitoring
            setupAutonomousMonitoring(context.applicationContext)

            // Health check after start
            LocationLogger.d(TAG, "   └─ Scheduling health check in 2 seconds...")
            verifyServiceHealth(context) { running ->
                if (running) {
                    LocationLogger.i(TAG, "✅ Service verified running successfully")
                    clearFailureTracking() // Success! Clear all failure tracking
                    resetCircuitBreaker()
                } else {
                    LocationLogger.e(TAG, "❌ Service failed to start within 2 seconds")
                    recordFailureAttempt(FailureReason.SERVICE_CRASH)
                }
            }

            LocationLogger.i(TAG, "✅ Service start sequence completed")
            return ServiceStartResult.Success

        } catch (e: Exception) {
            LocationLogger.e(TAG, "❌ Failed to start location service", e)
            recordFailureAttempt(FailureReason.UNKNOWN)
            return ServiceStartResult.Failed("Exception during start", e, FailureReason.UNKNOWN)
        }
    }

    /**
     * Start service without user ID (uses cached or current user)
     */
    fun startLocationService(context: Context): ServiceStartResult {
        val userId = getSavedUserId(context)
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: "unknown"
        return startLocationService(context, userId)
    }

    // ============================================================================
    // FAILURE TRACKING & SMART RECOVERY
    // ============================================================================

    /**
     * Record a failure attempt with its specific reason
     */
    private fun recordFailureAttempt(reason: FailureReason) {
        cleanupOldFailureAttempts()

        val attempt = FailureAttempt(
            timestamp = System.currentTimeMillis(),
            reason = reason
        )
        failureAttempts.add(attempt)

        // Update primary failure reason
        primaryFailureReason = reason

        LocationLogger.d(TAG, "   📊 Failure recorded: $reason (${failureAttempts.size}/$MAX_RESTART_ATTEMPTS)")

        // Check if circuit breaker should activate
        if (failureAttempts.size >= MAX_RESTART_ATTEMPTS) {
            circuitBreakerCooldownEndTime = System.currentTimeMillis() + RETRY_DELAY_MS
            LocationLogger.w(TAG, "   🔴 Circuit breaker ACTIVATED due to: $reason")
            LocationLogger.w(TAG, "      Cooldown until: ${circuitBreakerCooldownEndTime}")
            LocationLogger.w(TAG, "      BUT will resume immediately if $reason is fixed!")
        }
    }

    /**
     * Clean up old failure attempts outside the window
     */
    private fun cleanupOldFailureAttempts() {
        val now = System.currentTimeMillis()
        failureAttempts.removeAll { it.timestamp < now - RESTART_WINDOW_MS }
    }

    /**
     * Clear all failure tracking (called on successful start)
     */
    private fun clearFailureTracking() {
        failureAttempts.clear()
        primaryFailureReason = FailureReason.NONE
        circuitBreakerCooldownEndTime = 0L

        // Cancel any pending retry
        pendingRetryRunnable?.let { handler.removeCallbacks(it) }
        pendingRetryRunnable = null

        LocationLogger.d(TAG, "   ✅ Failure tracking cleared")
    }

    /**
     * Check if a specific failure condition has been resolved
     * If resolved, bypass circuit breaker and allow immediate retry
     */
    fun checkAndResumeIfConditionFixed(context: Context, fixedCondition: FailureReason) {
        LocationLogger.i(TAG, "🔍 Checking if condition fixed: $fixedCondition (primary failure: $primaryFailureReason)")

        // If we're not in circuit breaker mode, just do normal check
        if (!isCircuitBreakerActive()) {
            LocationLogger.d(TAG, "   ℹ️ Circuit breaker not active, doing normal check")
            checkAndStartTrackingIfNeeded(context)
            return
        }

        // Check if the fixed condition matches our primary failure reason
        if (fixedCondition == primaryFailureReason || shouldResumeForCondition(fixedCondition)) {
            LocationLogger.i(TAG, "   ✅ PRIMARY FAILURE CONDITION FIXED! Bypassing circuit breaker!")

            // Cancel pending retry
            pendingRetryRunnable?.let { handler.removeCallbacks(it) }
            pendingRetryRunnable = null

            // Clear failure tracking for this specific reason
            clearFailureTrackingForReason(fixedCondition)

            // Immediately try to start
            scope.launch(Dispatchers.IO) {
                LocationLogger.i(TAG, "   🚀 Immediate retry due to condition fix...")

                // Small delay to let system stabilize
                delay(500)

                // Schedule via WorkManager for Android 12+ compatibility
                scheduleImmediateRetryViaWorkManager(context.applicationContext, "CONDITION_FIXED_$fixedCondition")
            }
        } else {
            LocationLogger.d(TAG, "   ℹ️ Fixed condition ($fixedCondition) doesn't match primary failure ($primaryFailureReason)")
            // Still check in case multiple conditions were blocking
            checkAndStartTrackingIfNeeded(context)
        }
    }

    /**
     * Determine if we should resume for a given fixed condition
     * Even if it doesn't exactly match the primary failure
     */
    private fun shouldResumeForCondition(fixedCondition: FailureReason): Boolean {
        // Location being enabled should always trigger a retry if we had location-related failures
        if (fixedCondition == FailureReason.LOCATION_SERVICES_DISABLED) {
            return primaryFailureReason in listOf(
                FailureReason.LOCATION_SERVICES_DISABLED,
                FailureReason.UNKNOWN,
                FailureReason.SERVICE_CRASH
            )
        }

        // Permission granted should trigger retry for permission-related failures
        if (fixedCondition in listOf(
                FailureReason.FINE_LOCATION_PERMISSION,
                FailureReason.BACKGROUND_LOCATION_PERMISSION
            )) {
            return primaryFailureReason in listOf(
                FailureReason.FINE_LOCATION_PERMISSION,
                FailureReason.BACKGROUND_LOCATION_PERMISSION,
                FailureReason.UNKNOWN,
                FailureReason.SERVICE_CRASH
            )
        }

        // Battery recovery should trigger retry for battery-related failures
        if (fixedCondition == FailureReason.BATTERY_TOO_LOW) {
            return primaryFailureReason in listOf(
                FailureReason.BATTERY_TOO_LOW,
                FailureReason.UNKNOWN
            )
        }

        // Power save mode ended
        if (fixedCondition == FailureReason.POWER_SAVE_MODE) {
            return primaryFailureReason in listOf(
                FailureReason.POWER_SAVE_MODE,
                FailureReason.UNKNOWN
            )
        }

        // Doze mode ended
        if (fixedCondition == FailureReason.DOZE_MODE) {
            return primaryFailureReason in listOf(
                FailureReason.DOZE_MODE,
                FailureReason.UNKNOWN
            )
        }

        return false
    }

    /**
     * Clear failure tracking for a specific reason
     * Removes attempts with that reason and resets if it was the primary
     */
    private fun clearFailureTrackingForReason(reason: FailureReason) {
        failureAttempts.removeAll { it.reason == reason }

        if (primaryFailureReason == reason) {
            // Find the next most recent failure reason
            primaryFailureReason = failureAttempts.lastOrNull()?.reason ?: FailureReason.NONE
        }

        // If we've cleared enough attempts, deactivate circuit breaker
        if (failureAttempts.size < MAX_RESTART_ATTEMPTS) {
            circuitBreakerCooldownEndTime = 0L
            LocationLogger.d(TAG, "   ✅ Circuit breaker deactivated after clearing $reason failures")
        }
    }

    /**
     * Schedule immediate retry via WorkManager (for condition fixed scenarios)
     */
    private fun scheduleImmediateRetryViaWorkManager(context: Context, trigger: String) {
        try {
            val userId = getSavedUserId(context) ?: FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

            val inputData = androidx.work.workDataOf(
                "userId" to userId,
                "triggerTime" to System.currentTimeMillis(),
                "trigger" to trigger
            )

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ConditionFixedServiceStartWorker>()
                .setInitialDelay(500, TimeUnit.MILLISECONDS) // Minimal delay
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("condition_fixed_service_start")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "condition_fixed_service_start",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            LocationLogger.i(TAG, "   ✅ Immediate retry scheduled via WorkManager (trigger: $trigger)")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to schedule immediate retry: ${e.message}")
        }
    }

    // ============================================================================
    // STOP SERVICE
    // ============================================================================

    /**
     * Stop location tracking service
     */
    fun stopLocationService(context: Context): ServiceStopResult {
        return try {
            LocationLogger.i(TAG, "🛑 Stopping location service")

            if (!isServiceRunning(context)) {
                LocationLogger.d(TAG, "   ℹ️  Service not running")
                return ServiceStopResult.NotRunning
            }

            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationConstants.ACTION_STOP_TRACKING
            }
            context.stopService(intent)

            // Save preference
            setTrackingEnabled(context, false)

            // Mark that tracking should NOT be running (intentional stop)
            ServiceStatePersistence.setTrackingShouldBeRunning(context, false)
            ServiceStatePersistence.markServiceStopped(context, "User requested stop")

            // Stop all monitoring layers
            android.util.Log.d(TAG, "   ├─ Stopping monitoring layers...")

            // Stop NetworkRecoveryManager
            try {
                NetworkRecoveryManager.getInstance(context).stopMonitoring()
                android.util.Log.d(TAG, "   │  ✅ NetworkRecoveryManager stopped")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "   │  ⚠️ Error stopping NetworkRecoveryManager: ${e.message}")
            }

            // Stop WatchdogAlarmManager
            try {
                WatchdogAlarmManager.stopWatchdog(context)
                android.util.Log.d(TAG, "   │  ✅ WatchdogAlarmManager stopped")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "   │  ⚠️ Error stopping WatchdogAlarmManager: ${e.message}")
            }

            // Unregister system monitors
            unregisterSystemMonitor(context.applicationContext)
            cancelSafetyNetWork(context)

            // Clear failure tracking since this is intentional stop
            clearFailureTracking()

            LocationLogger.i(TAG, "✅ Location service stopped")
            return ServiceStopResult.Success

        } catch (e: Exception) {
            LocationLogger.e(TAG, "❌ Failed to stop location service", e)
            return ServiceStopResult.Failed("Exception during stop", e)
        }
    }

    // ============================================================================
    // AUTONOMOUS MONITORING SETUP
    // ============================================================================

    /**
     * Setup all autonomous monitoring systems
     * Ensures tracking automatically resumes under any condition
     *
     * MULTI-LAYER REDUNDANCY:
     * 1. NetworkRecoveryManager - INSTANT network recovery (within 2 seconds)
     * 2. WatchdogAlarmManager - 5-minute Doze-proof health checks
     * 3. System broadcast receiver - Power, battery, screen events
     * 4. WorkManager safety net - 15-minute periodic (Android's minimum)
     * 5. Location settings listener - GPS toggle detection
     *
     * Together these guarantee tracking NEVER stays dead for long
     */
    private fun setupAutonomousMonitoring(context: Context) {
        LocationLogger.i(TAG, "═══════════════════════════════════════════════════════════════")
        LocationLogger.i(TAG, "🔧 SETTING UP MULTI-LAYER AUTONOMOUS MONITORING")
        LocationLogger.i(TAG, "═══════════════════════════════════════════════════════════════")

        // 1. NetworkRecoveryManager - CRITICAL for network loss recovery
        // This is what was MISSING - instant restart when network returns
        android.util.Log.d(TAG, "   ├─ Layer 1: NetworkRecoveryManager (instant network recovery)")
        try {
            NetworkRecoveryManager.getInstance(context).startMonitoring()
            android.util.Log.d(TAG, "   │  ✅ NetworkRecoveryManager started")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "   │  ❌ NetworkRecoveryManager failed: ${e.message}")
        }

        // 2. WatchdogAlarmManager - 5-minute health checks (faster than WorkManager)
        android.util.Log.d(TAG, "   ├─ Layer 2: WatchdogAlarmManager (5-min health checks)")
        try {
            WatchdogAlarmManager.startWatchdog(context)
            android.util.Log.d(TAG, "   │  ✅ WatchdogAlarmManager started")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "   │  ❌ WatchdogAlarmManager failed: ${e.message}")
        }

        // 3. System broadcast receiver - Power, battery, screen events
        android.util.Log.d(TAG, "   ├─ Layer 3: System broadcast monitor")
        registerSystemMonitor(context)

        // 4. WorkManager safety net - 15-minute periodic (ultimate fallback)
        android.util.Log.d(TAG, "   ├─ Layer 4: WorkManager safety net (15-min)")
        // NOTE: Worker now unified in LocationTrackingApplication.PeriodicTrackingCheckWorker
        // setupSafetyNetWork(context) // DISABLED - Using unified worker instead

        // 5. Location settings listener - GPS toggle detection
        android.util.Log.d(TAG, "   ├─ Layer 5: Location settings listener")
        registerLocationSettingsListener(context)

        // 6. Mark state as tracking should be running
        android.util.Log.d(TAG, "   ├─ Layer 6: Persisting tracking state")
        ServiceStatePersistence.setTrackingShouldBeRunning(context, true)
        ServiceStatePersistence.markServiceRunning(context)

        // 7. Log OEM info for debugging
        android.util.Log.d(TAG, "   ├─ Layer 7: OEM detection")
        OemBatteryHandler.logOemInfo()

        android.util.Log.d(TAG, "   └─ ✅ ALL MONITORING LAYERS ACTIVE")
        LocationLogger.i(TAG, "✅ Multi-layer autonomous monitoring setup complete")
    }

    /**
     * Register broadcast receiver for system-wide events
     * Listens for: power changes, doze mode, network changes, etc.
     * NOW WITH SMART FAILURE RECOVERY!
     */
    private fun registerSystemMonitor(context: Context) {
        if (isSystemMonitorRegistered.getAndSet(true)) {
            LocationLogger.d(TAG, "   ℹ️  System monitor already registered")
            return
        }

        try {
            systemMonitorReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (ctx == null || intent == null) return

                    LocationLogger.d(TAG, "📡 System broadcast received: ${intent.action}")

                    when (intent.action) {
                        // Power connected - battery may have recovered
                        Intent.ACTION_POWER_CONNECTED -> {
                            LocationLogger.i(TAG, "🔌 Power connected - checking battery recovery")
                            val batteryHelper = BatteryOptimizationHelper(ctx)
                            if (batteryHelper.getBatteryLevel() >= 5) {
                                // Battery is now adequate - check if this was the failure reason
                                checkAndResumeIfConditionFixed(ctx, FailureReason.BATTERY_TOO_LOW)
                            } else {
                                checkAndStartTrackingIfNeeded(ctx)
                            }
                        }

                        // Battery level changed
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                            if (level >= 5 && primaryFailureReason == FailureReason.BATTERY_TOO_LOW) {
                                LocationLogger.i(TAG, "🔋 Battery recovered to $level% - resuming!")
                                checkAndResumeIfConditionFixed(ctx, FailureReason.BATTERY_TOO_LOW)
                            }
                        }

                        // Power save mode changes
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (!powerManager.isPowerSaveMode) {
                                LocationLogger.i(TAG, "⚡ Power save mode ended - checking for recovery")
                                checkAndResumeIfConditionFixed(ctx, FailureReason.POWER_SAVE_MODE)
                            }
                        }

                        // Doze mode changes (Android 6+)
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!powerManager.isDeviceIdleMode) {
                                    LocationLogger.i(TAG, "🔋 Doze mode ended - checking for recovery")
                                    checkAndResumeIfConditionFixed(ctx, FailureReason.DOZE_MODE)
                                }
                            }
                        }

                        // User present (device unlocked)
                        Intent.ACTION_USER_PRESENT -> {
                            LocationLogger.i(TAG, "👤 User present - checking tracking status")
                            checkAndStartTrackingIfNeeded(ctx)
                        }

                        // Screen on
                        Intent.ACTION_SCREEN_ON -> {
                            LocationLogger.d(TAG, "📱 Screen on - checking tracking status")
                            checkAndStartTrackingIfNeeded(ctx)
                        }

                        // Time changed (may affect scheduled tasks)
                        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                            LocationLogger.d(TAG, "🕐 Time changed - verifying tracking")
                            checkAndStartTrackingIfNeeded(ctx)
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(systemMonitorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(systemMonitorReceiver, filter)
            }

            LocationLogger.i(TAG, "   ✅ System monitor registered")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to register system monitor", e)
            isSystemMonitorRegistered.set(false)
        }
    }

    /**
     * Unregister system monitor
     */
    private fun unregisterSystemMonitor(context: Context) {
        if (!isSystemMonitorRegistered.getAndSet(false)) return

        try {
            systemMonitorReceiver?.let {
                context.unregisterReceiver(it)
                systemMonitorReceiver = null
            }
            LocationLogger.i(TAG, "   ✅ System monitor unregistered")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ⚠️  Error unregistering system monitor", e)
        }
    }

    /**
     * Register listener for location settings changes
     * Detects when user enables/disables location in system settings
     * NOW WITH SMART FAILURE RECOVERY!
     */
    private fun registerLocationSettingsListener(context: Context) {
        try {
            locationSettingsReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (ctx == null) return

                    if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                        LocationLogger.i(TAG, "📍 Location settings changed - GPS: $isGpsEnabled, Network: $isNetworkEnabled")

                        if (isGpsEnabled || isNetworkEnabled) {
                            // Location was enabled - this is a key recovery scenario!
                            LocationLogger.i(TAG, "📍 Location services ENABLED - checking for recovery")
                            checkAndResumeIfConditionFixed(ctx, FailureReason.LOCATION_SERVICES_DISABLED)
                        }
                    }
                }
            }

            val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(locationSettingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(locationSettingsReceiver, filter)
            }

            LocationLogger.i(TAG, "   ✅ Location settings listener registered")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to register location settings listener", e)
        }
    }

    // ============================================================================
    // PERMISSION CHANGE HANDLER
    // ============================================================================

    /**
     * Called when a permission is granted
     * Should be called from permission request results
     */
    fun onPermissionGranted(context: Context, permission: String) {
        LocationLogger.i(TAG, "🔓 Permission granted: $permission")

        val failureReason = when {
            permission.contains("FINE_LOCATION", ignoreCase = true) -> FailureReason.FINE_LOCATION_PERMISSION
            permission.contains("BACKGROUND_LOCATION", ignoreCase = true) -> FailureReason.BACKGROUND_LOCATION_PERMISSION
            permission.contains("NOTIFICATION", ignoreCase = true) -> FailureReason.NOTIFICATION_PERMISSION
            else -> null
        }

        failureReason?.let {
            checkAndResumeIfConditionFixed(context, it)
        }
    }

    // ============================================================================
    // WORKMANAGER SAFETY NET
    // ============================================================================

    /**
     * Setup periodic WorkManager job as safety net
     * Runs every 15 minutes to ensure tracking is active
     */
    private fun setupSafetyNetWork(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .build()

            val workRequest = PeriodicWorkRequestBuilder<TrackingSafetyNetWorker>(
                SAFETY_NET_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME_SAFETY_NET)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_SAFETY_NET,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            LocationLogger.i(TAG, "   ✅ Safety net WorkManager scheduled (every $SAFETY_NET_INTERVAL_MINUTES min)")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to setup safety net work", e)
        }
    }

    /**
     * Cancel safety net work
     */
    private fun cancelSafetyNetWork(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_SAFETY_NET)
            LocationLogger.i(TAG, "   ✅ Safety net WorkManager cancelled")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ⚠️  Error cancelling safety net work", e)
        }
    }

    // ============================================================================
    // AUTONOMOUS TRACKING CHECKS
    // ============================================================================

    /**
     * Check if tracking should be running and start if needed
     * Called from various system event handlers
     *
     * IMPORTANT: Uses WorkManager for Android 12+ compatibility
     * Starting foreground services from background is restricted on Android 12+
     */
    fun checkAndStartTrackingIfNeeded(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                LocationLogger.d(TAG, "🔍 Checking if tracking should be started...")

                // Check if tracking is enabled in preferences
                if (!isTrackingEnabled(context)) {
                    LocationLogger.d(TAG, "   ℹ️  Tracking is disabled by user")
                    return@launch
                }

                // Check if already running
                if (isServiceRunning(context)) {
                    LocationLogger.d(TAG, "   ✅ Service already running")
                    return@launch
                }

                // Check permissions
                val permissionManager = LocationPermissionManager(context)
                val permissionStatus = permissionManager.getComprehensivePermissionStatus()

                if (!permissionStatus.canStartTracking) {
                    LocationLogger.d(TAG, "   ℹ️  Missing permissions, cannot auto-start")
                    return@launch
                }

                if (!permissionStatus.isLocationEnabled) {
                    LocationLogger.d(TAG, "   ℹ️  Location services disabled")
                    return@launch
                }

                // All checks passed - schedule tracking via WorkManager!
                // IMPORTANT: Use WorkManager for Android 12+ foreground service restrictions
                LocationLogger.i(TAG, "   🚀 Scheduling tracking service start via WorkManager...")

                scheduleServiceStartViaWorkManager(context.applicationContext)
            } catch (e: Exception) {
                LocationLogger.e(TAG, "   ❌ Error in checkAndStartTrackingIfNeeded", e)
            }
        }
    }

    /**
     * Schedule service start via WorkManager (for Android 12+ compatibility)
     * WorkManager has exemptions from foreground service start restrictions
     */
    private fun scheduleServiceStartViaWorkManager(context: Context) {
        try {
            val userId = getSavedUserId(context) ?: FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

            val inputData = androidx.work.workDataOf(
                "userId" to userId,
                "triggerTime" to System.currentTimeMillis(),
                "trigger" to "AUTO_CHECK"
            )

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AutoCheckServiceStartWorker>()
                .setInitialDelay(2000, TimeUnit.MILLISECONDS) // Small delay for stability
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("auto_check_service_start")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "auto_check_service_start",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            LocationLogger.i(TAG, "   ✅ Service start scheduled via WorkManager")
        } catch (e: Exception) {
            LocationLogger.e(TAG, "   ❌ Failed to schedule WorkManager: ${e.message}")
        }
    }

    // ============================================================================
    // CIRCUIT BREAKER & RETRY LOGIC
    // ============================================================================

    /**
     * Schedule automatic retry after circuit breaker cooldown
     */
    private fun scheduleRetryAfterCooldown(context: Context, userId: String, delayMs: Long) {
        LocationLogger.d(TAG, "   ⏱️  Scheduling retry in ${delayMs / 1000}s (will cancel if condition fixed)")

        // Cancel any existing pending retry
        pendingRetryRunnable?.let { handler.removeCallbacks(it) }

        pendingRetryRunnable = Runnable {
            LocationLogger.i(TAG, "🔄 Circuit breaker cooldown complete - retrying...")
            resetCircuitBreaker()
            startLocationService(context, userId)
        }

        handler.postDelayed(pendingRetryRunnable!!, delayMs)
    }

    /**
     * Check if circuit breaker is active
     */
    fun isCircuitBreakerActive(): Boolean {
        cleanupOldFailureAttempts()

        // Check if we're still in cooldown period
        if (circuitBreakerCooldownEndTime > System.currentTimeMillis()) {
            return true
        }

        // Check if we have too many recent failures
        return failureAttempts.size >= MAX_RESTART_ATTEMPTS
    }

    /**
     * Record a restart attempt (legacy method for compatibility)
     */
    private fun recordRestartAttempt() {
        recordFailureAttempt(FailureReason.UNKNOWN)
    }

    /**
     * Clean up old restart attempts outside the window (legacy method)
     */
    private fun cleanupOldAttempts() {
        cleanupOldFailureAttempts()
    }

    /**
     * Get time until next restart attempt allowed
     */
    fun getNextAttemptTime(): Long {
        // If in cooldown, return remaining cooldown time
        if (circuitBreakerCooldownEndTime > System.currentTimeMillis()) {
            return circuitBreakerCooldownEndTime - System.currentTimeMillis()
        }

        if (failureAttempts.isEmpty()) return 0L

        val oldestAttempt = failureAttempts.minByOrNull { it.timestamp }?.timestamp ?: return 0L
        val windowEnd = oldestAttempt + RESTART_WINDOW_MS
        val now = System.currentTimeMillis()

        return if (windowEnd > now) windowEnd - now else 0L
    }

    /**
     * Reset circuit breaker (call on successful start)
     */
    fun resetCircuitBreaker() {
        clearFailureTracking()
        LocationLogger.d(TAG, "   ✅ Circuit breaker reset")
    }

    /**
     * Get the current primary failure reason
     */
    fun getPrimaryFailureReason(): FailureReason = primaryFailureReason

    // ============================================================================
    // HEALTH CHECK
    // ============================================================================

    /**
     * Verify service health after start
     */
    private fun verifyServiceHealth(context: Context, callback: (Boolean) -> Unit) {
        handler.postDelayed({
            val running = isServiceRunning(context)
            lastHealthCheckTime = System.currentTimeMillis()
            callback(running)
        }, 2000L)
    }

    // ============================================================================
    // SERVICE STATE
    // ============================================================================

    /**
     * Check if location tracking service is running
     */
    fun isServiceRunning(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(LocationConstants.KEY_SERVICE_RUNNING, false)
    }

    /**
     * Set service running state (called by service)
     */
    fun setServiceRunning(context: Context, running: Boolean) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(LocationConstants.KEY_SERVICE_RUNNING, running)
            .putLong("service_state_changed_at", System.currentTimeMillis())
            .apply()
    }

    /**
     * Set tracking enabled/disabled preference
     */
    fun setTrackingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(LocationConstants.KEY_TRACKING_ENABLED, enabled)
            .putLong(LocationConstants.KEY_TRACKING_TOGGLED_AT, System.currentTimeMillis())
            .apply()

        LocationLogger.d(TAG, "📍 Tracking ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if tracking is enabled in preferences
     */
    fun isTrackingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
    }

    /**
     * Save user ID for autonomous restarts
     */
    private fun saveUserId(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString("saved_user_id", userId)
            .apply()
    }

    /**
     * Get saved user ID
     */
    private fun getSavedUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(
            LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        return prefs.getString("saved_user_id", null)
    }

    // ============================================================================
    // COMPREHENSIVE STATUS
    // ============================================================================

    /**
     * Get comprehensive service status
     */
    fun getServiceStatus(context: Context): Map<String, Any> {
        val permissionManager = LocationPermissionManager(context)
        val batteryHelper = BatteryOptimizationHelper(context)
        val settingsManager = LocationSettingsManager(context)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()
        val currentSettings = settingsManager.getCurrentSettings()

        return mapOf(
            // Service state
            "running" to isServiceRunning(context),
            "enabled" to isTrackingEnabled(context),

            // Permissions
            "can_start_tracking" to permissionStatus.canStartTracking,
            "permissions_granted" to permissionStatus.hasAllRequired,
            "fine_location" to permissionStatus.hasFineLocation,
            "background_location" to permissionStatus.hasBackgroundLocation,
            "notification_permission" to permissionStatus.hasNotificationPermission,
            "exact_alarm" to permissionStatus.canScheduleExactAlarms,
            "missing_permissions" to permissionStatus.getMissingPermissions(),

            // Battery
            "battery_level" to batteryHelper.getBatteryLevel(),
            "battery_optimized" to batteryHelper.isBatteryOptimized(),
            "is_charging" to batteryHelper.isCharging(),

            // Settings
            "settings_source" to currentSettings.source,
            "settings_version" to currentSettings.version,
            "settings_age_ms" to currentSettings.getAgeMs(),
            "current_interval_ms" to currentSettings.getCurrentInterval(),
            "active_mode" to currentSettings.getActiveMode(),

            // Circuit breaker (enhanced)
            "failure_attempts" to failureAttempts.size,
            "primary_failure_reason" to primaryFailureReason.name,
            "circuit_breaker_active" to isCircuitBreakerActive(),
            "next_attempt_ms" to if (isCircuitBreakerActive()) getNextAttemptTime() else 0L,
            "cooldown_end_time" to circuitBreakerCooldownEndTime,

            // Health
            "last_health_check" to lastHealthCheckTime,
            "location_services_enabled" to permissionStatus.isLocationEnabled,

            // Autonomous monitoring
            "system_monitor_registered" to isSystemMonitorRegistered.get()
        )
    }

    /**
     * Get comprehensive status as ServiceStatus object
     */
    fun getComprehensiveStatus(context: Context): ServiceStatus {
        val permissionManager = LocationPermissionManager(context)
        val batteryHelper = BatteryOptimizationHelper(context)
        val settingsManager = LocationSettingsManager(context)

        return ServiceStatus(
            isRunning = isServiceRunning(context),
            isEnabled = isTrackingEnabled(context),
            permissionStatus = permissionManager.getComprehensivePermissionStatus(),
            batteryInfo = batteryHelper.getCompleteBatteryInfo(),
            settingsInfo = settingsManager.getCurrentSettings(),
            circuitBreakerActive = isCircuitBreakerActive(),
            lastHealthCheck = lastHealthCheckTime,
            restartAttempts = failureAttempts.size,
            nextAttemptTime = if (isCircuitBreakerActive()) getNextAttemptTime() else 0L
        )
    }

    // ============================================================================
    // RESTART SERVICE
    // ============================================================================

    /**
     * Restart location service
     */
    fun restartLocationService(context: Context, userId: String, callback: ((ServiceStartResult) -> Unit)? = null) {
        LocationLogger.i(TAG, "🔄 Restarting location service")

        scope.launch {
            val stopResult = stopLocationService(context)

            when (stopResult) {
                is ServiceStopResult.Success, is ServiceStopResult.NotRunning -> {
                    delay(500)

                    // Re-enable tracking
                    setTrackingEnabled(context, true)

                    val startResult = startLocationService(context, userId)
                    callback?.invoke(startResult)
                }
                is ServiceStopResult.Failed -> {
                    LocationLogger.e(TAG, "❌ Restart failed: could not stop service")
                    callback?.invoke(ServiceStartResult.Failed("Could not stop service", stopResult.exception))
                }
            }
        }
    }

    // ============================================================================
    // CLEANUP
    // ============================================================================

    /**
     * Clean up resources
     */
    fun cleanup(context: Context) {
        unregisterSystemMonitor(context.applicationContext)
        locationSettingsReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) { /* ignore */ }
            locationSettingsReceiver = null
        }

        // Cancel pending retry
        pendingRetryRunnable?.let { handler.removeCallbacks(it) }
        pendingRetryRunnable = null

        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        clearFailureTracking()
    }
}

// ============================================================================
// WORKMANAGER SAFETY NET WORKER
// ============================================================================

/**
 * Worker that runs periodically (every 15 minutes) to:
 * 1. Ensure tracking service is active when it should be
 * 2. Check for time-based upload triggers (data age 2+ hours, tracking stopped 5+ min)
 *
 * This is the ultimate safety net - runs even when app is killed
 * Combines both tracking check AND upload check in one worker to save battery
 */
class TrackingSafetyNetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.d("SAFETY_NET", "═══════════════════════════════════════════════════════")
        android.util.Log.d("SAFETY_NET", "🔍 Safety net worker running (15 min periodic)...")
        android.util.Log.d("SAFETY_NET", "   Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        android.util.Log.d("SAFETY_NET", "═══════════════════════════════════════════════════════")

        try {
            // ========== PART 1: Check if tracking should be running ==========
            val isEnabled = LocationServiceHelper.isTrackingEnabled(applicationContext)
            val isRunning = LocationServiceHelper.isServiceRunning(applicationContext)

            android.util.Log.d("SAFETY_NET", "   📡 Tracking Status:")
            android.util.Log.d("SAFETY_NET", "      Enabled: $isEnabled")
            android.util.Log.d("SAFETY_NET", "      Running: $isRunning")

            // Check permissions and location services
            val permissionManager = LocationPermissionManager(applicationContext)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()

            android.util.Log.d("SAFETY_NET", "   🔐 Permissions:")
            android.util.Log.d("SAFETY_NET", "      Can Start: ${permissionStatus.canStartTracking}")
            android.util.Log.d("SAFETY_NET", "      Location Enabled: ${permissionStatus.isLocationEnabled}")

            if (isEnabled && !isRunning && permissionStatus.canStartTracking && permissionStatus.isLocationEnabled) {
                android.util.Log.i("SAFETY_NET", "   🚀 Tracking should be running but isn't - STARTING!")
                // Use checkAndStartTrackingIfNeeded which handles WorkManager properly
                LocationServiceHelper.checkAndStartTrackingIfNeeded(applicationContext)
            } else if (isEnabled && !isRunning) {
                android.util.Log.d("SAFETY_NET", "   ⚠️ Tracking enabled but can't start:")
                android.util.Log.d("SAFETY_NET", "      Reason: ${if (!permissionStatus.canStartTracking) "Missing permissions" else "Location disabled"}")
            }

            // ========== PART 2: Check for time-based upload triggers ==========
            android.util.Log.d("SAFETY_NET", "   📤 Checking time-based upload triggers...")
            checkAndTriggerTimeBasedUpload()

            // ========== PART 3: Check network and retry uploads if needed ==========
            android.util.Log.d("SAFETY_NET", "   🌐 Checking network status...")
            checkNetworkAndRetryUploads()

            android.util.Log.d("SAFETY_NET", "═══════════════════════════════════════════════════════")
            android.util.Log.d("SAFETY_NET", "✅ Safety net worker completed")
            android.util.Log.d("SAFETY_NET", "═══════════════════════════════════════════════════════")

            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("SAFETY_NET", "   ❌ Safety net worker error", e)
            return Result.retry()
        }
    }

    /**
     * Check network status and retry any failed uploads
     * This handles the "resuming upload from bad network" scenario
     */
    private suspend fun checkNetworkAndRetryUploads() {
        try {
            val networkMonitor = NetworkMonitor(applicationContext)
            val hasNetwork = networkMonitor.isConnected()

            android.util.Log.d("SAFETY_NET", "      Network available: $hasNetwork")

            if (!hasNetwork) {
                android.util.Log.d("SAFETY_NET", "      ⏳ No network - will check again later")
                return
            }

            // Check if we have pending or failed uploads
            val database = LocationDatabase.getInstance(applicationContext)
            val pendingLocations = database.getPendingLocations(100)
            val failedLocations = database.getFailedLocations()

            val totalPending = pendingLocations.size + failedLocations.size

            if (totalPending > 0) {
                android.util.Log.i("SAFETY_NET", "      🔄 Found $totalPending locations to upload (${pendingLocations.size} pending, ${failedLocations.size} failed)")

                // Trigger upload - BatchUploader will handle retries
                val settingsManager = LocationSettingsManager(applicationContext)
                val batchUploader = BatchUploader(applicationContext, database, networkMonitor, settingsManager)

                // Force retry failed uploads
                if (failedLocations.isNotEmpty()) {
                    android.util.Log.i("SAFETY_NET", "      🔄 Retrying ${failedLocations.size} failed uploads...")
                    batchUploader.retryFailedUploads()
                }

                // Also check pending
                if (pendingLocations.size >= 3) {
                    batchUploader.uploadPendingLocations()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SAFETY_NET", "      ❌ Error checking network/retrying: ${e.message}")
        }
    }

    /**
     * Check and trigger time-based uploads
     * This runs even when the foreground service is not active
     */
    private suspend fun checkAndTriggerTimeBasedUpload() {
        try {
            // Get database instance
            val database = LocationDatabase.getInstance(applicationContext)

            // Check if there are pending locations
            val pendingLocations = database.getPendingLocations(100)
            if (pendingLocations.isEmpty()) {
                android.util.Log.d("SAFETY_NET", "      No pending locations to upload")
                return
            }

            android.util.Log.d("SAFETY_NET", "      Pending locations: ${pendingLocations.size}")

            // Check data age trigger (oldest data is 2+ hours old)
            val oldestLocation = database.getOldestPendingLocation()
            val dataAge = if (oldestLocation != null) {
                System.currentTimeMillis() - oldestLocation.clientTimestamp
            } else 0L

            val dataAgeMinutes = dataAge / 60000
            val shouldUploadDueToAge = dataAge >= com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants.DATA_AGE_FORCE_UPLOAD_MS

            android.util.Log.d("SAFETY_NET", "      Oldest data age: ${dataAgeMinutes}min")
            android.util.Log.d("SAFETY_NET", "      Should upload due to age (2h): $shouldUploadDueToAge")

            // Check if network is available
            val networkMonitor = NetworkMonitor(applicationContext)
            val hasNetwork = networkMonitor.isConnected()

            android.util.Log.d("SAFETY_NET", "      Network available: $hasNetwork")

            if (!hasNetwork) {
                android.util.Log.d("SAFETY_NET", "      ⏳ No network - skipping upload")
                return
            }

            // Check if we should upload
            // Condition 1: Data is 2+ hours old - upload regardless of count
            // Condition 2: Data count >= 3 (for hardcoded defaults when no settings)
            // Condition 3: Data count >= 10 (normal threshold)

            val shouldUpload = shouldUploadDueToAge || pendingLocations.size >= 3

            if (shouldUpload) {
                android.util.Log.d("SAFETY_NET", "      🚀 Triggering time-based upload!")
                android.util.Log.d("SAFETY_NET", "         Reason: ${if (shouldUploadDueToAge) "Data age >= 2 hours" else "Count >= 3"}")

                // Create BatchUploader and trigger upload
                val settingsManager = LocationSettingsManager(applicationContext)
                val batchUploader = BatchUploader(applicationContext, database, networkMonitor, settingsManager)
                batchUploader.uploadPendingLocations()

                android.util.Log.d("SAFETY_NET", "      ✅ Time-based upload triggered")
            } else {
                android.util.Log.d("SAFETY_NET", "      ℹ️ No upload needed (count: ${pendingLocations.size}, age: ${dataAgeMinutes}min)")
            }

        } catch (e: Exception) {
            android.util.Log.e("SAFETY_NET", "      ❌ Error checking time-based upload", e)
        }
    }
}

// ============================================================================
// AUTO CHECK SERVICE START WORKER
// ============================================================================

/**
 * Worker that starts the location service from background
 * WorkManager workers have exemptions from Android 12+ foreground service restrictions.
 * This is used by checkAndStartTrackingIfNeeded for reliable background service starts.
 */
class AutoCheckServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "AutoCheckWorker"
    }

    override fun doWork(): Result {
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.i(TAG, "🚀 AutoCheckServiceStartWorker.doWork() START")
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        val userId = inputData.getString("userId") ?: "unknown"
        val triggerTime = inputData.getLong("triggerTime", 0L)
        val trigger = inputData.getString("trigger") ?: "unknown"
        val actualDelay = System.currentTimeMillis() - triggerTime

        android.util.Log.d(TAG, "   ├─ User ID: $userId")
        android.util.Log.d(TAG, "   ├─ Trigger: $trigger")
        android.util.Log.d(TAG, "   ├─ Actual delay: ${actualDelay}ms")

        // Final permission check
        val permissionManager = LocationPermissionManager(applicationContext)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking) {
            android.util.Log.w(TAG, "   ❌ Permissions not valid")
            return Result.failure()
        }

        if (!permissionStatus.isLocationEnabled) {
            android.util.Log.w(TAG, "   ❌ Location services disabled")
            return Result.failure()
        }

        // Check if already running
        if (LocationServiceHelper.isServiceRunning(applicationContext)) {
            android.util.Log.i(TAG, "   ℹ️ Service already running")
            return Result.success()
        }

        android.util.Log.d(TAG, "   ✅ All conditions valid - starting service...")

        // Start service
        val result = LocationServiceHelper.startLocationService(applicationContext, userId)

        return when (result) {
            is LocationServiceHelper.ServiceStartResult.Success -> {
                android.util.Log.i(TAG, "✅ Service started successfully!")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                android.util.Log.i(TAG, "   ℹ️ Service already running")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.PermissionsMissing -> {
                android.util.Log.w(TAG, "   ❌ Permissions missing: ${result.missing}")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.LocationServicesDisabled -> {
                android.util.Log.w(TAG, "   ❌ Location services disabled")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                android.util.Log.w(TAG, "   ⏳ Circuit breaker active (reason: ${result.reason})")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.BatteryTooLow -> {
                android.util.Log.w(TAG, "   ❌ Battery too low")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.PowerSaveModeActive -> {
                android.util.Log.w(TAG, "   ❌ Power save mode active")
                Result.retry()
            }

            is LocationServiceHelper.ServiceStartResult.NoUserSession -> {
                android.util.Log.w(TAG, "   ❌ No user session")
                Result.failure()
            }

            is LocationServiceHelper.ServiceStartResult.Failed -> {
                android.util.Log.e(TAG, "   ❌ Failed: ${result.reason}")
                Result.retry()
            }
        }
    }
}

// ============================================================================
// CONDITION FIXED SERVICE START WORKER
// ============================================================================

/**
 * Worker that starts the location service when a failure condition is fixed
 * This bypasses circuit breaker since the specific issue has been resolved
 */
class ConditionFixedServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ConditionFixedWorker"
    }

    override fun doWork(): Result {
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.i(TAG, "🚀 ConditionFixedServiceStartWorker.doWork() START")
        android.util.Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        val userId = inputData.getString("userId") ?: "unknown"
        val triggerTime = inputData.getLong("triggerTime", 0L)
        val trigger = inputData.getString("trigger") ?: "unknown"
        val actualDelay = System.currentTimeMillis() - triggerTime

        android.util.Log.d(TAG, "   ├─ User ID: $userId")
        android.util.Log.d(TAG, "   ├─ Trigger: $trigger")
        android.util.Log.d(TAG, "   ├─ Actual delay: ${actualDelay}ms")
        android.util.Log.i(TAG, "   ├─ 🔓 BYPASS MODE: Condition was fixed, ignoring circuit breaker")

        // Final permission check
        val permissionManager = LocationPermissionManager(applicationContext)
        val permissionStatus = permissionManager.getComprehensivePermissionStatus()

        if (!permissionStatus.canStartTracking) {
            android.util.Log.w(TAG, "   ❌ Still missing permissions")
            return Result.failure()
        }

        if (!permissionStatus.isLocationEnabled) {
            android.util.Log.w(TAG, "   ❌ Location services still disabled")
            return Result.failure()
        }

        // Check if already running
        if (LocationServiceHelper.isServiceRunning(applicationContext)) {
            android.util.Log.i(TAG, "   ℹ️ Service already running")
            return Result.success()
        }

        android.util.Log.d(TAG, "   ✅ All conditions valid - starting service (condition fixed bypass)...")

        // Reset circuit breaker since condition was fixed
        LocationServiceHelper.resetCircuitBreaker()

        // Start service
        val result = LocationServiceHelper.startLocationService(applicationContext, userId)

        return when (result) {
            is LocationServiceHelper.ServiceStartResult.Success -> {
                android.util.Log.i(TAG, "✅ Service started successfully after condition fix!")
                Result.success()
            }

            is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                android.util.Log.i(TAG, "   ℹ️ Service already running")
                Result.success()
            }

            else -> {
                android.util.Log.e(TAG, "   ❌ Start failed: $result")
                Result.retry()
            }
        }
    }
}
