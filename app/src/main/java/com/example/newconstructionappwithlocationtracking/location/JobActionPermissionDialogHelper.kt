/*
 * ============================================================================
 * JOB ACTION PERMISSION DIALOG HELPER
 * ============================================================================
 *
 * PURPOSE:
 * Helper class to show permission dialogs for job actions (APPLY/VIEW/BUYER)
 * Handles all dialog creation, styling, and callbacks.
 *
 * DIALOGS:
 * 1. Background Permission Needed (after fine location granted)
 * 2. Permission Denied (after 1st denial - "Continue to Settings")
 * 3. Permission Denied (after 2nd+ denial - "Go to Settings")
 * 4. GPS Required (when device GPS/location is turned off)
 *
 * GPS FLOW:
 * After fine location permission is granted, if device GPS is OFF:
 * 1. Show system GPS enable dialog
 * 2. If rejected → show custom "GPS Required" dialog with Okay button
 * 3. If Okay tapped → show system GPS enable dialog again (2nd attempt)
 * 4. If rejected again → show custom dialog → Okay returns to page normally
 * 5. Next time button is tapped → flow restarts from step 1 (GPS prompt)
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.app.Activity
import android.app.Dialog
import android.content.IntentSender
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

class JobActionPermissionDialogHelper(private val activity: Activity) {

    companion object {
        private const val TAG = "GPS_PROMPT"
        private const val MAX_GPS_ATTEMPTS = 2
    }

    // GPS attempt counter - tracks how many times GPS has been rejected in this session
    private var gpsAttemptCount = 0

    // Callback to run after GPS is enabled
    private var onGpsEnabledCallback: (() -> Unit)? = null

    // Callback to run when GPS flow ends without enabling (user returns to page)
    private var onGpsFlowEndedCallback: (() -> Unit)? = null

    // The ActivityResultLauncher for GPS resolution - registered by activity
    private var gpsResolutionLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    /**
     * Initialize the GPS resolution launcher.
     * MUST be called from the activity's onCreate (before any fragment transactions).
     * For ComponentActivity (AppCompatActivity) only.
     */
    fun registerGpsLauncher(componentActivity: ComponentActivity) {
        gpsResolutionLauncher = componentActivity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // GPS was enabled
                Log.d(TAG, "✅ GPS enabled by user")
                gpsAttemptCount = 0
                onGpsEnabledCallback?.invoke()
            } else {
                // GPS was rejected
                gpsAttemptCount++
                Log.d(TAG, "❌ GPS rejected (attempt $gpsAttemptCount/$MAX_GPS_ATTEMPTS)")

                if (gpsAttemptCount < MAX_GPS_ATTEMPTS) {
                    // First rejection: show custom dialog, then retry GPS on Okay
                    showGpsRequiredDialog(retryGps = true)
                } else {
                    // Second rejection: show custom dialog, then return to page on Okay
                    showGpsRequiredDialog(retryGps = false)
                }
            }
        }
    }

    /**
     * Prompt the user to enable GPS/Location services using Google Play Services.
     * This shows the system GPS enable dialog (in-app, not navigating to settings).
     *
     * @param onGpsEnabled Called when GPS is successfully enabled
     * @param onGpsFlowEnded Called when GPS flow ends without GPS being enabled (user returns to page)
     */
    fun promptEnableGps(onGpsEnabled: () -> Unit, onGpsFlowEnded: (() -> Unit)? = null) {
        this.onGpsEnabledCallback = onGpsEnabled
        this.onGpsFlowEndedCallback = onGpsFlowEnded

        Log.d(TAG, "🔍 Checking if GPS/Location is enabled...")

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10000
        ).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(activity)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                // GPS is already enabled — proceed
                Log.d(TAG, "✅ GPS is already enabled")
                gpsAttemptCount = 0
                onGpsEnabled()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    Log.d(TAG, "📱 GPS is OFF - showing system enable dialog (attempt ${gpsAttemptCount + 1})")
                    try {
                        if (gpsResolutionLauncher != null) {
                            // Use the registered launcher (preferred, handles result via callback)
                            val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                            gpsResolutionLauncher?.launch(intentSenderRequest)
                        } else {
                            // Fallback: use deprecated startResolutionForResult
                            // The activity must handle onActivityResult with REQUEST_CODE_GPS
                            exception.startResolutionForResult(activity, REQUEST_CODE_GPS)
                        }
                    } catch (e: IntentSender.SendIntentException) {
                        Log.e(TAG, "❌ Failed to show GPS enable dialog", e)
                        // Can't show dialog - just proceed anyway
                        onGpsEnabled()
                    }
                } else {
                    Log.e(TAG, "❌ Location settings check failed with non-resolvable exception", exception)
                    // Non-resolvable - proceed anyway
                    onGpsEnabled()
                }
            }
    }

    /**
     * Handle GPS activity result (for activities using deprecated onActivityResult).
     * Call this from onActivityResult with REQUEST_CODE_GPS.
     */
    fun handleGpsResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "✅ GPS enabled by user (via onActivityResult)")
            gpsAttemptCount = 0
            onGpsEnabledCallback?.invoke()
        } else {
            gpsAttemptCount++
            Log.d(TAG, "❌ GPS rejected via onActivityResult (attempt $gpsAttemptCount/$MAX_GPS_ATTEMPTS)")

            if (gpsAttemptCount < MAX_GPS_ATTEMPTS) {
                showGpsRequiredDialog(retryGps = true)
            } else {
                showGpsRequiredDialog(retryGps = false)
            }
        }
    }

    /**
     * Reset GPS attempt counter. Call this when a new button tap starts the flow.
     */
    fun resetGpsAttempts() {
        gpsAttemptCount = 0
    }

    /**
     * Show the custom GPS Required dialog.
     * Styled exactly like other app dialogs with the message:
     * "To ensure all buyers are within Pakistan for accurate nearby job matching,
     *  location permission is highly required."
     *
     * @param retryGps If true, tapping Okay shows GPS prompt again.
     *                 If false, tapping Okay returns the user to the page normally.
     */
    private fun showGpsRequiredDialog(retryGps: Boolean) {
        Log.d(TAG, "📋 Showing GPS required dialog (retryGps=$retryGps)")

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_gps_required)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val btnOkay = dialog.findViewById<Button>(R.id.btnOkay)
        btnOkay.setOnClickListener {
            dialog.dismiss()
            if (retryGps) {
                // Show GPS system prompt again
                Log.d(TAG, "🔄 Retrying GPS prompt after Okay tap")
                promptEnableGps(
                    onGpsEnabled = { onGpsEnabledCallback?.invoke() },
                    onGpsFlowEnded = onGpsFlowEndedCallback
                )
            } else {
                // Second rejection complete - reset counter, return to page normally
                Log.d(TAG, "🏠 GPS flow ended - returning to page")
                gpsAttemptCount = 0
                onGpsFlowEndedCallback?.invoke()
            }
        }

        dialog.show()
    }

    /**
     * Show background permission needed dialog (SCENARIO 2a)
     * Message: "Geographical area not detected. To apply for this job opportunity,
     *          please enable location access at all times. Click Continue to update your settings."
     * Button: "Continue to Settings"
     */
    fun showBackgroundPermissionNeededDialog(onContinue: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_background_permission_needed)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val continueButton = dialog.findViewById<Button>(R.id.continueButton)

        continueButton.setOnClickListener {
            dialog.dismiss()
            onContinue()
        }

        dialog.show()
    }

    /**
     * Show permission denied dialog (SCENARIO 2b - 1st denial)
     * Message: "Geographical area not detected. This job opportunity is not currently
     *          applicable in your region. Please try again.
     *          To apply for jobs and verify you're in the right area, please enable
     *          location access to continue."
     * Button: "Continue to Settings"
     */
    fun showPermissionDeniedDialog(message: String, showSettingsButton: Boolean, onAction: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_location_permission_denied)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val dialogMessage = dialog.findViewById<TextView>(R.id.dialogMessage)
        val actionButton = dialog.findViewById<Button>(R.id.actionButton)

        // Set message
        dialogMessage.text = message

        // Set button text based on scenario
        if (showSettingsButton) {
            // After 2nd+ denial - "Go to Settings"
            actionButton.text = "Go to Settings"
        } else {
            // After 1st denial - "Continue to Settings"
            actionButton.text = "Continue to Settings"
        }

        actionButton.setOnClickListener {
            dialog.dismiss()
            onAction()
        }

        dialog.show()
    }

    /**
     * Show background permission still needed dialog
     * (When user returns from settings without granting background permission)
     * Same as SCENARIO 2a
     */
    fun showBackgroundPermissionStillNeededDialog(onContinue: () -> Unit) {
        showBackgroundPermissionNeededDialog(onContinue)
    }

    /**
     * Show loading dialog while checking permission status
     */
    fun showLoadingDialog(): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_permission_loading)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)
        dialog.show()
        return dialog
    }

    /**
     * Show permission rationale dialog before requesting permissions
     * "Please verify your geographical location to ensure you are qualified to apply for this job opportunity."
     */
    fun showPermissionRationaleDialog(onOkay: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_permission_rationale)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val okayButton = dialog.findViewById<Button>(R.id.okayButton)

        okayButton.setOnClickListener {
            dialog.dismiss()
            // Wait for dismiss animation to complete before calling onOkay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onOkay()
            }, 200) // 200ms delay for smooth transition
        }

        dialog.show()
    }
}

// Request code for GPS resolution (used with deprecated onActivityResult fallback)
const val REQUEST_CODE_GPS = 3001

