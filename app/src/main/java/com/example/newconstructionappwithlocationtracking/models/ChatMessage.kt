package com.example.newconstructionappwithlocationtracking.models

data class ChatMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val recipientId: String, // Changed from receiverId to recipientId for consistency
    val content: String,
    val timestamp: String,
    val imageUrl: String? = null,
    val jobDetails: JobDetails? = null
)

data class JobDetails(
    val jobId: String,
    val title: String,
    val description: String,
    val imageUrl: String,
    val price: Int,
    val buyerId: String
)
