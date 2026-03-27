package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * TRACKING STATUS LOGGER
 * ═══════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE: Logs tracking status to Firebase so admins can see EXACTLY why tracking
 * is or isn't working for any user at any time.
 *
 * FIREBASE COLLECTION: tracking_status/{userId}
 *
 * WHAT IT LOGS:
 * - Current tracking state (running/stopped)
 * - All permission statuses
 * - Device state (battery, charging, doze mode, battery saver)
 * - Network availability
 * - Location service state
 * - Issues preventing tracking
 * - Device switched off/rebooted events
 * - Each location capture
 * - Upload success/failure
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════
 */
object TrackingStatusLogger {

    private const val TAG = "TRACKING_STATUS"
    private const val COLLECTION_NAME = "tracking_status"
    private const val PREFS_NAME = "tracking_status_prefs"
    private const val KEY_LAST_BOOT_TIME = "last_boot_time"
    private const val KEY_WAS_TRACKING_BEFORE_SHUTDOWN = "was_tracking_before_shutdown"
    private const val KEY_LAST_TRACKING_TIME = "last_tracking_time"

    // Issue codes
    object Issues {
        const val PERM_FINE_DENIED = "PERM_FINE_DENIED"
        const val PERM_BG_DENIED = "PERM_BG_DENIED"
        const val PERM_NOTIFICATION_DENIED = "PERM_NOTIFICATION_DENIED"
        const val LOC_SERVICES_OFF = "LOC_SERVICES_OFF"
        const val GPS_DISABLED = "GPS_DISABLED"
        const val SERVICE_STOPPED = "SERVICE_STOPPED"
        const val BATTERY_SAVER = "BATTERY_SAVER"
        const val DOZE_MODE = "DOZE_MODE"
        const val NO_NETWORK = "NO_NETWORK"
        const val ADMIN_DISABLED = "ADMIN_DISABLED"
        const val DEVICE_REBOOTED = "DEVICE_REBOOTED"
        const val DEVICE_SHUTDOWN = "DEVICE_SHUTDOWN"
        const val APP_FORCE_STOPPED = "APP_FORCE_STOPPED"
    }

    private val issueDescriptions = mapOf(
        Issues.PERM_FINE_DENIED to "Fine location permission denied",
        Issues.PERM_BG_DENIED to "Background location permission denied",
        Issues.PERM_NOTIFICATION_DENIED to "Notification permission denied",
        Issues.LOC_SERVICES_OFF to "Device location services OFF",
        Issues.GPS_DISABLED to "GPS provider disabled",
        Issues.SERVICE_STOPPED to "Tracking service not running",
        Issues.BATTERY_SAVER to "Battery saver ON",
        Issues.DOZE_MODE to "Doze mode active",
        Issues.NO_NETWORK to "No network connection",
        Issues.ADMIN_DISABLED to "Tracking disabled by admin",
        Issues.DEVICE_REBOOTED to "Device rebooted",
        Issues.DEVICE_SHUTDOWN to "Device switched off",
        Issues.APP_FORCE_STOPPED to "App force stopped"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Log current tracking status with all diagnostics
     */
    fun logStatus(
        context: Context,
        isTracking: Boolean,
        additionalIssues: List<String> = emptyList(),
        reason: String? = null
    ) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val issues = mutableListOf<String>()
            issues.addAll(additionalIssues)

            // Check permissions
            val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true

            if (!hasFine) issues.add(Issues.PERM_FINE_DENIED)
            if (!hasBg) issues.add(Issues.PERM_BG_DENIED)
            if (!hasNotif) issues.add(Issues.PERM_NOTIFICATION_DENIED)

            // Check location services
            val locMgr = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val locEnabled = locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER) || locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val gpsEnabled = locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!locEnabled) issues.add(Issues.LOC_SERVICES_OFF)
            if (!gpsEnabled) issues.add(Issues.GPS_DISABLED)

            // Check power state
            val pwrMgr = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pwrSave = pwrMgr.isPowerSaveMode
            val doze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pwrMgr.isDeviceIdleMode else false
            if (pwrSave) issues.add(Issues.BATTERY_SAVER)
            if (doze) issues.add(Issues.DOZE_MODE)

            // Check network
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = connMgr.activeNetwork
            val caps = connMgr.getNetworkCapabilities(net)
            val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val netType = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "none"
            }
            if (!hasNet) issues.add(Issues.NO_NETWORK)

            // Check battery
            val batMgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batLevel = batMgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = batMgr.isCharging

            if (!isTracking) issues.add(Issues.SERVICE_STOPPED)

            val primaryIssue = issues.firstOrNull()
            val primaryDesc = primaryIssue?.let { issueDescriptions[it] } ?: "No issues"
            val statusMsg = when {
                issues.isEmpty() -> "Tracking working normally"
                issues.size == 1 -> primaryDesc
                else -> "Multiple issues: ${issues.joinToString(", ")}"
            }

            val data = hashMapOf(
                "isTracking" to isTracking,
                "issues" to issues.distinct(),
                "primaryIssue" to primaryIssue,
                "statusMessage" to statusMsg,
                "reason" to reason,
                "permissions" to hashMapOf("fineLocation" to hasFine, "backgroundLocation" to hasBg, "notifications" to hasNotif),
                "deviceState" to hashMapOf("locationEnabled" to locEnabled, "gpsEnabled" to gpsEnabled, "batteryLevel" to batLevel, "isCharging" to charging, "batterySaverOn" to pwrSave, "dozeMode" to doze),
                "network" to hashMapOf("available" to hasNet, "type" to netType),
                "lastUpdated" to getCurrentTimestamp(),
                "deviceModel" to Build.MODEL,
                "osVersion" to Build.VERSION.SDK_INT.toString()
            )

            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
                .addOnSuccessListener { Log.d(TAG, "✅ Status logged: $statusMsg") }
                .addOnFailureListener { Log.e(TAG, "❌ Failed: ${it.message}") }

            if (isTracking) {
                getPrefs(context).edit().putBoolean(KEY_WAS_TRACKING_BEFORE_SHUTDOWN, true).putLong(KEY_LAST_TRACKING_TIME, System.currentTimeMillis()).apply()
            }
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log device boot
     */
    fun logDeviceBoot(context: Context) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val prefs = getPrefs(context)
            val wasTracking = prefs.getBoolean(KEY_WAS_TRACKING_BEFORE_SHUTDOWN, false)
            val lastTime = prefs.getLong(KEY_LAST_TRACKING_TIME, 0)
            val offDuration = if (lastTime > 0) System.currentTimeMillis() - lastTime else 0

            val data = hashMapOf(
                "lastBootAt" to getCurrentTimestamp(),
                "wasTrackingBeforeShutdown" to wasTracking,
                "deviceOffDurationMs" to offDuration,
                "deviceOffDuration" to formatDuration(offDuration),
                "bootReason" to "Device rebooted/powered on",
                "lastUpdated" to getCurrentTimestamp()
            )
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            prefs.edit().putBoolean(KEY_WAS_TRACKING_BEFORE_SHUTDOWN, false).apply()
            Log.d(TAG, "🔄 Boot logged - off for ${formatDuration(offDuration)}")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log app shutdown
     */
    fun logAppShutdown(context: Context, reason: String = "App shutdown") {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = hashMapOf("lastShutdownAt" to getCurrentTimestamp(), "shutdownReason" to reason, "isTracking" to false, "lastUpdated" to getCurrentTimestamp())
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            Log.d(TAG, "📴 Shutdown: $reason")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log tracking started
     */
    fun logTrackingStarted(context: Context, intervalMs: Long) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = hashMapOf(
                "isTracking" to true,
                "trackingStartedAt" to getCurrentTimestamp(),
                "serviceState" to hashMapOf("isRunning" to true, "currentIntervalMs" to intervalMs, "currentIntervalFormatted" to formatInterval(intervalMs)),
                "lastUpdated" to getCurrentTimestamp()
            )
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            Log.d(TAG, "▶️ Started - ${formatInterval(intervalMs)}")
            logStatus(context, true, reason = "Service started")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log tracking stopped
     */
    fun logTrackingStopped(context: Context, reason: String = "Service stopped") {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = hashMapOf("isTracking" to false, "trackingStoppedAt" to getCurrentTimestamp(), "stopReason" to reason, "serviceState" to hashMapOf("isRunning" to false), "lastUpdated" to getCurrentTimestamp())
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            Log.d(TAG, "⏹️ Stopped - $reason")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log location captured
     */
    fun logLocationCaptured(context: Context, latitude: Double, longitude: Double, accuracy: Float, provider: String) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = hashMapOf(
                "lastCaptureAt" to getCurrentTimestamp(),
                "lastLatitude" to latitude,
                "lastLongitude" to longitude,
                "lastAccuracy" to accuracy,
                "lastProvider" to provider,
                "captureCount" to com.google.firebase.firestore.FieldValue.increment(1),
                "lastUpdated" to getCurrentTimestamp()
            )
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            Log.d(TAG, "📍 Captured: $latitude, $longitude")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log upload result
     */
    fun logUploadResult(context: Context, success: Boolean, count: Int, error: String? = null) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = if (success) {
                hashMapOf("lastUploadAt" to getCurrentTimestamp(), "lastUploadCount" to count, "lastUploadSuccess" to true, "totalUploaded" to com.google.firebase.firestore.FieldValue.increment(count.toLong()), "lastUpdated" to getCurrentTimestamp())
            } else {
                hashMapOf("lastUploadAttemptAt" to getCurrentTimestamp(), "lastUploadSuccess" to false, "lastUploadError" to error, "lastUpdated" to getCurrentTimestamp())
            }
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            if (success) Log.d(TAG, "☁️ Upload OK: $count") else Log.e(TAG, "☁️ Upload FAIL: $error")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    /**
     * Log interval change
     */
    fun logIntervalChange(context: Context, oldMs: Long, newMs: Long, reason: String) {
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val data = hashMapOf(
                "serviceState" to hashMapOf("currentIntervalMs" to newMs, "currentIntervalFormatted" to formatInterval(newMs), "previousIntervalMs" to oldMs, "intervalChangeReason" to reason, "intervalChangedAt" to getCurrentTimestamp()),
                "lastUpdated" to getCurrentTimestamp()
            )
            FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(userId).set(data, SetOptions.merge())
            Log.d(TAG, "⏱️ Interval: ${formatInterval(oldMs)} → ${formatInterval(newMs)}")
        } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun formatInterval(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return when { h > 0 -> "${h}h ${m % 60}m"; m > 0 -> "${m}m ${s % 60}s"; else -> "${s}s" }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "Unknown"
        val s = ms / 1000; val m = s / 60; val h = m / 60; val d = h / 24
        return when { d > 0 -> "${d}d ${h % 24}h"; h > 0 -> "${h}h ${m % 60}m"; m > 0 -> "${m}m ${s % 60}s"; else -> "${s}s" }
    }
}
