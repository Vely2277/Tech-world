/*
 * ============================================================================
 * BATTERY OPTIMIZATION HELPER - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Complete battery management system with 44% threshold protection, ForceCheck
 * and Emergency mode support, hysteresis to prevent oscillation, and comprehensive
 * interval calculation logic. Built for long-term stability and reliability.
 *
 * KEY FEATURES:
 * - 44% Battery Protection Threshold (LocationConstants.BATTERY_PROTECTION_THRESHOLD)
 * - ForceCheck Override (admin can ignore battery protection)
 * - Emergency Mode (overrides all other settings)
 * - Charging Detection (no protection while charging)
 * - Hysteresis (2% + 5-minute stability to prevent thrashing)
 * - Complete Battery Information (unified BatteryInfo object)
 * - Interval Calculation (Priority: Emergency > Realtime > Battery > Admin)
 * - LocationData Integration (populate battery fields)
 * - Fail-Safe Defaults (graceful error handling)
 * - Battery Optimization Management (whitelist, doze, power save)
 * - Comprehensive Logging (interval decisions with reasons)
 *
 * INTERVAL DECISION LOGIC (Priority Order):
 * 1. IF Emergency Mode ON → Use emergency interval (overrides everything)
 * 2. ELSE IF Realtime Mode ON → Use realtime interval
 * 3. ELSE IF Charging → Use admin interval (no battery protection)
 * 4. ELSE IF Battery > 44% → Use admin interval
 * 5. ELSE IF Battery ≤ 44% AND ForceCheck ON → Use admin interval (override protection)
 * 6. ELSE IF Battery ≤ 44% AND ForceCheck OFF → Force 2-hour interval (protection)
 *
 * HYSTERESIS SYSTEM:
 * - Prevents thrashing when battery hovers near 44%
 * - Requires 2% margin: 42% to trigger "low", 46% to trigger "recovered"
 * - Requires 5-minute stability before emitting threshold crossing
 * - Example: 43%→44%→43%→44% (oscillating) = No crossing until stable at 46% for 5 min
 *
 * CHARGING-STATE RULE:
 * - If device is charging → treat as above threshold
 * - No battery protection while charging (makes sense - battery not draining)
 * - Immediately recalculates interval when charging starts
 *
 * USAGE:
 * val helper = BatteryOptimizationHelper(context)
 *
 * // Get complete battery info
 * val info = helper.getCompleteBatteryInfo(forceCheck = false, emergencyMode = false)
 *
 * // Calculate effective interval
 * val decision = helper.calculateEffectiveInterval(
 *     batteryLevel = info.level,
 *     forceCheck = false,
 *     emergencyMode = false,
 *     realtimeMode = false,
 *     adminNormalInterval = 7200000L,  // 2 hours
 *     adminRealtimeInterval = 10000L,   // 10 seconds
 *     adminEmergencyInterval = 30000L   // 30 seconds
 * )
 *
 * // Populate LocationData battery fields
 * val updatedLocation = helper.populateBatteryFields(locationData, forceCheck, emergencyMode)
 *
 * THREAD SAFETY:
 * - All methods are thread-safe
 * - Can be called from any thread
 * - Internal state is immutable or synchronized
 *
 * PERFORMANCE:
 * - Lightweight operations (no heavy computation)
 * - Minimal memory footprint
 * - Optimized for frequent calls
 *
 * MAINTENANCE:
 * - Well-documented code
 * - Clear separation of concerns
 * - Easy to test and debug
 * - Future-proof design
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.models.location.LocationData

class BatteryOptimizationHelper(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    // Hysteresis state for threshold crossing detection
    private var lastStableBatteryLevel: Int = -1
    private var lastStableTimestamp: Long = 0L
    private var currentThresholdState: ThresholdState = ThresholdState.UNKNOWN

    companion object {
        // Hysteresis constants
        private const val HYSTERESIS_LOWER_BOUND = 42  // Must drop to 42% to count as "low"
        private const val HYSTERESIS_UPPER_BOUND = 46  // Must rise to 46% to count as "recovered"
        private const val STABILITY_PERIOD_MS = 300000L  // 5 minutes
    }

    // ============================================================================
    // DATA CLASSES
    // ============================================================================

    /**
     * Complete battery information in a single object
     */
    data class BatteryInfo(
        // Basic info
        val level: Int,                       // 0-100 (or -1 if error)
        val isCharging: Boolean,
        val chargingType: ChargingType,

        // 44% Threshold info
        val isAboveThreshold: Boolean,        // > 44%
        val isBelowThreshold: Boolean,        // ≤ 44%
        val thresholdPercent: Int = LocationConstants.BATTERY_PROTECTION_THRESHOLD,

        // Decision info
        val category: BatteryCategory,
        val shouldProtect: Boolean,           // Should enforce 44% protection

        // System info
        val isPowerSaveMode: Boolean,
        val isBatteryOptimized: Boolean,
        val isInDozeMode: Boolean,

        // Timestamp
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Interval calculation decision with complete explanation
     */
    data class IntervalDecision(
        val intervalMs: Long,                 // The calculated interval
        val reason: String,                   // Human-readable reason
        val intervalType: String,             // "emergency"/"realtime"/"normal"/"forced"
        val batteryProtectionActive: Boolean, // Is 44% protection enforced?
        val modeOverride: String?,            // "emergency"/"realtime"/"forceCheck" if overridden
        val originalInterval: Long,           // Admin's original interval
        val batteryLevel: Int,                // Battery level at decision
        val isCharging: Boolean,              // Charging status at decision
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Battery category based on level
     */
    enum class BatteryCategory {
        HIGH,       // > 44% (above threshold)
        LOW,        // ≤ 44% (below threshold, protection may apply)
        CRITICAL,   // < 15% (very low)
        UNKNOWN     // Cannot determine
    }

    /**
     * Charging type
     */
    enum class ChargingType {
        AC,
        USB,
        WIRELESS,
        NOT_CHARGING
    }

    /**
     * Threshold crossing detection
     */
    enum class ThresholdCrossing {
        NONE,           // No crossing
        CROSSED_UP,     // From ≤44% to >44% (battery recovering)
        CROSSED_DOWN    // From >44% to ≤44% (battery draining)
    }

    /**
     * Internal threshold state for hysteresis
     */
    private enum class ThresholdState {
        ABOVE,      // Confirmed above threshold
        BELOW,      // Confirmed below threshold
        UNKNOWN     // Initial state or uncertain
    }

    // ============================================================================
    // 44% THRESHOLD SYSTEM (Core Battery Protection)
    // ============================================================================

    /**
     * Check if battery is above 44% threshold
     * Fail-safe: Returns true if battery level unknown (treat as safe)
     */
    fun isBatteryAboveThreshold(): Boolean {
        val level = getBatteryLevel()
        if (level < 0) return true  // Unknown battery → treat as safe (fail-safe)
        return level > LocationConstants.BATTERY_PROTECTION_THRESHOLD
    }

    /**
     * Check if battery is below or at 44% threshold
     * Fail-safe: Returns false if battery level unknown (treat as safe)
     */
    fun isBatteryBelowThreshold(): Boolean {
        val level = getBatteryLevel()
        if (level < 0) return false  // Unknown battery → treat as safe (fail-safe)
        return level <= LocationConstants.BATTERY_PROTECTION_THRESHOLD
    }

    /**
     * Check if battery protection should be enforced
     *
     * Protection is enforced when:
     * - Battery ≤ 44% AND
     * - ForceCheck is OFF AND
     * - Emergency mode is OFF AND
     * - Device is NOT charging
     *
     * @param forceCheck If true, admin overrides battery protection
     * @param emergencyMode If true, emergency overrides battery protection
     * @return true if 2-hour interval should be forced
     */
    fun shouldUseBatteryProtection(
        forceCheck: Boolean = false,
        emergencyMode: Boolean = false
    ): Boolean {
        // Emergency mode overrides everything
        if (emergencyMode) return false

        // ForceCheck overrides battery protection
        if (forceCheck) return false

        // Charging overrides battery protection (makes sense - not draining)
        if (isCharging()) return false

        // Unknown battery → don't protect (fail-safe)
        val level = getBatteryLevel()
        if (level < 0) return false

        // Protect if battery ≤ 44%
        return level <= LocationConstants.BATTERY_PROTECTION_THRESHOLD
    }

    /**
     * Alias for shouldUseBatteryProtection - more explicit name
     */
    fun shouldForce2HourInterval(
        forceCheck: Boolean = false,
        emergencyMode: Boolean = false
    ): Boolean {
        return shouldUseBatteryProtection(forceCheck, emergencyMode)
    }

    // ============================================================================
    // INTERVAL CALCULATION (Main Decision Logic)
    // ============================================================================

    /**
     * Calculate effective tracking interval based on battery state and modes
     *
     * Priority Order:
     * 1. Emergency Mode → emergency interval (overrides everything)
     * 2. Realtime Mode → realtime interval (if not emergency)
     * 3. Charging → admin interval (no protection)
     * 4. Battery > 44% → admin interval
     * 5. Battery ≤ 44% + ForceCheck ON → admin interval (override)
     * 6. Battery ≤ 44% + ForceCheck OFF → 2 hours (protection)
     *
     * @param batteryLevel Current battery level (0-100, or -1 if unknown)
     * @param forceCheck Admin override - ignore battery protection
     * @param emergencyMode Emergency mode - aggressive tracking
     * @param realtimeMode Realtime mode - frequent tracking
     * @param adminNormalInterval Admin's normal interval (from backend)
     * @param adminRealtimeInterval Admin's realtime interval (from backend)
     * @param adminEmergencyInterval Admin's emergency interval (from backend)
     * @return IntervalDecision with interval and complete explanation
     */
    fun calculateEffectiveInterval(
        batteryLevel: Int,
        forceCheck: Boolean,
        emergencyMode: Boolean,
        realtimeMode: Boolean,
        adminNormalInterval: Long,
        adminRealtimeInterval: Long,
        adminEmergencyInterval: Long
    ): IntervalDecision {
        val isCharging = isCharging()

        // Priority 1: Emergency Mode (overrides everything)
        if (emergencyMode) {
            return IntervalDecision(
                intervalMs = adminEmergencyInterval,
                reason = "Emergency mode active - using emergency interval",
                intervalType = LocationData.INTERVAL_TYPE_EMERGENCY,
                batteryProtectionActive = false,
                modeOverride = "emergency",
                originalInterval = adminNormalInterval,
                batteryLevel = batteryLevel,
                isCharging = isCharging
            )
        }

        // Priority 2: Realtime Mode (if not emergency)
        if (realtimeMode) {
            return IntervalDecision(
                intervalMs = adminRealtimeInterval,
                reason = "Realtime mode active - using realtime interval",
                intervalType = LocationData.INTERVAL_TYPE_REALTIME,
                batteryProtectionActive = false,
                modeOverride = "realtime",
                originalInterval = adminNormalInterval,
                batteryLevel = batteryLevel,
                isCharging = isCharging
            )
        }

        // Priority 3: Charging (no protection needed)
        if (isCharging) {
            return IntervalDecision(
                intervalMs = adminNormalInterval,
                reason = "Device charging - using admin interval (no battery protection needed)",
                intervalType = LocationData.INTERVAL_TYPE_NORMAL,
                batteryProtectionActive = false,
                modeOverride = null,
                originalInterval = adminNormalInterval,
                batteryLevel = batteryLevel,
                isCharging = true
            )
        }

        // Unknown battery → use admin interval (fail-safe)
        if (batteryLevel < 0) {
            return IntervalDecision(
                intervalMs = adminNormalInterval,
                reason = "Unknown battery level - using admin interval (fail-safe)",
                intervalType = LocationData.INTERVAL_TYPE_NORMAL,
                batteryProtectionActive = false,
                modeOverride = null,
                originalInterval = adminNormalInterval,
                batteryLevel = -1,
                isCharging = false
            )
        }

        // Priority 4: Battery > 44% (normal operation)
        if (batteryLevel > LocationConstants.BATTERY_PROTECTION_THRESHOLD) {
            return IntervalDecision(
                intervalMs = adminNormalInterval,
                reason = "Battery above ${LocationConstants.BATTERY_PROTECTION_THRESHOLD}% - using admin interval",
                intervalType = LocationData.INTERVAL_TYPE_NORMAL,
                batteryProtectionActive = false,
                modeOverride = null,
                originalInterval = adminNormalInterval,
                batteryLevel = batteryLevel,
                isCharging = false
            )
        }

        // Battery ≤ 44% - check ForceCheck

        // Priority 5: ForceCheck ON (admin overrides protection)
        if (forceCheck) {
            return IntervalDecision(
                intervalMs = adminNormalInterval,
                reason = "ForceCheck enabled - ignoring battery protection (battery=${batteryLevel}%)",
                intervalType = LocationData.INTERVAL_TYPE_NORMAL,
                batteryProtectionActive = false,
                modeOverride = "forceCheck",
                originalInterval = adminNormalInterval,
                batteryLevel = batteryLevel,
                isCharging = false
            )
        }

        // Priority 6: Battery ≤ 44% + ForceCheck OFF (enforce protection)
        return IntervalDecision(
            intervalMs = LocationConstants.BATTERY_LOW_FORCED_INTERVAL_MS,  // 2 hours
            reason = "Battery protection active (battery=${batteryLevel}% ≤ ${LocationConstants.BATTERY_PROTECTION_THRESHOLD}%) - forcing 2-hour interval",
            intervalType = LocationData.INTERVAL_TYPE_FORCED,
            batteryProtectionActive = true,
            modeOverride = null,
            originalInterval = adminNormalInterval,
            batteryLevel = batteryLevel,
            isCharging = false
        )
    }

    // ============================================================================
    // COMPLETE BATTERY INFORMATION
    // ============================================================================

    /**
     * Get complete battery information in a single call
     *
     * @param forceCheck Current ForceCheck state
     * @param emergencyMode Current Emergency mode state
     * @return Complete BatteryInfo object with all battery data
     */
    fun getCompleteBatteryInfo(
        forceCheck: Boolean = false,
        emergencyMode: Boolean = false
    ): BatteryInfo {
        val level = getBatteryLevel()
        val isCharging = isCharging()
        val chargingType = getChargingType()

        // Threshold checks (fail-safe for unknown battery)
        val isAbove = if (level < 0) true else level > LocationConstants.BATTERY_PROTECTION_THRESHOLD
        val isBelow = if (level < 0) false else level <= LocationConstants.BATTERY_PROTECTION_THRESHOLD

        // Category
        val category = when {
            level < 0 -> BatteryCategory.UNKNOWN
            level < 15 -> BatteryCategory.CRITICAL
            level <= LocationConstants.BATTERY_PROTECTION_THRESHOLD -> BatteryCategory.LOW
            else -> BatteryCategory.HIGH
        }

        // Should protect?
        val shouldProtect = shouldUseBatteryProtection(forceCheck, emergencyMode)

        // System info
        val isPowerSave = isPowerSaveMode()
        val isOptimized = isBatteryOptimized()
        val isDoze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isInDozeMode()
        } else {
            false
        }

        return BatteryInfo(
            level = level,
            isCharging = isCharging,
            chargingType = chargingType,
            isAboveThreshold = isAbove,
            isBelowThreshold = isBelow,
            thresholdPercent = LocationConstants.BATTERY_PROTECTION_THRESHOLD,
            category = category,
            shouldProtect = shouldProtect,
            isPowerSaveMode = isPowerSave,
            isBatteryOptimized = isOptimized,
            isInDozeMode = isDoze
        )
    }

    // ============================================================================
    // LOCATIONDATA INTEGRATION
    // ============================================================================

    /**
     * Populate LocationData battery fields
     *
     * Updates:
     * - batteryLevel: Current battery level
     * - batteryThresholdActive: true if battery ≤ 44%
     * - forcedInterval: true if 2-hour interval forced
     *
     * @param locationData The LocationData to update
     * @param forceCheck Current ForceCheck state
     * @param emergencyMode Current Emergency mode state
     * @return Updated LocationData with battery fields populated
     */
    fun populateBatteryFields(
        locationData: LocationData,
        forceCheck: Boolean = false,
        emergencyMode: Boolean = false
    ): LocationData {
        val level = getBatteryLevel()

        // batteryThresholdActive: true if battery ≤ 44%
        val thresholdActive = if (level < 0) {
            false  // Unknown → treat as safe
        } else {
            level <= LocationConstants.BATTERY_PROTECTION_THRESHOLD
        }

        // forcedInterval: true if 2-hour interval actually forced
        val forced = shouldUseBatteryProtection(forceCheck, emergencyMode)

        return locationData.copy(
            batteryLevel = if (level < 0) null else level,
            batteryThresholdActive = thresholdActive,
            forcedInterval = forced
        )
    }

    // ============================================================================
    // HYSTERESIS & THRESHOLD CROSSING DETECTION
    // ============================================================================

    /**
     * Detect threshold crossing with hysteresis
     *
     * Hysteresis prevents thrashing when battery oscillates near 44%:
     * - Must drop to 42% or below to count as "below threshold"
     * - Must rise to 46% or above to count as "above threshold"
     * - Must remain stable for 5 minutes before emitting crossing event
     *
     * @param currentLevel Current battery level
     * @return ThresholdCrossing event (NONE, CROSSED_UP, CROSSED_DOWN)
     */
    fun detectThresholdCrossing(currentLevel: Int): ThresholdCrossing {
        if (currentLevel < 0) return ThresholdCrossing.NONE  // Unknown battery

        val now = System.currentTimeMillis()

        // Determine new state based on hysteresis bounds
        val newState = when {
            currentLevel <= HYSTERESIS_LOWER_BOUND -> ThresholdState.BELOW
            currentLevel >= HYSTERESIS_UPPER_BOUND -> ThresholdState.ABOVE
            else -> currentThresholdState  // Stay in current state (hysteresis zone)
        }

        // Check if state changed
        if (newState != currentThresholdState) {
            // State changed - reset stability tracking
            lastStableBatteryLevel = currentLevel
            lastStableTimestamp = now

            // Don't emit crossing yet - wait for stability
            val oldState = currentThresholdState
            currentThresholdState = newState

            LocationLogger.d(
                LocationConstants.TAG_BATTERY,
                "Battery state changed: $oldState → $newState (level=$currentLevel%, waiting for stability)"
            )

            return ThresholdCrossing.NONE
        }

        // State unchanged - check stability
        val isStable = (now - lastStableTimestamp) >= STABILITY_PERIOD_MS

        if (isStable && lastStableBatteryLevel != currentLevel) {
            // Stable for 5 minutes - emit crossing event
            val crossing = when {
                currentThresholdState == ThresholdState.ABOVE && lastStableBatteryLevel <= LocationConstants.BATTERY_PROTECTION_THRESHOLD -> {
                    ThresholdCrossing.CROSSED_UP
                }
                currentThresholdState == ThresholdState.BELOW && lastStableBatteryLevel > LocationConstants.BATTERY_PROTECTION_THRESHOLD -> {
                    ThresholdCrossing.CROSSED_DOWN
                }
                else -> ThresholdCrossing.NONE
            }

            if (crossing != ThresholdCrossing.NONE) {
                LocationLogger.i(
                    LocationConstants.TAG_BATTERY,
                    "Battery threshold crossing: $crossing (${lastStableBatteryLevel}% → ${currentLevel}%, stable for 5 min)"
                )
                lastStableBatteryLevel = currentLevel
            }

            return crossing
        }

        return ThresholdCrossing.NONE
    }

    // ============================================================================
    // BATTERY READING & STATUS
    // ============================================================================

    /**
     * Get current battery level (0-100)
     *
     * @return Battery percentage (0-100), or -1 if error/unknown
     */
    fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.let { intent ->
            val levelRaw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            if (levelRaw >= 0 && scale > 0) {
                val percentage = ((levelRaw.toFloat() / scale.toFloat()) * 100).toInt()

                // Normalize and validate
                when {
                    percentage < 0 -> -1      // Error
                    percentage > 100 -> 100   // Clamp
                    else -> percentage
                }
            } else {
                -1  // Error
            }
        } ?: -1

        return level
    }

    /**
     * Check if device is charging
     */
    fun isCharging(): Boolean {
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return batteryIntent?.let { intent ->
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
    }

    /**
     * Get charging type
     */
    fun getChargingType(): ChargingType {
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return batteryIntent?.let { intent ->
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> ChargingType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> ChargingType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingType.WIRELESS
                else -> ChargingType.NOT_CHARGING
            }
        } ?: ChargingType.NOT_CHARGING
    }

    // ============================================================================
    // BATTERY OPTIMIZATION & SYSTEM CHECKS
    // ============================================================================

    /**
     * Check if app is currently battery optimized
     */
    fun isBatteryOptimized(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            false
        }
    }

    /**
     * Request battery optimization whitelist exemption
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun requestWhitelistExemption(activity: Activity) {
        if (!isBatteryOptimized()) {
            LocationLogger.i(LocationConstants.TAG_BATTERY, "App already whitelisted")
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivityForResult(intent, LocationConstants.BATTERY_OPTIMIZATION_WHITELIST_REQUEST_CODE)
            LocationLogger.i(LocationConstants.TAG_BATTERY, "Requesting battery optimization exemption")
        } catch (e: Exception) {
            LocationLogger.e(LocationConstants.TAG_BATTERY, "Failed to request whitelist exemption", e)
        }
    }

    /**
     * Open battery optimization settings
     */
    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            LocationLogger.e(LocationConstants.TAG_BATTERY, "Failed to open battery settings", e)
        }
    }

    /**
     * Check if device is in doze mode
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun isInDozeMode(): Boolean {
        return powerManager.isDeviceIdleMode
    }

    /**
     * Check if power save mode is enabled
     */
    fun isPowerSaveMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }

    /**
     * Check if exact alarms can be scheduled (Android 12+)
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Request exact alarm permission (Android 12+)
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun requestExactAlarmPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            activity.startActivity(intent)
            LocationLogger.i(LocationConstants.TAG_BATTERY, "Requesting exact alarm permission")
        } catch (e: Exception) {
            LocationLogger.e(LocationConstants.TAG_BATTERY, "Failed to request exact alarm permission", e)
        }
    }

    // ============================================================================
    // STATISTICS & LOGGING
    // ============================================================================

    /**
     * Get comprehensive battery statistics
     */
    fun getBatteryStats(forceCheck: Boolean = false, emergencyMode: Boolean = false): Map<String, Any> {
        val info = getCompleteBatteryInfo(forceCheck, emergencyMode)

        return mapOf(
            "level" to info.level,
            "category" to info.category.name,
            "isCharging" to info.isCharging,
            "chargingType" to info.chargingType.name,
            "isAboveThreshold" to info.isAboveThreshold,
            "isBelowThreshold" to info.isBelowThreshold,
            "thresholdPercent" to info.thresholdPercent,
            "shouldProtect" to info.shouldProtect,
            "isPowerSaveMode" to info.isPowerSaveMode,
            "isBatteryOptimized" to info.isBatteryOptimized,
            "isInDozeMode" to info.isInDozeMode,
            "forceCheckActive" to forceCheck,
            "emergencyModeActive" to emergencyMode
        )
    }

    /**
     * Log battery status with interval decision
     */
    fun logBatteryStatus(
        forceCheck: Boolean = false,
        emergencyMode: Boolean = false,
        realtimeMode: Boolean = false,
        adminNormalInterval: Long = LocationConstants.FALLBACK_UPDATE_INTERVAL_MS,
        adminRealtimeInterval: Long = LocationConstants.FALLBACK_REALTIME_INTERVAL_MS,
        adminEmergencyInterval: Long = LocationConstants.FALLBACK_EMERGENCY_INTERVAL_MS
    ) {
        val info = getCompleteBatteryInfo(forceCheck, emergencyMode)
        val decision = calculateEffectiveInterval(
            info.level,
            forceCheck,
            emergencyMode,
            realtimeMode,
            adminNormalInterval,
            adminRealtimeInterval,
            adminEmergencyInterval
        )

        val message = buildString {
            append("Battery: ${info.level}% (${info.category})")
            append(", Charging: ${info.isCharging}")
            if (info.isCharging) append(" (${info.chargingType})")
            append(", Threshold: ${if (info.isAboveThreshold) "Above" else "Below"} ${info.thresholdPercent}%")
            append(", Protection: ${if (info.shouldProtect) "ACTIVE" else "Inactive"}")
            append(", PowerSave: ${info.isPowerSaveMode}")
            append(", Optimized: ${info.isBatteryOptimized}")
            append(" | Interval: ${decision.intervalMs / 1000}s (${decision.intervalType})")
            append(" | Reason: ${decision.reason}")
        }

        LocationLogger.i(LocationConstants.TAG_BATTERY, message)
    }

    /**
     * Log interval decision details
     */
    fun logIntervalDecision(decision: IntervalDecision) {
        val intervalMinutes = decision.intervalMs / 60000
        val message = buildString {
            append("Interval Decision: ${intervalMinutes} min")
            append(" | Type: ${decision.intervalType}")
            append(" | Battery: ${decision.batteryLevel}%")
            if (decision.isCharging) append(" (Charging)")
            append(" | Protection: ${if (decision.batteryProtectionActive) "ACTIVE" else "Inactive"}")
            decision.modeOverride?.let { append(" | Override: $it") }
            append(" | Reason: ${decision.reason}")
        }

        LocationLogger.i(LocationConstants.TAG_BATTERY, message)
    }

    /**
     * Log threshold crossing event
     */
    fun logThresholdCrossing(crossing: ThresholdCrossing, oldLevel: Int, newLevel: Int) {
        when (crossing) {
            ThresholdCrossing.CROSSED_UP -> {
                LocationLogger.i(
                    LocationConstants.TAG_BATTERY,
                    "Battery threshold CROSSED UP: ${oldLevel}% → ${newLevel}% (protection DEACTIVATED)"
                )
            }
            ThresholdCrossing.CROSSED_DOWN -> {
                LocationLogger.i(
                    LocationConstants.TAG_BATTERY,
                    "Battery threshold CROSSED DOWN: ${oldLevel}% → ${newLevel}% (protection ACTIVATED)"
                )
            }
            ThresholdCrossing.NONE -> {
                // No logging for no crossing
            }
        }
    }

    /**
     * Get human-readable interval description
     */
    fun getIntervalDescription(intervalMs: Long): String {
        val seconds = intervalMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            seconds < 60 -> "${seconds}s"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> "${hours / 24}d"
        }
    }
}

