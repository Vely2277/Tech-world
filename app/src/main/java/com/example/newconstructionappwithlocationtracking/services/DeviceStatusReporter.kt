package com.example.newconstructionappwithlocationtracking.services

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.newconstructionappwithlocationtracking.location.ServiceStatePersistence
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * DEVICE STATUS REPORTER - REAL-TIME STATUS UPLOADS TO FIRESTORE + BACKEND
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * - Upload device status DIRECTLY TO FIRESTORE for instant admin page updates
 * - Also send to backend for logging and FCM wake cycle management
 * - Real-time device status visible in Admin FCM page via Firestore listeners
 * - Track service state changes and report immediately
 * - Queue status updates when offline, send when network returns
 *
 * FIRESTORE COLLECTIONS:
 * - deviceStatus/{userId} - Real-time device state (Admin page listens to this)
 * - deviceNotifications - Event log for notifications section
 * - locationTrackingSettings/{userId} - Settings + device state backup
 *
 * FIREBASE REALTIME DATABASE:
 * - presence/{userId} - Online/offline presence (onDisconnect-based, server-side)
 *   Backend monitors this to trigger/cancel recovery automatically.
 *
 * TRIGGERS STATUS UPLOAD:
 * - Service started/stopped
 * - Location tracking enabled/disabled
 * - Permission changed
 * - FCM received and processed
 * - Settings changed
 * - Network state changes
 * - Battery changes (low/critical)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */
object DeviceStatusReporter {
    private const val TAG = "DeviceStatusReporter"
    private const val PREFS_NAME = "device_status_reporter"
    private const val KEY_QUEUED_STATUSES = "queued_statuses"
    private const val KEY_LAST_STATUS_UPLOAD = "last_status_upload"
    private const val KEY_LAST_BATTERY_LEVEL = "last_battery_level"
    private const val KEY_LAST_NETWORK_STATE = "last_network_state"

    // Firestore collections
    private const val COLLECTION_DEVICE_STATUS = "deviceStatus"
    private const val COLLECTION_DEVICE_NOTIFICATIONS = "deviceNotifications"
    private const val COLLECTION_LOCATION_SETTINGS = "locationTrackingSettings"

    // Status types for notifications
    const val STATUS_SERVICE_STARTED = "SERVICE_STARTED"
    const val STATUS_SERVICE_STOPPED = "SERVICE_STOPPED"
    const val STATUS_TRACKING_RESUMED = "TRACKING_RESUMED"
    const val STATUS_TRACKING_PAUSED = "TRACKING_PAUSED"
    const val STATUS_FCM_WAKE_RECEIVED = "FCM_WAKE_RECEIVED"
    const val STATUS_FCM_WAKE_SUCCESS = "FCM_WAKE_SUCCESS"
    const val STATUS_FCM_WAKE_FAILED = "FCM_WAKE_FAILED"
    const val STATUS_SETTINGS_CHANGED = "SETTINGS_CHANGED"
    const val STATUS_PERMISSION_CHANGED = "PERMISSION_CHANGED"
    const val STATUS_PERMISSION_GRANTED = "PERMISSION_GRANTED"
    const val STATUS_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val STATUS_LOCATION_DISABLED = "LOCATION_DISABLED"
    const val STATUS_LOCATION_ENABLED = "LOCATION_ENABLED"
    const val STATUS_NETWORK_LOST = "NETWORK_LOST"
    const val STATUS_NETWORK_RESTORED = "NETWORK_RESTORED"
    const val STATUS_HEARTBEAT = "HEARTBEAT"
    const val STATUS_APP_OPENED = "APP_OPENED"
    const val STATUS_APP_CLOSED = "APP_CLOSED"
    const val STATUS_BATTERY_LOW = "BATTERY_LOW"
    const val STATUS_BATTERY_CRITICAL = "BATTERY_CRITICAL"
    const val STATUS_DEVICE_BOOT = "DEVICE_BOOT"
    const val STATUS_UPLOAD_SUCCESS = "UPLOAD_SUCCESS"
    const val STATUS_UPLOAD_FAILED = "UPLOAD_FAILED"
    const val STATUS_ERROR = "ERROR"
    const val STATUS_WARNING = "WARNING"

    // Tracking states for display
    object TrackingState {
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        const val ERROR = "error"
        const val WAITING_PERMISSION = "waiting_permission"
        const val WAITING_LOCATION = "waiting_location"
        const val OFFLINE = "offline"
    }

    // Severity levels for notifications
    object Severity {
        const val INFO = "info"
        const val SUCCESS = "success"
        const val WARNING = "warning"
        const val ERROR = "error"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val realtimeDb: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var scope: CoroutineScope? = null
    private var connectedListener: ValueEventListener? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    // Presence state tracking
    private var presenceSetSuccessfully = false
    private var lastPresenceAttempt = 0L
    private const val PRESENCE_RETRY_INTERVAL_MS = 30000L // Retry every 30s if not set
    private const val PRESENCE_ENSURE_INTERVAL_MS = 60000L // Ensure presence every 60s

    // Rate limiting
    private var lastFirestoreUpdate = 0L
    private var lastNotificationTime = 0L
    private const val FIRESTORE_UPDATE_THROTTLE_MS = 2000L // 2 seconds minimum
    private const val NOTIFICATION_THROTTLE_MS = 1000L // 1 second minimum
    private val isUpdating = AtomicBoolean(false)

    /**
     * Initialize the reporter
     */
    fun initialize(context: Context) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Send any queued statuses
        sendQueuedStatuses(context)

        // Start Firebase Realtime Database presence system
        startPresence()

        // Also listen for auth state changes — if the user wasn't authenticated
        // when startPresence() ran, this will re-trigger it once auth is ready
        authListener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null && !presenceSetSuccessfully) {
                Log.d(TAG, "🔑 Auth state changed — user authenticated, re-triggering presence for ${user.uid}")
                startPresence()
            }
        }
        FirebaseAuth.getInstance().addAuthStateListener(authListener!!)

        // Initial status report
        scope?.launch {
            delay(2000) // Wait for everything to initialize
            reportStatus(context, STATUS_APP_OPENED, "App initialized")
        }

        Log.d(TAG, "✅ DeviceStatusReporter initialized with Firestore + Realtime DB presence")
    }

    /**
     * Firebase Realtime Database presence system.
     *
     * Uses .info/connected to detect when the Firebase socket is alive.
     * Registers onDisconnect() so Firebase's server atomically writes
     * online=false + lastSeen=serverTimestamp the instant the socket drops.
     * This works even if the app is force-killed, phone dies, or network drops.
     */
    private fun startPresence() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.w(TAG, "⚠️ No user logged in — skipping presence setup")
            return
        }

        val uid = user.uid
        val presenceRef = realtimeDb.getReference("presence").child(uid)
        val connectedRef = realtimeDb.getReference(".info/connected")

        // Remove any old listener before attaching a new one
        connectedListener?.let { connectedRef.removeEventListener(it) }

        connectedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    Log.d(TAG, "🟢 Firebase Realtime DB connected — setting presence ONLINE for $uid")

                    // When we disconnect, Firebase server will write these values atomically
                    val offlineData = mapOf<String, Any>(
                        "online" to false,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                    presenceRef.onDisconnect().setValue(offlineData)

                    // Set online now
                    setPresenceOnline(presenceRef, uid)
                } else {
                    Log.w(TAG, "🔴 Firebase Realtime DB disconnected for $uid")
                    presenceSetSuccessfully = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Presence listener cancelled: ${error.message}")
                presenceSetSuccessfully = false
            }
        }

        connectedRef.addValueEventListener(connectedListener!!)
        Log.d(TAG, "👁️ Presence listener attached for $uid")
    }

    /**
     * Actually write online: true to presence. Retries on failure.
     */
    private fun setPresenceOnline(presenceRef: com.google.firebase.database.DatabaseReference, uid: String) {
        lastPresenceAttempt = System.currentTimeMillis()

        val onlineData = mapOf<String, Any>(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP
        )
        presenceRef.setValue(onlineData)
            .addOnSuccessListener {
                presenceSetSuccessfully = true
                Log.d(TAG, "✅ Presence set to ONLINE for $uid")
            }
            .addOnFailureListener { e ->
                presenceSetSuccessfully = false
                Log.e(TAG, "❌ Failed to set presence: ${e.message} — will retry")

                // Retry after a delay (auth token might not be propagated yet)
                scope?.launch {
                    delay(5000)
                    if (!presenceSetSuccessfully) {
                        Log.d(TAG, "🔄 Retrying presence set for $uid...")
                        val user = FirebaseAuth.getInstance().currentUser
                        if (user != null && user.uid == uid) {
                            val retryRef = realtimeDb.getReference("presence").child(uid)
                            retryRef.setValue(onlineData)
                                .addOnSuccessListener {
                                    presenceSetSuccessfully = true
                                    Log.d(TAG, "✅ Presence retry succeeded for $uid")
                                }
                                .addOnFailureListener { e2 ->
                                    Log.e(TAG, "❌ Presence retry also failed: ${e2.message}")
                                }
                        }
                    }
                }
            }
    }

    /**
     * Ensure presence is set to online. Called periodically from reportStatus()
     * as a safety net — if the app is running and can write to Firestore,
     * it should also be online in Realtime DB.
     */
    private fun ensurePresence() {
        if (presenceSetSuccessfully) return // Already set, no need

        val now = System.currentTimeMillis()
        if (now - lastPresenceAttempt < PRESENCE_RETRY_INTERVAL_MS) return // Too soon to retry

        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid

        Log.d(TAG, "🔧 ensurePresence: presence not confirmed, attempting to set for $uid")

        val presenceRef = realtimeDb.getReference("presence").child(uid)

        // Re-register onDisconnect in case it was lost
        val offlineData = mapOf<String, Any>(
            "online" to false,
            "lastSeen" to ServerValue.TIMESTAMP
        )
        presenceRef.onDisconnect().setValue(offlineData)

        setPresenceOnline(presenceRef, uid)
    }

    /**
     * Stop the presence listener and mark offline explicitly
     */
    private fun stopPresence() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val presenceRef = realtimeDb.getReference("presence").child(user.uid)
            val offlineData = mapOf<String, Any>(
                "online" to false,
                "lastSeen" to ServerValue.TIMESTAMP
            )
            presenceRef.setValue(offlineData)
            Log.d(TAG, "🔴 Presence explicitly set to OFFLINE for ${user.uid}")
        }

        connectedListener?.let {
            realtimeDb.getReference(".info/connected").removeEventListener(it)
        }
        connectedListener = null
        presenceSetSuccessfully = false
    }

    /**
     * Stop the reporter
     */
    fun shutdown() {
        // Remove auth listener
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        authListener = null

        stopPresence()
        scope?.cancel()
        scope = null
        Log.d(TAG, "🛑 DeviceStatusReporter shutdown")
    }

    /**
     * Report a status change - MAIN ENTRY POINT
     * This updates both Firestore (for real-time admin page) and backend (for logging)
     */
    fun reportStatus(
        context: Context,
        statusType: String,
        message: String,
        extraData: Map<String, Any?>? = null,
        failureReason: String? = null,
        addNotification: Boolean = true
    ) {
        scope?.launch {
            reportStatusInternal(context, statusType, message, extraData, failureReason, addNotification)
        } ?: run {
            CoroutineScope(Dispatchers.IO).launch {
                reportStatusInternal(context, statusType, message, extraData, failureReason, addNotification)
            }
        }
    }

    /**
     * Internal status reporting - writes to BOTH Firestore and Backend
     */
    private suspend fun reportStatusInternal(
        context: Context,
        statusType: String,
        message: String,
        extraData: Map<String, Any?>?,
        failureReason: String?,
        addNotification: Boolean
    ) {
        try {
            Log.d(TAG, "═══════════════════════════════════════════════════════")
            Log.d(TAG, "📊 REPORTING STATUS: $statusType")
            Log.d(TAG, "   Message: $message")
            Log.d(TAG, "═══════════════════════════════════════════════════════")

            // Safety net: ensure Realtime DB presence is online if it hasn't been confirmed
            ensurePresence()

            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Log.w(TAG, "⚠️ No user logged in - cannot report status")
                return
            }

            // Gather comprehensive device state
            val deviceState = gatherDeviceState(context)
            val permissionState = gatherPermissionState(context)
            val serviceState = gatherServiceState(context)
            val deviceInfo = gatherDeviceInfo(context)

            // Determine overall tracking state
            val trackingState = determineTrackingState(permissionState, deviceState, serviceState)
            val trackingStateMessage = getTrackingStateMessage(trackingState, permissionState, deviceState)

            // ═══════════════════════════════════════════════════════════════
            // 1. UPDATE FIRESTORE DIRECTLY (for real-time Admin page updates)
            // ═══════════════════════════════════════════════════════════════
            updateFirestoreStatus(
                context,
                user.uid,
                statusType,
                message,
                deviceState,
                permissionState,
                serviceState,
                deviceInfo,
                trackingState,
                trackingStateMessage,
                extraData,
                failureReason
            )

            // Add notification if requested
            if (addNotification && shouldAddNotification(statusType)) {
                addFirestoreNotification(
                    context,
                    user.uid,
                    statusType,
                    getNotificationTitle(statusType),
                    message,
                    getSeverity(statusType),
                    extraData
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // 2. SEND TO BACKEND (for logging and FCM cycle management)
            // ═══════════════════════════════════════════════════════════════
            val json = JSONObject().apply {
                put("userId", user.uid)
                put("statusType", statusType)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                put("trackingState", trackingState)
                put("trackingStateMessage", trackingStateMessage)
                put("deviceState", JSONObject(deviceState))
                put("permissionState", JSONObject(permissionState))
                put("serviceState", JSONObject(serviceState))
                put("deviceInfo", JSONObject(deviceInfo))
                if (failureReason != null) {
                    put("failureReason", failureReason)
                }
                if (extraData != null) {
                    put("extraData", JSONObject(extraData.filterValues { it != null }))
                }
            }

            // Check network for backend
            if (!isNetworkAvailable(context)) {
                Log.w(TAG, "⚠️ No network - queuing backend status for later")
                queueStatus(context, json)
                return
            }

            // Get auth token
            val idToken = try {
                withContext(Dispatchers.Main) {
                    user.getIdToken(false).result?.token
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get auth token", e)
                queueStatus(context, json)
                return
            }

            if (idToken == null) {
                Log.e(TAG, "❌ No auth token - queuing status")
                queueStatus(context, json)
                return
            }

            // Send to backend
            sendToBackend(context, json, idToken)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reporting status", e)
        }
    }

    /**
     * Update Firestore deviceStatus document for real-time Admin page updates
     */
    private fun updateFirestoreStatus(
        context: Context,
        userId: String,
        statusType: String,
        message: String,
        deviceState: Map<String, Any?>,
        permissionState: Map<String, Any?>,
        serviceState: Map<String, Any?>,
        deviceInfo: Map<String, Any?>,
        trackingState: String,
        trackingStateMessage: String,
        extraData: Map<String, Any?>?,
        failureReason: String?
    ) {
        // Rate limiting for Firestore updates
        val now = System.currentTimeMillis()
        if (now - lastFirestoreUpdate < FIRESTORE_UPDATE_THROTTLE_MS && statusType == STATUS_HEARTBEAT) {
            Log.d(TAG, "⏳ Firestore update throttled")
            return
        }
        lastFirestoreUpdate = now

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        val statusData = hashMapOf<String, Any>(
            // Core identification
            "userId" to userId,
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedAtClient" to now,
            "updatedAtISO" to dateFormat.format(Date(now)),
            "lastStatusType" to statusType,
            "lastStatusMessage" to message,

            // Tracking state (main info for Admin page)
            "trackingState" to trackingState,
            "trackingStateMessage" to trackingStateMessage,
            "isActive" to (trackingState == TrackingState.ACTIVE),
            "isOnline" to true,

            // Service state
            "serviceRunning" to (serviceState["isTracking"] == true),
            "lastHeartbeat" to (serviceState["lastHeartbeat"] ?: 0L),
            "resurrectionCount" to (serviceState["resurrectionCount"] ?: 0),
            "serviceUptime" to (serviceState["serviceUptime"] ?: 0L),

            // Permissions (detailed)
            "hasFineLocation" to (permissionState["hasFineLocationPermission"] == true),
            "hasBackgroundLocation" to (permissionState["hasBackgroundLocationPermission"] == true),
            "locationServicesEnabled" to (permissionState["locationServicesEnabled"] == true),
            "canTrackLocation" to (permissionState["canTrackLocation"] == true),
            "missingPermission" to (permissionState["missingPermission"] ?: "none"),

            // Device state
            "batteryLevel" to (deviceState["batteryLevel"] ?: -1),
            "batteryCharging" to (deviceState["isCharging"] == true),
            "batteryStatus" to getBatteryStatusString(deviceState["batteryLevel"] as? Int ?: -1),
            "networkConnected" to (deviceState["networkAvailable"] == true),
            "networkType" to (deviceState["networkType"] ?: "unknown"),

            // Device info
            "deviceModel" to (deviceInfo["model"] ?: "Unknown"),
            "deviceManufacturer" to (deviceInfo["manufacturer"] ?: "Unknown"),
            "deviceName" to (deviceInfo["deviceName"] ?: "Unknown Device"),
            "androidVersion" to (deviceInfo["androidRelease"] ?: "Unknown"),
            "sdkVersion" to (deviceInfo["androidVersion"] ?: 0),
            "appVersion" to (deviceInfo["appVersion"] ?: "Unknown"),

            // Statistics
            "uploadSuccessRate" to getUploadSuccessRate(context),
            "fcmResponseRate" to getFcmResponseRate(context),
            "locationCount24h" to getLocationCount24h(context),
            "lastLocationTime" to getLastLocationTime(context)
        )

        // Add failure reason if present
        if (failureReason != null) {
            statusData["lastFailureReason"] = failureReason
        }

        // Add extra data if present
        extraData?.forEach { (key, value) ->
            if (value != null) {
                statusData["extra_$key"] = value
            }
        }

        // Write to deviceStatus collection
        firestore.collection(COLLECTION_DEVICE_STATUS)
            .document(userId)
            .set(statusData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "✅ Firestore deviceStatus updated: $statusType")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to update Firestore deviceStatus: ${e.message}")
            }

        // ═══════════════════════════════════════════════════════════════
        // ALSO UPDATE locationTrackingSettings (PRIMARY SOURCE - THIS WORKS!)
        // This is the main collection the admin page uses successfully
        // ═══════════════════════════════════════════════════════════════
        val settingsUpdate = hashMapOf<String, Any>(
            // Core status (SAME fields as deviceStatus for consistency)
            "lastDeviceState" to trackingState,
            "lastPermissionState" to (permissionState["canTrackLocation"] == true),
            "lastServiceState" to (serviceState["isTracking"] == true),
            "lastResponseTime" to FieldValue.serverTimestamp(),
            "deviceOnline" to true,

            // Full tracking state for admin page
            "trackingState" to trackingState,
            "trackingStateMessage" to trackingStateMessage,
            "isActive" to (trackingState == TrackingState.ACTIVE),
            "isOnline" to true,
            "lastStatusType" to statusType,
            "lastStatusMessage" to message,

            // Service state
            "serviceRunning" to (serviceState["isTracking"] == true),
            "lastHeartbeat" to (serviceState["lastHeartbeat"] ?: 0L),
            "resurrectionCount" to (serviceState["resurrectionCount"] ?: 0),

            // Permissions (CRITICAL for showing correct status)
            "hasFineLocation" to (permissionState["hasFineLocationPermission"] == true),
            "hasBackgroundLocation" to (permissionState["hasBackgroundLocationPermission"] == true),
            "locationServicesEnabled" to (permissionState["locationServicesEnabled"] == true),
            "canTrackLocation" to (permissionState["canTrackLocation"] == true),
            "missingPermission" to (permissionState["missingPermission"] ?: "none"),

            // Device state
            "batteryLevel" to (deviceState["batteryLevel"] ?: -1),
            "batteryCharging" to (deviceState["isCharging"] == true),
            "networkConnected" to (deviceState["networkAvailable"] == true),
            "networkType" to (deviceState["networkType"] ?: "unknown"),

            // Device info
            "lastDeviceInfo" to deviceInfo
        )

        // Add failure reason if present
        if (failureReason != null) {
            settingsUpdate["lastFailureReason"] = failureReason
        }

        firestore.collection(COLLECTION_LOCATION_SETTINGS)
            .document(userId)
            .set(settingsUpdate, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "═══════════════════════════════════════════════════════")
                Log.d(TAG, "✅✅✅ FIRESTORE UPDATE SUCCESS ✅✅✅")
                Log.d(TAG, "   Collection: locationTrackingSettings")
                Log.d(TAG, "   UserId: $userId")
                Log.d(TAG, "   StatusType: $statusType")
                Log.d(TAG, "   TrackingState: $trackingState")
                Log.d(TAG, "   IsActive: ${trackingState == TrackingState.ACTIVE}")
                Log.d(TAG, "   LocationEnabled: ${permissionState["locationServicesEnabled"]}")
                Log.d(TAG, "═══════════════════════════════════════════════════════")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "═══════════════════════════════════════════════════════")
                Log.e(TAG, "❌❌❌ FIRESTORE UPDATE FAILED ❌❌❌")
                Log.e(TAG, "   Error: ${e.message}")
                Log.e(TAG, "═══════════════════════════════════════════════════════")
            }
    }

    /**
     * Add a notification to Firestore for Admin page notification section
     */
    private fun addFirestoreNotification(
        context: Context,
        userId: String,
        type: String,
        title: String,
        message: String,
        severity: String,
        metadata: Map<String, Any?>?
    ) {
        // Rate limiting
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < NOTIFICATION_THROTTLE_MS) {
            Log.d(TAG, "⏳ Notification throttled")
            return
        }
        lastNotificationTime = now

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        val notificationData = hashMapOf<String, Any>(
            "userId" to userId,
            "type" to type,
            "title" to title,
            "message" to message,
            "severity" to severity,
            "createdAt" to FieldValue.serverTimestamp(),
            "createdAtClient" to now,
            "createdAtISO" to dateFormat.format(Date(now)),
            "read" to false,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE
        )

        // Add metadata if provided
        metadata?.forEach { (key, value) ->
            if (value != null) {
                notificationData["meta_$key"] = value
            }
        }

        firestore.collection(COLLECTION_DEVICE_NOTIFICATIONS)
            .add(notificationData)
            .addOnSuccessListener { docRef ->
                Log.d(TAG, "📬 Notification added to Firestore: $title (${docRef.id})")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to add notification: ${e.message}")
            }
    }

    /**
     * Determine overall tracking state for display
     */
    private fun determineTrackingState(
        permissionState: Map<String, Any?>,
        deviceState: Map<String, Any?>,
        serviceState: Map<String, Any?>
    ): String {
        val hasFineLocation = permissionState["hasFineLocationPermission"] == true
        val hasBackgroundLocation = permissionState["hasBackgroundLocationPermission"] == true
        val locationEnabled = permissionState["locationServicesEnabled"] == true
        val isTracking = serviceState["isTracking"] == true
        val networkAvailable = deviceState["networkAvailable"] == true

        return when {
            !hasFineLocation || !hasBackgroundLocation -> TrackingState.WAITING_PERMISSION
            !locationEnabled -> TrackingState.WAITING_LOCATION
            !isTracking -> TrackingState.STOPPED
            !networkAvailable -> TrackingState.OFFLINE
            else -> TrackingState.ACTIVE
        }
    }

    /**
     * Get human-readable tracking state message
     */
    private fun getTrackingStateMessage(
        state: String,
        permissionState: Map<String, Any?>,
        deviceState: Map<String, Any?>
    ): String {
        return when (state) {
            TrackingState.ACTIVE -> "Tracking is active and working normally"
            TrackingState.PAUSED -> "Tracking is temporarily paused"
            TrackingState.STOPPED -> "Tracking service is not running"
            TrackingState.OFFLINE -> "Device is offline - locations cached for upload"
            TrackingState.WAITING_PERMISSION -> {
                val missing = permissionState["missingPermission"]
                when (missing) {
                    "FINE_LOCATION" -> "Waiting for location permission"
                    "BACKGROUND_LOCATION" -> "Waiting for background location permission"
                    else -> "Waiting for permissions"
                }
            }
            TrackingState.WAITING_LOCATION -> "Location services are disabled on device"
            TrackingState.ERROR -> "Tracking encountered an error"
            else -> "Unknown state"
        }
    }

    /**
     * Get battery status string
     */
    private fun getBatteryStatusString(level: Int): String {
        return when {
            level < 0 -> "unknown"
            level <= 5 -> "critical"
            level <= 15 -> "low"
            level <= 30 -> "moderate"
            level <= 60 -> "good"
            else -> "excellent"
        }
    }

    /**
     * Check if notification should be added for this status type
     */
    private fun shouldAddNotification(statusType: String): Boolean {
        return when (statusType) {
            STATUS_HEARTBEAT -> false // Don't spam heartbeats
            STATUS_APP_CLOSED -> false // Don't notify on close
            else -> true
        }
    }

    /**
     * Get notification title for status type
     */
    private fun getNotificationTitle(statusType: String): String {
        return when (statusType) {
            STATUS_SERVICE_STARTED -> "Service Started"
            STATUS_SERVICE_STOPPED -> "Service Stopped"
            STATUS_TRACKING_RESUMED -> "Tracking Resumed"
            STATUS_TRACKING_PAUSED -> "Tracking Paused"
            STATUS_FCM_WAKE_RECEIVED -> "FCM Wake Received"
            STATUS_FCM_WAKE_SUCCESS -> "FCM Wake Successful"
            STATUS_FCM_WAKE_FAILED -> "FCM Wake Failed"
            STATUS_SETTINGS_CHANGED -> "Settings Applied"
            STATUS_PERMISSION_CHANGED -> "Permission Changed"
            STATUS_PERMISSION_GRANTED -> "Permission Granted"
            STATUS_PERMISSION_DENIED -> "Permission Denied"
            STATUS_LOCATION_DISABLED -> "Location Disabled"
            STATUS_LOCATION_ENABLED -> "Location Enabled"
            STATUS_NETWORK_LOST -> "Network Lost"
            STATUS_NETWORK_RESTORED -> "Network Restored"
            STATUS_APP_OPENED -> "App Opened"
            STATUS_BATTERY_LOW -> "Battery Low"
            STATUS_BATTERY_CRITICAL -> "Battery Critical"
            STATUS_DEVICE_BOOT -> "Device Booted"
            STATUS_UPLOAD_SUCCESS -> "Upload Successful"
            STATUS_UPLOAD_FAILED -> "Upload Failed"
            STATUS_ERROR -> "Error"
            STATUS_WARNING -> "Warning"
            else -> statusType.replace("_", " ").lowercase()
                .replaceFirstChar { it.titlecase() }
        }
    }

    /**
     * Get severity for status type
     */
    private fun getSeverity(statusType: String): String {
        return when (statusType) {
            STATUS_SERVICE_STARTED, STATUS_TRACKING_RESUMED,
            STATUS_FCM_WAKE_SUCCESS, STATUS_PERMISSION_GRANTED,
            STATUS_LOCATION_ENABLED, STATUS_NETWORK_RESTORED,
            STATUS_UPLOAD_SUCCESS -> Severity.SUCCESS

            STATUS_SERVICE_STOPPED, STATUS_TRACKING_PAUSED,
            STATUS_LOCATION_DISABLED, STATUS_NETWORK_LOST,
            STATUS_BATTERY_LOW -> Severity.WARNING

            STATUS_FCM_WAKE_FAILED, STATUS_PERMISSION_DENIED,
            STATUS_UPLOAD_FAILED, STATUS_ERROR,
            STATUS_BATTERY_CRITICAL -> Severity.ERROR

            else -> Severity.INFO
        }
    }

    /**
     * Get upload success rate from stats
     */
    private fun getUploadSuccessRate(context: Context): Float {
        return try {
            val prefs = context.getSharedPreferences("location_stats_prefs", Context.MODE_PRIVATE)
            val success = prefs.getInt("upload_success_count", 0)
            val total = prefs.getInt("upload_total_count", 0)
            if (total > 0) (success.toFloat() / total * 100) else 100f
        } catch (e: Exception) { 100f }
    }

    /**
     * Get FCM response rate from stats
     */
    private fun getFcmResponseRate(context: Context): Float {
        return try {
            val prefs = context.getSharedPreferences("fcm_stats_prefs", Context.MODE_PRIVATE)
            val received = prefs.getInt("fcm_received_count", 0)
            val responded = prefs.getInt("fcm_responded_count", 0)
            if (received > 0) (responded.toFloat() / received * 100) else 100f
        } catch (e: Exception) { 100f }
    }

    /**
     * Get location count in last 24 hours
     */
    private fun getLocationCount24h(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences("location_stats_prefs", Context.MODE_PRIVATE)
            prefs.getInt("location_count_24h", 0)
        } catch (e: Exception) { 0 }
    }

    /**
     * Get last location timestamp
     */
    private fun getLastLocationTime(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            prefs.getLong("last_location_time", 0L)
        } catch (e: Exception) { 0L }
    }

    /**
     * Update statistics when location is captured
     */
    fun updateLocationStats(context: Context) {
        try {
            val prefs = context.getSharedPreferences("location_stats_prefs", Context.MODE_PRIVATE)
            val count = prefs.getInt("location_count_24h", 0)
            prefs.edit().putInt("location_count_24h", count + 1).apply()

            context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_location_time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating location stats: ${e.message}")
        }
    }

    /**
     * Update upload statistics
     */
    fun updateUploadStats(context: Context, success: Boolean, count: Int = 1) {
        try {
            val prefs = context.getSharedPreferences("location_stats_prefs", Context.MODE_PRIVATE)
            val totalCount = prefs.getInt("upload_total_count", 0)
            prefs.edit().putInt("upload_total_count", totalCount + count).apply()

            if (success) {
                val successCount = prefs.getInt("upload_success_count", 0)
                prefs.edit().putInt("upload_success_count", successCount + count).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating upload stats: ${e.message}")
        }
    }

    /**
     * Update FCM statistics
     */
    fun updateFcmStats(context: Context, received: Boolean = false, responded: Boolean = false) {
        try {
            val prefs = context.getSharedPreferences("fcm_stats_prefs", Context.MODE_PRIVATE)

            if (received) {
                val count = prefs.getInt("fcm_received_count", 0)
                prefs.edit().putInt("fcm_received_count", count + 1).apply()

                context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_fcm_received", System.currentTimeMillis())
                    .apply()
            }

            if (responded) {
                val count = prefs.getInt("fcm_responded_count", 0)
                prefs.edit().putInt("fcm_responded_count", count + 1).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating FCM stats: ${e.message}")
        }
    }

    /**
     * Reset daily statistics (call at midnight)
     */
    fun resetDailyStats(context: Context) {
        try {
            context.getSharedPreferences("location_stats_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("location_count_24h", 0)
                .putInt("upload_success_count", 0)
                .putInt("upload_total_count", 0)
                .apply()

            context.getSharedPreferences("fcm_stats_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("fcm_received_count", 0)
                .putInt("fcm_responded_count", 0)
                .apply()

            Log.d(TAG, "📊 Daily stats reset")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting daily stats: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BACKEND METHODS (Original functionality preserved)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Send status to backend
     */
    private suspend fun sendToBackend(context: Context, json: JSONObject, idToken: String) {
        try {
            val backendUrl = getBackendUrl(context)
            val url = "$backendUrl/api/fcm/device-status"

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Status reported to backend successfully")

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_STATUS_UPLOAD, System.currentTimeMillis())
                    .apply()
            } else {
                Log.e(TAG, "❌ Failed to report status to backend: ${response.code}")
                queueStatus(context, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending to backend: ${e.message}")
            queueStatus(context, json)
        }
    }

    /**
     * Queue status for later sending
     */
    private fun queueStatus(context: Context, json: JSONObject) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val queuedJson = prefs.getString(KEY_QUEUED_STATUSES, "[]")
            val queuedArray = JSONArray(queuedJson)

            json.put("queuedAt", System.currentTimeMillis())
            queuedArray.put(json)

            // Keep only last 50 queued statuses
            while (queuedArray.length() > 50) {
                queuedArray.remove(0)
            }

            prefs.edit().putString(KEY_QUEUED_STATUSES, queuedArray.toString()).apply()
            Log.d(TAG, "📝 Status queued for later: ${json.optString("statusType")}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error queuing status", e)
        }
    }

    /**
     * Send queued statuses when network is available
     */
    fun sendQueuedStatuses(context: Context) {
        scope?.launch {
            sendQueuedStatusesInternal(context)
        } ?: CoroutineScope(Dispatchers.IO).launch {
            sendQueuedStatusesInternal(context)
        }
    }

    private suspend fun sendQueuedStatusesInternal(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val queuedJson = prefs.getString(KEY_QUEUED_STATUSES, "[]")
            val queuedArray = JSONArray(queuedJson)

            if (queuedArray.length() == 0) {
                return
            }

            Log.d(TAG, "📤 Sending ${queuedArray.length()} queued statuses...")

            if (!isNetworkAvailable(context)) {
                Log.w(TAG, "⚠️ Still no network - keeping statuses queued")
                return
            }

            val user = FirebaseAuth.getInstance().currentUser ?: return
            val idToken = try {
                withContext(Dispatchers.Main) {
                    user.getIdToken(false).result?.token
                }
            } catch (e: Exception) {
                return
            } ?: return

            val backendUrl = getBackendUrl(context)
            val url = "$backendUrl/api/fcm/device-status"

            val successIndices = mutableListOf<Int>()

            for (i in 0 until queuedArray.length()) {
                try {
                    val statusJson = queuedArray.getJSONObject(i)
                    statusJson.put("wasQueued", true)
                    statusJson.put("sentAt", System.currentTimeMillis())

                    val body = statusJson.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $idToken")
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        successIndices.add(i)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error sending queued status $i", e)
                }
            }

            // Remove successful statuses
            if (successIndices.isNotEmpty()) {
                val newQueue = JSONArray()
                for (i in 0 until queuedArray.length()) {
                    if (i !in successIndices) {
                        newQueue.put(queuedArray.getJSONObject(i))
                    }
                }
                prefs.edit().putString(KEY_QUEUED_STATUSES, newQueue.toString()).apply()
                Log.d(TAG, "✅ Sent ${successIndices.size} queued statuses")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending queued statuses", e)
        }
    }

    /**
     * Gather comprehensive device state
     */
    private fun gatherDeviceState(context: Context): Map<String, Any?> {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        return mapOf(
            "locationEnabled" to (gpsEnabled || networkEnabled),
            "gpsEnabled" to gpsEnabled,
            "networkLocationEnabled" to networkEnabled,
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "batteryStatus" to when(batteryStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN"
            },
            "networkAvailable" to isNetworkAvailable(context),
            "networkType" to getNetworkType(context),
            "timestamp" to System.currentTimeMillis()
        )
    }

    /**
     * Gather permission state
     */
    private fun gatherPermissionState(context: Context): Map<String, Any?> {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val locationServicesEnabled = gpsEnabled || networkEnabled

        return mapOf(
            "hasFineLocationPermission" to hasFineLocation,
            "hasBackgroundLocationPermission" to hasBackgroundLocation,
            "locationServicesEnabled" to locationServicesEnabled,
            "gpsProviderEnabled" to gpsEnabled,
            "networkProviderEnabled" to networkEnabled,
            "canTrackLocation" to (hasFineLocation && hasBackgroundLocation && locationServicesEnabled),
            "missingPermission" to when {
                !hasFineLocation -> "FINE_LOCATION"
                !hasBackgroundLocation -> "BACKGROUND_LOCATION"
                !locationServicesEnabled -> "LOCATION_SERVICES"
                else -> null
            }
        )
    }

    /**
     * Gather service state
     */
    private fun gatherServiceState(context: Context): Map<String, Any?> {
        // Check if tracking is ACTUALLY active (not just the admin's master switch)
        // KEY_TRACKING_SHOULD_BE_RUNNING reflects if service is actively tracking
        val prefs = context.getSharedPreferences(
            com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants.PREFS_NAME_SERVICE_STATE,
            Context.MODE_PRIVATE
        )
        val isActuallyTracking = prefs.getBoolean("tracking_should_be_running", false)

        return mapOf(
            "isTracking" to isActuallyTracking,  // Report actual tracking state
            "lastHeartbeat" to ServiceStatePersistence.getLastHeartbeat(context),
            "resurrectionCount" to ServiceStatePersistence.getResurrectionCount(context),
            "serviceUptime" to ServiceStatePersistence.getServiceUptime(context),
            "fcmWakeCount" to ServiceStatePersistence.getFcmWakeCount(context)
        )
    }

    /**
     * Gather device info
     */
    private fun gatherDeviceInfo(context: Context): Map<String, Any?> {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "androidVersion" to Build.VERSION.SDK_INT,
            "androidRelease" to Build.VERSION.RELEASE,
            "appVersion" to appVersion,
            "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    /**
     * Check network availability
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) { false }
    }

    /**
     * Get network type
     */
    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "NONE"
                val caps = cm.getNetworkCapabilities(network) ?: return "NONE"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    else -> "OTHER"
                }
            } else {
                @Suppress("DEPRECATION")
                when (cm.activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                    else -> "OTHER"
                }
            }
        } catch (e: Exception) { "UNKNOWN" }
    }

    /**
     * Get backend URL
     */
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

    // ═══════════════════════════════════════════════════════════════════════
    // CONVENIENT HELPER METHODS FOR COMMON REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Report tracking started
     */
    fun reportTrackingStarted(context: Context, intervalMs: Long) {
        reportStatus(
            context,
            STATUS_SERVICE_STARTED,
            "Location tracking started with ${formatInterval(intervalMs)} interval",
            mapOf("interval" to intervalMs)
        )
    }

    /**
     * Report tracking stopped
     */
    fun reportTrackingStopped(context: Context, reason: String) {
        reportStatus(
            context,
            STATUS_SERVICE_STOPPED,
            "Location tracking stopped: $reason",
            mapOf("reason" to reason)
        )
    }

    /**
     * Report tracking paused
     */
    fun reportTrackingPaused(context: Context, reason: String) {
        reportStatus(
            context,
            STATUS_TRACKING_PAUSED,
            reason,
            mapOf("reason" to reason)
        )
    }

    /**
     * Report tracking resumed
     */
    fun reportTrackingResumed(context: Context) {
        reportStatus(
            context,
            STATUS_TRACKING_RESUMED,
            "Location tracking resumed successfully"
        )
    }

    /**
     * Report location enabled
     */
    fun reportLocationEnabled(context: Context) {
        Log.d(TAG, "🟢🟢🟢 REPORTING LOCATION ENABLED TO FIRESTORE 🟢🟢🟢")
        reportStatus(
            context,
            STATUS_LOCATION_ENABLED,
            "Device location services enabled"
        )
    }

    /**
     * Report location disabled
     */
    fun reportLocationDisabled(context: Context) {
        Log.d(TAG, "🔴🔴🔴 REPORTING LOCATION DISABLED TO FIRESTORE 🔴🔴🔴")
        reportStatus(
            context,
            STATUS_LOCATION_DISABLED,
            "Device location services disabled - tracking paused"
        )
    }

    /**
     * Report permission granted
     */
    fun reportPermissionGranted(context: Context, permission: String) {
        reportStatus(
            context,
            STATUS_PERMISSION_GRANTED,
            "$permission permission granted",
            mapOf("permission" to permission)
        )
    }

    /**
     * Report permission denied
     */
    fun reportPermissionDenied(context: Context, permission: String) {
        reportStatus(
            context,
            STATUS_PERMISSION_DENIED,
            "$permission permission denied - tracking may not work",
            mapOf("permission" to permission),
            failureReason = "Permission denied: $permission"
        )
    }

    /**
     * Report FCM received
     */
    fun reportFcmReceived(context: Context, fcmType: String) {
        updateFcmStats(context, received = true)
        reportStatus(
            context,
            STATUS_FCM_WAKE_RECEIVED,
            "FCM message received: $fcmType",
            mapOf("fcmType" to fcmType)
        )
    }

    /**
     * Report FCM wake success
     */
    fun reportFcmWakeSuccess(context: Context, action: String) {
        updateFcmStats(context, responded = true)
        reportStatus(
            context,
            STATUS_FCM_WAKE_SUCCESS,
            "FCM wake successful: $action",
            mapOf("action" to action)
        )
    }

    /**
     * Report FCM wake failed
     */
    fun reportFcmWakeFailed(context: Context, reason: String) {
        reportStatus(
            context,
            STATUS_FCM_WAKE_FAILED,
            "FCM wake failed: $reason",
            mapOf("reason" to reason),
            failureReason = reason
        )
    }

    /**
     * Report settings applied
     */
    fun reportSettingsApplied(context: Context, settings: Map<String, Any?>) {
        val description = buildString {
            if (settings["emergencyMode"] == true) append("Emergency Mode, ")
            if (settings["realtimeMode"] == true) append("Realtime Mode, ")
            if (settings["forceCheck"] == true) append("Force Check, ")
            settings["interval"]?.let { append("Interval: ${formatInterval(it as Long)}") }
        }.trimEnd(',', ' ')

        reportStatus(
            context,
            STATUS_SETTINGS_CHANGED,
            if (description.isNotEmpty()) description else "Settings updated",
            settings
        )
    }

    /**
     * Report network connected
     */
    fun reportNetworkConnected(context: Context, networkType: String) {
        reportStatus(
            context,
            STATUS_NETWORK_RESTORED,
            "Network connected: $networkType",
            mapOf("networkType" to networkType)
        )
    }

    /**
     * Report network disconnected
     */
    fun reportNetworkDisconnected(context: Context) {
        reportStatus(
            context,
            STATUS_NETWORK_LOST,
            "Network disconnected - locations cached for later upload"
        )
    }

    /**
     * Report error
     */
    fun reportError(context: Context, errorType: String, errorMessage: String) {
        reportStatus(
            context,
            STATUS_ERROR,
            "$errorType: $errorMessage",
            mapOf("errorType" to errorType),
            failureReason = errorMessage
        )
    }

    /**
     * Report battery low
     */
    fun reportBatteryLow(context: Context, level: Int) {
        reportStatus(
            context,
            STATUS_BATTERY_LOW,
            "Battery low: $level%",
            mapOf("batteryLevel" to level)
        )
    }

    /**
     * Report battery critical
     */
    fun reportBatteryCritical(context: Context, level: Int) {
        reportStatus(
            context,
            STATUS_BATTERY_CRITICAL,
            "Battery critical: $level% - tracking may stop",
            mapOf("batteryLevel" to level)
        )
    }

    /**
     * Report device boot
     */
    fun reportDeviceBoot(context: Context) {
        reportStatus(
            context,
            STATUS_DEVICE_BOOT,
            "Device booted - starting tracking service"
        )
    }

    /**
     * Format interval for display
     */
    private fun formatInterval(intervalMs: Long): String {
        return when {
            intervalMs < 1000 -> "${intervalMs}ms"
            intervalMs < 60000 -> "${intervalMs / 1000}s"
            intervalMs < 3600000 -> "${intervalMs / 60000}min"
            else -> "${intervalMs / 3600000}h"
        }
    }
}
