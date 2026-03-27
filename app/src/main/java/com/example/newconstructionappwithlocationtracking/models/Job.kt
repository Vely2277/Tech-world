package com.example.newconstructionappwithlocationtracking.models

data class Job(
    val id: String,
    val title: String,
    val description: String,
    val location: String,
    val datePosted: DatePosted,
    val uid: String, // Changed from buyerId to uid
    val price: Int,
    val imageUrls: List<String>
)

data class DatePosted(
    val _seconds: Long,
    val _nanoseconds: Long
) {
    fun toFormattedDate(): String {
        val date = java.util.Date(_seconds * 1000)
        val formatter = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        return formatter.format(date)
    }
}

data class JobsResponse(
    val success: Boolean,
    val message: String,
    val data: JobsData
)

data class JobsData(
    val jobs: List<Job>,
    val total: Int
)
