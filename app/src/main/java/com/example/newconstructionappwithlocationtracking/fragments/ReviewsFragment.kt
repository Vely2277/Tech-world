package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.ProfileActivity
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.adapters.ReviewsAdapter
import com.example.newconstructionappwithlocationtracking.models.Review
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ReviewsFragment : Fragment() {

    private lateinit var reviewsRecyclerView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyStateText: TextView
    private lateinit var reviewsAdapter: ReviewsAdapter
    private val reviewsList = mutableListOf<Review>()
    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reviews, container, false)
        initViews(view)
        setupRecyclerView()
        loadCurrentUserReviews()
        return view
    }

    private fun initViews(view: View) {
        // Create dynamic views since the current layout is static
        val rootLayout = view as? ViewGroup ?: return
        rootLayout.removeAllViews() // Clear static content

        // Add loading indicator
        loadingProgress = ProgressBar(requireContext()).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 48, 0, 48)
            }
        }

        // Add RecyclerView
        reviewsRecyclerView = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(16, 16, 16, 16)
            }
        }

        // Add empty state text
        emptyStateText = TextView(requireContext()).apply {
            text = "No reviews yet. Deliver great work to start building your reputation."
            textSize = 16f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(32, 48, 32, 48)
            }
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
            // No header animation scroll here; ProfileActivity handles scroll via outer container.
        }
    }

    private fun loadCurrentUserReviews() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            showEmptyState("Please sign in to view reviews")
            return
        }

        showLoading(true)

        currentUser.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result?.token
                fetchUserReviews(currentUser.uid, token)
            } else {
                Log.e("REVIEWS_FRAGMENT", "Failed to get ID token", task.exception)
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
                Log.e("REVIEWS_FRAGMENT", "Failed to fetch reviews", e)
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
                                    showEmptyState("No reviews yet. Deliver great work to start building your reputation.")
                                }
                            } else {
                                val message = json.optString("message", "Failed to load reviews")
                                showEmptyState(message)
                            }
                        } else {
                            showEmptyState("Failed to load reviews. Please try again.")
                        }
                    } catch (e: Exception) {
                        Log.e("REVIEWS_FRAGMENT", "Error parsing reviews response", e)
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
                Log.e("REVIEWS_FRAGMENT", "Error parsing review at index $i", e)
            }
        }

        if (reviewsList.isNotEmpty()) {
            showReviews()
        } else {
            showEmptyState("No reviews yet. Deliver great work to start building your reputation.")
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
}
