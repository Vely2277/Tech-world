package com.example.newconstructionappwithlocationtracking.models

data class Review(
    val id: String = "",
    val orderId: String = "",
    val reviewerId: String = "",
    val revieweeId: String = "",
    val jobId: String = "",
    val reviewerName: String = "",
    val revieweeName: String = "",
    val jobTitle: String = "",
    val rating: Int = 0,
    val comment: String = "",
    val reply: String = "",
    val createdAt: String = "",
    val reviewType: String = "", // "buyer_to_seller" or "seller_to_buyer"
    val reviewerProfilePic: String = ""
)
