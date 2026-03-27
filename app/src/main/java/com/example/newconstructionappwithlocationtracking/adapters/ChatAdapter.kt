package com.example.newconstructionappwithlocationtracking.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.models.ChatItem

class ChatAdapter(
    private var chatList: List<ChatItem>,
    private val onChatClick: (ChatItem) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userAvatar: View = view.findViewById(R.id.userAvatar)
        val userAvatarImage: ImageView = view.findViewById(R.id.userAvatarImage)
        val username: TextView = view.findViewById(R.id.username)
        val messagePreview: TextView = view.findViewById(R.id.messagePreview)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val onlineIndicator: View = view.findViewById(R.id.onlineIndicator)
        val avatarLetter: TextView = view.findViewById(R.id.avatarLetter)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chatItem = chatList[position]

        // Set username and message
        holder.username.text = chatItem.username
        holder.messagePreview.text = chatItem.messagePreview
        holder.timestamp.text = chatItem.timestamp

        // Set avatar background color
        val avatarDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(chatItem.avatarColor))
        }
        holder.userAvatar.background = avatarDrawable

        // Check if profile picture URL is available
        if (!chatItem.avatarUrl.isNullOrEmpty()) {
            // Load profile picture using Glide
            holder.avatarLetter.visibility = View.GONE
            holder.userAvatarImage.visibility = View.VISIBLE

            Glide.with(holder.itemView.context)
                .load(chatItem.avatarUrl)
                .transform(CircleCrop())
                .placeholder(avatarDrawable)
                .error(avatarDrawable)
                .into(holder.userAvatarImage)
        } else {
            // Show first letter fallback
            holder.userAvatarImage.visibility = View.GONE
            holder.avatarLetter.visibility = View.VISIBLE
            holder.avatarLetter.text = chatItem.avatarLetter
        }

        // Show/hide online indicator
        holder.onlineIndicator.visibility = if (chatItem.isOnline) View.VISIBLE else View.GONE

        // Set click listener
        holder.itemView.setOnClickListener {
            onChatClick(chatItem)
        }
    }

    override fun getItemCount(): Int = chatList.size

    fun updateChats(newChatList: List<ChatItem>) {
        chatList = newChatList
        notifyDataSetChanged()
    }
}
