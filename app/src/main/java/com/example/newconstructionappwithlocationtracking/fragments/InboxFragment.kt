package com.example.newconstructionappwithlocationtracking.fragments

import android.Manifest
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.adapters.ChatAdapter
import com.example.newconstructionappwithlocationtracking.models.ChatItem
import com.example.newconstructionappwithlocationtracking.services.FCMNotificationService
import com.example.newconstructionappwithlocationtracking.utils.ErrorStateManager
import com.example.newconstructionappwithlocationtracking.utils.NotificationPermissionManager
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class InboxFragment : Fragment() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var errorContainer: FrameLayout
    private lateinit var chatAdapter: ChatAdapter
    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val senderNameCache = mutableMapOf<String, String>()
    private val senderProfilePicCache = mutableMapOf<String, String?>()
    private val senderLastLoginCache = mutableMapOf<String, String>()

    // Notification permission manager
    private lateinit var notificationPermissionManager: NotificationPermissionManager
    private val TAG = "InboxFragment"

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionManager.handlePermissionResult(
            isGranted,
            onGranted = {
                Log.d(TAG, "✅ Notification permission granted from InboxFragment")
            },
            onDenied = {
                Log.d(TAG, "❌ Notification permission denied from InboxFragment")
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_inbox, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideActionBar()

        // Initialize notification permission manager
        notificationPermissionManager = NotificationPermissionManager(requireContext())

        setupViews(view)
        setupRecyclerView()
        // Always load inbox messages - direct chat opening is handled by MainActivity
        loadMessages()

        // Check notification permission when entering inbox
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (notificationPermissionManager.shouldShowPermissionDialog()) {
            notificationPermissionManager.showNotificationPermissionDialog(
                fragment = this,
                permissionLauncher = notificationPermissionLauncher,
                onGranted = {
                    Log.d(TAG, "✅ Notifications enabled from inbox!")
                },
                onDenied = {
                    Log.d(TAG, "User chose not to enable notifications from inbox")
                }
            )
        } else if (notificationPermissionManager.isNotificationPermissionGranted()) {
            // Already granted, register FCM token
            FCMNotificationService.registerTokenWithBackend(requireContext())
        }
    }



    private fun setupViews(view: View) {
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        loadingLayout = view.findViewById(R.id.loadingLayout)
        errorContainer = view.findViewById(R.id.errorContainer)

        val bellIcon = view.findViewById<ImageView>(R.id.bellIcon)
        bellIcon?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, NotificationFragment())
                .addToBackStack(null)
                .commit()
        }

        val filterIcon = view.findViewById<ImageView>(R.id.filterIcon)
        filterIcon?.setOnClickListener {
            // Handle filter button click
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(emptyList()) { chatItem ->
            // Handle chat item click
            val currentUserUID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val chatFragment = ChatFragment()
            val args = Bundle().apply {
                putString("chatId", chatItem.chatId)
                putString("senderId", currentUserUID) // For backward compatibility
                putString("currentUserId", currentUserUID)
                putString("recipientId", chatItem.chatPartnerUID) // Use chatPartnerUID as recipient
                putString("username", chatItem.username)
            }
            chatFragment.arguments = args
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, chatFragment)
                .addToBackStack(null)
                .commit()
        }

        chatRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        chatRecyclerView.adapter = chatAdapter
        // Add a custom ItemDecoration that draws a thin inset divider under every item (including last)
        val density = resources.displayMetrics.density
        val insetStartPx = (56 * density).toInt() // inset from left (aligns after avatar)
        val insetEndPx = (16 * density).toInt()   // inset from right edge
        val thicknessPx = (0.5f * density)
        val gapPx = (4 * density).toInt()
        val paint = Paint().apply { color = Color.parseColor("#BDBDBD"); style = Paint.Style.FILL }

        val dividerDecoration = object : RecyclerView.ItemDecoration() {
            // Draw divider on top of items so it's not hidden by item backgrounds
            override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                val childCount = parent.childCount
                val left = parent.paddingLeft + insetStartPx
                val right = parent.width - parent.paddingRight - insetEndPx
                val thickness = kotlin.math.max(1, (1f * resources.displayMetrics.density).toInt())
                for (i in 0 until childCount) {
                    val child = parent.getChildAt(i)
                    val params = child.layoutParams as RecyclerView.LayoutParams
                    val top = child.bottom + params.bottomMargin
                    val bottom = top + thickness
                    c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
                }
            }
        }
        chatRecyclerView.addItemDecoration(dividerDecoration)
    }

    private fun loadMessages() {
        showLoading()

        // Check connectivity first
        if (!ErrorStateManager.isNetworkAvailable(requireContext())) {
            showNetworkError(null)
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showEmptyState()
            return
        }
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                fetchInbox(idToken)
            } else {
                showNetworkError(task.exception)
            }
        }
    }

    private fun showNetworkError(exception: Exception?) {
        if (!::errorContainer.isInitialized) return

        ErrorStateManager.showError(
            errorContainer = errorContainer,
            contentView = chatRecyclerView,
            loadingView = loadingLayout,
            title = ErrorStateManager.getErrorTitle(exception),
            message = ErrorStateManager.getErrorMessage(exception),
            onRetry = { loadMessages() }
        )
        emptyStateContainer.visibility = View.GONE
    }

    private fun fetchSenderName(senderId: String, idToken: String, callback: (String, String?, String) -> Unit) {
        val cachedName = senderNameCache[senderId]
        val cachedProfilePic = senderProfilePicCache[senderId]
        val cachedLastLogin = senderLastLoginCache[senderId]
        if (cachedName != null) {
            callback(cachedName, cachedProfilePic, cachedLastLogin ?: "")
            return
        }

        val backendUrl = getBackendUrl()
        val request = Request.Builder()
            .url("$backendUrl/api/profile/$senderId")
            .addHeader("Authorization", "Bearer $idToken")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(senderId, null, "") // fallback to senderId with no profile pic or lastLogin
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                var name = senderId
                var profilePicUrl: String? = null
                var lastLoginStatus = ""

                if (response.isSuccessful && body != null) {
                    try {
                        val json = org.json.JSONObject(body)
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            name = data.optString("name") ?: (data.optString("firstName") ?: senderId)
                            val lastName = data.optString("lastName", "")
                            if (lastName.isNotEmpty()) {
                                name = "${data.optString("firstName", senderId)} $lastName"
                            }
                            profilePicUrl = data.optString("profilePicUrl", "")
                            if (profilePicUrl?.isEmpty() == true) profilePicUrl = null

                            // Get lastLogin and calculate relative time
                            val lastLoginObj = data.optJSONObject("lastLogin")
                            if (lastLoginObj != null && lastLoginObj.has("_seconds")) {
                                val lastLoginSeconds = lastLoginObj.getLong("_seconds")
                                lastLoginStatus = calculateLastSeenStatus(lastLoginSeconds)
                            } else {
                                // Fallback: try direct timestamp format or default
                                lastLoginStatus = "Last seen recently"
                            }
                        }
                    } catch (_: Exception) {}
                }

                senderNameCache[senderId] = name
                senderProfilePicCache[senderId] = profilePicUrl
                senderLastLoginCache[senderId] = lastLoginStatus
                callback(name, profilePicUrl, lastLoginStatus)
            }
        })
    }

    private fun fetchInbox(idToken: String?) {
        if (idToken == null) {
            showEmptyState()
            return
        }
        val backendUrl = getBackendUrl()
        val request = Request.Builder()
            .url("$backendUrl/api/inbox")
            .addHeader("Authorization", "Bearer $idToken")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { showNetworkError(e) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    activity?.runOnUiThread { showNetworkError(Exception("Server error: ${response.code}")) }
                    return
                }
                val json = JSONObject(body)
                val messages = json.optJSONArray("messages") ?: JSONArray()
                if (messages.length() == 0) {
                    activity?.runOnUiThread { showEmptyState() }
                    return
                }

                // Get current user to identify chat partners correctly
                val currentUserUID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""

                // Group messages by conversation (chatId)
                val conversationMap = mutableMapOf<String, MutableList<JSONObject>>()

                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val senderId = msg.optString("senderId", "Unknown")
                    val recipientId = msg.optString("recipientId", "Unknown")

                    // Determine chat partner and create consistent chatId
                    val chatPartnerUID = if (senderId == currentUserUID) recipientId else senderId
                    val standardChatId = listOf(currentUserUID, chatPartnerUID).sorted().joinToString("_")

                    // Group messages by conversation
                    if (!conversationMap.containsKey(standardChatId)) {
                        conversationMap[standardChatId] = mutableListOf()
                    }
                    conversationMap[standardChatId]!!.add(msg)
                }

                val chatList = mutableListOf<com.example.newconstructionappwithlocationtracking.models.ChatItem>()
                var remaining = conversationMap.size

                // Process each conversation (not each message)
                for ((chatId, conversationMessages) in conversationMap) {
                    // Get the LATEST message for this conversation
                    val latestMessage = conversationMessages.maxByOrNull { msg ->
                        val createdAt = msg.optJSONObject("createdAt")
                        createdAt?.optLong("_seconds", 0L) ?: 0L
                    } ?: conversationMessages.first()

                    val senderId = latestMessage.optString("senderId", "Unknown")
                    val recipientId = latestMessage.optString("recipientId", "Unknown")
                    val content = latestMessage.optString("content", "")
                    val createdAt = latestMessage.optJSONObject("createdAt")
                    val timestamp = if (createdAt != null && createdAt.has("_seconds")) {
                        val seconds = createdAt.getLong("_seconds")
                        dateFormat.format(Date(seconds * 1000))
                    } else {
                        ""
                    }

                    // Determine who the chat partner is
                    val chatPartnerUID = if (senderId == currentUserUID) recipientId else senderId

                    val avatarLetter = chatPartnerUID.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                    val avatarColor = "#6200EE" // Default purple

                    // Fetch chat partner's profile info
                    fetchSenderName(chatPartnerUID, idToken) { partnerName, profilePicUrl, lastLoginStatus ->
                        synchronized(chatList) {
                            chatList.add(
                                com.example.newconstructionappwithlocationtracking.models.ChatItem(
                                    id = latestMessage.optString("id", ""),
                                    chatId = chatId,
                                    senderId = senderId,
                                    recipientId = recipientId,
                                    chatPartnerUID = chatPartnerUID,
                                    username = partnerName,
                                    messagePreview = content,
                                    timestamp = timestamp,
                                    avatarUrl = profilePicUrl,
                                    avatarColor = avatarColor,
                                    avatarLetter = avatarLetter,
                                    isOnline = lastLoginStatus.startsWith("Online"),
                                    lastLoginStatus = lastLoginStatus
                                )
                            )
                            remaining--
                            if (remaining == 0) {
                                activity?.runOnUiThread {
                                    // Sort by timestamp (latest first) to show most recent conversations at top
                                    val sortedChats = chatList.sortedByDescending { chat ->
                                        // Parse timestamp for proper sorting
                                        try {
                                            dateFormat.parse(chat.timestamp)?.time ?: 0L
                                        } catch (e: Exception) {
                                            0L
                                        }
                                    }
                                    chatAdapter.updateChats(sortedChats)
                                    chatRecyclerView.visibility = View.VISIBLE
                                    emptyStateContainer.visibility = View.GONE
                                    loadingLayout.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    private fun calculateLastSeenStatus(lastLoginSeconds: Long): String {
        val now = System.currentTimeMillis() / 1000
        val diffMinutes = (now - lastLoginSeconds) / 60

        return when {
            diffMinutes < 2 -> "Online"
            diffMinutes < 60 -> "Last seen ${diffMinutes}m ago"
            diffMinutes < 1440 -> { // 24 hours
                val hours = diffMinutes / 60
                "Last seen ${hours}h ago"
            }
            diffMinutes < 10080 -> { // 1 week
                val days = diffMinutes / 1440
                "Last seen ${days}d ago"
            }
            else -> {
                val formatter = SimpleDateFormat("MMM dd", Locale.getDefault())
                val date = Date(lastLoginSeconds * 1000)
                "Last seen ${formatter.format(date)}"
            }
        }
    }

    private fun showLoading() {
        if (::errorContainer.isInitialized) errorContainer.visibility = View.GONE
        chatRecyclerView.visibility = View.GONE
        emptyStateContainer.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
    }

    private fun showEmptyState() {
        if (::errorContainer.isInitialized) errorContainer.visibility = View.GONE
        chatRecyclerView.visibility = View.GONE
        emptyStateContainer.visibility = View.VISIBLE
        loadingLayout.visibility = View.GONE
    }

    private fun getBackendUrl(): String {
        // For demo, hardcoded. Replace with .env reading if needed.
        return "https://real-pakistan-backend.onrender.com"
    }

    private fun hideActionBar() {
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
    }
}
