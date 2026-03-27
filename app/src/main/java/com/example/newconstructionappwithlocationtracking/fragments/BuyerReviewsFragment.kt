package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.adapters.ReviewsAdapter
import com.example.newconstructionappwithlocationtracking.models.Review
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class BuyerReviewsFragment : Fragment() {
    private var profileDataJson: JSONObject? = null
    private lateinit var reviewsRecyclerView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyStateText: TextView
    private lateinit var reviewsAdapter: ReviewsAdapter
    private val reviewsList = mutableListOf<Review>()
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileDataJson = arguments?.getString("profileDataJson")?.let { JSONObject(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_buyer_reviews, container, false)
        setupDynamicViews(view)
        setupRecyclerView()
        loadUserReviews()
        return view
    }

    private fun setupDynamicViews(view: View) {
        // Find the existing LinearLayout from the XML
        val rootLayout = view.findViewById<LinearLayout>(R.id.reviewsRootLayout)
            ?: (view as? LinearLayout)
            ?: return

        // Clear existing static content
        rootLayout.removeAllViews()
        rootLayout.orientation = LinearLayout.VERTICAL

        // Add loading indicator
        loadingProgress = ProgressBar(requireContext()).apply {
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.topMargin = 48
            layoutParams.bottomMargin = 48
            layoutParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
            this.layoutParams = layoutParams
        }

        // Add RecyclerView with padding
        reviewsRecyclerView = RecyclerView(requireContext()).apply {
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            this.layoutParams = layoutParams
            setPadding(0, 8, 0, 16)
            clipToPadding = false
        }

        // Add empty state text with modern styling
        emptyStateText = TextView(requireContext()).apply {
            text = "No reviews yet"
            textSize = 16f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(resources.getColor(R.color.text_secondary, null))
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(32, 64, 32, 64)
            this.layoutParams = layoutParams
            visibility = View.GONE
        }

        rootLayout.addView(loadingProgress)
        rootLayout.addView(reviewsRecyclerView)
        rootLayout.addView(emptyStateText)
    }

    private fun setupRecyclerView() {
        reviewsAdapter = ReviewsAdapter(reviewsList)
        reviewsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = reviewsAdapter
        }
    }

    private fun loadUserReviews() {
        val userId = profileDataJson?.optString("uid")
        if (userId.isNullOrEmpty()) {
            showEmptyState("User information not available")
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            showEmptyState("Please sign in to view reviews")
            return
        }

        showLoading(true)

        currentUser.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result?.token
                fetchUserReviews(userId, token)
            } else {
                Log.e("BUYER_REVIEWS_FRAGMENT", "Failed to get ID token", task.exception)
                showEmptyState("Authentication failed")
            }
        }
    }

    private fun fetchUserReviews(userId: String, token: String?) {
        if (token == null) {
            showEmptyState("Authentication failed")
            return
        }

        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/reviews/user/$userId"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BUYER_REVIEWS_FRAGMENT", "Failed to fetch reviews", e)
                Handler(Looper.getMainLooper()).post {
                    showEmptyState("Failed to load reviews. Please try again.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()

                Handler(Looper.getMainLooper()).post {
                    try {
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success", false)) {
                                val reviewsArray = json.optJSONArray("reviews")
                                if (reviewsArray != null && reviewsArray.length() > 0) {
                                    parseAndDisplayReviews(reviewsArray)
                                } else {
                                    showEmptyState("No reviews yet")
                                }
                            } else {
                                val message = json.optString("message", "Failed to load reviews")
                                showEmptyState(message)
                            }
                        } else {
                            showEmptyState("Failed to load reviews. Please try again.")
                        }
                    } catch (e: Exception) {
                        Log.e("BUYER_REVIEWS_FRAGMENT", "Error parsing reviews response", e)
                        showEmptyState("Error loading reviews")
                    }
                }
            }
        })
    }

    private fun parseAndDisplayReviews(reviewsArray: org.json.JSONArray) {
        reviewsList.clear()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        for (i in 0 until reviewsArray.length()) {
            try {
                val reviewObj = reviewsArray.getJSONObject(i)

                val review = Review(
                    id = reviewObj.optString("id", ""),
                    orderId = reviewObj.optString("orderId", ""),
                    reviewerId = reviewObj.optString("reviewerId", ""),
                    revieweeId = reviewObj.optString("revieweeId", ""),
                    jobId = reviewObj.optString("jobId", ""),
                    rating = reviewObj.optInt("rating", 0),
                    comment = reviewObj.optString("comment", ""),
                    reviewType = reviewObj.optString("reviewType", ""),
                    reviewerName = reviewObj.optString("reviewerName", "Anonymous"),
                    reviewerProfilePic = reviewObj.optString("reviewerProfilePic", ""),
                    jobTitle = reviewObj.optString("jobTitle", "Job"),
                    createdAt = formatTimestamp(reviewObj.optJSONObject("createdAt"), dateFormat),
                    reply = reviewObj.optString("reply", "")
                )

                reviewsList.add(review)
            } catch (e: Exception) {
                Log.e("BUYER_REVIEWS_FRAGMENT", "Error parsing review at index $i", e)
            }
        }

        if (reviewsList.isNotEmpty()) {
            showReviews()
        } else {
            showEmptyState("No reviews yet")
        }
    }

    private fun formatTimestamp(timestampObj: JSONObject?, dateFormat: SimpleDateFormat): String {
        return try {
            if (timestampObj != null && timestampObj.has("_seconds")) {
                val seconds = timestampObj.getLong("_seconds")
                val date = Date(seconds * 1000)
                dateFormat.format(date)
            } else {
                "Date unknown"
            }
        } catch (e: Exception) {
            "Date unknown"
        }
    }

    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
        reviewsRecyclerView.visibility = View.GONE
        emptyStateText.visibility = View.GONE
    }

    private fun showReviews() {
        loadingProgress.visibility = View.GONE
        reviewsRecyclerView.visibility = View.VISIBLE
        emptyStateText.visibility = View.GONE
        reviewsAdapter.notifyDataSetChanged()
    }

    private fun showEmptyState(message: String) {
        loadingProgress.visibility = View.GONE
        reviewsRecyclerView.visibility = View.GONE
        emptyStateText.visibility = View.VISIBLE
        emptyStateText.text = message
    }

    private fun getBackendUrl(): String {
        return "https://real-pakistan-backend.onrender.com"
    }

    companion object {
        fun newInstance(profileDataJson: JSONObject?): BuyerReviewsFragment {
            val fragment = BuyerReviewsFragment()
            val args = Bundle()
            args.putString("profileDataJson", profileDataJson?.toString() ?: "")
            fragment.arguments = args
            return fragment
        }
    }
}
