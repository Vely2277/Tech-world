package com.example.newconstructionappwithlocationtracking.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.models.Review
import java.text.SimpleDateFormat
import java.util.*

class ReviewsAdapter(
    private var reviews: MutableList<Review>
) : RecyclerView.Adapter<ReviewsAdapter.ReviewViewHolder>() {

    class ReviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val reviewerProfilePic: ImageView = itemView.findViewById(R.id.reviewerProfilePic)
        val reviewerName: TextView = itemView.findViewById(R.id.reviewerName)
        val reviewType: TextView = itemView.findViewById(R.id.reviewType)
        val reviewRating: RatingBar = itemView.findViewById(R.id.reviewRating)
        val reviewDate: TextView = itemView.findViewById(R.id.reviewDate)
        val reviewComment: TextView = itemView.findViewById(R.id.reviewComment)
        val ratingValue: TextView? = itemView.findViewById(R.id.ratingValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_review, parent, false)
        return ReviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) {
        val review = reviews[position]

        // Set reviewer info
        holder.reviewerName.text = review.reviewerName

        // Load reviewer profile picture
        if (review.reviewerProfilePic.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(review.reviewerProfilePic)
                .circleCrop()
                .placeholder(R.drawable.ic_profile_placeholder)
                .into(holder.reviewerProfilePic)
        } else {
            holder.reviewerProfilePic.setImageResource(R.drawable.ic_profile_placeholder)
        }

        // Set review type text
        holder.reviewType.text = when (review.reviewType) {
            "buyer_to_seller" -> "Buyer Review"
            "seller_to_buyer" -> "Seller Review"
            else -> "Review"
        }

        // Set rating
        holder.reviewRating.rating = review.rating.toFloat()
        holder.ratingValue?.text = review.rating.toString() + ".0"

        // Format and set date
        holder.reviewDate.text = formatReviewDate(review.createdAt)

        // Set comment
        holder.reviewComment.text = if (review.comment.isNotEmpty()) {
            review.comment
        } else {
            "No comment provided"
        }
    }

    private fun formatReviewDate(dateString: String): String {
        return try {
            // Check if it's a Firestore timestamp JSON object string
            if (dateString.contains("_seconds") || dateString.contains("\"_seconds\"")) {
                try {
                    // Parse as JSON object
                    val cleanedString = dateString.trim()
                    val json = org.json.JSONObject(cleanedString)
                    val seconds = json.optLong("_seconds", 0)
                    if (seconds > 0) {
                        val date = Date(seconds * 1000)
                        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        return outputFormat.format(date)
                    }
                } catch (e: Exception) {
                    // Try regex extraction
                    val regex = "_seconds\"?\\s*:\\s*(\\d+)".toRegex()
                    val match = regex.find(dateString)
                    if (match != null) {
                        val seconds = match.groupValues[1].toLongOrNull()
                        if (seconds != null && seconds > 0) {
                            val date = Date(seconds * 1000)
                            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                            return outputFormat.format(date)
                        }
                    }
                }
            }

            // Try parsing ISO 8601 format first (e.g., "2025-11-11T10:30:00.000Z")
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            isoFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(dateString)
            if (date != null) {
                val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                return outputFormat.format(date)
            }

            // Try other common formats
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
                "dd/MM/yyyy",
                "MM/dd/yyyy"
            )

            for (formatString in formats) {
                try {
                    val format = SimpleDateFormat(formatString, Locale.getDefault())
                    val parsedDate = format.parse(dateString)
                    if (parsedDate != null) {
                        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        return outputFormat.format(parsedDate)
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }

            // If the date is already in a readable format or can't be parsed, return as is
            // But first check if it looks like a timestamp number
            if (dateString.matches(Regex("^\\d+$"))) {
                try {
                    val timestamp = dateString.toLong()
                    val date = Date(timestamp)
                    val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    return outputFormat.format(date)
                } catch (e: Exception) {
                    // Fall through
                }
            }

            // If already formatted nicely, return as-is
            if (dateString.matches(Regex("^[A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4}$"))) {
                return dateString
            }

            dateString
        } catch (e: Exception) {
            dateString
        }
    }

    override fun getItemCount(): Int = reviews.size

    fun updateReviews(newReviews: List<Review>) {
        reviews.clear()
        reviews.addAll(newReviews)
        notifyDataSetChanged()
    }
}
