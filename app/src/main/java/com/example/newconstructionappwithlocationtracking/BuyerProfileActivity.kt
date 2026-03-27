package com.example.newconstructionappwithlocationtracking

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.fragments.BuyerAboutFragment
import com.example.newconstructionappwithlocationtracking.fragments.BuyerReviewsFragment
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class BuyerProfileActivity : AppCompatActivity() {
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var profilePicture: ImageView
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private var uid: String? = null
    private var profileDataJson: JSONObject? = null

    // GPS dialog helper - shared across all job action permission flows
    val gpsDialogHelper: com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper by lazy {
        com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register GPS resolution launcher BEFORE setContentView
        gpsDialogHelper.registerGpsLauncher(this)

        setContentView(R.layout.activity_buyer_profile)
        loadingIndicator = findViewById(R.id.buyerProfileLoadingIndicator)
        profilePicture = findViewById(R.id.profilePicture)
        tabLayout = findViewById(R.id.buyerProfileTabLayout)
        viewPager = findViewById(R.id.buyerProfileViewPager)
        uid = intent.getStringExtra("uid")
        val buyerFullNameText = findViewById<TextView>(R.id.buyerFullNameText)
        val contactBuyerBtn = findViewById<View>(R.id.contactBuyerBtn)
        findViewById<ImageView>(R.id.backArrow).setOnClickListener { finish() }
        showLoading(true)
        fetchBuyerProfile(buyerFullNameText, contactBuyerBtn)

        // Contact Buyer - just open chat, NO permission checks
        contactBuyerBtn.setOnClickListener {
            val intent = android.content.Intent(this, MainActivity::class.java)
            intent.putExtra("OPEN_CHAT_WITH_UID", uid)
            startActivity(intent)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        tabLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        viewPager.visibility = if (isLoading) View.GONE else View.VISIBLE
        profilePicture.visibility = if (isLoading) View.GONE else View.VISIBLE
    }

    private fun capitalizeName(name: String): String {
        return name.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun showProfileData(profileData: JSONObject, buyerFullNameText: TextView, contactBuyerBtn: View) {
        val firstName = profileData.optString("firstName", "")
        val lastName = profileData.optString("lastName", "")
        val fullName = capitalizeName("$firstName $lastName".trim())
        buyerFullNameText.text = fullName
        buyerFullNameText.visibility = View.VISIBLE
        contactBuyerBtn.visibility = View.VISIBLE
    }

    private fun fetchBuyerProfile(buyerFullNameText: TextView, contactBuyerBtn: View) {
        if (uid == null) return
        val backendUrl = "https://real-pakistan-backend.onrender.com" // Hardcoded for now
        val url = "$backendUrl/api/profile/$uid"
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer " + getAccessToken())
            .build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showLoading(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                if (json.optBoolean("success") && json.has("data")) {
                    profileDataJson = json.getJSONObject("data")
                    runOnUiThread {
                        showLoading(false)
                        setupTabs()
                        val picUrl = profileDataJson?.optString("profilePicUrl", "") ?: ""
                        showProfileData(profileDataJson!!, buyerFullNameText, contactBuyerBtn)
                        if (picUrl.isNotEmpty()) {
                            Glide.with(this@BuyerProfileActivity)
                                .load(picUrl)
                                .circleCrop()
                                .placeholder(R.drawable.ic_profile_placeholder)
                                .error(R.drawable.ic_profile_placeholder)
                                .into(profilePicture)
                        }
                    }
                } else {
                    runOnUiThread { showLoading(false) }
                }
            }
        })
    }

    private fun setupTabs() {
        val adapter = BuyerProfilePagerAdapter(this, profileDataJson)
        viewPager.adapter = adapter

        // Enable nested scrolling for ViewPager2
        viewPager.isNestedScrollingEnabled = true

        // Keep all fragments in memory to prevent recreation when switching tabs
        viewPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "About" else "Reviews"
        }.attach()
    }

    private fun getAccessToken(): String {
        // Implement your logic to get the current user's access token
        return ""
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

        val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)

        when (requestCode) {
            com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.REQUEST_CODE_JOB_ACTION_FINE -> {
                // Handle fine location permission result for job action
                permissionManager.handleJobActionFineLocationResult(this, grantResults, object : com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.JobActionCallback {
                    override fun onPermissionsGranted() {
                        android.util.Log.d("JOB_PERMISSION", "✅ All permissions granted - checking GPS...")

                        // Both fine + background already granted - check GPS
                        gpsDialogHelper.resetGpsAttempts()
                        gpsDialogHelper.promptEnableGps(
                            onGpsEnabled = {
                                android.util.Log.d("JOB_PERMISSION", "✅ GPS is ON - starting LocationTrackingService")
                                try {
                                    com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this@BuyerProfileActivity)
                                    android.util.Log.d("JOB_PERMISSION", "✅ LocationTrackingService start command sent")
                                } catch (e: Exception) {
                                    android.util.Log.e("JOB_PERMISSION", "❌ Failed to start LocationTrackingService", e)
                                }
                            },
                            onGpsFlowEnded = {
                                android.util.Log.d("JOB_PERMISSION", "🏠 GPS flow ended without enabling - user returns to page")
                            }
                        )
                    }

                    override fun onPermissionDenied(showSettingsDialog: Boolean, message: String) {
                        // Show appropriate denial dialog
                        if (showSettingsDialog) {
                            // After 2nd+ denial - "Go to Settings"
                            gpsDialogHelper.showPermissionDeniedDialog(message, true) {
                                permissionManager.openBackgroundLocationSettings(this@BuyerProfileActivity)
                            }
                        } else {
                            // After 1st denial - "Continue to Settings"
                            gpsDialogHelper.showPermissionDeniedDialog(message, false) {
                                // User dismissed - stays on page
                            }
                        }
                    }

                    override fun onBackgroundPermissionNeeded() {
                        android.util.Log.d("JOB_PERMISSION", "✅ Fine location granted - checking GPS before background permission...")

                        // Fine location just granted - check GPS FIRST, then request background
                        gpsDialogHelper.resetGpsAttempts()
                        gpsDialogHelper.promptEnableGps(
                            onGpsEnabled = {
                                android.util.Log.d("JOB_PERMISSION", "✅ GPS is ON - now requesting background permission")
                                // GPS is on - now show background permission dialog
                                gpsDialogHelper.showBackgroundPermissionNeededDialog {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        permissionManager.requestBackgroundLocationPermission(this@BuyerProfileActivity)
                                    }
                                }
                            },
                            onGpsFlowEnded = {
                                android.util.Log.d("JOB_PERMISSION", "🏠 GPS flow ended without enabling - user returns to page")
                            }
                        )
                    }
                })
            }
        }
    }
}

class BuyerProfilePagerAdapter(
    fa: AppCompatActivity,
    private val profileDataJson: JSONObject?
) : androidx.viewpager2.adapter.FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = 2
    override fun createFragment(position: Int): androidx.fragment.app.Fragment {
        return if (position == 0) BuyerAboutFragment.newInstance(profileDataJson) else BuyerReviewsFragment.newInstance(profileDataJson)
    }
}