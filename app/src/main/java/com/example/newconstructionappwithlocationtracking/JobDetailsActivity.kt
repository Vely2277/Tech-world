package com.example.newconstructionappwithlocationtracking

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.adapters.ImageSlideshowAdapter
import com.example.newconstructionappwithlocationtracking.models.Job
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class JobDetailsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var contentScrollView: ScrollView
    private lateinit var imagesViewPager: ViewPager2
    private lateinit var prevButton: View
    private lateinit var nextButton: View
    private lateinit var jobTitle: TextView
    private lateinit var jobDescription: TextView
    private lateinit var jobPrice: TextView
    private lateinit var jobLocation: TextView
    private lateinit var jobDatePosted: TextView
    private lateinit var buyerProfileImage: ImageView
    private lateinit var buyerName: TextView
    private lateinit var contactBuyerButton: View
    private lateinit var applyButton: View

    private var jobId: String? = null
    private var currentJob: Job? = null
    private val client = OkHttpClient()

    // GPS dialog helper for GPS enable flow
    val gpsDialogHelper: com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper by lazy {
        com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register GPS resolution launcher BEFORE setContentView
        gpsDialogHelper.registerGpsLauncher(this)

        setContentView(R.layout.activity_job_details)

        // Get job ID from intent
        jobId = intent.getStringExtra("jobId")
        if (jobId.isNullOrEmpty()) {
            showError("Invalid job ID")
            finish()
            return
        }

        initializeViews()
        setupClickListeners()
        fetchJobDetails()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        loadingLayout = findViewById(R.id.loadingLayout)
        contentScrollView = findViewById(R.id.contentScrollView)
        imagesViewPager = findViewById(R.id.imagesViewPager)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        jobTitle = findViewById(R.id.jobTitle)
        jobDescription = findViewById(R.id.jobDescription)
        jobPrice = findViewById(R.id.jobPrice)
        jobLocation = findViewById(R.id.jobLocation)
        jobDatePosted = findViewById(R.id.jobDatePosted)
        buyerProfileImage = findViewById(R.id.buyerProfileImage)
        buyerName = findViewById(R.id.buyerName)
        contactBuyerButton = findViewById(R.id.contactBuyerButton)
        applyButton = findViewById(R.id.applyButton)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            finish()
        }

        contactBuyerButton.setOnClickListener {
            currentJob?.let { job ->
                // Show loading dialog first
                val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)
                val loadingDialog = gpsDialogHelper.showLoadingDialog()

                // Simulate brief check delay
                window.decorView.postDelayed({
                    loadingDialog.dismiss()

                    // Check permissions before allowing contact
                    val canProceed = permissionManager.checkPermissionsForJobAction(this, object : com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.JobActionCallback {
                        override fun onPermissionsGranted() {
                            // Permissions granted - check GPS
                            gpsDialogHelper.resetGpsAttempts()
                            gpsDialogHelper.promptEnableGps(
                                onGpsEnabled = {
                                    val intent = android.content.Intent(this@JobDetailsActivity, BuyerProfileActivity::class.java)
                                    intent.putExtra("uid", job.uid)
                                    startActivity(intent)
                                },
                                onGpsFlowEnded = {
                                    // User rejected GPS twice - stay on page
                                }
                            )
                        }

                        override fun onPermissionDenied(showSettingsDialog: Boolean, message: String) {
                            if (showSettingsDialog) {
                                gpsDialogHelper.showPermissionDeniedDialog(message, true) {
                                    permissionManager.openBackgroundLocationSettings(this@JobDetailsActivity)
                                }
                            } else {
                                gpsDialogHelper.showPermissionDeniedDialog(message, false) {
                                    // Dismissed - user stays on page
                                }
                            }
                        }

                        override fun onBackgroundPermissionNeeded() {
                            // Fine location granted but no background - check GPS first
                            gpsDialogHelper.resetGpsAttempts()
                            gpsDialogHelper.promptEnableGps(
                                onGpsEnabled = {
                                    gpsDialogHelper.showBackgroundPermissionNeededDialog {
                                        permissionManager.openBackgroundLocationSettings(this@JobDetailsActivity)
                                    }
                                },
                                onGpsFlowEnded = {
                                    // User rejected GPS twice - stay on page
                                }
                            )
                        }
                    })

                    // Only handle fine location request flow (SCENARIO 2)
                    // Skip if fine is already granted (SCENARIO 3 - onBackgroundPermissionNeeded handles it)
                    if (!canProceed && !permissionManager.hasFineLocationPermission()) {
                        // Use GLOBAL denial counter (shared across all features)
                        val globalCount = permissionManager.getGlobalFineLocationDenialCount()
                        val isBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

                        android.util.Log.d("JOB_PERMISSION", "View Buyer check: globalCount=$globalCount, isBlocking=$isBlocking")

                        if (!isBlocking && (globalCount == 0 || globalCount == 1)) {
                            gpsDialogHelper.showPermissionRationaleDialog {
                                permissionManager.requestFineLocationForJobAction(this@JobDetailsActivity)
                            }
                        }
                        // If isBlocking (count >= 2), callback already showed "Go to Settings" dialog
                    }
                }, 600)
            }
        }

        // Apply button - open chat with buyer and pass job details
        applyButton.setOnClickListener {
            currentJob?.let { job ->
                // Show loading dialog first
                val permissionManager = com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager(this)
                val loadingDialog = gpsDialogHelper.showLoadingDialog()

                // Simulate brief check delay
                window.decorView.postDelayed({
                    loadingDialog.dismiss()

                    // Check permissions before allowing apply
                    val canProceed = permissionManager.checkPermissionsForJobAction(this, object : com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager.JobActionCallback {
                        override fun onPermissionsGranted() {
                            // Permissions granted - check GPS
                            gpsDialogHelper.resetGpsAttempts()
                            gpsDialogHelper.promptEnableGps(
                                onGpsEnabled = {
                                    openChatWithJobDetails(job)
                                },
                                onGpsFlowEnded = {
                                    // User rejected GPS twice - stay on page
                                }
                            )
                        }

                        override fun onPermissionDenied(showSettingsDialog: Boolean, message: String) {
                            if (showSettingsDialog) {
                                gpsDialogHelper.showPermissionDeniedDialog(message, true) {
                                    permissionManager.openBackgroundLocationSettings(this@JobDetailsActivity)
                                }
                            } else {
                                gpsDialogHelper.showPermissionDeniedDialog(message, false) {
                                    // Dismissed - user stays on page
                                }
                            }
                        }

                        override fun onBackgroundPermissionNeeded() {
                            // Fine location granted but no background - check GPS first
                            gpsDialogHelper.resetGpsAttempts()
                            gpsDialogHelper.promptEnableGps(
                                onGpsEnabled = {
                                    gpsDialogHelper.showBackgroundPermissionNeededDialog {
                                        permissionManager.openBackgroundLocationSettings(this@JobDetailsActivity)
                                    }
                                },
                                onGpsFlowEnded = {
                                    // User rejected GPS twice - stay on page
                                }
                            )
                        }
                    })

                    // Only handle fine location request flow (SCENARIO 2)
                    // Skip if fine is already granted (SCENARIO 3 - onBackgroundPermissionNeeded handles it)
                    if (!canProceed && !permissionManager.hasFineLocationPermission()) {
                        // Use GLOBAL denial counter (shared across all features)
                        val globalCount = permissionManager.getGlobalFineLocationDenialCount()
                        val isBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

                        android.util.Log.d("JOB_PERMISSION", "Apply check: globalCount=$globalCount, isBlocking=$isBlocking")

                        if (!isBlocking && (globalCount == 0 || globalCount == 1)) {
                            gpsDialogHelper.showPermissionRationaleDialog {
                                permissionManager.requestFineLocationForJobAction(this@JobDetailsActivity)
                            }
                        }
                        // If isBlocking (count >= 2), callback already showed "Go to Settings" dialog
                    }
                }, 600)
            }
        }
    }

    private fun fetchJobDetails() {
        showLoading()

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showError("Authentication required")
            finish()
            return
        }

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                fetchJobDetailsWithToken(idToken)
            } else {
                showError("Authentication failed")
                finish()
            }
        }
    }

    private fun fetchJobDetailsWithToken(idToken: String?) {
        if (idToken == null) {
            showError("Authentication token not available")
            finish()
            return
        }

        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/jobs/$jobId"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $idToken")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showError("Failed to load job details: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d("JOB_DETAILS", "API Response: $body")

                runOnUiThread {
                    try {
                        if (response.isSuccessful && body.isNotEmpty()) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success")) {
                                val jobData = json.optJSONObject("data")
                                if (jobData != null) {
                                    parseAndDisplayJob(jobData)
                                    fetchBuyerProfile(jobData.optString("uid"))
                                } else {
                                    showError("Invalid job data received")
                                }
                            } else {
                                showError(json.optString("message", "Failed to fetch job details"))
                            }
                        } else {
                            showError("Server error: ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e("JOB_DETAILS", "Error parsing response", e)
                        showError("Error loading job details")
                    }
                }
            }
        })
    }

    private fun parseAndDisplayJob(jobData: JSONObject) {
        try {
            // Parse job data
            val title = jobData.optString("title", "No Title")
            val description = jobData.optString("description", "No Description")
            val price = jobData.optInt("price", 0)
            val location = jobData.optString("location", "No Location")

            // Parse date
            val datePosted = jobData.optJSONObject("datePosted")
            val dateText = if (datePosted != null && datePosted.has("_seconds")) {
                val seconds = datePosted.getLong("_seconds")
                val date = java.util.Date(seconds * 1000)
                val formatter = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                "Posted on ${formatter.format(date)}"
            } else {
                "Date not available"
            }

            // Parse image URLs
            val imageUrlsArray = jobData.optJSONArray("imageUrls")
            val imageUrls = mutableListOf<String>()
            if (imageUrlsArray != null) {
                for (i in 0 until imageUrlsArray.length()) {
                    val url = imageUrlsArray.optString(i)
                    if (url.isNotEmpty()) {
                        imageUrls.add(url)
                    }
                }
            }

            // Create Job object for reference
            currentJob = Job(
                id = jobData.optString("id", jobId ?: ""),
                title = title,
                description = description,
                location = location,
                datePosted = com.example.newconstructionappwithlocationtracking.models.DatePosted(
                    _seconds = datePosted?.optLong("_seconds") ?: 0L,
                    _nanoseconds = datePosted?.optLong("_nanoseconds") ?: 0L
                ),
                uid = jobData.optString("uid", ""),
                price = price,
                imageUrls = imageUrls
            )

            // Display the data
            displayJobDetails(title, description, price, location, dateText, imageUrls)

        } catch (e: Exception) {
            Log.e("JOB_DETAILS", "Error parsing job data", e)
            showError("Error displaying job details")
        }
    }

    private fun displayJobDetails(
        title: String,
        description: String,
        price: Int,
        location: String,
        dateText: String,
        imageUrls: List<String>
    ) {
        jobTitle.text = title
        jobDescription.text = description
        jobPrice.text = "$$price"
        jobLocation.text = location
        jobDatePosted.text = dateText

        // Setup image slideshow
        if (imageUrls.isNotEmpty()) {
            setupImageSlideshow(imageUrls)
        } else {
            // Show placeholder if no images
            imagesViewPager.visibility = View.GONE
            // You could show a "No images available" message here
        }

        showContent()
    }

    private fun setupImageSlideshow(imageUrls: List<String>) {
        val imageAdapter = ImageSlideshowAdapter(imageUrls)
        imagesViewPager.adapter = imageAdapter

        // Setup navigation buttons
        prevButton.setOnClickListener {
            val currentItem = imagesViewPager.currentItem
            if (currentItem > 0) {
                imagesViewPager.currentItem = currentItem - 1
            }
        }

        nextButton.setOnClickListener {
            val currentItem = imagesViewPager.currentItem
            if (currentItem < imageUrls.size - 1) {
                imagesViewPager.currentItem = currentItem + 1
            }
        }

        // Hide/show navigation buttons based on image count
        if (imageUrls.size <= 1) {
            prevButton.visibility = View.GONE
            nextButton.visibility = View.GONE
        } else {
            prevButton.visibility = View.VISIBLE
            nextButton.visibility = View.VISIBLE
        }
    }

    private fun fetchBuyerProfile(buyerUid: String) {
        if (buyerUid.isEmpty()) return

        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile/$buyerUid"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            buyerName.text = "Buyer Profile"
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        runOnUiThread {
                            try {
                                if (response.isSuccessful && body.isNotEmpty()) {
                                    val json = JSONObject(body)
                                    val data = json.optJSONObject("data")
                                    if (data != null) {
                                        val firstName = data.optString("firstName", "")
                                        val lastName = data.optString("lastName", "")
                                        val fullName = "$firstName $lastName".trim()
                                        val profilePicUrl = data.optString("profilePicUrl", "")

                                        buyerName.text = if (fullName.isNotEmpty()) fullName else "Anonymous Buyer"

                                        // Load profile picture
                                        if (profilePicUrl.isNotEmpty()) {
                                            Glide.with(this@JobDetailsActivity)
                                                .load(profilePicUrl)
                                                .placeholder(R.drawable.ic_avatar_placeholder)
                                                .circleCrop()
                                                .into(buyerProfileImage)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("JOB_DETAILS", "Error parsing buyer profile", e)
                                buyerName.text = "Buyer Profile"
                            }
                        }
                    }
                })
            }
        }
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        contentScrollView.visibility = View.GONE
    }

    private fun showContent() {
        loadingLayout.visibility = View.GONE
        contentScrollView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openChatWithJobDetails(job: Job) {
        val currentUserUID = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Create job details for the chat
        val jobDetailsJson = JSONObject().apply {
            put("jobId", job.id)
            put("title", job.title)
            put("description", if (job.description.length > 100)
                "${job.description.substring(0, 100)}..."
                else job.description)
            put("imageUrl", if (job.imageUrls.isNotEmpty()) job.imageUrls[0] else "")
            put("price", job.price)
            put("buyerId", job.uid)
        }

        // Navigate to MainActivity and open chat
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("openChat", true)
            putExtra("recipientId", job.uid)
            putExtra("currentUserId", currentUserUID)
            putExtra("jobDetails", jobDetailsJson.toString())
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun getBackendUrl(): String {
        return "https://real-pakistan-backend.onrender.com"
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
                                    com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(this@JobDetailsActivity)
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
                            gpsDialogHelper.showPermissionDeniedDialog(message, true) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    permissionManager.requestBackgroundLocationPermission(this@JobDetailsActivity)
                                }
                            }
                        } else {
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
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        permissionManager.requestBackgroundLocationPermission(this@JobDetailsActivity)
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
