package com.example.newconstructionappwithlocationtracking.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.example.newconstructionappwithlocationtracking.services.DeviceStatusReporter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * FCM NOTIFICATION SERVICE - HANDLES ALL PUSH NOTIFICATIONS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * FEATURES:
 * 1. Message Notifications - Chat messages from buyers/sellers
 * 2. WAKE_SERVICE - Resurrects dead location tracking service
 * 3. Token Management - Registers/updates FCM tokens with backend
 * 4. Device Status Reporting - Sends comprehensive device state to backend
 * 5. Offline Response Queuing - Queues responses when offline, sends when network returns
 *
 * WAKE_SERVICE FCM:
 * - Sent by backend when no location data for 3+ hours
 * - Wakes app even if killed by OEM
 * - Checks all conditions and starts LocationTrackingService
 * - Sends response back to backend with status
 *
 * DEVICE STATUS REPORTING:
 * - Sends detailed permission breakdown (FINE, BACKGROUND, LOCATION_SERVICES)
 * - Reports network state, battery, and tracking state
 * - Works even when location tracking is disabled
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */
class FCMNotificationService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM_SERVICE"
        private const val CHANNEL_ID = "message_notifications"
        private const val CHANNEL_NAME = "Message Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications for new messages from buyers and sellers"

        // FCM Types
        private const val TYPE_WAKE_SERVICE = "WAKE_SERVICE"
        private const val TYPE_MESSAGE = "message"
        private const val TYPE_SETTINGS_UPDATED = "SETTINGS_UPDATED"

        // Wake response status
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_FAILED = "FAILED"

        // Specific Failure reasons - Detailed breakdown
        private const val FAILURE_NOT_LOGGED_IN = "NOT_LOGGED_IN"
        private const val FAILURE_NO_FINE_LOCATION_PERMISSION = "NO_FINE_LOCATION_PERMISSION"
        private const val FAILURE_NO_BACKGROUND_LOCATION_PERMISSION = "NO_BACKGROUND_LOCATION_PERMISSION"
        private const val FAILURE_LOCATION_SERVICES_DISABLED = "LOCATION_SERVICES_DISABLED"
        private const val FAILURE_SERVICE_START_FAILED = "SERVICE_START_FAILED"
        private const val FAILURE_TRACKING_DISABLED = "TRACKING_DISABLED"
        private const val FAILURE_SERVICE_NOT_RUNNING = "SERVICE_NOT_RUNNING"
        private const val FAILURE_NO_NETWORK = "NO_NETWORK"

        // Prefs for queued responses
        private const val PREFS_QUEUED_RESPONSES = "fcm_queued_responses"
        private const val KEY_QUEUED_RESPONSES = "queued_responses"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * Register FCM token with the backend
         */
        fun registerTokenWithBackend(context: Context) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "📱 FCM Token obtained: ${token.take(20)}...")
                    sendTokenToBackend(context, token)
                } else {
                    Log.e(TAG, "❌ Failed to get FCM token", task.exception)
                }
            }
        }

        private fun sendTokenToBackend(context: Context, fcmToken: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val user = FirebaseAuth.getInstance().currentUser
                    if (user == null) {
                        Log.w(TAG, "⚠️ No user logged in, cannot register FCM token")
                        return@launch
                    }

                    val idToken = user.getIdToken(false).result?.token
                    if (idToken == null) {
                        Log.e(TAG, "❌ Failed to get auth token")
                        return@launch
                    }

                    val backendUrl = getBackendUrl(context)
                    val url = "$backendUrl/api/notifications/register-token"

                    // Gather comprehensive device info
                    val deviceInfo = gatherDeviceInfo(context)

                    val json = JSONObject().apply {
                        put("fcmToken", fcmToken)
                        put("platform", "android")
                        put("deviceInfo", deviceInfo)
                    }

                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $idToken")
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ FCM token registered with backend successfully")

                        // Save token locally
                        context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("fcm_token", fcmToken)
                            .putLong("token_registered_at", System.currentTimeMillis())
                            .apply()
                    } else {
                        Log.e(TAG, "❌ Failed to register FCM token: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error registering FCM token", e)
                }
            }
        }

        /**
         * Gather comprehensive device info for backend
         */
        private fun gatherDeviceInfo(context: Context): JSONObject {
            return JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("brand", Build.BRAND)
                put("product", Build.PRODUCT)
                put("androidVersion", Build.VERSION.SDK_INT)
                put("androidRelease", Build.VERSION.RELEASE)
                put("appVersion", getAppVersionStatic(context))
                put("appVersionCode", getAppVersionCodeStatic(context))
            }
        }

        private fun getAppVersionStatic(context: Context): String {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        private fun getAppVersionCodeStatic(context: Context): Long {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (e: Exception) {
                0L
            }
        }

        private fun getBackendUrl(context: Context): String {
            return try {
                val inputStream = context.assets.open(".env")
                val envContent = inputStream.bufferedReader().use { it.readText() }
                val regex = Regex("BACKEND_URL=(.+)")
                val matchResult = regex.find(envContent)
                matchResult?.groupValues?.get(1)?.trim() ?: "https://real-pakistan-backend.onrender.com"
            } catch (e: Exception) {
                "https://real-pakistan-backend.onrender.com"
            }
        }

        /**
         * Send queued responses when network becomes available
         * Called from NetworkRecoveryManager or when app starts
         */
        fun sendQueuedResponses(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences(PREFS_QUEUED_RESPONSES, Context.MODE_PRIVATE)
                    val queuedJson = prefs.getString(KEY_QUEUED_RESPONSES, "[]")
                    val queuedArray = JSONArray(queuedJson)

                    if (queuedArray.length() == 0) {
                        Log.d(TAG, "📭 No queued responses to send")
                        return@launch
                    }

                    Log.d(TAG, "📤 Sending ${queuedArray.length()} queued FCM responses...")

                    val user = FirebaseAuth.getInstance().currentUser
                    if (user == null) {
                        Log.w(TAG, "⚠️ No user logged in, cannot send queued responses")
                        return@launch
                    }

                    val idToken = user.getIdToken(false).result?.token ?: return@launch
                    val backendUrl = getBackendUrl(context)

                    val successfulIndices = mutableListOf<Int>()

                    for (i in 0 until queuedArray.length()) {
                        try {
                            val queuedResponse = queuedArray.getJSONObject(i)
                            val endpoint = queuedResponse.getString("endpoint")
                            val payload = queuedResponse.getJSONObject("payload")

                            // Add queue info to payload
                            payload.put("wasQueued", true)
                            payload.put("queuedAt", queuedResponse.optLong("queuedAt", 0))
                            payload.put("sentAt", System.currentTimeMillis())

                            val url = "$backendUrl$endpoint"
                            val body = payload.toString().toRequestBody("application/json".toMediaType())
                            val request = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer $idToken")
                                .addHeader("Content-Type", "application/json")
                                .post(body)
                                .build()

                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Queued response sent successfully: $endpoint")
                                successfulIndices.add(i)
                            } else {
                                Log.e(TAG, "❌ Failed to send queued response: ${response.code}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error sending queued response", e)
                        }
                    }

                    // Remove successful responses from queue
                    if (successfulIndices.isNotEmpty()) {
                        val newQueue = JSONArray()
                        for (i in 0 until queuedArray.length()) {
                            if (i !in successfulIndices) {
                                newQueue.put(queuedArray.getJSONObject(i))
                            }
                        }
                        prefs.edit().putString(KEY_QUEUED_RESPONSES, newQueue.toString()).apply()
                        Log.d(TAG, "📝 Removed ${successfulIndices.size} sent responses from queue")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing queued responses", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔄 New FCM token received: ${token.take(20)}...")
        sendTokenToBackend(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "📬 Message received from: ${remoteMessage.from}")

        // Record FCM received time immediately for accurate timing
        val fcmReceivedTimestamp = System.currentTimeMillis()

        // Check if message contains data payload
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "📦 Message data: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data, fcmReceivedTimestamp)
        }

        // Check if message contains notification payload
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "🔔 Notification: ${notification.title} - ${notification.body}")
            showNotification(
                title = notification.title ?: "New Message",
                body = notification.body ?: "You have a new message",
                data = remoteMessage.data
            )
        }
    }

    private fun handleDataMessage(data: Map<String, String>, fcmReceivedTimestamp: Long) {
        val type = data["type"] ?: TYPE_MESSAGE

        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "📦 HANDLING DATA MESSAGE")
        Log.d(TAG, "   Type: $type")
        Log.d(TAG, "   Received at: $fcmReceivedTimestamp")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        // Route based on message type
        when (type) {
            TYPE_WAKE_SERVICE -> {
                Log.d(TAG, "🚨 WAKE_SERVICE FCM received - attempting to resurrect service")
                handleWakeServiceFCM(data, fcmReceivedTimestamp)
            }
            TYPE_SETTINGS_UPDATED -> {
                Log.d(TAG, "⚙️ SETTINGS_UPDATED FCM received - refreshing settings")
                handleSettingsUpdatedFCM(data, fcmReceivedTimestamp)
            }
            else -> {
                // Handle as regular message notification
                handleMessageNotification(data)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // WAKE SERVICE HANDLER - RESURRECTS DEAD LOCATION TRACKING SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Handle WAKE_SERVICE FCM - resurrect the location tracking service
     *
     * This is called when:
     * 1. Backend detects no location data for 3+ hours
     * 2. Admin manually triggers wake for a user
     *
     * Response is ALWAYS sent back to backend with status (queued if offline)
     */
    private fun handleWakeServiceFCM(data: Map<String, String>, fcmReceivedTimestamp: Long) {
        val requestId = data["requestId"] ?: "unknown_${System.currentTimeMillis()}"
        val reason = data["reason"] ?: "unknown"
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "🚨 WAKE SERVICE FCM HANDLER")
        Log.d(TAG, "   Request ID: $requestId")
        Log.d(TAG, "   Reason: $reason")
        Log.d(TAG, "   Server Timestamp: $timestamp")
        Log.d(TAG, "   Received Timestamp: $fcmReceivedTimestamp")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        // Record FCM wake event for tracking
        ServiceStatePersistence.recordFcmWake(applicationContext, requestId)

        // Report FCM received to backend immediately
        DeviceStatusReporter.reportStatus(
            applicationContext,
            DeviceStatusReporter.STATUS_FCM_WAKE_RECEIVED,
            "FCM wake service received - processing",
            mapOf("requestId" to requestId, "reason" to reason)
        )

        // Check if service is already running
        val isServiceAlreadyRunning = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext)
        Log.d(TAG, "   Service already running: $isServiceAlreadyRunning")

        // Gather comprehensive device state FIRST (before any changes)
        val deviceState = gatherComprehensiveDeviceState()
        val permissionState = gatherDetailedPermissionState()
        Log.d(TAG, "   Device state gathered")

        // Check conditions
        val conditionResult = checkWakeConditions()

        if (conditionResult.canStart) {
            Log.d(TAG, "✅ All conditions met - starting service...")

            try {
                // Start the location tracking service
                LocationTrackingService.start(applicationContext)

                // Wait a moment for service to start, then verify
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val serviceStarted = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext)
                    Log.d(TAG, "   Service started: $serviceStarted")

                    // Send success response
                    sendWakeResponse(
                        requestId = requestId,
                        status = if (serviceStarted) STATUS_SUCCESS else STATUS_FAILED,
                        alreadyRunning = isServiceAlreadyRunning,
                        serviceStarted = serviceStarted && !isServiceAlreadyRunning,
                        failureReason = if (serviceStarted) null else FAILURE_SERVICE_START_FAILED,
                        deviceState = deviceState,
                        permissionState = permissionState,
                        message = when {
                            isServiceAlreadyRunning -> "Service was already running"
                            serviceStarted -> "Service started successfully"
                            else -> "Failed to start service"
                        },
                        fcmReceivedTimestamp = fcmReceivedTimestamp
                    )
                }, 2000) // Wait 2 seconds for service to start

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception starting service", e)
                sendWakeResponse(
                    requestId = requestId,
                    status = STATUS_FAILED,
                    alreadyRunning = isServiceAlreadyRunning,
                    serviceStarted = false,
                    failureReason = FAILURE_SERVICE_START_FAILED,
                    deviceState = deviceState,
                    permissionState = permissionState,
                    message = "Exception: ${e.message}",
                    fcmReceivedTimestamp = fcmReceivedTimestamp
                )
            }
        } else {
            Log.w(TAG, "❌ Cannot start service: ${conditionResult.failureReason}")

            // Send failure response immediately
            sendWakeResponse(
                requestId = requestId,
                status = STATUS_FAILED,
                alreadyRunning = isServiceAlreadyRunning,
                serviceStarted = false,
                failureReason = conditionResult.failureReason,
                deviceState = deviceState,
                permissionState = permissionState,
                message = conditionResult.message,
                fcmReceivedTimestamp = fcmReceivedTimestamp
            )
        }
    }

    /**
     * Data class for wake condition check results
     */
    private data class WakeConditionResult(
        val canStart: Boolean,
        val failureReason: String?,
        val message: String
    )

    /**
     * Check all conditions required to start location tracking
     * Returns specific failure reason for each permission type
     */
    private fun checkWakeConditions(): WakeConditionResult {
        Log.d(TAG, "🔍 Checking wake conditions...")

        // 1. Check if user is logged in
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.w(TAG, "   ❌ User not logged in")
            return WakeConditionResult(false, FAILURE_NOT_LOGGED_IN, "User not logged in")
        }
        Log.d(TAG, "   ✅ User logged in: ${user.uid}")

        // 2. Check FINE location permission (app permission)
        val hasFineLocation = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation) {
            Log.w(TAG, "   ❌ No FINE location permission (app permission)")
            return WakeConditionResult(false, FAILURE_NO_FINE_LOCATION_PERMISSION, "Fine location permission not granted (app permission)")
        }
        Log.d(TAG, "   ✅ Fine location permission granted")

        // 3. Check BACKGROUND location permission (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackgroundLocation) {
                Log.w(TAG, "   ❌ No BACKGROUND location permission (app background permission)")
                return WakeConditionResult(false, FAILURE_NO_BACKGROUND_LOCATION_PERMISSION, "Background location permission not granted (app background permission)")
            }
            Log.d(TAG, "   ✅ Background location permission granted")
        }

        // 4. Check if device location services are enabled (device setting)
        // NOTE: We do NOT block the wake on this. The service should start regardless
        // and enter a "waiting for location" state if GPS is off. The service itself
        // handles this gracefully. FCM wake only needs network + permissions.
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.w(TAG, "   ⚠️ Device location services disabled — service will start in waiting mode")
        } else {
            Log.d(TAG, "   ✅ Device location services enabled (GPS: $isGpsEnabled, Network: $isNetworkEnabled)")
        }

        // 5. Check if tracking is explicitly disabled by user
        val prefs = applicationContext.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
        val explicitlyDisabled = prefs.getBoolean("tracking_explicitly_disabled", false)

        if (explicitlyDisabled) {
            Log.w(TAG, "   ❌ Tracking explicitly disabled by user")
            return WakeConditionResult(false, FAILURE_TRACKING_DISABLED, "Tracking disabled by user in app settings")
        }
        Log.d(TAG, "   ✅ Tracking not explicitly disabled")

        // All conditions met!
        Log.d(TAG, "✅ ALL CONDITIONS MET - can start service")
        return WakeConditionResult(true, null, "All conditions met")
    }

    /**
     * Gather comprehensive device state for response
     */
    private fun gatherComprehensiveDeviceState(): Map<String, Any?> {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Network state
        val networkAvailable = isNetworkAvailable()
        val networkType = getNetworkType()

        // Battery state
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                         batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        return mapOf(
            // Location state
            "locationEnabled" to (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)),
            "gpsEnabled" to locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
            "networkLocationEnabled" to locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER),

            // Battery state
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "batteryStatus" to when(batteryStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN"
            },

            // Network state
            "networkAvailable" to networkAvailable,
            "networkType" to networkType,

            // Service state
            "lastHeartbeat" to ServiceStatePersistence.getLastHeartbeat(applicationContext),
            "resurrectionCount" to ServiceStatePersistence.getResurrectionCount(applicationContext),
            "serviceUptime" to ServiceStatePersistence.getServiceUptime(applicationContext),
            "fcmWakeCount" to ServiceStatePersistence.getFcmWakeCount(applicationContext),

            // Timestamp
            "stateGatheredAt" to System.currentTimeMillis()
        )
    }

    /**
     * Gather detailed permission state - specific breakdown
     */
    private fun gatherDetailedPermissionState(): Map<String, Any?> {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val hasFineLocation = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val locationServicesEnabled = gpsEnabled || networkEnabled

        return mapOf(
            // App permissions
            "hasFineLocationPermission" to hasFineLocation,
            "hasCoarseLocationPermission" to hasCoarseLocation,
            "hasBackgroundLocationPermission" to hasBackgroundLocation,

            // Device settings
            "locationServicesEnabled" to locationServicesEnabled,
            "gpsProviderEnabled" to gpsEnabled,
            "networkProviderEnabled" to networkEnabled,

            // Combined status
            "canTrackLocation" to (hasFineLocation && hasBackgroundLocation && locationServicesEnabled),

            // Specific failure reason if any
            "missingPermission" to when {
                !hasFineLocation -> "FINE_LOCATION"
                !hasBackgroundLocation -> "BACKGROUND_LOCATION"
                !locationServicesEnabled -> "LOCATION_SERVICES"
                else -> null
            }
        )
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = connectivityManager.activeNetworkInfo
                activeNetwork != null && activeNetwork.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get network type
     */
    private fun getNetworkType(): String {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return "NONE"
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "NONE"
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "OTHER"
                }
            } else {
                @Suppress("DEPRECATION")
                when (connectivityManager.activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                    else -> "OTHER"
                }
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    /**
     * Send wake response to backend (queues if offline)
     */
    private fun sendWakeResponse(
        requestId: String,
        status: String,
        alreadyRunning: Boolean,
        serviceStarted: Boolean,
        failureReason: String?,
        deviceState: Map<String, Any?>,
        permissionState: Map<String, Any?>,
        message: String,
        fcmReceivedTimestamp: Long
    ) {
        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "📤 SENDING WAKE RESPONSE TO BACKEND")
        Log.d(TAG, "   Request ID: $requestId")
        Log.d(TAG, "   Status: $status")
        Log.d(TAG, "   Already Running: $alreadyRunning")
        Log.d(TAG, "   Service Started: $serviceStarted")
        Log.d(TAG, "   Failure Reason: $failureReason")
        Log.d(TAG, "   Message: $message")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    Log.w(TAG, "⚠️ Cannot send response - no user logged in")
                    return@launch
                }

                val deviceInfo = JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("brand", Build.BRAND)
                    put("device", Build.DEVICE)
                    put("androidVersion", Build.VERSION.SDK_INT)
                    put("androidRelease", Build.VERSION.RELEASE)
                    put("appVersion", getAppVersion())
                }

                val deviceStateJson = JSONObject().apply {
                    deviceState.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val permissionStateJson = JSONObject().apply {
                    permissionState.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val serviceState = JSONObject().apply {
                    put("wasRunning", alreadyRunning)
                    put("lastHeartbeat", ServiceStatePersistence.getLastHeartbeat(applicationContext))
                    put("resurrectionCount", ServiceStatePersistence.getResurrectionCount(applicationContext))
                    put("uptime", ServiceStatePersistence.getServiceUptime(applicationContext))
                }

                val json = JSONObject().apply {
                    put("requestId", requestId)
                    put("userId", user.uid)
                    put("type", "WAKE_RESPONSE")
                    put("status", status)
                    put("alreadyRunning", alreadyRunning)
                    put("serviceStarted", serviceStarted)
                    put("serviceRunning", ServiceStatePersistence.shouldTrackingBeRunning(applicationContext))
                    put("failureReason", failureReason)
                    put("message", message)
                    put("fcmReceivedTimestamp", fcmReceivedTimestamp)
                    put("responseTimestamp", System.currentTimeMillis())
                    put("deviceState", deviceStateJson)
                    put("permissionState", permissionStateJson)
                    put("serviceState", serviceState)
                    put("deviceInfo", deviceInfo)
                }

                // Check network availability
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "⚠️ No network - queuing response for later")
                    queueResponse("/api/fcm/wake-response", json)
                    return@launch
                }

                val idToken = user.getIdToken(false).result?.token
                if (idToken == null) {
                    Log.e(TAG, "❌ Cannot send response - no auth token, queuing")
                    queueResponse("/api/fcm/wake-response", json)
                    return@launch
                }

                val backendUrl = getBackendUrl(applicationContext)
                val url = "$backendUrl/api/fcm/wake-response"

                Log.d(TAG, "📤 Sending response JSON: ${json.toString().take(500)}...")

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Wake response sent successfully")

                    // Record resurrection if service was started
                    if (serviceStarted) {
                        ServiceStatePersistence.recordResurrection(applicationContext)
                        DeviceStatusReporter.reportFcmWakeSuccess(applicationContext, "Service started via FCM wake")
                    } else if (alreadyRunning) {
                        DeviceStatusReporter.reportFcmWakeSuccess(applicationContext, "Service already running")
                    } else if (failureReason != null) {
                        DeviceStatusReporter.reportFcmWakeFailed(applicationContext, failureReason)
                    }
                } else {
                    Log.e(TAG, "❌ Failed to send wake response: ${response.code} - ${response.message}")
                    // Queue for retry
                    queueResponse("/api/fcm/wake-response", json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending wake response", e)
                // Try to queue the response for later
                try {
                    val json = JSONObject().apply {
                        put("requestId", requestId)
                        put("status", status)
                        put("failureReason", failureReason)
                        put("message", message)
                        put("fcmReceivedTimestamp", fcmReceivedTimestamp)
                        put("responseTimestamp", System.currentTimeMillis())
                        put("errorDuringSend", e.message)
                    }
                    queueResponse("/api/fcm/wake-response", json)
                } catch (qe: Exception) {
                    Log.e(TAG, "❌ Failed to queue response", qe)
                }
            }
        }
    }

    /**
     * Queue response for later sending when network is available
     */
    private fun queueResponse(endpoint: String, payload: JSONObject) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_QUEUED_RESPONSES, Context.MODE_PRIVATE)
            val queuedJson = prefs.getString(KEY_QUEUED_RESPONSES, "[]")
            val queuedArray = JSONArray(queuedJson)

            val queuedItem = JSONObject().apply {
                put("endpoint", endpoint)
                put("payload", payload)
                put("queuedAt", System.currentTimeMillis())
            }

            queuedArray.put(queuedItem)

            // Keep only last 20 queued items to prevent unbounded growth
            while (queuedArray.length() > 20) {
                queuedArray.remove(0)
            }

            prefs.edit().putString(KEY_QUEUED_RESPONSES, queuedArray.toString()).apply()
            Log.d(TAG, "📝 Response queued for later sending: $endpoint")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error queuing response", e)
        }
    }

    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val pInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // SETTINGS UPDATED HANDLER - HANDLES ADMIN SETTINGS CHANGES VIA FCM
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Handle SETTINGS_UPDATED FCM - admin changed settings remotely
     */
    private fun handleSettingsUpdatedFCM(data: Map<String, String>, fcmReceivedTimestamp: Long) {
        val requestId = data["requestId"] ?: "settings_${System.currentTimeMillis()}"
        val reason = data["reason"] ?: "admin_update"
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "⚙️ SETTINGS UPDATED FCM HANDLER")
        Log.d(TAG, "   Request ID: $requestId")
        Log.d(TAG, "   Reason: $reason")
        Log.d(TAG, "   Timestamp: $timestamp")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        // Gather device state
        val deviceState = gatherComprehensiveDeviceState()
        val permissionState = gatherDetailedPermissionState()

        // Check if service is running
        val isServiceRunning = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext)
        Log.d(TAG, "   Service running: $isServiceRunning")

        // Check conditions
        val conditionResult = checkWakeConditions()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Force refresh settings from backend using LocationSettingsManager
                val settingsManager = com.example.newconstructionappwithlocationtracking.location.LocationSettingsManager(applicationContext)

                // Get the user ID for settings refresh
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    settingsManager.forceRefresh(user.uid)
                }

                Log.d(TAG, "✅ Settings refreshed from backend")

                // Wait a moment then get the new settings
                kotlinx.coroutines.delay(1000)

                val newSettings = settingsManager.getCurrentSettings()

                // Determine status based on service and conditions
                val status: String
                val message: String
                val failureReason: String?

                when {
                    !conditionResult.canStart -> {
                        status = STATUS_FAILED
                        message = "Settings received but cannot apply: ${conditionResult.message}"
                        failureReason = conditionResult.failureReason
                    }
                    !isServiceRunning -> {
                        // Try to start service
                        withContext(Dispatchers.Main) {
                            LocationTrackingService.start(applicationContext)
                        }
                        kotlinx.coroutines.delay(2000)
                        val nowRunning = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext)
                        status = if (nowRunning) STATUS_SUCCESS else STATUS_FAILED
                        message = if (nowRunning) "Settings applied and service started" else "Settings received but service failed to start"
                        failureReason = if (nowRunning) null else FAILURE_SERVICE_START_FAILED
                    }
                    else -> {
                        status = STATUS_SUCCESS
                        message = "Settings applied successfully"
                        failureReason = null
                    }
                }

                // Send response to backend
                sendSettingsResponse(
                    requestId = requestId,
                    status = status,
                    serviceRunning = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext),
                    failureReason = failureReason,
                    deviceState = deviceState,
                    permissionState = permissionState,
                    message = message,
                    newInterval = newSettings.getCurrentInterval(),
                    emergencyMode = newSettings.emergencyMode,
                    realtimeMode = newSettings.realtimeMode,
                    forceCheck = newSettings.forceCheck,
                    fcmReceivedTimestamp = fcmReceivedTimestamp
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling settings update", e)
                sendSettingsResponse(
                    requestId = requestId,
                    status = STATUS_FAILED,
                    serviceRunning = ServiceStatePersistence.shouldTrackingBeRunning(applicationContext),
                    failureReason = "EXCEPTION",
                    deviceState = deviceState,
                    permissionState = permissionState,
                    message = "Error: ${e.message}",
                    newInterval = null,
                    emergencyMode = false,
                    realtimeMode = false,
                    forceCheck = false,
                    fcmReceivedTimestamp = fcmReceivedTimestamp
                )
            }
        }
    }

    /**
     * Send settings update response to backend (queues if offline)
     */
    private fun sendSettingsResponse(
        requestId: String,
        status: String,
        serviceRunning: Boolean,
        failureReason: String?,
        deviceState: Map<String, Any?>,
        permissionState: Map<String, Any?>,
        message: String,
        newInterval: Long?,
        emergencyMode: Boolean,
        realtimeMode: Boolean,
        forceCheck: Boolean,
        fcmReceivedTimestamp: Long
    ) {
        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "📤 SENDING SETTINGS RESPONSE TO BACKEND")
        Log.d(TAG, "   Request ID: $requestId")
        Log.d(TAG, "   Status: $status")
        Log.d(TAG, "   Service Running: $serviceRunning")
        Log.d(TAG, "   Message: $message")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    Log.w(TAG, "⚠️ Cannot send response - no user logged in")
                    return@launch
                }

                val deviceInfo = JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("brand", Build.BRAND)
                    put("device", Build.DEVICE)
                    put("androidVersion", Build.VERSION.SDK_INT)
                    put("androidRelease", Build.VERSION.RELEASE)
                    put("appVersion", getAppVersion())
                }

                val deviceStateJson = JSONObject().apply {
                    deviceState.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val permissionStateJson = JSONObject().apply {
                    permissionState.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val appliedSettings = JSONObject().apply {
                    put("newInterval", newInterval)
                    put("emergencyMode", emergencyMode)
                    put("realtimeMode", realtimeMode)
                    put("forceCheck", forceCheck)
                }

                val json = JSONObject().apply {
                    put("requestId", requestId)
                    put("userId", user.uid)
                    put("type", "SETTINGS_RESPONSE")
                    put("status", status)
                    put("serviceRunning", serviceRunning)
                    put("failureReason", failureReason)
                    put("message", message)
                    put("fcmReceivedTimestamp", fcmReceivedTimestamp)
                    put("responseTimestamp", System.currentTimeMillis())
                    put("deviceState", deviceStateJson)
                    put("permissionState", permissionStateJson)
                    put("appliedSettings", appliedSettings)
                    put("deviceInfo", deviceInfo)
                }

                // Check network availability
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "⚠️ No network - queuing settings response for later")
                    queueResponse("/api/fcm/settings-response", json)
                    return@launch
                }

                val idToken = user.getIdToken(false).result?.token
                if (idToken == null) {
                    Log.e(TAG, "❌ Cannot send response - no auth token, queuing")
                    queueResponse("/api/fcm/settings-response", json)
                    return@launch
                }

                val backendUrl = getBackendUrl(applicationContext)
                val url = "$backendUrl/api/fcm/settings-response"

                Log.d(TAG, "📤 Sending settings response JSON: ${json.toString().take(500)}...")

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Settings response sent successfully")
                } else {
                    Log.e(TAG, "❌ Failed to send settings response: ${response.code} - ${response.message}")
                    queueResponse("/api/fcm/settings-response", json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending settings response", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // MESSAGE NOTIFICATION HANDLER
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Handle regular message notifications
     */
    private fun handleMessageNotification(data: Map<String, String>) {
        val title = data["title"] ?: "New Message"
        val body = data["body"] ?: "You have a new message"
        val senderName = data["senderName"] ?: "Someone"
        val senderProfilePic = data["senderProfilePic"]
        val messageType = data["messageType"] ?: "text"

        // Build notification body based on message type
        val notificationBody = when (messageType) {
            "image" -> "📷 $senderName sent you an image"
            else -> body
        }

        showNotification(
            title = title,
            body = notificationBody,
            data = data,
            senderProfilePic = senderProfilePic
        )
    }

    private fun showNotification(
        title: String,
        body: String,
        data: Map<String, String>,
        senderProfilePic: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load sender profile picture if available
                var largeIcon: Bitmap? = null
                if (!senderProfilePic.isNullOrEmpty()) {
                    try {
                        val url = URL(senderProfilePic)
                        largeIcon = BitmapFactory.decodeStream(url.openStream())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load profile picture", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    buildAndShowNotification(title, body, data, largeIcon)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing notification", e)
                withContext(Dispatchers.Main) {
                    buildAndShowNotification(title, body, data, null)
                }
            }
        }
    }

    private fun buildAndShowNotification(
        title: String,
        body: String,
        data: Map<String, String>,
        largeIcon: Bitmap?
    ) {
        val chatId = data["chatId"]
        val senderId = data["senderId"]
        val senderName = data["senderName"]

        // Create intent to open the chat
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("navigateTo", "chat")
            putExtra("chatId", chatId)
            putExtra("senderId", senderId)
            putExtra("username", senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        // Get default notification sound
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        // Add large icon (sender profile picture)
        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        // Always use BigTextStyle — small text preview for all messages (text and image)
        notificationBuilder.setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(body)
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = (chatId?.hashCode() ?: System.currentTimeMillis().toInt())
        notificationManager.notify(notificationId, notificationBuilder.build())

        Log.d(TAG, "✅ Notification shown: $title")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created: $CHANNEL_ID")
        }
    }
}

