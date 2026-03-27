/*
 * ============================================================================
 * LOCATION TRACKING INITIALIZER - CONTENT PROVIDER
 * ============================================================================
 *
 * PURPOSE:
 * ContentProvider that initializes location tracking at the earliest possible
 * moment in the app lifecycle. ContentProviders are initialized BEFORE the
 * Application class, making this the most reliable initialization point.
 *
 * WHY CONTENT PROVIDER:
 * - Runs automatically when the app process starts (any entry point)
 * - Runs BEFORE Application.onCreate()
 * - Runs when triggered by broadcast receivers (like BOOT_COMPLETED)
 * - No need for manual initialization
 *
 * INITIALIZATION SEQUENCE:
 * 1. ContentProvider.onCreate() [EARLIEST - this file]
 * 2. Application.onCreate()
 * 3. Activity.onCreate() / Service.onCreate() / BroadcastReceiver.onReceive()
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.LocationServiceHelper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Auto-initializing ContentProvider for location tracking.
 * This runs at the earliest possible moment when the app process starts.
 */
class LocationTrackingInitProvider : ContentProvider() {

    companion object {
        private const val TAG = "LocationInitProvider"
        private const val WORK_NAME_INIT_CHECK = "location_init_check"

        @Volatile
        private var isInitialized = false
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "🚀 LocationTrackingInitProvider.onCreate() - EARLIEST INIT POINT")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        val ctx = context ?: return true

        try {
            // Initialize Firebase first
            FirebaseApp.initializeApp(ctx)
            Log.d(TAG, "✅ Firebase initialized")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Firebase init: ${e.message}")
        }

        // Schedule immediate tracking check via WorkManager
        // Using WorkManager because we can't do long operations in ContentProvider.onCreate()
        scheduleImmediateCheck(ctx)

        isInitialized = true
        Log.i(TAG, "✅ LocationTrackingInitProvider initialized")

        return true
    }

    /**
     * Schedule an immediate check for location tracking.
     * Uses WorkManager with 0 delay to run as soon as possible after init.
     */
    private fun scheduleImmediateCheck(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<LocationInitCheckWorker>()
                .setConstraints(constraints)
                .setInitialDelay(3, TimeUnit.SECONDS) // Small delay to let system stabilize
                .addTag(WORK_NAME_INIT_CHECK)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_INIT_CHECK,
                    ExistingWorkPolicy.KEEP, // Don't replace if already scheduled
                    workRequest
                )

            Log.d(TAG, "📅 Immediate tracking check scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule init check: ${e.message}")
        }
    }

    // ContentProvider required methods (we don't actually provide content)
    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                      selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                       selectionArgs: Array<String>?): Int = 0
}

/**
 * Worker that checks if location tracking should be started.
 * Runs immediately after app process starts.
 */
class LocationInitCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "LocationInitCheck"
    }

    override fun doWork(): Result {
        Log.d(TAG, "═══════════════════════════════════════════════════════════════")
        Log.d(TAG, "🔍 LocationInitCheckWorker running...")
        Log.d(TAG, "═══════════════════════════════════════════════════════════════")

        return try {
            // Check if tracking should be running
            val prefs = applicationContext.getSharedPreferences(
                LocationConstants.PREFS_NAME_SERVICE_STATE,
                Context.MODE_PRIVATE
            )

            // 1. Check tracking enabled
            val trackingEnabled = prefs.getBoolean(LocationConstants.KEY_TRACKING_ENABLED, true)
            Log.d(TAG, "   tracking_enabled: $trackingEnabled")

            if (!trackingEnabled) {
                Log.d(TAG, "   ℹ️ Tracking disabled by user")
                return Result.success()
            }

            // 2. Check for user session
            val savedUserId = prefs.getString("saved_user_id", null)
            val firebaseUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                Log.e(TAG, "   Firebase not ready: ${e.message}")
                null
            }
            val userId = firebaseUser?.uid ?: savedUserId

            Log.d(TAG, "   saved_user_id: $savedUserId")
            Log.d(TAG, "   firebase_user: ${firebaseUser?.uid}")
            Log.d(TAG, "   effective_user_id: $userId")

            if (userId == null) {
                Log.d(TAG, "   ℹ️ No user session available")
                return Result.success()
            }

            // 3. Check permissions
            val permissionManager = LocationPermissionManager(applicationContext)
            val permissionStatus = permissionManager.getComprehensivePermissionStatus()

            Log.d(TAG, "   hasFineLocation: ${permissionStatus.hasFineLocation}")
            Log.d(TAG, "   hasBackgroundLocation: ${permissionStatus.hasBackgroundLocation}")
            Log.d(TAG, "   isLocationEnabled: ${permissionStatus.isLocationEnabled}")
            Log.d(TAG, "   canStartTracking: ${permissionStatus.canStartTracking}")

            if (!permissionStatus.canStartTracking) {
                Log.d(TAG, "   ℹ️ Missing required permissions")
                return Result.success()
            }

            if (!permissionStatus.isLocationEnabled) {
                Log.d(TAG, "   ℹ️ Location services disabled")
                return Result.success()
            }

            // 4. Check if already running
            if (LocationServiceHelper.isServiceRunning(applicationContext)) {
                Log.d(TAG, "   ✅ Service already running")
                return Result.success()
            }

            // 5. ALL CONDITIONS MET - START TRACKING!
            Log.i(TAG, "═══════════════════════════════════════════════════════════════")
            Log.i(TAG, "🚀 ALL CONDITIONS MET - STARTING LOCATION TRACKING!")
            Log.i(TAG, "═══════════════════════════════════════════════════════════════")

            val result = LocationServiceHelper.startLocationService(applicationContext, userId)
            Log.i(TAG, "📊 Service start result: $result")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Init check failed: ${e.message}")
            e.printStackTrace()
            Result.retry()
        }
    }
}

