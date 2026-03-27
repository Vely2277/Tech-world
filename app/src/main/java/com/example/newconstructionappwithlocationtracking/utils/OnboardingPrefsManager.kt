package com.example.newconstructionappwithlocationtracking.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages onboarding state - specifically tracks if user has been asked
 * for location permission before.
 *
 * CRITICAL: Once hasBeenAskedLocationPermission = true, user goes straight
 * to homepage on future logins (no onboarding, no permission prompts).
 */
class OnboardingPrefsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "onboarding_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_HAS_BEEN_ASKED_LOCATION = "has_been_asked_location_permission"
    }

    /**
     * Check if user has EVER been asked for location permission.
     */
    fun hasBeenAskedLocationPermission(): Boolean {
        return prefs.getBoolean(KEY_HAS_BEEN_ASKED_LOCATION, false)
    }

    /**
     * Mark that user has been asked for location permission.
     * Call this IMMEDIATELY after showing the system permission dialog.
     */
    fun setLocationPermissionAsked() {
        prefs.edit().putBoolean(KEY_HAS_BEEN_ASKED_LOCATION, true).apply()
    }

    /**
     * Reset flag (for testing only).
     */
    fun reset() {
        prefs.edit().clear().apply()
    }
}

