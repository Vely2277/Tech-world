package com.example.newconstructionappwithlocationtracking

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.fragments.HomeFragment
import com.example.newconstructionappwithlocationtracking.fragments.InboxFragment
import com.example.newconstructionappwithlocationtracking.fragments.GigFragment
import com.example.newconstructionappwithlocationtracking.fragments.OrdersFragment
import com.example.newconstructionappwithlocationtracking.fragments.AccountFragment
import com.example.newconstructionappwithlocationtracking.api.Config
import com.example.newconstructionappwithlocationtracking.interfaces.NavigationHost
import com.example.newconstructionappwithlocationtracking.services.LocationTrackingService
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity(), NavigationHost {

    private lateinit var bottomNavigationView: BottomNavigationView

    // GPS dialog helper - shared across all job action permission flows
    // Initialized early so the ActivityResultLauncher is registered before any fragment transaction
    val gpsDialogHelper: com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper by lazy {
        com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register GPS resolution launcher BEFORE setContentView to ensure it's ready
        gpsDialogHelper.registerGpsLauncher(this)

        setContentView(R.layout.activity_main)

        // Initialize Config with context
        Config.init(this)

        bottomNavigationView = findViewById(R.id.bottomNavigation)

        // Check for profile completion trigger from signup
        val showProfileCompletion = intent.getBooleanExtra("show_profile_completion", false)

        // Check for chat opening intent
        val chatTargetUID = intent.getStringExtra("OPEN_CHAT_WITH_UID") ?: intent.getStringExtra("uid")
        val openChat = intent.getBooleanExtra("openChat", false)
        val jobDetails = intent.getStringExtra("jobDetails")

        // Check for notification navigation
        val navigateTo = intent.getStringExtra("navigateTo")
        val notificationChatId = intent.getStringExtra("chatId")
        val notificationSenderId = intent.getStringExtra("senderId")

        // Handle deep links from email buttons
        val deepLinkTarget = handleDeepLink(intent)

        if (showProfileCompletion) {
            // New user signup - navigate directly to ProfileActivity with completion popup
            android.util.Log.d("MainActivity", "🆕 New user signup detected - navigating to ProfileActivity")
            val intent = Intent(this, ProfileActivity::class.java)
            intent.putExtra("show_profile_completion_popup", true)
            startActivity(intent)
        } else if (navigateTo == "chat" && notificationChatId != null) {
            // Navigating from notification to chat
            android.util.Log.d("MainActivity", "📬 Opening chat from notification: $notificationChatId")
            openChatFromNotification(notificationChatId, notificationSenderId, intent.getStringExtra("username"))
        } else if (openChat && intent.getStringExtra("recipientId") != null) {
            // Open chat with job details for job application
            openChatWithJobApplication(
                intent.getStringExtra("recipientId")!!,
                intent.getStringExtra("currentUserId")!!,
                jobDetails
            )
        } else if (chatTargetUID != null) {
            // Open chat directly with the specified user
            openChatWithUser(chatTargetUID)
        } else {
            // No chat target, load home fragment by default
            loadFragment(HomeFragment())
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_inbox -> {
                    loadFragment(InboxFragment())
                    true
                }
                R.id.nav_gig -> {
                    loadFragment(GigFragment())
                    true
                }
                R.id.nav_orders -> {
                    loadFragment(OrdersFragment())
                    true
                }
                R.id.nav_account -> {
                    loadFragment(AccountFragment())
                    true
                }
                else -> false
            }
        }

        // Start location service if permissions are granted
        // Permissions are ONLY requested in OnboardingActivity
        startLocationServiceIfPermissionsGranted()
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * NavigationHost implementation - Navigate to a tab and update bottom navigation
     */
    override fun navigateToTab(tabId: Int) {
        // Update the bottom navigation selection (this won't trigger the listener again)
        bottomNavigationView.selectedItemId = tabId
    }

    /**
     * NavigationHost implementation - Update selected tab without loading fragment
     */
    override fun updateSelectedTab(tabId: Int) {
        // Use post to avoid triggering the listener during fragment transaction
        bottomNavigationView.post {
            bottomNavigationView.menu.findItem(tabId)?.isChecked = true
        }
    }

    /**
     * Handle deep links from email buttons
     * Supported paths: home, gigs, inbox, orders, account, verify
     */
    private fun handleDeepLink(intent: Intent): String? {
        val data = intent.data ?: return null

        android.util.Log.d("DEEP_LINK", "Received deep link: $data")

        if (data.scheme != "constructionapp") return null

        val path = data.host ?: data.path?.removePrefix("/") ?: "home"
        android.util.Log.d("DEEP_LINK", "Deep link path: $path")

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        when (path) {
            "home" -> {
                bottomNav.selectedItemId = R.id.nav_home
                loadFragment(HomeFragment())
            }
            "gigs", "jobs" -> {
                bottomNav.selectedItemId = R.id.nav_gig
                loadFragment(GigFragment())
            }
            "inbox", "messages", "chat" -> {
                bottomNav.selectedItemId = R.id.nav_inbox
                loadFragment(InboxFragment())
            }
            "orders" -> {
                bottomNav.selectedItemId = R.id.nav_orders
                loadFragment(OrdersFragment())
            }
            "account", "profile" -> {
                bottomNav.selectedItemId = R.id.nav_account
                loadFragment(AccountFragment())
            }
            "verify" -> {
                // Navigate to email verification
                startActivity(Intent(this, com.example.newconstructionappwithlocationtracking.auth.EmailVerificationActivity::class.java))
            }
            else -> {
                loadFragment(HomeFragment())
            }
        }

        return path
    }

    private fun startLocationServiceIfPermissionsGranted() {
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")
        android.util.Log.d("LOCATION_START", "🔍 MainActivity.startLocationServiceIfPermissionsGranted()")
        android.util.Log.d("LOCATION_START", "═══════════════════════════════════════════════════════")

        val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)

        val hasFine = permissionManager.hasFineLocationPermission()
        val hasBackground = permissionManager.hasBackgroundLocationPermission()
        val isEnabled = permissionManager.isLocationEnabled()

        android.util.Log.d("LOCATION_START", "📋 Permission Check:")
        android.util.Log.d("LOCATION_START", "   hasFineLocation: $hasFine")
        android.util.Log.d("LOCATION_START", "   hasBackgroundLocation: $hasBackground")
        android.util.Log.d("LOCATION_START", "   isLocationEnabled: $isEnabled")

        // Only start service if permissions are already granted
        // DO NOT request permissions here - that's done in OnboardingActivity
        if (hasFine) {
            android.util.Log.d("LOCATION_START", "✅ Fine location granted - attempting to start service...")
            startLocationService()
        } else {
            android.util.Log.d("LOCATION_START", "❌ Fine location NOT granted - service NOT started")
        }
    }

    private fun startLocationService() {
        android.util.Log.d("LOCATION_START", "🚀 MainActivity.startLocationService() called")
        try {
            // Save user session for autonomous tracking (boot receiver, etc.)
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                val prefs = getSharedPreferences("location_service_state", MODE_PRIVATE)
                prefs.edit()
                    .putString("saved_user_id", currentUser.uid)
                    .putLong("last_login_time", System.currentTimeMillis())
                    .putBoolean("has_valid_session", true)
                    .putBoolean("tracking_enabled", true)
                    .apply()
                android.util.Log.d("LOCATION_START", "💾 User session saved: ${currentUser.uid}")
            }

            LocationTrackingService.start(this)
            android.util.Log.d("LOCATION_START", "✅ LocationTrackingService.start() completed")
        } catch (e: Exception) {
            android.util.Log.e("LOCATION_START", "❌ Failed to start location service: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun openChatWithJobApplication(recipientId: String, currentUserId: String, jobDetailsJson: String?) {
        // Generate consistent chatId using sorted UIDs
        val chatId = listOf(currentUserId, recipientId).sorted().joinToString("_")

        // Pass to ChatFragment with job details
        val chatFragment = com.example.newconstructionappwithlocationtracking.fragments.ChatFragment()
        val args = Bundle().apply {
            putString("chatId", chatId)
            putString("senderId", currentUserId)
            putString("currentUserId", currentUserId)
            putString("recipientId", recipientId)
            putString("username", null) // Let ChatFragment fetch the username
            putString("pendingJobDetails", jobDetailsJson) // Pass job details for preview
        }
        chatFragment.arguments = args

        loadFragment(chatFragment)
    }

    private fun openChatWithUser(targetUID: String) {
        val currentUserUID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserUID == null) {
            loadFragment(HomeFragment())
            return
        }

        // Generate consistent chatId using sorted UIDs
        val chatId = listOf(currentUserUID, targetUID).sorted().joinToString("_")

        // Log chat creation for debugging
        android.util.Log.d("CHAT_CREATION", """
            Chat Opening:
            - ChatId: $chatId
            - CurrentUser: $currentUserUID  
            - Target: $targetUID
            - Format Valid: ${chatId == listOf(currentUserUID, targetUID).sorted().joinToString("_")}
        """.trimIndent())

        // Pass to ChatFragment directly with all required parameters
        val chatFragment = com.example.newconstructionappwithlocationtracking.fragments.ChatFragment()
        val args = Bundle().apply {
            putString("chatId", chatId)
            putString("senderId", currentUserUID) // For backward compatibility
            putString("currentUserId", currentUserUID)
            putString("recipientId", targetUID)
            putString("username", null) // Let ChatFragment fetch the username from profile API
        }
        chatFragment.arguments = args

        loadFragment(chatFragment)
    }

    /**
     * Open chat from notification
     */
    private fun openChatFromNotification(chatId: String, senderId: String?, username: String?) {
        val currentUserUID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserUID == null) {
            loadFragment(HomeFragment())
            return
        }

        // Extract recipientId from chatId (the other user)
        val chatParts = chatId.split("_")
        val recipientId = chatParts.find { it != currentUserUID } ?: senderId ?: ""

        android.util.Log.d("NOTIFICATION_NAV", """
            Opening chat from notification:
            - ChatId: $chatId
            - CurrentUser: $currentUserUID
            - RecipientId: $recipientId
            - Username: $username
        """.trimIndent())

        val chatFragment = com.example.newconstructionappwithlocationtracking.fragments.ChatFragment()
        val args = Bundle().apply {
            putString("chatId", chatId)
            putString("senderId", currentUserUID)
            putString("currentUserId", currentUserUID)
            putString("recipientId", recipientId)
            putString("username", username)
        }
        chatFragment.arguments = args

        // Also switch to inbox tab in bottom navigation
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNavigationView.selectedItemId = R.id.nav_inbox

        loadFragment(chatFragment)
    }

    /**
     * Handle new intents when activity is already running (e.g., from notification)
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        intent?.let {
            val navigateTo = it.getStringExtra("navigateTo")
            val chatId = it.getStringExtra("chatId")
            val senderId = it.getStringExtra("senderId")
            val username = it.getStringExtra("username")

            android.util.Log.d("NOTIFICATION_NAV", "onNewIntent - navigateTo: $navigateTo, chatId: $chatId")

            if (navigateTo == "chat" && chatId != null) {
                openChatFromNotification(chatId, senderId, username)
            }
        }
    }

    /**
     * Handle permission results for job action permissions
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        android.util.Log.d("MAIN_PERMISSION", "===== MainActivity.onRequestPermissionsResult =====")
        android.util.Log.d("MAIN_PERMISSION", "Request code: $requestCode")
        android.util.Log.d("MAIN_PERMISSION", "Permissions: ${permissions.joinToString()}")
        android.util.Log.d("MAIN_PERMISSION", "Results: ${grantResults.joinToString()}")

        // Handle job action permissions from JobsAdapter (job cards)
        if (requestCode == com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.REQUEST_CODE_JOB_ACTION_FINE) {
            android.util.Log.d("MAIN_PERMISSION", "Handling job action fine location result...")

            val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)

            permissionManager.handleJobActionFineLocationResult(this, grantResults, object : com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.JobActionCallback {
                override fun onPermissionsGranted() {
                    android.util.Log.d("MAIN_PERMISSION", "✅ All permissions GRANTED - checking GPS...")

                    // Both fine + background already granted - check GPS
                    gpsDialogHelper.resetGpsAttempts()
                    gpsDialogHelper.promptEnableGps(
                        onGpsEnabled = {
                            android.util.Log.d("MAIN_PERMISSION", "✅ GPS is ON - starting LocationTrackingService")
                            try {
                                com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this@MainActivity)
                                android.util.Log.d("MAIN_PERMISSION", "✅ LocationTrackingService start command sent")
                            } catch (e: Exception) {
                                android.util.Log.e("MAIN_PERMISSION", "❌ Failed to start LocationTrackingService", e)
                            }
                        },
                        onGpsFlowEnded = {
                            android.util.Log.d("MAIN_PERMISSION", "🏠 GPS flow ended without enabling - user returns to page")
                        }
                    )
                }

                override fun onPermissionDenied(showSettingsDialog: Boolean, message: String) {
                    android.util.Log.d("MAIN_PERMISSION", "❌ Fine location DENIED")

                    val currentCount = permissionManager.getGlobalFineLocationDenialCount()
                    android.util.Log.d("MAIN_PERMISSION", "Global count after denial: $currentCount")

                    // Don't show any dialog here - user stays on current page
                    // Next time they click a button, it will check the count and show appropriate dialog
                    android.util.Log.d("MAIN_PERMISSION", "User stays on page, can try again")
                }

                override fun onBackgroundPermissionNeeded() {
                    android.util.Log.d("MAIN_PERMISSION", "✅ Fine location GRANTED - checking GPS before background permission...")

                    // Fine location just granted - check GPS FIRST, then request background
                    gpsDialogHelper.resetGpsAttempts()
                    gpsDialogHelper.promptEnableGps(
                        onGpsEnabled = {
                            android.util.Log.d("MAIN_PERMISSION", "✅ GPS is ON - now requesting background permission")
                            // GPS is on - now show background permission dialog
                            gpsDialogHelper.showBackgroundPermissionNeededDialog {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    permissionManager.requestBackgroundLocationPermission(this@MainActivity)
                                }
                            }
                        },
                        onGpsFlowEnded = {
                            android.util.Log.d("MAIN_PERMISSION", "🏠 GPS flow ended without enabling - user returns to page")
                            // User rejected GPS twice - they return to the page normally
                            // Next button tap will restart from GPS prompt
                        }
                    )
                }
            })
        }
    }

    /**
     * Handle activity result (when returning from settings)
     */
    override fun onResume() {
        super.onResume()

        android.util.Log.d("MAIN_PERMISSION", "═════════════════════════════════════")
        android.util.Log.d("MAIN_PERMISSION", "📱 MainActivity.onResume() - Checking permissions...")

        // Check if background permission was granted after returning from settings
        val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)

        val hasFine = permissionManager.hasFineLocationPermission()
        val hasBackground = permissionManager.hasBackgroundLocationPermission()

        android.util.Log.d("MAIN_PERMISSION", "   Fine Location: $hasFine")
        android.util.Log.d("MAIN_PERMISSION", "   Background Location: $hasBackground")

        // 🚀 START SERVICE IF BOTH PERMISSIONS ARE GRANTED
        if (hasFine && hasBackground) {
            android.util.Log.d("MAIN_PERMISSION", "✅ All location permissions granted!")
            android.util.Log.d("MAIN_PERMISSION", "🌍 Starting LocationTrackingService...")

            try {
                com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this)
                android.util.Log.d("MAIN_PERMISSION", "✅ LocationTrackingService start command sent")
            } catch (e: Exception) {
                android.util.Log.e("MAIN_PERMISSION", "❌ Failed to start LocationTrackingService", e)
            }
        } else {
            android.util.Log.d("MAIN_PERMISSION", "⏳ Waiting for all permissions to be granted")
        }

        // If fine location is granted but background is not, and user just returned from settings
        // Show the dialog again
        if (hasFine && !hasBackground) {
            // User returned from settings without granting background permission
            // The dialog will be shown again when they click the button
            android.util.Log.d("JOB_PERMISSION", "User returned without granting background permission")
        }

        android.util.Log.d("MAIN_PERMISSION", "═════════════════════════════════════")
    }
}
