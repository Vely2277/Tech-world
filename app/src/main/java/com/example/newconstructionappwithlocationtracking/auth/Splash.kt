package com.example.newconstructionappwithlocationtracking.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.api.Config
import com.example.newconstructionappwithlocationtracking.services.LocationTrackingService

class Splash : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash)

        Config.init(this)

        Handler(Looper.getMainLooper()).postDelayed({
            checkLoginStatus()
        }, 2000) // 2 seconds delay for splash screen
    }

    private fun checkLoginStatus() {
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_START", "🔍 Splash.checkLoginStatus()")
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")

        // Check Firebase auth AND UI login flag
        // Firebase stays signed in even after UI logout (for background tracking)
        // So we also check is_logged_in to determine UI routing
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val userPrefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val isUiLoggedIn = userPrefs.getBoolean("is_logged_in", false)
        val isLoggedIn = currentUser != null && isUiLoggedIn

        android.util.Log.d("LOCATION_START", "   Firebase User: ${currentUser?.uid}")
        android.util.Log.d("LOCATION_START", "   UI Logged In: $isUiLoggedIn")
        android.util.Log.d("LOCATION_START", "   Final Decision: $isLoggedIn")

        if (isLoggedIn) {
            // User IS logged in → Go to MainActivity
            android.util.Log.d("LOCATION_START", "✅ User logged in - checking permissions...")

            // Only start location service if permissions are already granted
            val hasPerms = hasLocationPermissions()
            if (hasPerms) {
                android.util.Log.d("LOCATION_START", "✅ All permissions granted - starting location service from Splash")
                startLocationTrackingService()
            } else {
                android.util.Log.d("LOCATION_START", "⚠️ Permissions NOT granted - service will start later")
            }

            android.util.Log.d("LOCATION_START", "🏠 Navigating to MainActivity...")
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            // User NOT logged in → Go to Auth screen (Login/Signup)
            android.util.Log.d("LOCATION_START", "❌ User NOT logged in - going to Auth")
            val intent = Intent(this, Auth::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older Android versions
        }

        android.util.Log.d("LOCATION_START", "📍 Permission check in Splash:")
        android.util.Log.d("LOCATION_START", "   Fine Location: $hasFineLocation")
        android.util.Log.d("LOCATION_START", "   Background Location: $hasBackgroundLocation")
        android.util.Log.d("LOCATION_START", "   Result (both required): ${hasFineLocation && hasBackgroundLocation}")

        return hasFineLocation && hasBackgroundLocation
    }

    private fun startLocationTrackingService() {
        android.util.Log.d("LOCATION_START", "🚀 Splash.startLocationTrackingService() called")
        try {
            LocationTrackingService.start(this)
            android.util.Log.d("LOCATION_START", "✅ LocationTrackingService.start() completed from Splash")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_START", "❌ Failed to start location service: ${e.message}", e)
            e.printStackTrace()
        }
    }
}
