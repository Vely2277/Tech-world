# FCM System Documentation - Complete Implementation

## Overview

This document describes the comprehensive FCM (Firebase Cloud Messaging) system for location tracking service resurrection and device monitoring.

## System Components

### 1. Android App (FCMNotificationService.kt)

**Features:**
- ✅ Detailed permission breakdown (FINE, BACKGROUND, LOCATION_SERVICES)
- ✅ Offline response queuing with automatic send when network returns
- ✅ Comprehensive device state reporting
- ✅ FCM received timestamp tracking for accurate timing
- ✅ Support for both WAKE_SERVICE and SETTINGS_UPDATED FCM types

**Permission Breakdown:**
```
- NO_FINE_LOCATION_PERMISSION: App location permission not granted
- NO_BACKGROUND_LOCATION_PERMISSION: Background location permission not granted
- LOCATION_SERVICES_DISABLED: Device GPS/Location services turned OFF
- NOT_LOGGED_IN: User not logged into the app
- TRACKING_DISABLED: User explicitly disabled tracking
- SERVICE_START_FAILED: Failed to start the service
```

**Response Queuing:**
- When offline, responses are queued locally
- Maximum 20 queued items
- Queue is processed when network returns
- Call `FCMNotificationService.sendQueuedResponses(context)` when network is back

### 2. Backend (fcmController.js)

**Endpoints:**
- `POST /api/fcm/wake-response` - Device responds to wake FCM
- `POST /api/fcm/settings-response` - Device responds to settings update FCM
- `POST /api/fcm/admin/wake-user` - Admin manually triggers wake
- `GET /api/fcm/admin/status/:userId` - Get device status and FCM history
- `POST /api/fcm/admin/cancel-wake` - Cancel active wake cycle
- `GET /api/fcm/admin/wake-history/:userId` - Get wake history
- `GET /api/fcm/admin/unreachable` - Get list of unreachable users
- `GET /api/fcm/health` - Health check

**Timing Parameters:**
- SILENCE_THRESHOLD: 3 hours - Time without data before first FCM
- WAKE_INTERVAL: 2 hours - Time between FCM attempts
- MAX_ATTEMPTS: 12 - Maximum FCM attempts (24 hours)
- UNREACHABLE_AFTER: 24 hours - Mark as unreachable if no response

**Database Collections:**
- `fcmWakeHistory` - Stores all wake FCM attempts
- `fcmResponseLog` - Unified log for all FCM responses (wake & settings)
- `locationTracking` - Per-user wake state and last device info

### 3. Admin Page (FCM/Notifications)

**Features:**
- ✅ Beautiful modern device visualization
- ✅ Detailed permission breakdown display
- ✅ Last location with "View" button (opens Google Maps)
- ✅ Real-time device status
- ✅ FCM statistics (sent, responded, success rate)
- ✅ Response/Notification log with detailed status
- ✅ Instant Wake mode toggle
- ✅ Manual wake FCM button
- ✅ Wake cycle cancel button
- ✅ User ID persistence in localStorage

**Permission Display:**
- Shows each permission with ✅/❌/❓ status
- Explains what each permission is for
- Shows specific failure reason if tracking can't work

## FCM Data Flow

### Wake FCM Flow:
```
1. Backend detects silence (3+ hours no data)
   OR Admin manually triggers wake

2. Backend sends WAKE_SERVICE FCM with:
   - requestId (unique identifier)
   - reason (auto_silence_check or admin_manual_wake)
   - timestamp

3. App receives FCM and:
   - Records FCM received timestamp
   - Gathers device state and permission state
   - Checks all wake conditions
   - Starts service if conditions met
   - Sends response back to backend

4. Response includes:
   - status (SUCCESS/FAILED)
   - failureReason (specific code if failed)
   - deviceState (battery, network, location status)
   - permissionState (detailed permission breakdown)
   - deviceInfo (manufacturer, model, Android version)
   - fcmReceivedTimestamp (when FCM was received)
   - responseTimestamp (when response was sent)

5. If offline:
   - Response is queued locally
   - Sent when network returns
   - Includes wasQueued and queuedAt timestamps
```

### Settings Updated FCM Flow:
```
1. Admin changes settings in admin panel

2. App receives SETTINGS_UPDATED FCM via realtime listener
   (No separate FCM needed - realtime listener handles this)

3. App refreshes settings from Firestore

4. App sends SETTINGS_RESPONSE back to backend with:
   - Applied settings (interval, modes)
   - Device state
   - Permission state
   - Success/failure status
```

## Admin Page Usage

### Loading a User:
1. Enter User ID in the input field
2. Click "Load User" button
3. View device status, permissions, and FCM history

### Sending Wake FCM:
1. Load user first
2. Optionally enable "Instant Wake Mode" to bypass 3-hour silence check
3. Click "Send Wake FCM Now"
4. Monitor response in the log

### Viewing Last Location:
1. Load user first
2. Click "View" button next to "Last Location"
3. Opens Google Maps at the last known coordinates

### Understanding Failure Reasons:
The log shows readable failure reasons:
- "App location permission not granted" instead of "NO_FINE_LOCATION_PERMISSION"
- "Device GPS/Location services are turned OFF" instead of "LOCATION_SERVICES_DISABLED"

## Database Indexes Required

Create these Firestore indexes:

1. `fcmWakeHistory`:
   - userId (ASC), sentAt (DESC)

2. `fcmResponseLog`:
   - userId (ASC), receivedAt (DESC)

3. `locations`:
   - userId (ASC), serverTimestamp (DESC)

## Error Handling

### App Side:
- All exceptions are caught and logged
- Responses are queued if network unavailable
- Auth token failures trigger queue

### Backend Side:
- All endpoints return consistent error responses
- Failed FCM sends are logged
- Invalid tokens are auto-removed

### Admin Side:
- Toast notifications for success/error
- Console logs for debugging
- Loading states during API calls

## Testing Checklist

- [ ] Wake FCM when app is running
- [ ] Wake FCM when app is killed
- [ ] Wake FCM when location disabled
- [ ] Wake FCM when permission denied
- [ ] Settings update via admin panel
- [ ] Response when offline (queue test)
- [ ] View location in Google Maps
- [ ] Permission breakdown accuracy
- [ ] FCM statistics accuracy
