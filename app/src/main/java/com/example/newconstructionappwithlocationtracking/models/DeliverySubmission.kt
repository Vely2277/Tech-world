package com.example.newconstructionappwithlocationtracking.models

data class DeliverySubmission(
    val orderId: String,
    val deliveryText: String,
    val deliveryImageUrl: String? = null
)
