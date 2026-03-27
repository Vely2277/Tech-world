package com.example.newconstructionappwithlocationtracking

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.models.OrderDetails
import com.example.newconstructionappwithlocationtracking.adapters.JobImagesAdapter
import com.example.newconstructionappwithlocationtracking.adapters.DeliveryFilesAdapter
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.*

class OrderDetailsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var contentScrollView: ScrollView
    private lateinit var imagesViewPager: ViewPager2
    private lateinit var jobTitle: TextView
    private lateinit var jobDescription: TextView
    private lateinit var jobPrice: TextView
    private lateinit var orderStatus: TextView
    private lateinit var clientProfileImage: ImageView
    private lateinit var clientName: TextView
    private lateinit var clientLocation: TextView
    private lateinit var deliveryCard: androidx.cardview.widget.CardView
    private lateinit var deliveryText: TextView
    private lateinit var deliveryImage: ImageView
    private lateinit var deliverButton: androidx.appcompat.widget.AppCompatButton
    private lateinit var approveButton: androidx.appcompat.widget.AppCompatButton
    private lateinit var contactButton: androidx.appcompat.widget.AppCompatButton
    private lateinit var filesRecyclerView: RecyclerView

    // Review-related views
    private lateinit var reviewSection: androidx.cardview.widget.CardView
    private lateinit var writeReviewButton: Button
    private lateinit var reviewsRecyclerView: RecyclerView
    private lateinit var noReviewsMessage: TextView
    private lateinit var reviewsLoadingIndicator: ProgressBar

    // Remove old single review views since we now use RecyclerView
    // private lateinit var reviewCard: androidx.cardview.widget.CardView
    // private lateinit var reviewerName: TextView
    // private lateinit var reviewRating: RatingBar
    // private lateinit var reviewComment: TextView
    // private lateinit var reviewReply: TextView
    // private lateinit var replyButton: Button
    // private lateinit var reviewDate: TextView

    private var currentUserId: String? = null
    private var orderId: String? = null

    // Store order data for chat navigation
    private var orderUid: String? = null
    private var sellerId: String? = null
    private var sellerName: String? = null
    private var jobTitleStr: String? = null
    private var sellerProfilePicUrl: String? = null
    private var buyerName: String? = null
    private var buyerProfilePicUrl: String? = null

    // Reviews data
    private val reviewsList = mutableListOf<com.example.newconstructionappwithlocationtracking.models.Review>()
    private var reviewsAdapter: com.example.newconstructionappwithlocationtracking.adapters.ReviewsAdapter? = null

    private val client = OkHttpClient()

    // Activity result launcher for delivery
    private lateinit var deliveryLauncher: ActivityResultLauncher<Intent>
    // Activity result launcher for review
    private lateinit var reviewLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_details)

        currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        orderId = intent.getStringExtra("orderId")

        if (orderId == null || currentUserId == null) {
            Toast.makeText(this, "Invalid order or authentication", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize delivery launcher
        deliveryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Refresh order details after successful delivery
                fetchOrderDetails()
                Toast.makeText(this, "Order delivered successfully!", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize review launcher
        reviewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Show loading and refresh reviews after successful review
                Log.d("OrderDetailsActivity", "Review activity returned with success, refreshing reviews")
                showReviewsLoading()
                Toast.makeText(this, "Review updated successfully!", Toast.LENGTH_SHORT).show()
            }
        }

        initializeViews()
        setupClickListeners()
        fetchOrderDetails()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        loadingLayout = findViewById(R.id.loadingLayout)
        contentScrollView = findViewById(R.id.contentScrollView)
        imagesViewPager = findViewById(R.id.imagesViewPager)
        jobTitle = findViewById(R.id.jobTitle)
        jobDescription = findViewById(R.id.jobDescription)
        jobPrice = findViewById(R.id.jobPrice)
        orderStatus = findViewById(R.id.orderStatus)
        clientProfileImage = findViewById(R.id.clientProfileImage)
        clientName = findViewById(R.id.clientName)
        clientLocation = findViewById(R.id.clientLocation)
        deliveryCard = findViewById(R.id.deliveryCard)
        deliveryText = findViewById(R.id.deliveryText)
        deliveryImage = findViewById(R.id.deliveryImage)
        deliverButton = findViewById(R.id.deliverButton)
        approveButton = findViewById(R.id.approveButton)
        contactButton = findViewById(R.id.contactButton)
        filesRecyclerView = findViewById(R.id.filesRecyclerView)

        // Review-related views
        reviewSection = findViewById(R.id.reviewSection)
        writeReviewButton = findViewById(R.id.writeReviewButton)
        reviewsRecyclerView = findViewById(R.id.reviewsRecyclerView)
        noReviewsMessage = findViewById(R.id.noReviewsMessage)
        reviewsLoadingIndicator = findViewById(R.id.reviewsLoadingIndicator)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            finish()
        }

        deliverButton.setOnClickListener {
            openDeliveryActivity()
        }

        approveButton.setOnClickListener {
            approveOrder()
        }

        contactButton.setOnClickListener {
            openChat()
        }

        writeReviewButton.setOnClickListener {
            openReviewActivity()
        }
    }

    private fun openDeliveryActivity() {
        val intent = Intent(this, DeliveryActivity::class.java)
        intent.putExtra("orderId", orderId)
        deliveryLauncher.launch(intent)
    }

    private fun openReviewActivity() {
        // Check if user has already written a review (for editing)
        val existingReview = reviewsList.find { it.reviewerId == currentUserId }

        // Find the other user's review to get their profile info for display
        val otherUserReview = reviewsList.find { it.reviewerId != currentUserId }

        // Determine who the current user can review based on their role in this order
        val revieweeId: String
        val revieweeName: String
        val revieweeProfilePic: String
        val reviewType: String

        if (currentUserId == orderUid) {
            // Current user is the buyer, they can review the seller
            revieweeId = sellerId ?: ""
            reviewType = "buyer_to_seller"

            // Use seller info from other user's review if available, fallback to order data
            if (otherUserReview != null && otherUserReview.reviewType == "seller_to_buyer") {
                revieweeName = otherUserReview.reviewerName
                revieweeProfilePic = otherUserReview.reviewerProfilePic
            } else {
                revieweeName = sellerName ?: "Seller"
                revieweeProfilePic = sellerProfilePicUrl ?: ""
            }
        } else if (currentUserId == sellerId) {
            // Current user is the seller, they can review the buyer
            revieweeId = orderUid ?: ""
            reviewType = "seller_to_buyer"

            // Use buyer info from other user's review if available, fallback to order data
            if (otherUserReview != null && otherUserReview.reviewType == "buyer_to_seller") {
                revieweeName = otherUserReview.reviewerName
                revieweeProfilePic = otherUserReview.reviewerProfilePic
            } else {
                revieweeName = buyerName ?: clientName.text.toString()
                revieweeProfilePic = buyerProfilePicUrl ?: ""
            }
        } else {
            Toast.makeText(this, "You cannot review this order", Toast.LENGTH_SHORT).show()
            return
        }

        if (revieweeId.isEmpty()) {
            Toast.makeText(this, "Cannot determine reviewee", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("ORDER_DETAILS", "Opening review activity - revieweeName: $revieweeName, revieweeProfilePic: '$revieweeProfilePic', reviewType: $reviewType")
        Log.d("ORDER_DETAILS", "Using profile data from ${if (otherUserReview != null) "other user's review" else "order data"}")

        val intent = Intent(this, ReviewActivity::class.java)
        intent.putExtra("ORDER_ID", orderId)
        intent.putExtra("REVIEWEE_ID", revieweeId)
        intent.putExtra("USER_NAME", revieweeName)
        intent.putExtra("JOB_TITLE", jobTitleStr ?: "Construction Job")
        intent.putExtra("USER_PROFILE_PIC", revieweeProfilePic)
        intent.putExtra("REVIEW_TYPE", reviewType)

        // If editing existing review, pass the review data
        if (existingReview != null) {
            intent.putExtra("IS_EDIT_MODE", true)
            intent.putExtra("EXISTING_REVIEW_ID", existingReview.id)
            intent.putExtra("EXISTING_RATING", existingReview.rating)
            intent.putExtra("EXISTING_COMMENT", existingReview.comment)
        } else {
            intent.putExtra("IS_EDIT_MODE", false)
        }

        reviewLauncher.launch(intent)
    }

    private fun approveOrder() {
        if (orderId == null) return

        // Show confirmation dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Approve Delivery")
            .setMessage("Are you sure you want to approve this delivery? This action cannot be undone and will complete the order.")
            .setPositiveButton("Approve") { _, _ ->
                performApproveOrder()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performApproveOrder() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        showLoading()

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/orders/$orderId/approve"

                val request = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            hideLoading()
                            Toast.makeText(this@OrderDetailsActivity, "Failed to approve order: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        runOnUiThread {
                            hideLoading()
                            try {
                                if (response.isSuccessful) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        Toast.makeText(this@OrderDetailsActivity, "Order approved successfully!", Toast.LENGTH_SHORT).show()
                                        fetchOrderDetails() // Refresh to show updated status
                                    } else {
                                        throw Exception(json.optString("message", "Approval failed"))
                                    }
                                } else {
                                    throw Exception("HTTP ${response.code}")
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@OrderDetailsActivity, "Approval failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            }
        }
    }

    private fun fetchOrderDetails() {
        showLoading()

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showError("User not authenticated")
            return
        }

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                fetchOrderDetails(idToken)
            } else {
                showError("Failed to get authentication token")
            }
        }
    }

    private fun fetchOrderDetails(idToken: String?) {
        if (idToken == null) {
            showError("Authentication token is null")
            return
        }

        val backendUrl = getBackendUrl()
        val request = Request.Builder()
            .url("$backendUrl/api/orders/$orderId/details")  // Fixed: Added /details to get complete order information
            .addHeader("Authorization", "Bearer $idToken")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showError("Failed to load order details: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    runOnUiThread {
                        showError("Failed to load order details")
                    }
                    return
                }

                try {
                    val json = JSONObject(body)
                    if (json.getBoolean("success")) {
                        val orderData = json.getJSONObject("data")
                        runOnUiThread {
                            displayOrderDetails(orderData)
                        }
                    } else {
                        runOnUiThread {
                            showError(json.optString("message", "Unknown error"))
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showError("Error parsing response: ${e.message}")
                    }
                }
            }
        })
    }

    private fun displayOrderDetails(orderData: JSONObject) {
        try {
            // Store order data for chat navigation
            orderUid = orderData.optString("uid", "")
            sellerId = orderData.optString("sellerId", "")

            // Store additional data for reviews
            sellerName = orderData.optString("sellerName", "Seller") // Add this if backend provides it
            jobTitleStr = orderData.optString("jobTitle", "Construction Job")

            // Display job information
            jobTitle.text = orderData.optString("jobTitle", "Unknown Job")
            jobDescription.text = orderData.optString("jobDescription", "No description")
            jobPrice.text = "$${orderData.optInt("price", 0)}"

            val status = orderData.optString("status", "unknown")
            orderStatus.text = status.uppercase()

            // Display client information
            buyerName = orderData.optString("clientName", "Unknown Client")
            clientName.text = buyerName ?: "Unknown Client"
            clientLocation.text = orderData.optString("clientLocation", "Unknown Location")

            buyerProfilePicUrl = orderData.optString("clientProfilePic", "")
            Log.d("ORDER_DETAILS", "Retrieved buyer profile data - name: '$buyerName', profilePicUrl: '$buyerProfilePicUrl'")

            if (buyerProfilePicUrl?.isNotEmpty() == true) {
                Glide.with(this)
                    .load(buyerProfilePicUrl)
                    .circleCrop()
                    .into(clientProfileImage)
            }

            // Handle delivery section based on user role and status
            val isAssignedUser = currentUserId == orderData.optString("sellerId")

            when {
                status == "active" && isAssignedUser -> {
                    // Show deliver button for assigned user
                    deliverButton.visibility = View.VISIBLE
                    approveButton.visibility = View.GONE
                    deliveryCard.visibility = View.GONE
                    reviewSection.visibility = View.GONE
                }
                status == "delivered" && !isAssignedUser -> {
                    // Show delivery details and approve button for job owner
                    showDeliveryDetails(orderData)
                    deliverButton.visibility = View.GONE
                    approveButton.visibility = View.VISIBLE
                    reviewSection.visibility = View.GONE
                }
                status == "delivered" && isAssignedUser -> {
                    // Show delivery details only for assigned user
                    showDeliveryDetails(orderData)
                    deliverButton.visibility = View.GONE
                    approveButton.visibility = View.GONE
                    reviewSection.visibility = View.GONE
                }
                status == "completed" -> {
                    // Show delivery details, hide action buttons, show reviews
                    showDeliveryDetails(orderData)
                    deliverButton.visibility = View.GONE
                    approveButton.visibility = View.GONE
                    // Always fetch and show reviews for completed orders
                    Log.d("OrderDetailsActivity", "Order is completed, fetching reviews for orderId: $orderId")
                    fetchOrderReviews()
                }
                else -> {
                    // Hide all delivery related UI
                    deliverButton.visibility = View.GONE
                    approveButton.visibility = View.GONE
                    deliveryCard.visibility = View.GONE
                    reviewSection.visibility = View.GONE
                }
            }

            // Load job images into ViewPager
            val imagesJsonArray = orderData.optJSONArray("jobImageUrls")
            if (imagesJsonArray != null && imagesJsonArray.length() > 0) {
                val imageUrls = ArrayList<String>()
                for (i in 0 until imagesJsonArray.length()) {
                    imageUrls.add(imagesJsonArray.optString(i))
                }
                // Set up ViewPager with images
                imagesViewPager.adapter = JobImagesAdapter(imageUrls)
                imagesViewPager.visibility = View.VISIBLE
            } else {
                // If no images, hide the ViewPager
                imagesViewPager.visibility = View.GONE
            }

            // Handle delivery files RecyclerView
            val deliveryFilesJsonArray = orderData.optJSONArray("deliveryFileUrls")
            if (deliveryFilesJsonArray != null && deliveryFilesJsonArray.length() > 0) {
                val fileUrls = ArrayList<String>()
                for (i in 0 until deliveryFilesJsonArray.length()) {
                    fileUrls.add(deliveryFilesJsonArray.optString(i))
                }
                // Set up RecyclerView with delivery files
                setupDeliveryFilesRecyclerView(fileUrls)
                filesRecyclerView.visibility = View.VISIBLE
            } else {
                // If no delivery files, hide the RecyclerView
                filesRecyclerView.visibility = View.GONE
            }

            hideLoading()
        } catch (e: Exception) {
            showError("Error displaying order details: ${e.message}")
        }
    }

    private fun fetchOrderReviews() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            reviewSection.visibility = View.GONE
            return
        }

        reviewSection.visibility = View.VISIBLE
        // showReviewsLoading() will automatically fetch fresh data
        showReviewsLoading()
    }

    private fun fetchReviewsFromBackend(idToken: String?) {
        Log.d("OrderDetailsActivity", "fetchReviewsFromBackend called with orderId: $orderId")
        if (idToken == null) {
            Log.e("OrderDetailsActivity", "No auth token available for fetching reviews")
            hideReviewsLoading()
            showNoReviewsMessage()
            return
        }

        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/reviews/order/$orderId/all"

        Log.d("OrderDetailsActivity", "Fetching reviews from: $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $idToken")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("OrderDetailsActivity", "Network failure fetching reviews: ${e.message}")
                runOnUiThread {
                    hideReviewsLoading()
                    Log.e("OrderDetailsActivity", "Failed to fetch reviews: ${e.message}")
                    showNoReviewsMessage()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.d("OrderDetailsActivity", "Reviews API response code: ${response.code}")
                Log.d("OrderDetailsActivity", "Reviews API response body: $body")

                runOnUiThread {
                    try {
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success", false)) {
                                val reviewsArray = json.optJSONArray("reviews")
                                Log.d("OrderDetailsActivity", "Reviews array length: ${reviewsArray?.length() ?: 0}")

                                if (reviewsArray != null && reviewsArray.length() > 0) {
                                    Log.d("OrderDetailsActivity", "Processing ${reviewsArray.length()} reviews")
                                    reviewsList.clear()
                                    for (i in 0 until reviewsArray.length()) {
                                        val reviewObj = reviewsArray.getJSONObject(i)
                                        val reviewerId = reviewObj.optString("reviewerId", "")
                                        val comment = reviewObj.optString("comment", "")
                                        val rating = reviewObj.optInt("rating", 0)
                                        Log.d("OrderDetailsActivity", "Processing review $i: reviewerId=$reviewerId, comment='$comment', rating=$rating")

                                        // Format createdAt - handle both string and Firestore timestamp object
                                        val formattedCreatedAt = formatCreatedAtDate(reviewObj)

                                        val review = com.example.newconstructionappwithlocationtracking.models.Review(
                                            id = reviewObj.optString("id", ""),
                                            orderId = reviewObj.optString("orderId", ""),
                                            reviewerId = reviewerId,
                                            revieweeId = reviewObj.optString("revieweeId", ""),
                                            reviewerName = reviewObj.optString("reviewerName", "Unknown"),
                                            revieweeName = reviewObj.optString("revieweeName", "Unknown"),
                                            rating = rating,
                                            comment = comment,
                                            createdAt = formattedCreatedAt,
                                            reviewType = reviewObj.optString("reviewType", ""),
                                            reviewerProfilePic = reviewObj.optString("reviewerProfilePic", "")
                                        )
                                        reviewsList.add(review)
                                    }
                                    Log.d("OrderDetailsActivity", "Successfully loaded ${reviewsList.size} reviews, calling displayReviews()")
                                    hideReviewsLoading()
                                    displayReviews()
                                } else {
                                    Log.d("OrderDetailsActivity", "No reviews found in response")
                                    hideReviewsLoading()
                                    showNoReviewsMessage()
                                }
                            } else {
                                val errorMessage = json.optString("message", "Unknown error")
                                Log.e("OrderDetailsActivity", "API returned success=false: $errorMessage")
                                hideReviewsLoading()
                                showNoReviewsMessage()
                            }
                        } else {
                            Log.e("OrderDetailsActivity", "HTTP error ${response.code} or empty response")
                            hideReviewsLoading()
                            showNoReviewsMessage()
                        }
                    } catch (e: Exception) {
                        Log.e("OrderDetailsActivity", "Error parsing reviews: ${e.message}", e)
                        hideReviewsLoading()
                        showNoReviewsMessage()
                    }
                }
            }
        })
    }



    private fun displayReviews() {
        Log.d("OrderDetailsActivity", "displayReviews called with ${reviewsList.size} reviews")
        reviewSection.visibility = View.VISIBLE

        if (reviewsList.isEmpty()) {
            Log.d("OrderDetailsActivity", "No reviews to display, showing no reviews message")
            showNoReviewsMessage()
            return
        }

        // Hide loading indicator and no reviews message, show reviews
        hideReviewsLoading()
        noReviewsMessage.visibility = View.GONE
        reviewsRecyclerView.visibility = View.VISIBLE

        // Check if current user has already written a review
        val userReview = reviewsList.find { it.reviewerId == currentUserId }
        Log.d("OrderDetailsActivity", "Current user ($currentUserId) review found: ${userReview != null}")

        if (userReview != null) {
            Log.d("OrderDetailsActivity", "User review: rating=${userReview.rating}, comment='${userReview.comment}'")
        }

        if (userReview != null) {
            // User has already reviewed - show "Edit Review" button
            writeReviewButton.text = "Edit Review"
            writeReviewButton.visibility = View.VISIBLE
            Log.d("OrderDetailsActivity", "Showing Edit Review button")
        } else {
            // User hasn't reviewed yet - show "Write Review" button
            writeReviewButton.text = "Write Review"
            writeReviewButton.visibility = View.VISIBLE
            Log.d("OrderDetailsActivity", "Showing Write Review button")
        }

        // Set up RecyclerView to display all reviews
        if (reviewsAdapter == null) {
            Log.d("OrderDetailsActivity", "Creating new ReviewsAdapter with ${reviewsList.size} reviews")
            reviewsAdapter = com.example.newconstructionappwithlocationtracking.adapters.ReviewsAdapter(
                reviewsList // Already a MutableList
            )
            reviewsRecyclerView.adapter = reviewsAdapter
            reviewsRecyclerView.layoutManager = LinearLayoutManager(this)
        } else {
            Log.d("OrderDetailsActivity", "Updating existing ReviewsAdapter with ${reviewsList.size} fresh reviews")
            reviewsAdapter?.updateReviews(reviewsList)
            reviewsAdapter?.notifyDataSetChanged()
        }

        Log.d("OrderDetailsActivity", "Reviews display setup complete - showing ${reviewsList.size} reviews in RecyclerView")

        // Log each review for debugging
        reviewsList.forEachIndexed { index, review ->
            Log.d("OrderDetailsActivity", "Review $index: ${review.reviewerName} -> ${review.revieweeName}, rating=${review.rating}, comment='${review.comment}'")
        }
    }

    private fun showNoReviewsMessage() {
        Log.d("OrderDetailsActivity", "Showing no reviews message")
        reviewSection.visibility = View.VISIBLE
        reviewsRecyclerView.visibility = View.GONE
        noReviewsMessage.visibility = View.VISIBLE
        noReviewsMessage.text = "No reviews yet for this order"

        // Show write review button for completed orders
        if (orderStatus.text.toString().lowercase() == "completed") {
            writeReviewButton.text = "Write Review"
            writeReviewButton.visibility = View.VISIBLE
        } else {
            writeReviewButton.visibility = View.GONE
        }
    }

    private fun showDeliveryDetails(orderData: JSONObject) {
        try {
            val deliveryText = orderData.optString("deliveryText", "")
            val deliveryImageUrl = orderData.optString("deliveryImageUrl", "")

            if (deliveryText.isNotEmpty() || deliveryImageUrl.isNotEmpty()) {
                deliveryCard.visibility = View.VISIBLE

                if (deliveryText.isNotEmpty()) {
                    this.deliveryText.text = deliveryText
                    this.deliveryText.visibility = View.VISIBLE
                } else {
                    this.deliveryText.visibility = View.GONE
                }

                if (deliveryImageUrl.isNotEmpty()) {
                    deliveryImage.visibility = View.VISIBLE
                    Glide.with(this)
                        .load(deliveryImageUrl)
                        .into(deliveryImage)
                } else {
                    deliveryImage.visibility = View.GONE
                }
            } else {
                deliveryCard.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("OrderDetailsActivity", "Error showing delivery details: ${e.message}")
            deliveryCard.visibility = View.GONE
        }
    }

    private fun setupDeliveryFilesRecyclerView(fileUrls: ArrayList<String>) {
        val adapter = DeliveryFilesAdapter(fileUrls, object : DeliveryFilesAdapter.OnFileItemClickListener {
            override fun onFileView(fileUrl: String) {
                openFileViewer(fileUrl)
            }

            override fun onFileDownload(fileUrl: String) {
                downloadFile(fileUrl)
            }
        })
        filesRecyclerView.adapter = adapter
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun showFileViewerDialog(fileUrl: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("File Options")
            .setMessage("What would you like to do with this file?")
            .setPositiveButton("View") { _, _ ->
                openFileViewer(fileUrl)
            }
            .setNegativeButton("Download") { _, _ ->
                downloadFile(fileUrl)
            }
            .setNeutralButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun openFileViewer(fileUrl: String) {
        val intent = Intent(this, MediaViewerActivity::class.java)
        intent.putExtra("FILE_URL", fileUrl)
        startActivity(intent)
    }

    private fun downloadFile(fileUrl: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(fileUrl))
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "delivery_file_${System.currentTimeMillis()}")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle("Downloading Delivery File")

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openChat() {
        // Determine chat partner based on current user role in the order
        val chatPartnerUID = when {
            currentUserId == orderUid -> {
                // Current user is the buyer, chat with seller
                sellerId ?: ""
            }
            currentUserId == sellerId -> {
                // Current user is the seller, chat with buyer
                orderUid ?: ""
            }
            else -> {
                Toast.makeText(this, "Cannot determine chat recipient", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (chatPartnerUID.isEmpty()) {
            Toast.makeText(this, "Invalid chat partner", Toast.LENGTH_SHORT).show()
            return
        }

        // Log for debugging
        android.util.Log.d("ORDER_CHAT", """
            Opening chat from order:
            - CurrentUser: $currentUserId
            - ChatPartner: $chatPartnerUID
            - UserIsBuyer: ${currentUserId == orderUid}
        """.trimIndent())

        // Use unified chat opening system
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("OPEN_CHAT_WITH_UID", chatPartnerUID)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        contentScrollView.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingLayout.visibility = View.GONE
        contentScrollView.visibility = View.VISIBLE
    }

    private fun showReviewsLoading() {
        reviewsLoadingIndicator.visibility = View.VISIBLE
        reviewsRecyclerView.visibility = View.GONE
        noReviewsMessage.visibility = View.GONE

        // Always fetch fresh data when loading indicator appears
        fetchReviewsFromBackendWithAuth()
    }

    private fun fetchReviewsFromBackendWithAuth() {
        Log.d("OrderDetailsActivity", "fetchReviewsFromBackendWithAuth called")
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.e("OrderDetailsActivity", "User not authenticated")
            hideReviewsLoading()
            showNoReviewsMessage()
            return
        }

        // Clear existing reviews to force fresh data
        reviewsList.clear()
        reviewsAdapter?.notifyDataSetChanged()
        Log.d("OrderDetailsActivity", "Cleared existing reviews, fetching fresh auth token")

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                Log.d("OrderDetailsActivity", "Got auth token, calling fetchReviewsFromBackend")
                fetchReviewsFromBackend(idToken)
            } else {
                Log.e("OrderDetailsActivity", "Failed to get auth token: ${task.exception?.message}")
                hideReviewsLoading()
                showNoReviewsMessage()
                Toast.makeText(this, "Failed to refresh reviews", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideReviewsLoading() {
        reviewsLoadingIndicator.visibility = View.GONE
    }

    private fun showError(message: String) {
        hideLoading()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun getBackendUrl(): String {
        return try {
            val inputStream = assets.open(".env")
            val content = inputStream.bufferedReader().use { it.readText() }
            val lines = content.split("\n")
            for (line in lines) {
                if (line.startsWith("BACKEND_URL=")) {
                    return line.substring("BACKEND_URL=".length).trim()
                }
            }
            "https://real-pakistan-backend.onrender.com"
        } catch (e: Exception) {
            Log.w("OrderDetailsActivity", ".env not found in assets")
            "https://real-pakistan-backend.onrender.com"
        }
    }

    /**
     * Format createdAt date from review object - handles both string format and Firestore timestamp object
     */
    private fun formatCreatedAtDate(reviewObj: JSONObject): String {
        return try {
            // First check if createdAt is a Firestore timestamp object with _seconds
            val createdAtObj = reviewObj.opt("createdAt")

            when {
                createdAtObj is JSONObject -> {
                    // It's a Firestore timestamp object: {"_seconds": 123456789, "_nanoseconds": 0}
                    val seconds = createdAtObj.optLong("_seconds", 0)
                    if (seconds > 0) {
                        val date = java.util.Date(seconds * 1000)
                        val outputFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                        outputFormat.format(date)
                    } else {
                        "Date unknown"
                    }
                }
                createdAtObj is String && createdAtObj.isNotEmpty() -> {
                    // It's already a string, try to parse it or return as is
                    val createdAtStr = createdAtObj

                    // Check if it's a JSON string representation of timestamp object
                    if (createdAtStr.contains("_seconds")) {
                        try {
                            val timestampJson = JSONObject(createdAtStr)
                            val seconds = timestampJson.optLong("_seconds", 0)
                            if (seconds > 0) {
                                val date = java.util.Date(seconds * 1000)
                                val outputFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                return outputFormat.format(date)
                            }
                        } catch (e: Exception) {
                            // Not a valid JSON, continue
                        }
                    }

                    // Try to parse as ISO date or other common formats
                    val formats = listOf(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        "yyyy-MM-dd'T'HH:mm:ss'Z'",
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy-MM-dd",
                        "MMM dd, yyyy"
                    )

                    for (formatString in formats) {
                        try {
                            val format = java.text.SimpleDateFormat(formatString, java.util.Locale.getDefault())
                            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            val parsedDate = format.parse(createdAtStr)
                            if (parsedDate != null) {
                                val outputFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                return outputFormat.format(parsedDate)
                            }
                        } catch (e: Exception) {
                            // Try next format
                        }
                    }

                    // If all else fails, return the string as-is (or cleaned up)
                    createdAtStr
                }
                else -> "Date unknown"
            }
        } catch (e: Exception) {
            Log.e("OrderDetailsActivity", "Error formatting createdAt date: ${e.message}")
            "Date unknown"
        }
    }
}
