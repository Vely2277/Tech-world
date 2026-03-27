package com.example.newconstructionappwithlocationtracking.models

data class ChatItem(
    val id: String,
    val chatId: String,
    val senderId: String,
    val recipientId: String, // Changed from receiverId to recipientId
    val chatPartnerUID: String, // NEW: Clear identification of who you're chatting with
    val username: String,
    val messagePreview: String,
    val timestamp: String,
    val avatarUrl: String? = null,
    val avatarColor: String,
    val avatarLetter: String,
    val isOnline: Boolean = true,
    val lastLoginStatus: String = "Last seen recently",
    val unreadCount: Int = 0
)
