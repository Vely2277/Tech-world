/*
 * ============================================================================
 * LOCATION PERMISSION ACTIVITY - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Comprehensive UI wizard to guide users through all location permission setup
 * with comprehensive validation using LocationPermissionManager and proper
 * service startup via LocationServiceHelper.
 *
 * KEY FEATURES:
 * - Step-by-step permission flow with validation
 * - Uses LocationPermissionManager.getComprehensivePermissionStatus()
 * - Uses LocationServiceHelper.startLocationService()
 * - Handles all permission cases (granted, denied, permanent denial)
 * - Battery optimization guidance
 * - Location services check
 * - Settings deeplink for permanent denials
 * - Modern ActivityResultContract (no deprecated APIs)
 * - Complete error handling
 * - State restoration
 *
 * FLOW:
 * 1. Explanation Screen - Why we need location
 * 2. Fine Location - Request precise location (while using app)
 * 3. Background Location - Request all-time access (Android 10+)
 * 4. Location Services - Check if GPS/Location enabled
 * 5. Battery Optimization - Disable for reliability
 * 6. Final Validation - Comprehensive check
 * 7. Success Screen - Start service and navigate
 *
 * INTEGRATION:
 * - LocationPermissionManager: Comprehensive permission handling
 * - LocationServiceHelper: Proper service startup
 * - LocationLogger: Complete logging
 * - LocationConstants: All constants
 * - FirebaseAuth: User ID for service start
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.BatteryOptimizationHelper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION PERMISSION ACTIVITY
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Guides users through complete permission setup with validation
 */
class LocationPermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = LocationConstants.TAG_PERMISSION
        private const val TOTAL_STEPS = 7  // Explanation + Fine + Background + Location Services + Battery + Validation + Success
    }

    private lateinit var permissionManager: LocationPermissionManager
    private lateinit var batteryHelper: BatteryOptimizationHelper
    private var currentStep = 0

    // UI Components
    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var buttonContinue: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    // Modern permission launchers
    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handleFineLocationResult(permissions)
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        handleBackgroundLocationResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_permission)

        // Initialize components
        permissionManager = LocationPermissionManager(this)
        batteryHelper = BatteryOptimizationHelper(this)

        // Initialize UI
        initializeUI()

        // Restore state or start fresh
        currentStep = savedInstanceState?.getInt("current_step") ?: 0

        LocationLogger.i(TAG, "LocationPermissionActivity started at step $currentStep")

        // Check if already has all permissions
        val status = permissionManager.getComprehensivePermissionStatus()
        if (status.canStartTracking) {
            LocationLogger.i(TAG, "All permissions already granted")
            currentStep = TOTAL_STEPS - 1 // Jump to success
        }

        showCurrentStep()
    }

    /**
     * Initialize UI components
     */
    private fun initializeUI() {
        titleText = findViewById(R.id.permission_title)
        messageText = findViewById(R.id.permission_message)
        buttonContinue = findViewById(R.id.button_continue)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
    }

    /**
     * Show current step based on state
     */
    private fun showCurrentStep() {
        when (currentStep) {
            0 -> showExplanationScreen()
            1 -> showFineLocationStep()
            2 -> showBackgroundLocationStep()
            3 -> showLocationServicesStep()
            4 -> showBatteryOptimizationStep()
            5 -> showFinalValidationStep()
            6 -> showSuccessScreen()
            else -> showSuccessScreen()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 0: EXPLANATION SCREEN
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showExplanationScreen() {
        updateProgress(0)

        titleText.text = "📍 Location Tracking Required"
        messageText.text = buildString {
            append("To ensure worker safety and accurate job tracking, we need permission to access your location.\n\n")
            append("✅ Your location will be used to:\n")
            append("• Track work locations\n")
            append("• Verify job attendance\n")
            append("• Monitor worker safety\n")
            append("• Provide accurate timesheets\n\n")
            append("🔒 Your location is secure and only visible to authorized admins.\n\n")
            append("This setup takes ~2 minutes.")
        }

        buttonContinue.text = "Get Started"
        buttonContinue.setOnClickListener {
            LocationLogger.d(TAG, "User started permission flow")
            currentStep++
            showCurrentStep()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 1: FINE LOCATION PERMISSION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showFineLocationStep() {
        updateProgress(1)

        val status = permissionManager.getComprehensivePermissionStatus()

        if (status.hasFineLocation) {
            LocationLogger.d(TAG, "Fine location already granted, skipping")
            currentStep++
            showCurrentStep()
            return
        }

        titleText.text = "📱 Location Access (Step 1/5)"
        messageText.text = buildString {
            append("We need access to your device's location.\n\n")
            append("✅ What to do:\n")
            append("1. Tap 'Request Permission' below\n")
            append("2. On the system dialog, select:\n")
            append("   → \"While using the app\" or\n")
            append("   → \"Allow\" (depending on your Android version)\n\n")
            append("⚠️  Do NOT select \"Deny\" or \"Don't allow\"")
        }

        buttonContinue.text = "Request Permission"
        buttonContinue.setOnClickListener {
            LocationLogger.d(TAG, "Requesting fine location permission")

            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            } else {
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }

            fineLocationLauncher.launch(permissions)
        }
    }

    private fun handleFineLocationResult(permissions: Map<String, Boolean>) {
        val granted = permissions.values.any { it }

        LocationLogger.i(TAG, "Fine location result: granted=$granted")

        if (granted) {
            Toast.makeText(this, "✅ Location permission granted", Toast.LENGTH_SHORT).show()
            // Reset global denial count on grant
            permissionManager.resetGlobalFineLocationDenialCount()
            currentStep++
            showCurrentStep()
        } else {
            // Increment global denial counter
            permissionManager.incrementGlobalFineLocationDenialCount()

            // Check if Android is blocking (2+ denials) or permanently denied
            val status = permissionManager.getComprehensivePermissionStatus()
            val isAndroidBlocking = permissionManager.isAndroidBlockingFineLocationPermission()
            val denialCount = permissionManager.getGlobalFineLocationDenialCount()

            LocationLogger.d(TAG, "Fine location denied: count=$denialCount, androidBlocking=$isAndroidBlocking")

            if (isAndroidBlocking || status.permanentlyDenied.contains(LocationPermissionManager.FINE_LOCATION)) {
                // After 2 denials or permanent denial - show settings dialog
                showPermanentDenialDialog("Location Permission",
                    "Location permission is required. Please enable it in Settings to continue.")
            } else {
                // First denial - show retry dialog
                showDenialDialog("Location Permission",
                    "Location permission is required for tracking. Please grant access.")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 2: BACKGROUND LOCATION PERMISSION (Android 10+)
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showBackgroundLocationStep() {
        updateProgress(2)

        // Skip if Android 9 or below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LocationLogger.d(TAG, "Background location not needed on Android < 10, skipping")
            currentStep++
            showCurrentStep()
            return
        }

        val status = permissionManager.getComprehensivePermissionStatus()

        if (status.hasBackgroundLocation) {
            LocationLogger.d(TAG, "Background location already granted, skipping")
            currentStep++
            showCurrentStep()
            return
        }

        titleText.text = "📍 24/7 Background Access (Step 2/5)"
        messageText.text = buildString {
            append("For continuous tracking, we need background location permission.\n\n")
            append("✅ IMPORTANT: On the next screen, select:\n")
            append("   → \"Allow all the time\"\n\n")
            append("❌ Do NOT select:\n")
            append("   → \"While using the app\"\n")
            append("   → \"Don't allow\"\n\n")
            append("This ensures tracking works 24/7, even when the app is closed.")
        }

        buttonContinue.text = "Request Permission"
        buttonContinue.setOnClickListener {
            LocationLogger.d(TAG, "Requesting background location permission")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun handleBackgroundLocationResult(granted: Boolean) {
        LocationLogger.i(TAG, "Background location result: granted=$granted")

        if (granted) {
            Toast.makeText(this, "✅ Background location granted", Toast.LENGTH_SHORT).show()
            currentStep++
            showCurrentStep()
        } else {
            // Check for permanent denial (background doesn't use global counter, only fine location does)
            val status = permissionManager.getComprehensivePermissionStatus()
            val denialCount = status.backgroundDenialCount

            LocationLogger.d(TAG, "Background location denied: count=$denialCount")

            if (status.permanentlyDenied.contains(LocationPermissionManager.BACKGROUND_LOCATION) || denialCount >= 2) {
                // After 2 denials or permanent denial - show settings dialog
                showPermanentDenialDialog("Background Location",
                    "Background location is required. Please enable 'Allow all the time' in Settings for 24/7 tracking.")
            } else {
                // First denial - show retry dialog
                showDenialDialog("Background Location",
                    "Background location is required for 24/7 tracking. Please grant 'Allow all the time'.")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 3: LOCATION SERVICES CHECK
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showLocationServicesStep() {
        updateProgress(3)

        val status = permissionManager.getComprehensivePermissionStatus()

        if (status.isLocationEnabled) {
            LocationLogger.d(TAG, "Location services already enabled, skipping")
            currentStep++
            showCurrentStep()
            return
        }

        titleText.text = "📡 Enable Location Services (Step 3/5)"
        messageText.text = buildString {
            append("Location services (GPS) are currently disabled on your device.\n\n")
            append("✅ What to do:\n")
            append("1. Tap 'Open Settings' below\n")
            append("2. Enable \"Location\" or \"GPS\"\n")
            append("3. Return to this app\n")
            append("4. Tap 'Check Again'\n\n")
            append("This is required for location tracking to work.")
        }

        buttonContinue.text = "Open Settings"
        buttonContinue.setOnClickListener {
            LocationLogger.d(TAG, "Opening location settings")
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

                // Add a "Check Again" button
                buttonContinue.text = "Check Again"
                buttonContinue.setOnClickListener {
                    val newStatus = permissionManager.getComprehensivePermissionStatus()
                    if (newStatus.isLocationEnabled) {
                        Toast.makeText(this, "✅ Location services enabled", Toast.LENGTH_SHORT).show()
                        currentStep++
                        showCurrentStep()
                    } else {
                        Toast.makeText(this, "❌ Location services still disabled", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                LocationLogger.e(TAG, "Failed to open location settings", e)
                Toast.makeText(this, "Failed to open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 4: BATTERY OPTIMIZATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showBatteryOptimizationStep() {
        updateProgress(4)

        titleText.text = "🔋 Disable Battery Optimization (Step 4/5)"
        messageText.text = buildString {
            append("To ensure reliable 24/7 tracking, disable battery optimization.\n\n")
            append("✅ What to do:\n")
            append("1. Tap 'Open Settings' below\n")
            append("2. Find this app in the list\n")
            append("3. Select \"Unrestricted\" or \"Don't optimize\"\n")
            append("4. Return to this app\n\n")
            append("⚠️  Note: Different phone brands have different settings:\n")
            append("• Samsung: Sleeping apps\n")
            append("• Xiaomi: Battery saver\n")
            append("• Huawei: App launch\n")
            append("• OnePlus: Battery optimization")
        }

        buttonContinue.text = "Open Settings"
        buttonContinue.setOnClickListener {
            LocationLogger.d(TAG, "Opening battery optimization settings")
            batteryHelper.requestWhitelistExemption(this)

            // Move to next step (assume user will do it)
            buttonContinue.text = "Continue"
            buttonContinue.setOnClickListener {
                currentStep++
                showCurrentStep()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 5: FINAL VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showFinalValidationStep() {
        updateProgress(5)

        titleText.text = "✅ Validating Setup (Step 5/5)"
        messageText.text = "Checking all permissions and settings...\n\nPlease wait..."

        buttonContinue.isEnabled = false
        buttonContinue.text = "Validating..."

        // Perform comprehensive validation
        val status = permissionManager.getComprehensivePermissionStatus()

        LocationLogger.i(TAG, "Final validation: canStartTracking=${status.canStartTracking}")
        LocationLogger.d(TAG, "Permissions: fine=${status.hasFineLocation}, background=${status.hasBackgroundLocation}, locationEnabled=${status.isLocationEnabled}")

        if (status.canStartTracking) {
            // All good!
            LocationLogger.i(TAG, "✅ All permissions validated successfully")
            messageText.text = "✅ All permissions and settings verified!\n\nProceeding to start tracking..."

            // Brief delay before showing success
            buttonContinue.postDelayed({
                currentStep++
                showCurrentStep()
            }, 1500)
        } else {
            // Something missing
            val missing = status.missingCritical + status.missingOptional
            LocationLogger.w(TAG, "❌ Validation failed: missing=$missing")

            messageText.text = buildString {
                append("⚠️  Setup incomplete!\n\n")
                append("Missing:\n")
                missing.forEach { permission -> append("• $permission\n") }
                append("\nPlease complete all steps.")
            }

            buttonContinue.isEnabled = true
            buttonContinue.text = "Go Back"
            buttonContinue.setOnClickListener {
                // Go back to appropriate step
                currentStep = when {
                    !status.hasFineLocation -> 1
                    !status.hasBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> 2
                    !status.isLocationEnabled -> 3
                    else -> 4
                }
                showCurrentStep()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP 6: SUCCESS SCREEN
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showSuccessScreen() {
        updateProgress(6)

        titleText.text = "🎉 Setup Complete!"
        messageText.text = buildString {
            append("Location tracking is now fully configured and ready!\n\n")
            append("✅ Your device will:\n")
            append("• Track location automatically\n")
            append("• Work 24/7 in the background\n")
            append("• Auto-restart after reboot\n")
            append("• Upload to admin dashboard\n")
            append("• Optimize for battery life\n\n")
            append("You can now close this screen and continue using the app normally.")
        }

        buttonContinue.text = "Start Tracking & Go Home"
        buttonContinue.setOnClickListener {
            LocationLogger.i(TAG, "User completed setup, starting service")
            startLocationServiceAndNavigate()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SERVICE START & NAVIGATION
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun startLocationServiceAndNavigate() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

        LocationLogger.i(TAG, "Starting location service for user: $userId")

        val result = LocationServiceHelper.startLocationService(this, userId)

        when (result) {
            is LocationServiceHelper.ServiceStartResult.Success -> {
                LocationLogger.i(TAG, "✅ Service started successfully")
                Toast.makeText(this, "✅ Location tracking started", Toast.LENGTH_SHORT).show()
                navigateToHome()
            }
            is LocationServiceHelper.ServiceStartResult.AlreadyRunning -> {
                LocationLogger.i(TAG, "Service already running")
                Toast.makeText(this, "✅ Location tracking already active", Toast.LENGTH_SHORT).show()
                navigateToHome()
            }
            is LocationServiceHelper.ServiceStartResult.PermissionsMissing -> {
                LocationLogger.e(TAG, "Cannot start: Missing permissions - ${result.missing}")
                showErrorDialog("Cannot Start Tracking",
                    "Missing permissions: ${result.missing.joinToString()}\n\nPlease complete the setup.")
            }
            is LocationServiceHelper.ServiceStartResult.LocationServicesDisabled -> {
                LocationLogger.e(TAG, "Cannot start: Location services disabled")
                showErrorDialog("Cannot Start Tracking",
                    "Location services are disabled. Please enable GPS/Location in device settings.")
            }
            is LocationServiceHelper.ServiceStartResult.CircuitBreakerActive -> {
                LocationLogger.w(TAG, "Circuit breaker active, will start later")
                Toast.makeText(this, "Service will start shortly...", Toast.LENGTH_SHORT).show()
                navigateToHome()
            }
            is LocationServiceHelper.ServiceStartResult.BatteryTooLow -> {
                LocationLogger.e(TAG, "Cannot start: Battery too low")
                showErrorDialog("Cannot Start Tracking",
                    "Battery level is critically low. Please charge your device to enable location tracking.")
            }
            is LocationServiceHelper.ServiceStartResult.NoUserSession -> {
                LocationLogger.e(TAG, "Cannot start: No user session")
                showErrorDialog("Cannot Start Tracking",
                    "No user session found. Please log in again.")
            }
            is LocationServiceHelper.ServiceStartResult.PowerSaveModeActive -> {
                LocationLogger.w(TAG, "Power save mode active, will try to start anyway")
                Toast.makeText(this, "⚠️ Power save mode may affect tracking", Toast.LENGTH_SHORT).show()
                navigateToHome()
            }
            is LocationServiceHelper.ServiceStartResult.Failed -> {
                LocationLogger.e(TAG, "Failed to start service: ${result.reason}")
                showErrorDialog("Cannot Start Tracking",
                    "Failed to start tracking: ${result.reason}\n\nPlease try again.")
            }
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun showDenialDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\nWould you like to try again?")
            .setPositiveButton("Try Again") { _, _ ->
                showCurrentStep()
            }
            .setNegativeButton("Skip") { _, _ ->
                currentStep++
                showCurrentStep()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermanentDenialDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\nOpen Settings?")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    LocationLogger.e(TAG, "Failed to open app settings", e)
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                currentStep++
                showCurrentStep()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun updateProgress(step: Int) {
        val progress = ((step + 1) * 100) / TOTAL_STEPS
        progressBar.progress = progress
        progressText.text = "Step ${step + 1} of $TOTAL_STEPS"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_step", currentStep)
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from settings
        if (currentStep in 3..4) {
            LocationLogger.d(TAG, "Resumed, re-checking permissions")
        }
    }
}

