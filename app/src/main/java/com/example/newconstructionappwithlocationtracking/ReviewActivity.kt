package com.example.newconstructionappwithlocationtracking

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

class ReviewActivity : AppCompatActivity() {

    private lateinit var closeButton: ImageView
    private lateinit var userProfilePic: ImageView
    private lateinit var userNameText: TextView
    private lateinit var jobTitleText: TextView
    private lateinit var ratingBar: RatingBar
    private lateinit var commentEditText: EditText
    private lateinit var submitButton: Button
    private lateinit var loadingProgress: ProgressBar

    private lateinit var orderId: String
    private lateinit var revieweeId: String
    private lateinit var reviewType: String
    private var existingReviewId: String? = null
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        // Get data from intent
        orderId = intent.getStringExtra("ORDER_ID") ?: ""
        revieweeId = intent.getStringExtra("REVIEWEE_ID") ?: ""
        reviewType = intent.getStringExtra("REVIEW_TYPE") ?: ""
        val userName = intent.getStringExtra("USER_NAME") ?: "User"
        val jobTitle = intent.getStringExtra("JOB_TITLE") ?: "Construction Job"
        val userProfilePicUrl = intent.getStringExtra("USER_PROFILE_PIC") ?: ""
        existingReviewId = intent.getStringExtra("EXISTING_REVIEW_ID")

        Log.d("REVIEW_ACTIVITY", "Received intent extras:")
        Log.d("REVIEW_ACTIVITY", "  ORDER_ID: $orderId")
        Log.d("REVIEW_ACTIVITY", "  REVIEWEE_ID: $revieweeId")
        Log.d("REVIEW_ACTIVITY", "  REVIEW_TYPE: $reviewType")
        Log.d("REVIEW_ACTIVITY", "  USER_NAME: $userName")
        Log.d("REVIEW_ACTIVITY", "  JOB_TITLE: $jobTitle")
        Log.d("REVIEW_ACTIVITY", "  USER_PROFILE_PIC: '$userProfilePicUrl'")
        Log.d("REVIEW_ACTIVITY", "  EXISTING_REVIEW_ID: $existingReviewId")

        // Check if this is edit mode
        isEditMode = existingReviewId != null

        if (orderId.isEmpty() || revieweeId.isEmpty() || reviewType.isEmpty()) {
            Toast.makeText(this, "Invalid review data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupData(userName, jobTitle, userProfilePicUrl)
        setupClickListeners()

        // If edit mode, load existing review
        if (isEditMode) {
            loadExistingReview()
        }
    }

    private fun initViews() {
        closeButton = findViewById(R.id.closeButton)
        userProfilePic = findViewById(R.id.userProfilePic)
        userNameText = findViewById(R.id.userNameText)
        jobTitleText = findViewById(R.id.jobTitleText)
        ratingBar = findViewById(R.id.ratingBar)
        commentEditText = findViewById(R.id.commentEditText)
        submitButton = findViewById(R.id.submitButton)
        loadingProgress = findViewById(R.id.loadingProgress)
    }

    private fun setupData(userName: String, jobTitle: String, userProfilePicUrl: String) {
        Log.d("REVIEW_ACTIVITY", "Setting up data - userName: $userName, jobTitle: $jobTitle, profilePicUrl: '$userProfilePicUrl'")

        userNameText.text = userName
        jobTitleText.text = jobTitle

        if (userProfilePicUrl.isNotEmpty()) {
            Log.d("REVIEW_ACTIVITY", "Loading profile picture from URL: $userProfilePicUrl")
            try {
                Glide.with(this)
                    .load(userProfilePicUrl)
                    .circleCrop()
                    .placeholder(R.drawable.app_icon_logo)
                    .error(R.drawable.app_icon_logo)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.e("REVIEW_ACTIVITY", "Failed to load profile picture: ${e?.message}")
                            return false // Let Glide handle the error drawable
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.d("REVIEW_ACTIVITY", "Profile picture loaded successfully")
                            return false // Let Glide handle the drawable
                        }
                    })
                    .into(userProfilePic)
            } catch (e: Exception) {
                Log.e("REVIEW_ACTIVITY", "Exception loading profile picture", e)
                userProfilePic.setImageResource(R.drawable.app_icon_logo)
            }
        } else {
            Log.w("REVIEW_ACTIVITY", "Profile picture URL is empty, using placeholder")
            userProfilePic.setImageResource(R.drawable.app_icon_logo)
        }

        // Set default rating to 5 stars
        ratingBar.rating = 5.0f

        // Update button text based on mode
        submitButton.text = if (isEditMode) "Update Review" else "Submit Review"
    }

    private fun setupClickListeners() {
        closeButton.setOnClickListener { finish() }

        submitButton.setOnClickListener {
            val rating = ratingBar.rating.toInt()
            val comment = commentEditText.text.toString().trim()

            if (rating == 0) {
                Toast.makeText(this, "Please select a rating", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (comment.isEmpty()) {
                Toast.makeText(this, "Please write a comment", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isEditMode) {
                updateReview(rating, comment)
            } else {
                submitReview(rating, comment)
            }
        }
    }

    private fun loadExistingReview() {
        loadingProgress.visibility = View.VISIBLE

        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
            ?.addOnSuccessListener { result ->
                val token = result.token
                fetchExistingReview(token)
            }
            ?.addOnFailureListener { exception ->
                Log.e("REVIEW_ACTIVITY", "Failed to get token", exception)
                loadingProgress.visibility = View.GONE
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun fetchExistingReview(token: String?) {
        if (token == null || existingReviewId == null) {
            loadingProgress.visibility = View.GONE
            return
        }

        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/reviews/order/$orderId/all"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    loadingProgress.visibility = View.GONE
                    Log.e("REVIEW_ACTIVITY", "Failed to load existing review", e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d("REVIEW_ACTIVITY", "Existing review response: $responseBody")

                runOnUiThread {
                    loadingProgress.visibility = View.GONE

                    if (response.isSuccessful) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            if (jsonResponse.getBoolean("success")) {
                                val reviews = jsonResponse.getJSONArray("reviews")
                                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

                                // Find the review by current user for this review type
                                for (i in 0 until reviews.length()) {
                                    val review = reviews.getJSONObject(i)
                                    if (review.getString("reviewerId") == currentUserId &&
                                        review.getString("reviewType") == reviewType) {

                                        // Populate fields with existing data
                                        ratingBar.rating = review.getInt("rating").toFloat()
                                        commentEditText.setText(review.optString("comment", ""))
                                        break
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("REVIEW_ACTIVITY", "Error parsing existing review", e)
                        }
                    }
                }
            }
        })
    }

    private fun submitReview(rating: Int, comment: String) {
        loadingProgress.visibility = View.VISIBLE
        submitButton.isEnabled = false

        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
            ?.addOnSuccessListener { result ->
                val token = result.token
                makeReviewRequest(token, rating, comment, false)
            }
            ?.addOnFailureListener { exception ->
                Log.e("REVIEW_ACTIVITY", "Failed to get token", exception)
                loadingProgress.visibility = View.GONE
                submitButton.isEnabled = true
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateReview(rating: Int, comment: String) {
        loadingProgress.visibility = View.VISIBLE
        submitButton.isEnabled = false

        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
            ?.addOnSuccessListener { result ->
                val token = result.token
                makeReviewRequest(token, rating, comment, true)
            }
            ?.addOnFailureListener { exception ->
                Log.e("REVIEW_ACTIVITY", "Failed to get token", exception)
                loadingProgress.visibility = View.GONE
                submitButton.isEnabled = true
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun makeReviewRequest(token: String?, rating: Int, comment: String, isUpdate: Boolean) {
        if (token == null) {
            loadingProgress.visibility = View.GONE
            submitButton.isEnabled = true
            Toast.makeText(this, "Authentication token not available", Toast.LENGTH_SHORT).show()
            return
        }

        val backendUrl = getBackendUrl()
        val url = if (isUpdate && existingReviewId != null) {
            "$backendUrl/api/reviews/$existingReviewId"
        } else {
            "$backendUrl/api/reviews"
        }

        val jsonBody = JSONObject().apply {
            if (!isUpdate) {
                put("orderId", orderId)
                put("revieweeId", revieweeId)
                put("reviewType", reviewType)
            }
            put("rating", rating)
            put("comment", comment)
        }

        val requestBody = RequestBody.create(
            "application/json".toMediaType(),
            jsonBody.toString()
        )

        val request = Request.Builder()
            .url(url)
            .apply {
                if (isUpdate) {
                    put(requestBody)
                } else {
                    post(requestBody)
                }
            }
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        Log.d("REVIEW_ACTIVITY", "Making review request: rating=$rating, comment=$comment, isUpdate=$isUpdate")

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    loadingProgress.visibility = View.GONE
                    submitButton.isEnabled = true
                    Log.e("REVIEW_ACTIVITY", "Request failed", e)
                    Toast.makeText(this@ReviewActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d("REVIEW_ACTIVITY", "Review response: $responseBody")

                runOnUiThread {
                    loadingProgress.visibility = View.GONE
                    submitButton.isEnabled = true

                    if (response.isSuccessful) {
                        val message = if (isUpdate) "Review updated successfully!" else "Review submitted successfully!"
                        Toast.makeText(this@ReviewActivity, message, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        try {
                            val errorJson = JSONObject(responseBody)
                            val errorMessage = errorJson.optString("message", "Failed to submit review")

                            // Check if review already exists and offer edit option
                            if (errorJson.optBoolean("canEdit", false)) {
                                existingReviewId = errorJson.optString("existingReviewId")
                                isEditMode = true
                                submitButton.text = "Update Review"
                                loadExistingReview()
                                Toast.makeText(this@ReviewActivity, "Review already exists. You can edit it.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@ReviewActivity, errorMessage, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@ReviewActivity, "Failed to submit review", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun getBackendUrl(): String {
        return try {
            val inputStream = assets.open(".env")
            val envContent = inputStream.bufferedReader().use { it.readText() }
            val backendUrlLine = envContent.lines().find { it.startsWith("BACKEND_URL=") }
            backendUrlLine?.substringAfter("BACKEND_URL=")?.trim() ?: "https://real-pakistan-backend.onrender.com"
        } catch (e: Exception) {
            Log.w("REVIEW_ACTIVITY", ".env not found in assets")
            "https://real-pakistan-backend.onrender.com"
        }
    }
}
