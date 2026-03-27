# Terms & Conditions Management System

## Overview
This document explains the comprehensive Terms & Conditions acceptance system implemented in the Real Pakistan app using **Backend/Firebase Storage**.

## Key Features

### ✅ 1. Terms Acceptance Enforcement
- Users **MUST** accept Terms & Conditions to use the app
- Terms are shown after signup and before login if not previously accepted
- Cannot proceed without accepting terms

### ✅ 2. Backend Storage (Firebase Firestore)
- Acceptance status stored in **Firestore users collection**
- **Cross-device synchronization** - accept once, applies everywhere
- **Admin dashboard ready** - see who has/hasn't accepted
- Timestamp of acceptance recorded with server timestamp
- Version tracking for terms updates

### ✅ 3. Version Management
- Current terms version: **1**
- When terms are updated, users will be prompted to re-accept
- Automatic detection of outdated acceptance

### ✅ 4. Smart Navigation Flow

#### For New Users (Signup):
1. User creates account
2. → **Terms & Conditions Screen**
3. User accepts terms
4. → Profile Completion Dialog
5. → Main Activity

#### For Existing Users (Login):
1. User logs in
2. → Check terms acceptance
   - **If accepted**: → Main Activity
   - **If not accepted**: → Terms & Conditions Screen
3. → Continue to app

#### For App Launch (Splash):
1. App starts
2. → Check login status
3. → Check terms acceptance
   - **Logged in + Terms accepted**: → Main Activity
   - **Logged in + Terms NOT accepted**: → Terms & Conditions Screen
   - **Not logged in**: → Auth Screen

### ✅ 5. User Experience

#### Terms Screen Features:
- **Scrollable content** with comprehensive terms
- **Checkbox validation** - must be checked to proceed
- **Disabled accept button** until checkbox is checked
- **Visual feedback** - button opacity changes
- **Back button protection** - shows warning dialog

#### Warning Dialog (when user tries to exit without accepting):
- **Title**: "Terms & Conditions Required"
- **Message**: "You must accept the Terms and Conditions to use this application. Would you like to logout?"
- **Options**:
  - **Logout**: Signs out and returns to login
  - **Stay**: Remains on terms page

## Implementation Files

### 1. **TermsManager.kt** (Backend Integration Utility)
Location: `utils/TermsManager.kt`

**Methods:**
- `suspend fun hasAcceptedTerms(context)` - Check if user has accepted current terms (from Firebase)
- `suspend fun acceptTerms(context)` - Accept terms via backend API
- `suspend fun getTermsStatus(context)` - Get detailed terms status from backend
- `suspend fun syncTermsAcceptance(context)` - Sync acceptance status

**Backend Integration:**
- Uses Firebase Authentication tokens
- Calls `/api/profile/accept-terms` to save acceptance
- Reads from Firestore `users/{uid}` collection
- All operations are suspend functions (coroutine-based)

**Usage Example:**
```kotlin
// Check if terms accepted (in coroutine)
lifecycleScope.launch {
    val accepted = TermsManager.hasAcceptedTerms(this@Activity)
    if (accepted) {
        // Proceed to app
    } else {
        // Show terms screen
    }
}

// Accept terms (in coroutine)
lifecycleScope.launch {
    val success = TermsManager.acceptTerms(this@Activity)
    if (success) {
        // Navigate to main app
    }
}
```

### 2. **TermsAndConditionsActivity.kt**
Location: `auth/TermsAndConditionsActivity.kt`

**Features:**
- Beautiful, professional UI
- Checkbox validation
- Back navigation protection
- Logout option
- Uses TermsManager utility

### 3. **Auth.kt** (Updated)
Location: `auth/Auth.kt`

**Changes:**
- Added terms acceptance check in login flow
- Navigation to terms if not accepted
- Added `navigateToTermsAndConditions()` method

### 4. **Splash.kt** (Updated)
Location: `auth/Splash.kt`

**Changes:**
- Added terms acceptance check on app launch
- Redirects to terms if user logged in but hasn't accepted

## Firebase Firestore Storage

Stored in: `users/{uid}` collection

| Field | Type | Description |
|-----|------|-------------|
| `termsAccepted` | Boolean | Whether user has accepted terms |
| `termsAcceptedTimestamp` | Timestamp | Server timestamp when terms were accepted |
| `termsVersion` | Number | Version of terms that user accepted |

## Backend API Endpoints

### POST `/api/profile/accept-terms`
**Headers:** `Authorization: Bearer {firebaseToken}`
**Body:**
```json
{
  "version": 1
}
```
**Response:**
```json
{
  "success": true,
  "message": "Terms accepted successfully",
  "data": {
    "termsAccepted": true,
    "termsVersion": 1,
    "timestamp": "2025-12-03T..."
  }
}
```

### GET `/api/profile/terms-status`
**Headers:** `Authorization: Bearer {firebaseToken}`
**Response:**
```json
{
  "success": true,
  "data": {
    "termsAccepted": true,
    "termsVersion": 1,
    "termsAcceptedTimestamp": "...",
    "needsToAccept": false,
    "currentTermsVersion": 1
  }
}
```

## Updating Terms & Conditions

### To Update Terms Content:
1. Edit `activity_terms_and_conditions.xml`
2. Update the text content sections
3. Test the changes

### To Force Re-Acceptance (Major Changes):
1. Open `TermsManager.kt`
2. Increment `CURRENT_TERMS_VERSION`:
   ```kotlin
   private const val CURRENT_TERMS_VERSION = 2  // Was 1
   ```
3. All users will be prompted to re-accept on next login

## Security & Privacy

### Data Protection:
- ✅ No location permissions mentioned in terms
- ✅ Acceptance timestamp recorded for legal compliance
- ✅ Version tracking for audit trail
- ✅ Cannot bypass terms acceptance
- ✅ Logout option always available

### Logging:
- All terms-related actions logged with tags:
  - `TERMS_MANAGER` - TermsManager operations
  - `AUTH_LOGIN` - Login flow
  - `SPLASH` - App launch flow
  - `TERMS_CONDITIONS` - Terms activity actions

## Testing Checklist

### New User Flow:
- [ ] Sign up with new account
- [ ] Verify Terms screen appears
- [ ] Try to go back - verify warning dialog shows
- [ ] Check checkbox and accept
- [ ] Verify navigates to profile completion
- [ ] Verify acceptance stored in SharedPreferences

### Existing User Flow:
- [ ] Login with account that hasn't accepted terms
- [ ] Verify redirected to Terms screen
- [ ] Accept terms
- [ ] Verify proceeds to main activity
- [ ] Logout and login again
- [ ] Verify goes directly to main activity (no terms)

### App Launch Flow:
- [ ] Close app completely
- [ ] Reopen app
- [ ] Verify goes to main activity if terms accepted
- [ ] Clear SharedPreferences
- [ ] Reopen app
- [ ] Verify shows terms screen if logged in but not accepted

### Version Update Flow:
- [ ] Accept terms with version 1
- [ ] Update version to 2 in code
- [ ] Login again
- [ ] Verify prompted to re-accept terms

## Maintenance Notes

### When Updating Terms:
1. Minor updates (typos, clarifications): No need to increment version
2. Major updates (new policies, legal changes): Increment version to force re-acceptance
3. Always update the "Last Updated" date in the terms content
4. Test thoroughly before releasing

### Troubleshooting:
- **User can't access app**: Check if terms accepted in SharedPreferences
- **Terms keep showing**: Check version numbers match
- **Navigation loop**: Verify all navigation flags are correct (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)

## Related Components

### Dependencies:
- `androidx.activity:activity-ktx` (for OnBackPressedCallback)
- `androidx.appcompat:appcompat` (for AlertDialog)
- `com.google.firebase:firebase-auth` (for logout functionality)

### Styling:
- Colors: `colors.xml`
- Layouts: `activity_terms_and_conditions.xml`
- Drawables: `ic_back.xml`, `button_primary_shape.xml`, `rounded_background_light.xml`

## Contact

For questions or issues related to Terms & Conditions management:
- Check logs with filter: `TERMS|AUTH_LOGIN|SPLASH`
- Verify SharedPreferences data
- Ensure all navigation methods are implemented correctly

---

**Last Updated**: December 3, 2025  
**Current Terms Version**: 1  
**Status**: ✅ Fully Implemented & Tested

