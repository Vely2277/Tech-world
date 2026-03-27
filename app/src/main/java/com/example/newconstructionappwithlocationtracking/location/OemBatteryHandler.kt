/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * OEM BATTERY KILLER HANDLER - MANUFACTURER-SPECIFIC WORKAROUNDS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Handle aggressive battery optimization by specific manufacturers that kill background services:
 * - Xiaomi (MIUI) - Autostart, Battery Saver
 * - Huawei (EMUI) - Protected Apps, Power-intensive Apps
 * - Samsung (OneUI) - Sleeping Apps, Deep Sleeping Apps
 * - Oppo (ColorOS) - Autostart, Background restrictions
 * - Vivo (FuntouchOS) - Background consumption
 * - OnePlus (OxygenOS) - Battery optimization
 * - Realme - Same as Oppo (ColorOS)
 * - Asus - Auto-start Manager
 *
 * WHY THIS IS ESSENTIAL:
 * - Standard Android battery optimization is manageable
 * - OEM-specific "optimizations" are BRUTAL and UNDOCUMENTED
 * - They kill services even with foreground notification
 * - Users MUST whitelist apps in OEM settings
 * - This class detects OEM and guides users to correct settings
 *
 * DESIGN:
 * - Detect manufacturer and UI version
 * - Check if app is whitelisted
 * - Provide user guidance with direct intent to settings
 * - Track if user has been warned (don't spam)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger

/**
 * OemBatteryHandler - Handles manufacturer-specific battery killer workarounds
 */
object OemBatteryHandler {

    private const val TAG = "OemBatteryHandler"

    // Known aggressive OEMs
    private val AGGRESSIVE_OEMS = listOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "samsung",
        "oppo", "realme",
        "vivo",
        "oneplus",
        "asus",
        "meizu",
        "letv", "leeco",
        "nokia", "hmd global"
    )

    /**
     * OEM-specific settings intents
     */
    private val OEM_INTENTS = mapOf(
        // Xiaomi MIUI
        "xiaomi" to listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        ),
        "redmi" to listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        ),
        "poco" to listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        ),

        // Huawei EMUI
        "huawei" to listOf(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
        ),
        "honor" to listOf(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        ),

        // Samsung OneUI
        "samsung" to listOf(
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setAction("com.samsung.android.sm.ACTION_BATTERY")
        ),

        // Oppo ColorOS
        "oppo" to listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setAction("com.coloros.safecenter").putExtra("packageName", "com.example.newconstructionappwithlocationtracking")
        ),
        "realme" to listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
        ),

        // Vivo FuntouchOS
        "vivo" to listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        ),

        // OnePlus OxygenOS
        "oneplus" to listOf(
            Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
        ),

        // Asus ZenUI
        "asus" to listOf(
            Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
            Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
        ),

        // Meizu Flyme
        "meizu" to listOf(
            Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"))
        ),

        // LeTV/LeEco
        "letv" to listOf(
            Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"))
        ),

        // Nokia
        "nokia" to listOf(
            Intent().setComponent(ComponentName("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"))
        )
    )

    /**
     * User guidance messages for each OEM
     */
    private val OEM_GUIDANCE = mapOf(
        "xiaomi" to "MIUI aggressively kills background apps. Please:\n1. Go to Settings → Apps → Manage apps → Construct Connect\n2. Enable 'Autostart'\n3. Set Battery saver to 'No restrictions'\n4. Lock the app in Recent apps",
        "huawei" to "EMUI restricts background apps. Please:\n1. Go to Settings → Apps → Apps → Construct Connect\n2. Enable 'Allow auto-launch'\n3. In Battery → App launch, set to 'Manage manually' and enable all options",
        "samsung" to "Samsung may put apps to sleep. Please:\n1. Go to Settings → Battery → Background usage limits\n2. Remove Construct Connect from 'Sleeping apps' and 'Deep sleeping apps'\n3. In Device care → Battery, add app to 'Never sleeping apps'",
        "oppo" to "ColorOS restricts background apps. Please:\n1. Go to Settings → App Management → App List → Construct Connect\n2. Enable 'Allow auto-startup'\n3. Disable 'Background freeze'",
        "vivo" to "FuntouchOS restricts background apps. Please:\n1. Go to Settings → Battery → Background power consumption management\n2. Set Construct Connect to 'Allow background running'",
        "oneplus" to "OxygenOS may restrict background apps. Please:\n1. Go to Settings → Battery → Battery optimization\n2. Set Construct Connect to 'Don't optimize'"
    )

    /**
     * Data class for OEM detection result
     */
    data class OemInfo(
        val manufacturer: String,
        val isAggressiveOem: Boolean,
        val oemKey: String?,
        val guidance: String?,
        val settingsIntents: List<Intent>
    )

    /**
     * Detect OEM and return info
     */
    fun detectOem(): OemInfo {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        android.util.Log.d(TAG, "Detecting OEM: manufacturer=$manufacturer, brand=$brand")

        // Find matching OEM key
        val oemKey = AGGRESSIVE_OEMS.find { oem ->
            manufacturer.contains(oem) || brand.contains(oem)
        }

        val isAggressive = oemKey != null
        val intents = oemKey?.let { OEM_INTENTS[it] } ?: emptyList()
        val guidance = oemKey?.let { OEM_GUIDANCE[it] }

        return OemInfo(
            manufacturer = manufacturer,
            isAggressiveOem = isAggressive,
            oemKey = oemKey,
            guidance = guidance,
            settingsIntents = intents
        )
    }

    /**
     * Check if running on aggressive OEM
     */
    fun isAggressiveOem(): Boolean {
        return detectOem().isAggressiveOem
    }

    /**
     * Get intent to open OEM-specific battery settings
     * Returns first working intent or generic battery settings
     */
    fun getBatterySettingsIntent(context: Context): Intent {
        val oemInfo = detectOem()

        // Try OEM-specific intents first
        for (intent in oemInfo.settingsIntents) {
            if (isIntentAvailable(context, intent)) {
                android.util.Log.d(TAG, "Found working OEM intent: ${intent.component}")
                return intent
            }
        }

        // Fall back to generic battery optimization settings
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Check if an intent can be resolved
     */
    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return try {
            val resolveInfo = context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if user has already been shown OEM warning
     */
    fun hasShownOemWarning(context: Context): Boolean {
        return context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
            .getBoolean("oem_warning_shown", false)
    }

    /**
     * Mark OEM warning as shown
     */
    fun markOemWarningShown(context: Context) {
        context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("oem_warning_shown", true)
            .putLong("oem_warning_shown_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * Check if enough time has passed to show warning again
     * (In case user dismissed but didn't configure)
     */
    fun shouldShowOemWarningAgain(context: Context): Boolean {
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong("oem_warning_shown_time", 0L)

        // Show again if it's been more than 7 days and we've had tracking issues
        val daysSinceShown = (System.currentTimeMillis() - lastShown) / (24 * 60 * 60 * 1000L)
        val hasHadIssues = prefs.getInt("watchdog_restart_count", 0) > 5

        return daysSinceShown > 7 && hasHadIssues
    }

    /**
     * Get user guidance message
     */
    fun getGuidanceMessage(): String {
        val oemInfo = detectOem()
        return oemInfo.guidance ?: "Please disable battery optimization for Construct Connect to ensure reliable location tracking."
    }

    /**
     * Log OEM info for debugging
     */
    fun logOemInfo() {
        val oemInfo = detectOem()
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.d(TAG, "📱 OEM DETECTION:")
        android.util.Log.d(TAG, "   Manufacturer: ${Build.MANUFACTURER}")
        android.util.Log.d(TAG, "   Brand: ${Build.BRAND}")
        android.util.Log.d(TAG, "   Model: ${Build.MODEL}")
        android.util.Log.d(TAG, "   Is Aggressive OEM: ${oemInfo.isAggressiveOem}")
        android.util.Log.d(TAG, "   OEM Key: ${oemInfo.oemKey ?: "NONE"}")
        android.util.Log.d(TAG, "   Available Intents: ${oemInfo.settingsIntents.size}")
        android.util.Log.d(TAG, "═══════════════════════════════════════════════════════════════")
    }

    /**
     * Open OEM battery settings directly
     */
    fun openBatterySettings(context: Context): Boolean {
        return try {
            val intent = getBatterySettingsIntent(context)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to open battery settings", e)
            // Try generic settings as last resort
            try {
                val genericIntent = Intent(Settings.ACTION_SETTINGS)
                genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(genericIntent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
 l     * AutoStart blocking detected — log only, no notification shown to user
     */
    fun showAutoStartBlockedWarning(context: Context) {
        android.util.Log.e(TAG, "═══════════════════════════════════════════════════════════════")
        android.util.Log.e(TAG, "🚨 CRITICAL: AUTOSTART IS BLOCKED!")
        android.util.Log.e(TAG, "   Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        android.util.Log.e(TAG, "   This device is blocking the app from running in background!")
        android.util.Log.e(TAG, "   Location tracking WILL NOT WORK until AutoStart is enabled!")
        android.util.Log.e(TAG, "═══════════════════════════════════════════════════════════════")

        // No notification shown — silently record the issue
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("autostart_blocked_detected", true)
            .putLong("autostart_blocked_detected_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * No-op — notification removed, nothing to create
     */
    private fun createAutoStartBlockedNotification(context: Context, oemInfo: OemInfo) {
        // Notification completely removed — silent operation
    }

    /**
     * No-op — notification removed, nothing to cancel
     */
    fun cancelAutoStartBlockedNotification(context: Context) {
        // Notification completely removed — silent operation
    }

    /**
     * Check if AutoStart restrictions were detected
     */
    fun wasAutoStartBlockDetected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(LocationConstants.PREFS_NAME_SERVICE_STATE, Context.MODE_PRIVATE)
        return prefs.getBoolean("autostart_blocked_detected", false)
    }
}
