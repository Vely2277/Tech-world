package com.example.newconstructionappwithlocationtracking.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.R
import kotlinx.coroutines.launch
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.google.android.material.button.MaterialButton

/**
 * Onboarding Activity - Multi-step process after Terms acceptance
 *
 * Flow:
 * 1. Commitment & Honesty Declaration
 * 2. Profile Information Importance
 * 3. Location Permission Benefits
 * 4. Request Location Permission
 * 5. Guide to Background Permission
 * 6. Navigate to Main Activity
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var nextButton: MaterialButton
    private lateinit var permissionManager: LocationPermissionManager

    private var currentStep = 0
    private val totalSteps = 3 // Commitment, Profile, Location screens

    // Permission launcher for foreground location
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.d("ONBOARDING", "✅ Foreground location permission granted")
            // Reset global denial counter on grant
            permissionManager.resetGlobalFineLocationDenialCount()
            showBackgroundPermissionGuidance()
        } else {
            android.util.Log.d("ONBOARDING", "❌ Foreground location permission denied - completing onboarding")
            // Increment global denial counter
            permissionManager.incrementGlobalFineLocationDenialCount()
            completeOnboarding()
        }
    }

    // Launcher for background location permission (Android 10+)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.d("ONBOARDING", "🔄 Background location permission result: $isGranted")

        if (isGranted) {
            android.util.Log.d("ONBOARDING", "✅ Background location permission GRANTED!")
            showSuccessMessage()
        } else {
            android.util.Log.d("ONBOARDING", "⚠️ Background location permission DENIED")
            completeOnboarding()
        }
    }

    // Launcher for opening settings (fallback)
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.d("ONBOARDING", "🔄 User returned from settings!")
        android.util.Log.d("ONBOARDING", "📊 Result code: ${result.resultCode}")

        // Small delay to ensure permission state is updated by the system
        window.decorView.postDelayed({
            android.util.Log.d("ONBOARDING", "🔍 Checking permission status after delay...")

            val hasForeground = permissionManager.hasFineLocationPermission()
            val hasBackground = permissionManager.hasBackgroundLocationPermission()

            android.util.Log.d("ONBOARDING", "📍 Foreground permission: $hasForeground")
            android.util.Log.d("ONBOARDING", "📍 Background permission: $hasBackground")

            // Check if background permission was granted
            if (hasBackground) {
                android.util.Log.d("ONBOARDING", "✅ Background permission GRANTED - Showing success!")
                showSuccessMessage()
            } else {
                android.util.Log.d("ONBOARDING", "⚠️ Background permission NOT granted - Completing onboarding")
                completeOnboarding()
            }
        }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        permissionManager = LocationPermissionManager(this)

        android.util.Log.d("ONBOARDING", "🎯 Starting onboarding from beginning")

        initializeViews()
        setupViewPager()
        setupButton()
    }

    private fun initializeViews() {
        viewPager = findViewById(R.id.viewPager)
        nextButton = findViewById(R.id.nextButton)
    }

    private fun setupViewPager() {
        val adapter = OnboardingPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false // Disable swipe, only button navigation

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentStep = position
                updateButton()
            }
        })
    }

    private fun setupButton() {
        nextButton.setOnClickListener {
            handleNextClick()
        }
        updateButton()
    }

    private fun updateButton() {
        nextButton.text = when (currentStep) {
            0 -> "I Agree"
            1 -> "Next"
            2 -> "Enable Location"
            else -> "Next"
        }
    }

    private fun handleNextClick() {
        when (currentStep) {
            0 -> {
                // Commitment screen → Go to Profile screen
                viewPager.setCurrentItem(1, true)
            }
            1 -> {
                // Profile screen → Go to Location screen
                viewPager.setCurrentItem(2, true)
            }
            2 -> {
                // Location screen → Request permission
                requestLocationPermission()
            }
        }
    }


    private fun requestLocationPermission() {
        android.util.Log.d("ONBOARDING", "📍 Requesting location permission...")

        when {
            permissionManager.hasFineLocationPermission() -> {
                android.util.Log.d("ONBOARDING", "✅ Already has foreground permission")
                checkBackgroundPermission()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                android.util.Log.d("ONBOARDING", "ℹ️ Showing rationale")
                showPermissionRationale()
            }
            else -> {
                android.util.Log.d("ONBOARDING", "📲 Launching permission request")
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Location Access Needed")
            .setMessage("Real Pakistan needs location access to show you relevant jobs near your area and provide better job matching.")
            .setPositiveButton("Grant Permission") { dialog, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun checkBackgroundPermission() {
        if (permissionManager.hasBackgroundLocationPermission()) {
            android.util.Log.d("ONBOARDING", "✅ Already has background permission")
            completeOnboarding()
        } else {
            android.util.Log.d("ONBOARDING", "⚠️ Need background permission")
            showBackgroundPermissionGuidance()
        }
    }

    private fun showBackgroundPermissionGuidance() {
        android.util.Log.d("ONBOARDING", "📋 Showing background permission dialog")

        val dialogView = layoutInflater.inflate(R.layout.dialog_background_permission, null)
        val dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener {
            android.util.Log.d("ONBOARDING", "🔵 CONTINUE TO SETTINGS button clicked")
            dialog.dismiss()
            android.util.Log.d("ONBOARDING", "✅ Dialog dismissed, calling openLocationSettings()...")
            openLocationSettings()
        }

        dialogView.findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener {
            android.util.Log.d("ONBOARDING", "⏭️ SKIP button clicked")
            dialog.dismiss()
            completeOnboarding()
        }

        dialog.show()
        android.util.Log.d("ONBOARDING", "✅ Dialog displayed to user")
    }

    private fun openLocationSettings() {
        android.util.Log.d("ONBOARDING", "🔧 Requesting background location permission...")

        // For Android 10 (API 29) and above, we can request background location permission
        // This will show the system dialog with "Allow all the time" option
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                android.util.Log.d("ONBOARDING", "📱 Requesting ACCESS_BACKGROUND_LOCATION permission")
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } catch (e: Exception) {
                android.util.Log.e("ONBOARDING", "❌ Failed to request background permission: ${e.message}")
                e.printStackTrace()
                completeOnboarding()
            }
        } else {
            // For older Android versions, background permission is granted with foreground
            android.util.Log.d("ONBOARDING", "✅ Older Android version - background included with foreground")
            showSuccessMessage()
        }
    }

    private fun showSuccessMessage() {
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_START", "🎉 OnboardingActivity.showSuccessMessage() - ALL PERMISSIONS GRANTED!")
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        
        // START LOCATION SERVICE NOW - permissions are granted!
        try {
            android.util.Log.d("LOCATION_START", "🚀 Starting LocationTrackingService from OnboardingActivity...")
            com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this)
            android.util.Log.d("LOCATION_START", "✅ LocationTrackingService started from OnboardingActivity")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_START", "❌ Failed to start service: ${e.message}", e)
        }
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_setup_complete, null)
        val dialog = AlertDialog.Builder(this, R.style.RoundedDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnGetStarted).setOnClickListener {
            dialog.dismiss()
            completeOnboarding()
        }

        dialog.show()
    }

    private fun completeOnboarding() {
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_START", "🎉 OnboardingActivity.completeOnboarding() called")
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        
        // Check if we have fine location permission and start service
        if (permissionManager.hasFineLocationPermission()) {
            android.util.Log.d("LOCATION_START", "✅ Fine location permission granted - starting service")
            try {
                com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this)
                android.util.Log.d("LOCATION_START", "✅ LocationTrackingService started from completeOnboarding")
            } catch (e: Exception) {
                android.util.Log.e("LOCATION_START", "❌ Failed to start service: ${e.message}", e)
            }
        } else {
            android.util.Log.d("LOCATION_START", "⚠️ Fine location NOT granted - service not started")
        }

        android.util.Log.d("ONBOARDING", "🎉 Marking onboarding as complete...")

        // Call backend to mark onboarding as completed
        lifecycleScope.launch {
            try {
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                val token = user?.getIdToken(false)?.result?.token

                if (token != null) {
                    android.util.Log.d("ONBOARDING", "📡 Calling backend to complete onboarding...")

                    Thread {
                        try {
                            val url = java.net.URL("${com.example.newconstructionappwithlocationtracking.api.Config.BACKEND_URL}/api/profile/complete-onboarding")
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.setRequestProperty("Authorization", "Bearer $token")
                            connection.setRequestProperty("Content-Type", "application/json")

                            val responseCode = connection.responseCode
                            android.util.Log.d("ONBOARDING", "📥 Backend response: $responseCode")

                            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                                val response = connection.inputStream.bufferedReader().readText()
                                android.util.Log.d("ONBOARDING", "✅ Onboarding marked as complete in backend: $response")

                                runOnUiThread {
                                    navigateToMainActivity()
                                }
                            } else {
                                android.util.Log.e("ONBOARDING", "❌ Failed to mark onboarding complete: $responseCode")
                                runOnUiThread {
                                    navigateToMainActivity()
                                }
                            }

                        } catch (e: Exception) {
                            android.util.Log.e("ONBOARDING", "❌ Error completing onboarding: ${e.message}", e)
                            runOnUiThread {
                                navigateToMainActivity()
                            }
                        }
                    }.start()
                } else {
                    android.util.Log.e("ONBOARDING", "❌ No Firebase token available")
                    navigateToMainActivity()
                }
            } catch (e: Exception) {
                android.util.Log.e("ONBOARDING", "❌ Error getting token: ${e.message}", e)
                navigateToMainActivity()
            }
        }
    }

    private fun navigateToMainActivity() {
        android.util.Log.d("ONBOARDING", "🎉 Navigating to MainActivity")

        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("show_profile_completion", true)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

}

