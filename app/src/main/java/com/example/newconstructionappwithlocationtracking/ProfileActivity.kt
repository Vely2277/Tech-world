package com.example.newconstructionappwithlocationtracking

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.example.newconstructionappwithlocationtracking.fragments.AboutFragment
import com.example.newconstructionappwithlocationtracking.fragments.ReviewsFragment
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

class ProfileActivity : AppCompatActivity() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var profileContent: LinearLayout
    private lateinit var profileHeaderSection: LinearLayout
    private lateinit var tabSection: LinearLayout
    private var profileDataJson: JSONObject? = null
    private var shouldShowProfileCompletion = false
    private var shouldNavigateToAbout = false
    private var isUploading = false

    // Animation variables
    private var headerHeight = 0
    private var isHeaderCollapsed = false
    private var lastScrollY = 0

    // Image picker for profile picture upload
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadProfileImage(it) }
    }

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        loadingOverlay = findViewById(R.id.profileLoadingOverlay)
        profileContent = findViewById(R.id.profileContent)

        // Check intent extras
        shouldShowProfileCompletion = intent.getBooleanExtra("show_profile_completion_popup", false)
        shouldNavigateToAbout = intent.getBooleanExtra("navigate_to_about", false)

        android.util.Log.d("ProfileActivity", "=== ProfileActivity onCreate ===")
        android.util.Log.d("ProfileActivity", "shouldShowProfileCompletion: $shouldShowProfileCompletion")
        android.util.Log.d("ProfileActivity", "shouldNavigateToAbout: $shouldNavigateToAbout")

        setupViews()

        // Attach scroll listener to the outer scroll container
        val scrollContainer = findViewById<androidx.core.widget.NestedScrollView>(R.id.profileScrollContainer)
        scrollContainer.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            onContentScrolled(scrollY)
        }

        showLoading(true)
        fetchProfileData()
    }

    private fun setupViews() {
        android.util.Log.d("ProfileActivity", "🔧 Setting up views...")

        // Back arrow
        val backArrow = findViewById<ImageView>(R.id.backArrow)
        backArrow.setOnClickListener {
            finish()
        }

        // Setup profile picture upload click listener
        val profilePicture = findViewById<ImageView>(R.id.profilePicture)
        val profilePictureContainer = profilePicture.parent as? FrameLayout

        profilePictureContainer?.setOnClickListener {
            if (!isUploading) {
                imagePickerLauncher.launch("image/*")
            }
        }

        profilePicture.setOnClickListener {
            if (!isUploading) {
                imagePickerLauncher.launch("image/*")
            }
        }

        // Initialize header and tab sections for animations
        try {
            profileHeaderSection = findViewById(R.id.profileHeaderSection)
            tabSection = findViewById(R.id.tabSection)

            android.util.Log.d("ProfileActivity", "✅ Header section found: ${::profileHeaderSection.isInitialized}")
            android.util.Log.d("ProfileActivity", "✅ Tab section found: ${::tabSection.isInitialized}")

            // Measure header height for animations
            profileHeaderSection.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    profileHeaderSection.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    headerHeight = profileHeaderSection.height
                    android.util.Log.d("ProfileActivity", "📏 Header height measured: $headerHeight px")

                    if (headerHeight == 0) {
                        android.util.Log.w("ProfileActivity", "⚠️ Header height is 0! This will prevent animations.")
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "❌ Error setting up animation views: ${e.message}")
        }
    }

    private fun showLoading(isLoading: Boolean) {
        android.util.Log.d("ProfileActivity", "showLoading: $isLoading")
        if (isLoading) {
            loadingOverlay.visibility = View.VISIBLE
            profileContent.visibility = View.GONE
        } else {
            loadingOverlay.visibility = View.GONE
            profileContent.visibility = View.VISIBLE
        }
    }

    private fun fetchProfileData() {
        android.util.Log.d("ProfileActivity", "=== Starting fetchProfileData ===")

        // STEP 1: Clear any existing profile data first
        profileDataJson = null

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            android.util.Log.e("ProfileActivity", "❌ User is null - not authenticated")
            showLoading(false)
            Toast.makeText(this, "Please login again", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        android.util.Log.d("ProfileActivity", "✅ User authenticated: ${user.uid}")
        android.util.Log.d("ProfileActivity", "📧 User email: ${user.email}")
        android.util.Log.d("ProfileActivity", "🔄 Getting ID token...")

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                android.util.Log.d("ProfileActivity", "✅ ID token obtained successfully")
                android.util.Log.d("ProfileActivity", "📡 Making API call to backend...")

                val backendUrl = "https://real-pakistan-backend.onrender.com/api/profile"
                val request = Request.Builder()
                    .url(backendUrl)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                OkHttpClient().newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        android.util.Log.e("ProfileActivity", "API call failed: ${e.message}", e)
                        runOnUiThread {
                            showLoading(false)
                            Toast.makeText(this@ProfileActivity, "Failed to load profile. Please check your connection.", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()

                        runOnUiThread {
                            if (!response.isSuccessful || body == null) {
                                showLoading(false)

                                // Handle specific error cases
                                when (response.code) {
                                    401 -> {
                                        Toast.makeText(this@ProfileActivity, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                                        // Navigate back to auth
                                        val intent = Intent(this@ProfileActivity, com.example.newconstructionappwithlocationtracking.auth.Auth::class.java)
                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        startActivity(intent)
                                        finish()
                                    }
                                    404 -> {
                                        Toast.makeText(this@ProfileActivity, "Profile not found. Creating new profile...", Toast.LENGTH_SHORT).show()
                                        // Handle new user case - create basic profile data
                                        createBasicProfileData()
                                    }
                                    else -> {
                                        Toast.makeText(this@ProfileActivity, "Failed to load profile data", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                return@runOnUiThread
                            }

                            try {
                                val json = JSONObject(body)
                                val data = json.optJSONObject("data")

                                if (data != null) {
                                    profileDataJson = data
                                    updateProfileUI(data)
                                    updateAboutTabWithProfileData()
                                } else {
                                    createBasicProfileData()
                                }

                                showLoading(false)

                            } catch (e: Exception) {
                                android.util.Log.e("ProfileActivity", "❌ Error parsing JSON: ${e.message}", e)
                                showLoading(false)
                                Toast.makeText(this@ProfileActivity, "Error processing profile data", Toast.LENGTH_SHORT).show()
                                createBasicProfileData()
                            }
                        }
                    }
                })
            } else {
                android.util.Log.e("ProfileActivity", "❌ Failed to get ID token: ${task.exception?.message}")
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this@ProfileActivity, "Authentication error. Please login again.", Toast.LENGTH_SHORT).show()
                    // Navigate back to auth
                    val intent = Intent(this@ProfileActivity, com.example.newconstructionappwithlocationtracking.auth.Auth::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun createBasicProfileData() {
        android.util.Log.d("ProfileActivity", "🔄 Creating basic profile data for new user")

        val user = FirebaseAuth.getInstance().currentUser
        val sharedPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        // Create basic profile data from available sources
        val basicProfile = JSONObject().apply {
            put("firstName", sharedPrefs.getString("first_name", user?.email?.substringBefore("@") ?: "User"))
            put("lastName", sharedPrefs.getString("last_name", ""))
            put("email", user?.email ?: sharedPrefs.getString("email", ""))
            put("description", "")
            put("location", "")
            put("profilePicUrl", "")
            put("level", 1)
            put("rating", 5.0)
            put("totalReviews", 0)
            put("availableBalance", 0)
            put("skills", JSONArray())
            put("Languages", JSONArray()) // Start with empty languages array
        }

        android.util.Log.d("ProfileActivity", "📄 Basic profile created: $basicProfile")

        profileDataJson = basicProfile
        updateProfileUI(basicProfile)
        updateAboutTabWithProfileData()
        showLoading(false)
    }

    private fun updateProfileUI(data: JSONObject?) {
        android.util.Log.d("ProfileActivity", "=== updateProfileUI called ===")
        if (data == null) {
            android.util.Log.e("ProfileActivity", "❌ Profile data is null")
            return
        }

        try {
            val firstName = data.optString("firstName", "User")
            val lastName = data.optString("lastName", "")
            val fullName = if (lastName.isNotEmpty()) "$firstName $lastName" else firstName

            android.util.Log.d("ProfileActivity", "👤 Updating profile for: $fullName")

            val profileUserName = findViewById<TextView>(R.id.profileUserName)
            profileUserName.text = fullName

            val profilePicture = findViewById<ImageView>(R.id.profilePicture)
            val profilePicUrl = data.optString("profilePicUrl", "")

            // Get overlay and icon elements
            val profilePictureContainer = profilePicture.parent as? FrameLayout
            val cameraOverlay = profilePictureContainer?.findViewById<View>(R.id.headerCameraOverlay)
            val cameraIcon = profilePictureContainer?.findViewById<ImageView>(R.id.headerCameraIcon)
            val editAvatarIcon = profilePictureContainer?.findViewById<View>(R.id.headerEditAvatarIcon)
            val uploadProgress = profilePictureContainer?.findViewById<ProgressBar>(R.id.headerUploadProgress)

            android.util.Log.d("ProfileActivity", "🖼️ Profile picture URL: $profilePicUrl")

            if (profilePicUrl.isNotEmpty()) {
                try {
                    Glide.with(this)
                        .load(profilePicUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(profilePicture)

                    // Hide camera overlay and show edit icon when profile picture is loaded
                    cameraOverlay?.visibility = View.GONE
                    cameraIcon?.visibility = View.GONE
                    editAvatarIcon?.visibility = View.VISIBLE
                    uploadProgress?.visibility = View.GONE

                    android.util.Log.d("ProfileActivity", "✅ Profile picture loaded successfully")
                } catch (e: Exception) {
                    android.util.Log.e("ProfileActivity", "❌ Error loading profile picture: ${e.message}")
                    // Show camera overlay for error state
                    cameraOverlay?.visibility = View.VISIBLE
                    cameraIcon?.visibility = View.VISIBLE
                    editAvatarIcon?.visibility = View.GONE
                    uploadProgress?.visibility = View.GONE
                }
            } else {
                // Show camera overlay when no profile picture
                cameraOverlay?.visibility = View.VISIBLE
                cameraIcon?.visibility = View.VISIBLE
                editAvatarIcon?.visibility = View.GONE
                uploadProgress?.visibility = View.GONE
            }

            // Update star text
            val starText = findViewById<TextView>(R.id.profileStarText)
            val level = data.optInt("level", 1)
            val starTextValue = if (level == 1) "New seller" else "Level $level"
            starText?.text = starTextValue
            android.util.Log.d("ProfileActivity", "⭐ Level: $starTextValue")

            // Update description if available
            val description = data.optString("description", "")
            if (description.isNotEmpty()) {
                val profileDescription = findViewById<TextView>(R.id.profiledescription)
                profileDescription?.text = description
                android.util.Log.d("ProfileActivity", "📝 Description: $description")
            }

            android.util.Log.d("ProfileActivity", "✅ Profile UI updated successfully")

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "❌ Error updating profile UI: ${e.message}", e)
        }
    }

    private fun updateAboutTabWithProfileData() {
        android.util.Log.d("ProfileActivity", "=== updateAboutTabWithProfileData called ===")
        android.util.Log.d("ProfileActivity", "📄 Profile data available: ${profileDataJson != null}")
        android.util.Log.d("ProfileActivity", "🎯 shouldShowProfileCompletion: $shouldShowProfileCompletion")
        android.util.Log.d("ProfileActivity", "🎯 shouldNavigateToAbout: $shouldNavigateToAbout")

        try {
            val viewPager = findViewById<ViewPager2>(R.id.viewPager)
            val adapter = ProfilePagerAdapter(this, profileDataJson)
            viewPager.adapter = adapter

            // Keep all fragments in memory to prevent recreation when switching tabs
            viewPager.offscreenPageLimit = 2

            val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (position) {
                    0 -> "About"
                    1 -> "Reviews"
                    else -> ""
                }
            }.attach()

            android.util.Log.d("ProfileActivity", "✅ ViewPager and TabLayout setup complete")

            // Navigate to About tab if requested
            if (shouldNavigateToAbout || shouldShowProfileCompletion) {
                android.util.Log.d("ProfileActivity", "🔄 Navigating to About tab")
                viewPager.setCurrentItem(0, false) // Navigate to About tab (position 0)
            }

            // Show profile completion popup after a delay if requested
            if (shouldShowProfileCompletion) {
                android.util.Log.d("ProfileActivity", "⏱️ Scheduling profile completion popup")
                Handler(Looper.getMainLooper()).postDelayed({
                    android.util.Log.d("ProfileActivity", "🎉 Showing profile completion dialog")
                    showProfileCompletionDialog()
                }, 1500) // 1.5 second delay to let UI settle
            }

            android.util.Log.d("ProfileActivity", "✅ Tab setup complete")

            // Setup scroll animations after a short delay to ensure layout is ready
            Handler(Looper.getMainLooper()).postDelayed({
                setupScrollAnimations()
            }, 500)

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "❌ Error in updateAboutTabWithProfileData: ${e.message}", e)
        }
    }

    private fun setupScrollAnimations() {
        android.util.Log.d("ProfileActivity", "🎬 Setting up scroll animations")

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // Add scroll listener to detect scrolling in fragments
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                android.util.Log.d("ProfileActivity", "📑 Tab changed to position: $position")
                // Reset animations when changing tabs
                resetAnimations()
            }
        })

        // Test animation after a delay to make sure everything is set up
        Handler(Looper.getMainLooper()).postDelayed({
            android.util.Log.d("ProfileActivity", "🧪 Testing animation system...")
            testAnimation()
        }, 2000)

        // We need to access the fragment's scroll view to detect scrolling
        // This will be handled by passing a callback to the fragments
    }

    private fun testAnimation() {
        if (::profileHeaderSection.isInitialized && ::tabSection.isInitialized && headerHeight > 0) {
            android.util.Log.d("ProfileActivity", "✅ Running animation test...")
            // Test animation with 50% progress
            animateHeaderAndTabs(0.5f)

            // Reset after 2 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.d("ProfileActivity", "🔄 Resetting test animation")
                resetAnimations()
            }, 2000)
        } else {
            android.util.Log.e("ProfileActivity", "❌ Animation test failed - components not ready")
            android.util.Log.e("ProfileActivity", "Header initialized: ${::profileHeaderSection.isInitialized}")
            android.util.Log.e("ProfileActivity", "Tab initialized: ${::tabSection.isInitialized}")
            android.util.Log.e("ProfileActivity", "Header height: $headerHeight")
        }
    }

    fun onContentScrolled(scrollY: Int) {
        android.util.Log.d("ProfileActivity", "📜 CONTENT SCROLLED: scrollY=$scrollY, headerHeight=$headerHeight")

        if (headerHeight == 0) {
            android.util.Log.w("ProfileActivity", "⚠️ Header height is 0, skipping animation")
            return
        }

        if (!::profileHeaderSection.isInitialized || !::tabSection.isInitialized) {
            android.util.Log.w("ProfileActivity", "⚠️ Sections not initialized yet")
            return
        }

        lastScrollY = scrollY

        // Calculate animation progress based on scroll position
        val maxScroll = headerHeight.toFloat()
        val progress = min(1f, max(0f, scrollY / maxScroll))

        android.util.Log.d("ProfileActivity", "📊 ANIMATION: scrollY=$scrollY, maxScroll=$maxScroll, progress=$progress")

        animateHeaderAndTabs(progress)
    }

    private fun animateHeaderAndTabs(progress: Float) {
        // Ensure progress is within bounds
        val clampedProgress = min(1f, max(0f, progress))

        android.util.Log.d("ProfileActivity", "🎭 STARTING ANIMATION: progress=$clampedProgress")

        // Animate header sliding to left and fading
        val headerWidth = profileHeaderSection.width.toFloat()
        val translationX = -headerWidth * clampedProgress
        val alpha = 1f - clampedProgress

        android.util.Log.d("ProfileActivity", "🎭 HEADER ANIMATION: translationX=$translationX, alpha=$alpha, headerWidth=$headerWidth")

        profileHeaderSection.translationX = translationX
        profileHeaderSection.alpha = alpha

        // Animate tab section moving up
        val translationY = -headerHeight * clampedProgress

        android.util.Log.d("ProfileActivity", "📈 TAB ANIMATION: translationY=$translationY, headerHeight=$headerHeight")

        tabSection.translationY = translationY

        // Update collapsed state
        isHeaderCollapsed = clampedProgress >= 0.9f

        android.util.Log.d("ProfileActivity", "🔄 Animation applied! Header collapsed: $isHeaderCollapsed")
    }

    private fun resetAnimations() {
        android.util.Log.d("ProfileActivity", "🔄 Resetting animations")

        if (::profileHeaderSection.isInitialized && ::tabSection.isInitialized) {
            profileHeaderSection.translationX = 0f
            profileHeaderSection.alpha = 1f
            tabSection.translationY = 0f
            lastScrollY = 0
            isHeaderCollapsed = false
            android.util.Log.d("ProfileActivity", "✅ Animations reset successfully")
        } else {
            android.util.Log.w("ProfileActivity", "⚠️ Cannot reset - sections not initialized")
        }
    }

    private fun uploadProfileImage(uri: Uri) {
        android.util.Log.d("PROFILE_UPLOAD", "=== UPLOAD STARTED ===")
        android.util.Log.d("PROFILE_UPLOAD", "📁 URI: $uri")

        if (isUploading) {
            android.util.Log.w("PROFILE_UPLOAD", "⚠️ Upload already in progress, ignoring")
            return
        }

        isUploading = true
        android.util.Log.d("PROFILE_UPLOAD", "🔄 Setting upload state to true")

        // Show upload progress on header profile picture
        val profilePicture = findViewById<ImageView>(R.id.profilePicture)
        val profilePictureContainer = profilePicture.parent as? FrameLayout
        val cameraOverlay = profilePictureContainer?.findViewById<View>(R.id.headerCameraOverlay)
        val cameraIcon = profilePictureContainer?.findViewById<View>(R.id.headerCameraIcon)
        val editAvatarIcon = profilePictureContainer?.findViewById<View>(R.id.headerEditAvatarIcon)
        val uploadProgress = profilePictureContainer?.findViewById<ProgressBar>(R.id.headerUploadProgress)

        android.util.Log.d("PROFILE_UPLOAD", "🎨 UI elements found - Container: ${profilePictureContainer != null}")
        android.util.Log.d("PROFILE_UPLOAD", "🎨 Overlay: ${cameraOverlay != null}, Icon: ${cameraIcon != null}, Edit: ${editAvatarIcon != null}, Progress: ${uploadProgress != null}")

        // Show progress and hide all other icons
        cameraOverlay?.visibility = View.VISIBLE
        cameraIcon?.visibility = View.GONE
        editAvatarIcon?.visibility = View.GONE
        uploadProgress?.visibility = View.VISIBLE

        android.util.Log.d("PROFILE_UPLOAD", "✅ UI updated to show upload progress")

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            android.util.Log.e("PROFILE_UPLOAD", "❌ Firebase user is null")
            resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)
            Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show()
            return
        }

        android.util.Log.d("PROFILE_UPLOAD", "👤 Firebase user: ${user.uid}")
        android.util.Log.d("PROFILE_UPLOAD", "📧 User email: ${user.email}")

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                android.util.Log.d("PROFILE_UPLOAD", "🔑 ID Token obtained successfully")
                android.util.Log.d("PROFILE_UPLOAD", "🔑 Token length: ${idToken?.length ?: 0}")

                if (idToken == null) {
                    android.util.Log.e("PROFILE_UPLOAD", "❌ ID Token is null")
                    resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)
                    Toast.makeText(this@ProfileActivity, "Authentication token error", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile/upload-profile-image"
                android.util.Log.d("PROFILE_UPLOAD", "🌐 Backend URL: $backendUrl")
                android.util.Log.d("PROFILE_UPLOAD", "📡 Full upload URL: $url")
                android.util.Log.d("PROFILE_UPLOAD", "🔄 Using profile routes endpoint")

                android.util.Log.d("PROFILE_UPLOAD", "📖 Reading image file...")
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val fileBytes = inputStream?.readBytes()
                inputStream?.close()

                if (fileBytes == null) {
                    android.util.Log.e("PROFILE_UPLOAD", "❌ Failed to read image file - fileBytes is null")
                    resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)
                    Toast.makeText(this@ProfileActivity, "Failed to read image file", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                android.util.Log.d("PROFILE_UPLOAD", "✅ Image file read successfully")
                android.util.Log.d("PROFILE_UPLOAD", "📏 File size: ${fileBytes.size} bytes")
                android.util.Log.d("PROFILE_UPLOAD", "📏 File size: ${fileBytes.size / 1024}KB")

                android.util.Log.d("PROFILE_UPLOAD", "🔨 Building multipart request...")
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "profile.jpg",
                        RequestBody.create("image/jpeg".toMediaTypeOrNull(), fileBytes))
                    .build()

                android.util.Log.d("PROFILE_UPLOAD", "✅ Multipart body created")

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "multipart/form-data")
                    .build()

                android.util.Log.d("PROFILE_UPLOAD", "📡 Request built with headers:")
                android.util.Log.d("PROFILE_UPLOAD", "📡 - Authorization: Bearer ${idToken.take(20)}...")
                android.util.Log.d("PROFILE_UPLOAD", "📡 - Content-Type: multipart/form-data")
                android.util.Log.d("PROFILE_UPLOAD", "📡 Making HTTP request...")

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        android.util.Log.e("PROFILE_UPLOAD", "❌ HTTP REQUEST FAILED")
                        android.util.Log.e("PROFILE_UPLOAD", "❌ Exception type: ${e::class.java.simpleName}")
                        android.util.Log.e("PROFILE_UPLOAD", "❌ Exception message: ${e.message}")
                        android.util.Log.e("PROFILE_UPLOAD", "❌ Exception cause: ${e.cause}")
                        e.printStackTrace()

                        runOnUiThread {
                            resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)
                            Toast.makeText(this@ProfileActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        android.util.Log.d("PROFILE_UPLOAD", "📥 HTTP RESPONSE RECEIVED")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response code: ${response.code}")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response message: ${response.message}")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response successful: ${response.isSuccessful}")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response body length: ${body?.length ?: 0}")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response body: $body")
                        android.util.Log.d("PROFILE_UPLOAD", "📥 Response headers: ${response.headers}")

                        runOnUiThread {
                            resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)

                            try {
                                if (response.isSuccessful && body != null) {
                                    android.util.Log.d("PROFILE_UPLOAD", "✅ Response successful, parsing JSON...")
                                    val json = JSONObject(body)
                                    android.util.Log.d("PROFILE_UPLOAD", "📄 JSON parsed: $json")

                                    val success = json.optBoolean("success")
                                    android.util.Log.d("PROFILE_UPLOAD", "📊 Success field: $success")

                                    if (success) {
                                        android.util.Log.d("PROFILE_UPLOAD", "🎉 Upload marked as successful")
                                        val data = json.optJSONObject("data")
                                        android.util.Log.d("PROFILE_UPLOAD", "📄 Data object: $data")

                                        val imageUrl = data?.optString("imageUrl")
                                        android.util.Log.d("PROFILE_UPLOAD", "🖼️ Image URL: $imageUrl")

                                        if (imageUrl != null && imageUrl.isNotEmpty()) {
                                            android.util.Log.d("PROFILE_UPLOAD", "✅ Valid image URL received, updating UI...")

                                            // Update profile picture immediately
                                            Glide.with(this@ProfileActivity)
                                                .load(imageUrl)
                                                .circleCrop()
                                                .placeholder(R.drawable.ic_person)
                                                .error(R.drawable.ic_person)
                                                .into(profilePicture)

                                            cameraOverlay?.visibility = View.GONE
                                            cameraIcon?.visibility = View.GONE
                                            editAvatarIcon?.visibility = View.VISIBLE

                                            android.util.Log.d("PROFILE_UPLOAD", "🔄 Updating profile data in backend...")
                                            // Update profile data in backend
                                            updateProfilePicUrl(imageUrl)

                                            Toast.makeText(this@ProfileActivity, "Profile picture updated successfully", Toast.LENGTH_SHORT).show()
                                            android.util.Log.d("PROFILE_UPLOAD", "🎉 UPLOAD PROCESS COMPLETED SUCCESSFULLY")
                                        } else {
                                            android.util.Log.e("PROFILE_UPLOAD", "❌ Image URL is null or empty")
                                            Toast.makeText(this@ProfileActivity, "Upload failed: No image URL received", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        android.util.Log.e("PROFILE_UPLOAD", "❌ Server reported upload failure")
                                        val errorMessage = json.optString("message", "Unknown error")
                                        android.util.Log.e("PROFILE_UPLOAD", "❌ Error message: $errorMessage")
                                        Toast.makeText(this@ProfileActivity, "Upload failed: $errorMessage", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.util.Log.e("PROFILE_UPLOAD", "❌ Response not successful or body is null")
                                    android.util.Log.e("PROFILE_UPLOAD", "❌ Response code: ${response.code}")
                                    android.util.Log.e("PROFILE_UPLOAD", "❌ Response message: ${response.message}")
                                    Toast.makeText(this@ProfileActivity, "Upload failed: Server error ${response.code}", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PROFILE_UPLOAD", "❌ ERROR PARSING RESPONSE")
                                android.util.Log.e("PROFILE_UPLOAD", "❌ Exception: ${e.message}")
                                e.printStackTrace()
                                Toast.makeText(this@ProfileActivity, "Error processing upload response", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } else {
                android.util.Log.e("PROFILE_UPLOAD", "❌ FAILED TO GET ID TOKEN")
                android.util.Log.e("PROFILE_UPLOAD", "❌ Task exception: ${task.exception}")
                android.util.Log.e("PROFILE_UPLOAD", "❌ Task exception message: ${task.exception?.message}")
                task.exception?.printStackTrace()

                resetUploadState(cameraOverlay, cameraIcon, editAvatarIcon, uploadProgress)
                Toast.makeText(this@ProfileActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetUploadState(cameraOverlay: View?, cameraIcon: View?, editAvatarIcon: View?, uploadProgress: ProgressBar?) {
        isUploading = false
        uploadProgress?.visibility = View.GONE

        val currentProfilePic = profileDataJson?.optString("profilePicUrl", "")
        android.util.Log.d("PROFILE_UPLOAD", "🔄 Resetting UI state. Current profile pic: $currentProfilePic")

        if (currentProfilePic.isNullOrEmpty()) {
            android.util.Log.d("PROFILE_UPLOAD", "📷 No profile pic - showing camera icon")
            cameraOverlay?.visibility = View.VISIBLE
            cameraIcon?.visibility = View.VISIBLE
            editAvatarIcon?.visibility = View.GONE
        } else {
            android.util.Log.d("PROFILE_UPLOAD", "✏️ Has profile pic - showing edit icon")
            cameraOverlay?.visibility = View.GONE
            cameraIcon?.visibility = View.GONE
            editAvatarIcon?.visibility = View.VISIBLE
        }
    }

    private fun updateProfilePicUrl(imageUrl: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile"

                val updates = JSONObject()
                updates.put("profilePicUrl", imageUrl)

                val requestBody = RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    updates.toString()
                )

                val request = Request.Builder()
                    .url(url)
                    .put(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        android.util.Log.e("ProfileActivity", "Failed to update profile pic URL: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            android.util.Log.d("ProfileActivity", "Profile pic URL updated successfully")
                            // Refresh profile data
                            fetchProfileData()
                        }
                    }
                })
            }
        }
    }

    private fun getBackendUrl(): String {
        return sharedPreferences.getString("backend_url", "https://real-pakistan-backend.onrender.com") ?: "https://real-pakistan-backend.onrender.com"
    }

    private class ProfilePagerAdapter(fragmentActivity: FragmentActivity, private val profileData: JSONObject?) : FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            android.util.Log.d("ProfilePagerAdapter", "=== createFragment called for position: $position ===")
            return when (position) {
                0 -> {
                    android.util.Log.d("ProfilePagerAdapter", "🏗️ Creating AboutFragment")
                    android.util.Log.d("ProfilePagerAdapter", "📄 Profile data null: ${profileData == null}")
                    android.util.Log.d("ProfilePagerAdapter", "📄 Profile data string: ${profileData?.toString()?.take(200)}")

                    val fragment = AboutFragment()
                    val args = Bundle()
                    args.putString("profileData", profileData?.toString() ?: "")
                    fragment.arguments = args

                    android.util.Log.d("ProfilePagerAdapter", "✅ AboutFragment created with data")
                    fragment
                }
                1 -> {
                    android.util.Log.d("ProfilePagerAdapter", "🏗️ Creating ReviewsFragment")
                    ReviewsFragment()
                }
                else -> {
                    android.util.Log.w("ProfilePagerAdapter", "⚠️ Unknown position: $position, returning AboutFragment")
                    AboutFragment()
                }
            }
        }
    }

    private fun showProfileCompletionDialog() {
        android.util.Log.d("ProfileActivity", "=== showProfileCompletionDialog called ===")

        try {
            // Create custom dialog
            val dialog = Dialog(this)
            dialog.setContentView(R.layout.dialog_profile_completion)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            // Get views from dialog
            val completeNowButton = dialog.findViewById<Button>(R.id.completeNowButton)
            val skipForNowButton = dialog.findViewById<TextView>(R.id.skipForNowButton)
            val progressBar = dialog.findViewById<ProgressBar>(R.id.profileProgressBar)
            val progressPercentage = dialog.findViewById<TextView>(R.id.progressPercentage)

            // Calculate profile completion percentage based on available data
            val completionPercentage = calculateProfileCompletionPercentage()
            progressBar?.progress = completionPercentage
            progressPercentage?.text = "$completionPercentage%"

            android.util.Log.d("ProfileActivity", "📊 Profile completion: $completionPercentage%")

            // Show dialog
            android.util.Log.d("ProfileActivity", "🎉 Showing profile completion dialog")
            dialog.show()

            // Handle Complete Now button
            completeNowButton?.setOnClickListener {
                android.util.Log.d("ProfileActivity", "✅ Complete Now button clicked")
                dialog.dismiss()

                // User is already on the About page, just show a helpful message
                Toast.makeText(this,
                    "Tap the edit icons next to each section to complete your profile!",
                    Toast.LENGTH_LONG).show()
            }

            // Handle Skip button
            skipForNowButton?.setOnClickListener {
                android.util.Log.d("ProfileActivity", "⏭️ Skip button clicked")
                dialog.dismiss()

                // Save that user chose to skip (don't show again for a while)
                sharedPreferences.edit()
                    .putLong("profile_completion_skip_timestamp", System.currentTimeMillis())
                    .apply()

                Toast.makeText(this,
                    "You can complete your profile anytime by tapping the edit icons",
                    Toast.LENGTH_LONG).show()
            }

            android.util.Log.d("ProfileActivity", "✅ Profile completion dialog setup complete")

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "❌ Error showing profile completion dialog: ${e.message}", e)
            Toast.makeText(this, "Welcome! Please complete your profile using the edit icons.", Toast.LENGTH_LONG).show()
        }
    }

    private fun calculateProfileCompletionPercentage(): Int {
        val data = profileDataJson ?: return 25 // Basic signup completion

        var completedFields = 0
        val totalFields = 8 // Total important profile fields

        // Check each important field
        if (data.optString("firstName", "").isNotEmpty()) completedFields++
        if (data.optString("lastName", "").isNotEmpty()) completedFields++
        if (data.optString("email", "").isNotEmpty()) completedFields++
        if (data.optString("description", "").isNotEmpty()) completedFields++
        if (data.optString("location", "").isNotEmpty()) completedFields++
        if (data.optString("profilePicUrl", "").isNotEmpty()) completedFields++

        val languages = data.optJSONArray("Languages")
        if (languages != null && languages.length() > 0) completedFields++

        val skills = data.optJSONArray("skills")
        if (skills != null && skills.length() > 0) completedFields++

        return (completedFields * 100) / totalFields
    }
}
