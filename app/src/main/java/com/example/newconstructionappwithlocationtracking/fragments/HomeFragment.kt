package com.example.newconstructionappwithlocationtracking.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RelativeLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.activities.WithdrawalActivity
import com.example.newconstructionappwithlocationtracking.api.Config
import com.example.newconstructionappwithlocationtracking.services.FCMNotificationService
import com.example.newconstructionappwithlocationtracking.utils.ErrorStateManager
import com.example.newconstructionappwithlocationtracking.utils.NotificationPermissionManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var welcomeText: TextView
    private lateinit var homeContentScrollView: ScrollView
    private lateinit var homeLoadingLayout: RelativeLayout
    private lateinit var errorContainer: FrameLayout
    private lateinit var profileImage: ImageView
    private lateinit var levelProgressBar: ProgressBar
    private lateinit var successScoreProgressBar: ProgressBar
    private lateinit var successScoreText: TextView
    private lateinit var ratingText: TextView
    private lateinit var ratingProgressBar: ProgressBar
    private lateinit var responseRateProgressBar: ProgressBar
    private lateinit var responseRateText: TextView
    private lateinit var ordersText: TextView
    private lateinit var clientsText: TextView
    private lateinit var earningsText: TextView
    private lateinit var thisMonthEarningsText: TextView
    private lateinit var totalEarnedText: TextView
    private lateinit var availableBalanceText: TextView
    private lateinit var pendingPaymentsText: TextView
    private lateinit var withdrawButton: Button

    // Store current balance for withdrawal
    private var currentAvailableBalance = 0

    // Notification permission manager
    private lateinit var notificationPermissionManager: NotificationPermissionManager

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionManager.handlePermissionResult(
            isGranted,
            onGranted = {
                Log.d(TAG, "✅ Notification permission granted from HomeFragment")
            },
            onDenied = {
                Log.d(TAG, "❌ Notification permission denied from HomeFragment")
            }
        )
    }

    // Activity result launcher for withdrawal
    private val withdrawalLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val withdrawalSuccessful = result.data?.getBooleanExtra("withdrawal_successful", false) ?: false
            val withdrawalAmount = result.data?.getIntExtra("withdrawal_amount", 0) ?: 0

            if (withdrawalSuccessful) {
                Log.d(TAG, "Withdrawal successful, refreshing data from backend")

                // Use coroutine for delayed refresh
                lifecycleScope.launch {
                    // Add small delay to ensure Firestore write consistency
                    kotlinx.coroutines.delay(1500)

                    // Refresh data from server (backend has already updated the balance)
                    loadData()

                    // Show success message on main thread
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(),
                            "Withdrawal of $$withdrawalAmount processed successfully!",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Optimize OkHttp client with faster timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val TAG = "HomeFragment"
    private var isDataLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        Config.init(requireContext())

        // Initialize notification permission manager
        notificationPermissionManager = NotificationPermissionManager(requireContext())

        initializeViews(view)

        // Start loading immediately
        loadData()

        // Check notification permission after a short delay to not interrupt initial loading
        view.postDelayed({
            checkNotificationPermission()
        }, 1500)
    }

    private fun checkNotificationPermission() {
        if (notificationPermissionManager.shouldShowPermissionDialog()) {
            notificationPermissionManager.showNotificationPermissionDialog(
                fragment = this,
                permissionLauncher = notificationPermissionLauncher,
                onGranted = {
                    Log.d(TAG, "✅ Notifications enabled!")
                },
                onDenied = {
                    Log.d(TAG, "User chose not to enable notifications")
                }
            )
        } else if (notificationPermissionManager.isNotificationPermissionGranted()) {
            // Already granted, register FCM token
            FCMNotificationService.registerTokenWithBackend(requireContext())
        }
    }

    private fun initializeViews(view: View) {
        welcomeText = view.findViewById(R.id.welcomeText)
        homeContentScrollView = view.findViewById(R.id.homeContentScrollView)
        homeLoadingLayout = view.findViewById(R.id.loadingLayout)
        errorContainer = view.findViewById(R.id.errorContainer)
        profileImage = view.findViewById(R.id.profileImage)
        levelProgressBar = view.findViewById(R.id.levelProgressBar)
        successScoreProgressBar = view.findViewById(R.id.successScoreProgressBar)
        successScoreText = view.findViewById(R.id.successScoreText)
        ratingText = view.findViewById(R.id.ratingText)
        ratingProgressBar = view.findViewById(R.id.ratingProgressBar)
        responseRateProgressBar = view.findViewById(R.id.responseRateProgressBar)
        responseRateText = view.findViewById(R.id.responseRateText)
        ordersText = view.findViewById(R.id.ordersText)
        clientsText = view.findViewById(R.id.clientsText)
        earningsText = view.findViewById(R.id.earningsText)
        thisMonthEarningsText = view.findViewById(R.id.thisMonthEarningsText)
        totalEarnedText = view.findViewById(R.id.totalEarnedText)
        availableBalanceText = view.findViewById(R.id.availableBalanceText)
        pendingPaymentsText = view.findViewById(R.id.pendingPaymentsText)
        withdrawButton = view.findViewById(R.id.withdrawButton)

        // Setup withdraw button click listener
        withdrawButton.setOnClickListener {
            if (currentAvailableBalance > 0) {
                val intent = Intent(requireContext(), WithdrawalActivity::class.java)
                intent.putExtra("availableBalance", currentAvailableBalance)
                withdrawalLauncher.launch(intent)
            } else {
                android.widget.Toast.makeText(requireContext(),
                    "No balance available for withdrawal",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadData() {
        if (isDataLoading) return
        isDataLoading = true

        // Show loading immediately
        showLoading()

        // Check connectivity first
        if (!ErrorStateManager.isNetworkAvailable(requireContext())) {
            showError(null)
            isDataLoading = false
            return
        }

        // Use coroutines for better performance
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting data fetch...")
                val startTime = System.currentTimeMillis()

                // Get token and make API call efficiently
                val token = getFirebaseTokenQuickly()
                val earningsData = fetchEarningsData(token)

                val loadTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Data loaded in ${loadTime}ms")

                // Update UI immediately on main thread
                withContext(Dispatchers.Main) {
                    if (earningsData != null) {
                        updateUI(earningsData)
                        showContent()
                    } else {
                        // Data is null - likely a server error
                        showError(null)
                    }
                    isDataLoading = false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                withContext(Dispatchers.Main) {
                    showError(e)
                    isDataLoading = false
                }
            }
        }
    }

    private fun showError(exception: Exception?) {
        if (!::errorContainer.isInitialized) return

        ErrorStateManager.showError(
            errorContainer = errorContainer,
            contentView = homeContentScrollView,
            loadingView = homeLoadingLayout,
            title = ErrorStateManager.getErrorTitle(exception),
            message = ErrorStateManager.getErrorMessage(exception),
            onRetry = { loadData() }
        )
    }

    private suspend fun getFirebaseTokenQuickly(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    val task = user.getIdToken(false)
                    var token: String? = null

                    // Wait for token with timeout
                    val startTime = System.currentTimeMillis()
                    while (!task.isComplete && (System.currentTimeMillis() - startTime) < 5000) {
                        Thread.sleep(50)
                    }

                    if (task.isSuccessful) {
                        token = task.result?.token
                        Log.d(TAG, "Token obtained successfully")
                    } else {
                        Log.w(TAG, "Token failed: ${task.exception?.message}")
                    }
                    token
                } else {
                    Log.d(TAG, "No Firebase user")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token error: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchEarningsData(token: String?): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val backend = getBackendUrlFromEnv().ifBlank { "https://real-pakistan-backend.onrender.com" }
                val apiUrl = "$backend/api/earnings"

                Log.d(TAG, "Making API call to: $apiUrl")

                val builder = Request.Builder()
                    .url(apiUrl)
                    .get()
                    .addHeader("Cache-Control", "no-cache")

                if (!token.isNullOrBlank()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }

                val request = builder.build()
                val response = client.newCall(request).execute()

                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success")) {
                                Log.d(TAG, "API call successful")
                                return@withContext json.optJSONObject("earnings")
                            }
                        }
                    }
                    Log.w(TAG, "API call failed with code: ${it.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call exception", e)
                null
            }
        }
    }

    private fun updateUI(earnings: JSONObject) {
        // Extract data
        val firstName = earnings.optString("firstName", "")
        val profilePicUrl = earnings.optString("profilePicUrl", "")
        val level = earnings.optInt("level", 0)
        val successScore = earnings.optInt("successScore", 0)
        val rating = earnings.optInt("rating", 0)
        val responseRate = earnings.optInt("responseRate", 0)
        val totalCompletedOrders = earnings.optInt("totalCompletedOrders", 0)
        val totalClients = earnings.optInt("totalClients", 0)
        val totalEarned = earnings.optInt("totalEarned", 0)
        val thisMonthEarnings = earnings.optInt("thisMonthEarnings", 0)
        val availableBalance = earnings.optInt("availableBalance", 0)
        val pendingPayments = earnings.optInt("pendingPayments", 0)

        Log.d(TAG, "Updating UI with: firstName='$firstName', level=$level, totalEarned=$totalEarned")

        // Update greeting
        welcomeText.text = if (firstName.isNotBlank()) "Hi, ${firstName.trim()}" else ""

        // Update profile image
        if (profilePicUrl.isNotBlank()) {
            try {
                com.bumptech.glide.Glide.with(requireContext())
                    .load(profilePicUrl)
                    .placeholder(R.drawable.circle_background)
                    .error(R.drawable.circle_background)
                    .circleCrop()
                    .into(profileImage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profile image", e)
            }
        }

        // Update progress bars and values
        levelProgressBar.progress = level.coerceIn(0, 5)

        successScoreProgressBar.progress = successScore.coerceIn(0, 5)
        successScoreText.text = successScore.toString()

        ratingProgressBar.progress = rating.coerceIn(0, 5)
        ratingText.text = rating.toString()

        responseRateProgressBar.progress = responseRate.coerceIn(0, 100)
        responseRateText.text = "$responseRate%"

        // Update statistics
        ordersText.text = "$totalCompletedOrders/5"
        clientsText.text = "$totalClients/3"
        earningsText.text = "$totalEarned/400 $"

        // Update earnings overview
        thisMonthEarningsText.text = "$$thisMonthEarnings"
        totalEarnedText.text = "$$totalEarned"
        availableBalanceText.text = "$$availableBalance"
        pendingPaymentsText.text = "$$pendingPayments"

        // Store current balance for withdrawal
        currentAvailableBalance = availableBalance

        // Cache firstName for future use
        if (firstName.isNotBlank()) {
            sharedPreferences.edit().putString("user_firstName", firstName.trim()).apply()
        }
    }

    private fun showLoading() {
        if (::errorContainer.isInitialized) errorContainer.visibility = View.GONE
        homeContentScrollView.visibility = View.GONE
        homeLoadingLayout.visibility = View.VISIBLE
    }

    private fun showContent() {
        if (::errorContainer.isInitialized) errorContainer.visibility = View.GONE
        homeLoadingLayout.visibility = View.GONE
        homeContentScrollView.visibility = View.VISIBLE
    }

    private fun getBackendUrlFromEnv(): String {
        try {
            val assetManager = requireContext().assets
            assetManager.open(".env").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("BACKEND_URL=")) {
                        return line.substringAfter("=").trim()
                    }
                }
            }
        } catch (ignored: Exception) {
            Log.w(TAG, ".env not found in assets")
        }
        return ""
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when fragment becomes visible again
        if (!isDataLoading) {
            loadData()
        }
    }
}