package com.example.newconstructionappwithlocationtracking.utils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.services.FCMNotificationService

/**
 * Handles notification permission requests with a beautiful dialog
 */
class NotificationPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "NOTIFICATION_PERMISSION"
        private const val PREFS_NAME = "notification_permission_prefs"
        private const val KEY_PERMISSION_ASKED = "permission_asked"
        private const val KEY_PERMISSION_GRANTED = "permission_granted"
        private const val KEY_DONT_ASK_AGAIN = "dont_ask_again"
        const val REQUEST_CODE_NOTIFICATION = 1001
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if notification permission is granted
     */
    fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android 12 and below, notifications are enabled by default
            true
        }
    }

    /**
     * Check if we should show the permission dialog
     */
    fun shouldShowPermissionDialog(): Boolean {
        if (isNotificationPermissionGranted()) {
            return false
        }

        // Don't show if user selected "Don't ask again"
        if (sharedPrefs.getBoolean(KEY_DONT_ASK_AGAIN, false)) {
            return false
        }

        return true
    }

    /**
     * Mark that permission dialog has been shown
     */
    fun markPermissionAsked() {
        sharedPrefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    /**
     * Mark permission as granted
     */
    fun markPermissionGranted() {
        sharedPrefs.edit().putBoolean(KEY_PERMISSION_GRANTED, true).apply()
        // Register FCM token when permission is granted
        FCMNotificationService.registerTokenWithBackend(context)
    }

    /**
     * Show the beautiful notification permission dialog
     */
    fun showNotificationPermissionDialog(
        activity: Activity,
        onGranted: () -> Unit,
        onDenied: () -> Unit = {}
    ) {
        if (!shouldShowPermissionDialog()) {
            if (isNotificationPermissionGranted()) {
                onGranted()
            }
            return
        }

        Log.d(TAG, "📱 Showing notification permission dialog")

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_notification_permission, null)

        val dialog = AlertDialog.Builder(activity, R.style.RoundedDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnAllow = dialogView.findViewById<Button>(R.id.btnAllowNotifications)
        val btnNotNow = dialogView.findViewById<Button>(R.id.btnNotNow)

        btnAllow.setOnClickListener {
            dialog.dismiss()
            markPermissionAsked()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            } else {
                // For older Android versions, permission is granted by default
                markPermissionGranted()
                onGranted()
            }
        }

        btnNotNow.setOnClickListener {
            dialog.dismiss()
            markPermissionAsked()
            onDenied()
        }

        dialog.show()
    }

    /**
     * Show the notification permission dialog from a Fragment
     */
    fun showNotificationPermissionDialog(
        fragment: Fragment,
        permissionLauncher: ActivityResultLauncher<String>,
        onGranted: () -> Unit = {},
        onDenied: () -> Unit = {}
    ) {
        val activity = fragment.requireActivity()

        if (!shouldShowPermissionDialog()) {
            if (isNotificationPermissionGranted()) {
                onGranted()
            }
            return
        }

        Log.d(TAG, "📱 Showing notification permission dialog from fragment")

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_notification_permission, null)

        val dialog = AlertDialog.Builder(activity, R.style.RoundedDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnAllow = dialogView.findViewById<Button>(R.id.btnAllowNotifications)
        val btnNotNow = dialogView.findViewById<Button>(R.id.btnNotNow)

        btnAllow.setOnClickListener {
            dialog.dismiss()
            markPermissionAsked()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // For older Android versions, permission is granted by default
                markPermissionGranted()
                onGranted()
            }
        }

        btnNotNow.setOnClickListener {
            dialog.dismiss()
            markPermissionAsked()
            onDenied()
        }

        dialog.show()
    }

    /**
     * Handle permission result
     */
    fun handlePermissionResult(isGranted: Boolean, onGranted: () -> Unit = {}, onDenied: () -> Unit = {}) {
        if (isGranted) {
            Log.d(TAG, "✅ Notification permission GRANTED")
            markPermissionGranted()
            FCMNotificationService.registerTokenWithBackend(context)
            onGranted()
        } else {
            Log.d(TAG, "❌ Notification permission DENIED")
            onDenied()
        }
    }
}

