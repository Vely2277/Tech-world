package com.example.newconstructionappwithlocationtracking.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.models.ChatMessage
import java.text.SimpleDateFormat

// Define ChatItem sealed class
sealed class ChatItem {
    data class Message(val chatMessage: ChatMessage) : ChatItem()
    data class DateSeparator(val date: String) : ChatItem()
}

class ChatMessageAdapter(
    private var items: List<ChatItem>,
    private val currentUserId: String,
    private var userProfilePicUrl: String?,
    private var otherUserName: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var currentUserProfilePicUrl: String? = null
    companion object {
        private const val VIEW_TYPE_RIGHT = 1
        private const val VIEW_TYPE_LEFT = 2
        private const val VIEW_TYPE_DATE_SEPARATOR = 3
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
        val senderNameText: TextView? = view.findViewById(R.id.senderNameText)
        val avatarImage: ImageView? = view.findViewById(R.id.avatarImage)
        val messageImage: ImageView? = view.findViewById(R.id.messageImage)
        val messageImageContainer: View? = view.findViewById(R.id.messageImageContainer)
        val jobCardContainer: View? = view.findViewById(R.id.jobCardContainer)
        val jobCardImage: ImageView? = jobCardContainer?.findViewById(R.id.jobCardImage)
        val jobCardTitle: TextView? = jobCardContainer?.findViewById(R.id.jobCardTitle)
        val jobCardDescription: TextView? = jobCardContainer?.findViewById(R.id.jobCardDescription)
        val jobCardPrice: TextView? = jobCardContainer?.findViewById(R.id.jobCardPrice)
    }
    class DateSeparatorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateSeparatorText: TextView = view.findViewById(R.id.dateSeparatorText)
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ChatItem.Message -> if (item.chatMessage.senderId == currentUserId) VIEW_TYPE_RIGHT else VIEW_TYPE_LEFT
            is ChatItem.DateSeparator -> VIEW_TYPE_DATE_SEPARATOR
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_RIGHT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_message_right, parent, false)
                MessageViewHolder(view)
            }
            VIEW_TYPE_LEFT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_message_left, parent, false)
                MessageViewHolder(view)
            }
            VIEW_TYPE_DATE_SEPARATOR -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_date_separator, parent, false)
                DateSeparatorViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.Message -> {
                val messageHolder = holder as MessageViewHolder

                // Handle job card display first
                if (item.chatMessage.jobDetails != null) {
                    messageHolder.jobCardContainer?.visibility = View.VISIBLE
                    val jobDetails = item.chatMessage.jobDetails

                    messageHolder.jobCardTitle?.text = jobDetails.title
                    messageHolder.jobCardDescription?.text = jobDetails.description
                    messageHolder.jobCardPrice?.text = "$${jobDetails.price}"

                    // Load job image
                    if (jobDetails.imageUrl.isNotEmpty()) {
                        Glide.with(messageHolder.jobCardImage!!.context)
                            .load(jobDetails.imageUrl)
                            .placeholder(R.drawable.ic_image_placeholder)
                            .centerCrop()
                            .into(messageHolder.jobCardImage!!)
                    } else {
                        messageHolder.jobCardImage?.setImageResource(R.drawable.ic_image_placeholder)
                    }
                } else {
                    messageHolder.jobCardContainer?.visibility = View.GONE
                }

                // Display text content
                if (!item.chatMessage.content.isNullOrEmpty()) {
                    messageHolder.messageText.visibility = View.VISIBLE
                    messageHolder.messageText.text = item.chatMessage.content
                } else {
                    messageHolder.messageText.visibility = View.GONE
                }

                // Display image (control the CardView container, not just the ImageView)
                if (!item.chatMessage.imageUrl.isNullOrEmpty()) {
                    messageHolder.messageImageContainer?.visibility = View.VISIBLE
                    messageHolder.messageImage?.visibility = View.VISIBLE
                    Glide.with(messageHolder.messageImage!!.context)
                        .load(item.chatMessage.imageUrl)
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .centerCrop()
                        .into(messageHolder.messageImage!!)
                } else {
                    messageHolder.messageImageContainer?.visibility = View.GONE
                    messageHolder.messageImage?.visibility = View.GONE
                }
                // Format timestamp as 'h:mm a'
                val rawTimestamp = item.chatMessage.timestamp
                val formattedTime = try {
                    val inputFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                    val outputFormat = SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    val date = inputFormat.parse(rawTimestamp)
                    if (date != null) outputFormat.format(date) else rawTimestamp
                } catch (e: Exception) { rawTimestamp }
                messageHolder.timestampText.text = formattedTime
                messageHolder.senderNameText?.text = if (item.chatMessage.senderId == currentUserId) "Me" else (otherUserName ?: "User")
                // Load avatar image for current user or recipient
                val myPicUrl = currentUserProfilePicUrl
                val recipientPicUrl = userProfilePicUrl
                if (item.chatMessage.senderId == currentUserId && myPicUrl != null && myPicUrl.isNotEmpty()) {
                    Glide.with(messageHolder.avatarImage!!.context)
                        .load(myPicUrl)
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .circleCrop()
                        .into(messageHolder.avatarImage!!)
                } else if (item.chatMessage.senderId != currentUserId && recipientPicUrl != null && recipientPicUrl.isNotEmpty()) {
                    Glide.with(messageHolder.avatarImage!!.context)
                        .load(recipientPicUrl)
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .circleCrop()
                        .into(messageHolder.avatarImage!!)
                } else {
                    messageHolder.avatarImage?.setImageResource(R.drawable.ic_avatar_placeholder)
                }
            }
            is ChatItem.DateSeparator -> {
                val dateHolder = holder as DateSeparatorViewHolder
                dateHolder.dateSeparatorText.text = item.date
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ChatItem>, userPicUrl: String?, currentUserPicUrl: String?, otherUserFullName: String? = null) {
        items = newItems
        userProfilePicUrl = userPicUrl
        currentUserProfilePicUrl = currentUserPicUrl
        otherUserName = otherUserFullName
        notifyDataSetChanged()
    }
}