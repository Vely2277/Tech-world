package com.example.newconstructionappwithlocationtracking.models

data class OrderDetails(
    val id: String,
    val jobId: String,
    val jobTitle: String,
    val jobDescription: String,
    val jobPrice: Int,
    val jobLocation: String,
    val jobImageUrls: List<String>,
    val uid: String, // Job owner ID
    val sellerId: String, // Assigned worker ID
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val clientName: String, // Updated from buyerName to clientName
    val clientProfilePic: String, // Updated from buyerProfilePic to clientProfilePic
    val clientLocation: String? = null, // Added client location
    
    // Enhanced delivery fields
    val deliveryText: String? = null,
    val deliveryFileUrls: List<String>? = null, // Support multiple delivery files
    val deliveredAt: String? = null,
    val completedAt: String? = null, // Added completion timestamp
    
    // Payment fields
    val paymentStatus: String? = null,
    
    // Legacy field for backward compatibility
    val deliveryImageUrl: String? = null
)
