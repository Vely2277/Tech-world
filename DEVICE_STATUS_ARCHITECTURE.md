# Device Status Reporting Architecture

## Overview
Device status updates use **Firebase Realtime Listener ONLY** - no FCM, no HTTP requests.

---

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                       ANDROID APP                           │
│                   DeviceStatusReporter.kt                   │
│                                                             │
│  Detects status changes:                                   │
│  • Service started/stopped                                 │
│  • Tracking active/paused                                  │
│  • Permission changes                                      │
│  • Location enabled/disabled                               │
│  • FCM wake received                                       │
│  • Battery/network changes                                 │
│  • Periodic heartbeat (5 min)                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ Writes directly to Firestore
                            │ (no HTTP, no backend)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   GOOGLE FIRESTORE                          │
│                                                             │
│  Collections:                                              │
│  • deviceStatus/{userId}                                   │
│  • locationTrackingSettings/{userId}  ← PRIMARY SOURCE     │
│  • deviceNotifications                                     │
│                                                             │
│  Update latency: 2-5 seconds                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ Firebase SDK listens via WebSocket
                            │ (instant updates, no polling)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      ADMIN PAGE                             │
│               main.js + firebase.js                         │
│                                                             │
│  subscribeToDeviceStatus(userId, onUpdate)                 │
│  • Listens: locationTrackingSettings/{userId}              │
│  • Auto-updates: state.fcmPage.deviceStatus                │
│  • Triggers: renderApp() on each change                    │
│  • Displays: tracking state, battery, network, etc.        │
└─────────────────────────────────────────────────────────────┘
```

---

## Why This Architecture?

### ✅ Benefits
- **Instant updates**: 2-5 second latency (vs 30-60s with HTTP polling)
- **No server load**: Direct Firestore writes, no backend endpoints
- **Always current**: Realtime listeners never miss updates
- **Offline resilient**: Firebase SDK handles reconnection automatically
- **Scalable**: Firestore handles millions of concurrent listeners

### ❌ Previous Issues (FCM-based)
- FCM responses were queued and delayed
- Backend endpoint caused conflicts and undefined field errors
- Multiple data sources (FCM + Firestore) caused inconsistencies
- HTTP requests added unnecessary latency and complexity

---

## Key Collections

### 1. `locationTrackingSettings/{userId}` (PRIMARY)
**Written by:** Android app (DeviceStatusReporter)  
**Read by:** Admin page (Firebase Realtime Listener)  
**Update frequency:** On every status change + 5-minute heartbeat

**Key fields:**
```javascript
{
  // Tracking state (MAIN INDICATORS)
  trackingState: "active" | "paused" | "waiting_permission" | "waiting_location" | "offline",
  trackingStateMessage: "Location tracking active",
  isActive: true,
  isOnline: true,
  lastStatusType: "TRACKING_RESUMED",
  lastStatusMessage: "Location tracking resumed successfully",

  // Service state
  serviceRunning: true,
  lastHeartbeat: 1771216168740,
  resurrectionCount: 0,

  // Permissions
  hasFineLocation: true,
  hasBackgroundLocation: true,
  locationServicesEnabled: true,
  canTrackLocation: true,

  // Device state
  batteryLevel: 98,
  batteryCharging: true,
  networkConnected: true,
  networkType: "wifi",

  // Device info
  lastDeviceInfo: {
    model: "itel A663LC",
    manufacturer: "ITEL",
    androidVersion: "13",
    appVersion: "1.0"
  },

  // Timestamps
  updatedAt: Timestamp,
  lastResponseTime: Timestamp
}
```

### 2. `deviceStatus/{userId}`
**Written by:** Android app (DeviceStatusReporter)  
**Read by:** Admin page (optional, not currently used)  
**Purpose:** Dedicated status document (same data as locationTrackingSettings)

### 3. `deviceNotifications`
**Written by:** Android app (DeviceStatusReporter)  
**Read by:** Admin page (Firebase Realtime Listener)  
**Purpose:** Event log for notifications tab

**Document structure:**
```javascript
{
  userId: "xxx",
  type: "TRACKING_RESUMED",
  title: "Tracking Resumed",
  message: "Location tracking resumed successfully",
  severity: "success",
  timestamp: 1771216168740,
  createdAt: Timestamp,
  read: false
}
```

---

## Admin Page Implementation

### Starting Listeners
```javascript
// main.js - startFCMRealtimeListeners()
function startFCMRealtimeListeners(userId) {
    // Clean up existing listeners
    stopFCMRealtimeListeners();

    // Listen to device status
    const statusUnsubscribe = subscribeToDeviceStatus(
        userId,
        (statusData) => {
            // Update state
            state.fcmPage.deviceStatus = statusData;
            state.fcmPage.lastRealtimeUpdate = Date.now();
            state.fcmPage.realtimeConnected = true;
            
            // Re-render UI
            renderApp();
        },
        (error) => {
            console.error('Status listener error:', error);
        }
    );

    // Listen to notifications
    const notificationsUnsubscribe = subscribeToDeviceNotifications(
        userId,
        (notifications) => {
            state.fcmPage.notifications = notifications;
            renderApp();
        }
    );

    // Store cleanup functions
    state.fcmPage.fcmStatusUnsubscribe = () => {
        statusUnsubscribe();
        notificationsUnsubscribe();
    };
}
```

### Firebase Config
```javascript
// firebase.js - subscribeToDeviceStatus()
export function subscribeToDeviceStatus(userId, onUpdate, onError) {
    const docRef = doc(db, 'locationTrackingSettings', userId);

    const unsubscribe = onSnapshot(docRef,
        (snapshot) => {
            if (snapshot.exists()) {
                const data = snapshot.data();
                
                // Process and normalize data
                onUpdate({
                    trackingState: data.trackingState || 'unknown',
                    isActive: data.isActive === true,
                    serviceRunning: data.serviceRunning === true,
                    hasFineLocation: data.hasFineLocation,
                    hasBackgroundLocation: data.hasBackgroundLocation,
                    // ... more fields
                });
            }
        },
        (error) => {
            onError(error);
        }
    );

    return unsubscribe;
}
```

---

## Android App Implementation

### Reporting Status
```kotlin
// DeviceStatusReporter.kt
object DeviceStatusReporter {
    fun reportStatus(
        context: Context,
        statusType: String,
        message: String,
        extraData: Map<String, Any?>? = null
    ) {
        // Gather device state
        val deviceState = gatherDeviceState(context)
        val permissionState = gatherPermissionState(context)
        val serviceState = gatherServiceState(context)

        // Determine tracking state
        val trackingState = determineTrackingState(
            permissionState, deviceState, serviceState
        )

        // Write to Firestore (PRIMARY - for Admin page)
        updateFirestoreStatus(
            context, userId, statusType, message,
            deviceState, permissionState, serviceState,
            trackingState, ...
        )

        // Also add notification if significant
        if (shouldAddNotification(statusType)) {
            addFirestoreNotification(
                context, userId, statusType,
                message, severity, extraData
            )
        }
    }

    private fun updateFirestoreStatus(...) {
        val statusData = hashMapOf(
            "trackingState" to trackingState,
            "isActive" to (trackingState == TrackingState.ACTIVE),
            "serviceRunning" to serviceRunning,
            "hasFineLocation" to hasFineLocation,
            // ... all status fields
        )

        // Write to locationTrackingSettings (PRIMARY)
        firestore.collection("locationTrackingSettings")
            .document(userId)
            .set(statusData, SetOptions.merge())
    }
}
```

### Triggering Status Updates
```kotlin
// LocationTrackingService.kt
override fun onCreate() {
    // Initialize reporter
    DeviceStatusReporter.initialize(this)
}

private fun handleStartTracking() {
    // Start tracking
    startLocationUpdates()

    // Report status
    DeviceStatusReporter.reportStatus(
        this,
        DeviceStatusReporter.STATUS_TRACKING_RESUMED,
        "Location tracking resumed successfully"
    )
}

override fun onDestroy() {
    // Report status
    DeviceStatusReporter.reportStatus(
        this,
        DeviceStatusReporter.STATUS_SERVICE_STOPPED,
        "Service stopped"
    )
}
```

---

## Backend Role

### ⚠️ What Backend Does NOT Do
- ❌ Does NOT receive device status updates via HTTP
- ❌ Does NOT write to deviceStatus collections
- ❌ Does NOT act as a proxy for status data
- ❌ `handleDeviceStatus` endpoint is DEPRECATED

### ✅ What Backend DOES Do
- ✅ Manages FCM wake cycles (silent notifications)
- ✅ Monitors device silence (no location data for 3+ hours)
- ✅ Sends WAKE_SERVICE FCM to resurrect service
- ✅ Provides admin APIs for manual FCM control

---

## Deprecation Notice

### Deprecated Endpoints
```
POST /api/fcm/device-status
```
**Status:** Deprecated (kept for backwards compatibility only)  
**Reason:** Replaced by Firebase Realtime Listener  
**Action:** Returns success but does nothing

### Deprecated Collections
- `deviceStatusLog` - No longer used
- `fcmResponseLog` - No longer used  
- These were written by the old FCM-based system

---

## Monitoring & Debugging

### Check if Realtime Listener is Working
**Admin page console:**
```javascript
// Look for these logs
📡 Starting FCM realtime listeners for: {userId}
📊 Device Status Update: {status object}
✅ Realtime device status update: {data}
```

### Check Android Status Reporting
**Logcat filter:** `DeviceStatusReporter`
```
✅✅✅ FIRESTORE UPDATE SUCCESS ✅✅✅
   Collection: locationTrackingSettings
   UserId: xxx
   StatusType: TRACKING_RESUMED
   TrackingState: active
   IsActive: true
```

### Check Firestore Directly
**Firebase Console:**
1. Go to Firestore Database
2. Open `locationTrackingSettings/{userId}`
3. Check `updatedAt` timestamp (should be recent)
4. Check `trackingState` field

---

## Troubleshooting

### Issue: Admin page shows "Inactive" but device is tracking
**Cause:** Firestore update failed or listener not started  
**Fix:**
1. Check Android logcat for Firestore errors
2. Verify listener is started: `state.fcmPage.realtimeConnected === true`
3. Check Firebase console for recent `updatedAt` timestamp

### Issue: Status not updating in real-time
**Cause:** Listener stopped or Firebase connection lost  
**Fix:**
1. Check console for "Starting FCM realtime listeners"
2. Check `state.fcmPage.lastRealtimeUpdate` timestamp
3. Restart listeners by switching users in admin page

### Issue: "Location services disabled" despite being enabled
**Cause:** Permission state not correctly reported  
**Fix:**
1. Check `locationServicesEnabled` field in Firestore
2. Verify Android app has location permission
3. Check `DeviceStatusReporter.gatherPermissionState()` logic

---

## Performance Metrics

- **Update latency:** 2-5 seconds (Firestore propagation time)
- **Heartbeat interval:** 5 minutes (prevents stale data)
- **Firestore write rate:** ~1 per status change + 12/hour (heartbeat)
- **Admin page bandwidth:** Minimal (WebSocket only, no polling)

---

## Future Improvements

1. **Reduce Firestore writes:** Throttle heartbeats more aggressively
2. **Offline queue:** Buffer status updates when Firebase is offline
3. **Multi-device support:** Track status per device, not just per user
4. **Historical tracking:** Archive old status updates for analytics

---

## Summary

✅ **Single source of truth:** Firebase Realtime Listener only  
✅ **No HTTP requests:** Direct Firestore writes and reads  
✅ **Instant updates:** 2-5 second latency via WebSocket  
✅ **No conflicts:** FCM system completely removed from status reporting  
✅ **Scalable:** Firestore handles all the heavy lifting  

**Result:** Clean, fast, reliable device status reporting that works perfectly! 🎉
