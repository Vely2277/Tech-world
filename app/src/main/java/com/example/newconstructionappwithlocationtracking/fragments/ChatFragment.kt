package com.example.newconstructionappwithlocationtracking.fragments

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.text.TextWatcher
import android.text.Editable
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.adapters.ChatItem
import com.example.newconstructionappwithlocationtracking.adapters.ChatMessageAdapter
import com.example.newconstructionappwithlocationtracking.adapters.JobSelectionAdapter
import com.example.newconstructionappwithlocationtracking.adapters.JobSelectionItem
import com.example.newconstructionappwithlocationtracking.interfaces.NavigationHost
import com.example.newconstructionappwithlocationtracking.models.ChatMessage
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager

class ChatFragment : Fragment() {
    private var chatId: String? = null
    private var senderId: String? = null
    private var username: String? = null
    private var recipientId: String? = null
    private var currentUserRole: String? = null
    private val availableJobs = mutableListOf<JobItem>()

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendArrow: View // ImageView for send button
    private lateinit var loadingLayout: View
    private lateinit var assignJobButton: androidx.appcompat.widget.AppCompatButton
    private lateinit var chatAdapter: ChatMessageAdapter
    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val messages = mutableListOf<ChatMessage>()
    private var isMessagesLoaded = false
    private var isProfileLoaded = false
    private var userProfilePicUrl: String? = null
    private var currentUserProfilePicUrl: String? = null
    private var otherUserFullName: String? = null // Store the other user's full name

    private lateinit var imagePreviewContainer: View
    private lateinit var imagePreview: ImageView
    private lateinit var imageUploadSpinner: ProgressBar
    private lateinit var chatUserAvatar: ImageView
    private var selectedImageUri: Uri? = null
    private var uploadedImageUrl: String? = null
    private var isImageUploading: Boolean = false

    private lateinit var jobPreviewContainer: View
    private lateinit var jobPreviewImage: ImageView
    private lateinit var jobPreviewTitle: TextView
    private lateinit var jobPreviewDescription: TextView
    private lateinit var jobPreviewPrice: TextView
    private lateinit var removeJobPreview: ImageView
    private var pendingJobDetails: JSONObject? = null

    private var currentUserId: String? = null

    // Pending message placeholders used when permissions are required before sending
    private var pendingMessageContent: String? = null
    private var pendingMessageImageUrl: String? = null
    private var pendingMessageJobDetails: JSONObject? = null

    // Location permission enforcement
    private lateinit var permissionManager: LocationPermissionManager
    private var fineLocationDenialCount: Int = 0
    private var hasCheckedPermissionsOnce: Boolean = false

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            showImagePreview(uri)
            uploadImageToBackend(uri)
        }
    }

    data class JobItem(
        val id: String,
        val title: String,
        val description: String,
        val budget: Int,
        val location: String,
        val category: String,
        val status: String,
        val imageUrls: List<String> = emptyList() // Add imageUrls field
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get arguments
        chatId = arguments?.getString("chatId")
        senderId = arguments?.getString("senderId")
        recipientId = arguments?.getString("recipientId")
        username = arguments?.getString("username")
        currentUserId = FirebaseAuth.getInstance().currentUser?.uid

        // Initialize permission manager
        permissionManager = LocationPermissionManager(requireContext())

        // Get pending job details for job application
        val pendingJobDetailsJson = arguments?.getString("pendingJobDetails")
        if (!pendingJobDetailsJson.isNullOrEmpty()) {
            try {
                pendingJobDetails = JSONObject(pendingJobDetailsJson)
            } catch (e: Exception) {
                Log.e("CHAT_FRAGMENT", "Error parsing pending job details", e)
            }
        }

        // Initialize UI components
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        messageInput = view.findViewById(R.id.messageInput)
        sendArrow = view.findViewById(R.id.sendArrow)
        loadingLayout = view.findViewById(R.id.loadingLayout)
        assignJobButton = view.findViewById(R.id.assignJobButton)
        imagePreviewContainer = view.findViewById(R.id.imagePreviewContainer)
        imagePreview = view.findViewById(R.id.imagePreview)
        imageUploadSpinner = view.findViewById(R.id.imageUploadSpinner)
        chatUserAvatar = view.findViewById(R.id.chatUserAvatar)

        // Initialize job preview components
        jobPreviewContainer = view.findViewById(R.id.jobPreviewContainer)
        if (jobPreviewContainer != null) {
            jobPreviewImage = jobPreviewContainer.findViewById(R.id.jobPreviewImage)
            jobPreviewTitle = jobPreviewContainer.findViewById(R.id.jobPreviewTitle)
            jobPreviewDescription = jobPreviewContainer.findViewById(R.id.jobPreviewDescription)
            jobPreviewPrice = jobPreviewContainer.findViewById(R.id.jobPreviewPrice)
            removeJobPreview = jobPreviewContainer.findViewById(R.id.removeJobPreview)
        }

        // Initialize adapter with empty data initially
        chatAdapter = ChatMessageAdapter(emptyList(), currentUserId ?: "", null, null)
        chatRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        chatRecyclerView.adapter = chatAdapter

        // Setup keyboard hiding functionality
        setupKeyboardHiding(view)

        // Setup chat functionality
        setupChat()
        setupJobAssignment()
    }

    private fun setupChat() {
        showLoading()
        fetchCurrentUserProfile {
            if (chatId.isNullOrEmpty()) {
                Log.e("CHAT_FRAGMENT", "chatId is null or empty! Messaging will not work.")
                showMessagingErrorUI()
                showProfileErrorUI()
            } else {
                fetchMessages()
            }
        }

        setupMessageInput()
        setupNavigationAndActions()
        setupJobPreview()
    }

    private fun setupJobAssignment() {
        // Check if current user has any jobs to show assign job button
        fetchCurrentUserRole { role ->
            currentUserRole = role
            // Fetch user jobs regardless of role to check if they have any jobs
            fetchUserJobs()
        }

        assignJobButton.setOnClickListener {
            Log.d("ASSIGN_JOB_CLICK", "Assign job button clicked! Available jobs count: ${availableJobs.size}")
            Log.d("ASSIGN_JOB_CLICK", "Available jobs list: ${availableJobs.map { "${it.title} (ID: ${it.id})" }}")

            if (availableJobs.isNotEmpty()) {
                Log.d("ASSIGN_JOB_CLICK", "Showing job selection dialog with ${availableJobs.size} jobs")
                showJobSelectionDialog()
            } else {
                Log.e("ASSIGN_JOB_FAIL", "No jobs available! Button should not be visible.")
                showNoJobsMessage()
            }
        }
    }

    private fun fetchCurrentUserRole(callback: (String?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            callback(null)
            return
        }

        user.getIdToken(false).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                val backendUrl = getBackendUrlFromEnv().ifBlank { "https://real-pakistan-backend.onrender.com" }

                val request = Request.Builder()
                    .url("$backendUrl/api/profile")
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activity?.runOnUiThread { callback(null) }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.body?.string()
                            if (response.isSuccessful && body != null) {
                                val json = JSONObject(body)
                                if (json.optBoolean("success")) {
                                    val data = json.optJSONObject("data")
                                    val role = data?.optString("role")
                                    activity?.runOnUiThread { callback(role) }
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CHAT_FRAGMENT", "Error parsing user role", e)
                        }
                        activity?.runOnUiThread { callback(null) }
                    }
                })
            } else {
                callback(null)
            }
        }
    }

    private fun fetchUserJobs() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.e("CHAT_FRAGMENT", "User is null, cannot fetch jobs")
            return
        }

        Log.d("CHAT_FRAGMENT", "Starting to fetch jobs for user: ${user.uid}")

        user.getIdToken(false).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                val backendUrl = getBackendUrlFromEnv().ifBlank { "https://real-pakistan-backend.onrender.com" }
                val url = "$backendUrl/api/jobs/user/${user.uid}"

                Log.d("CHAT_FRAGMENT", "Fetching jobs from: $url")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("CHAT_FRAGMENT", "Failed to fetch user jobs: ${e.message}")
                        activity?.runOnUiThread {
                            // Hide button if we can't fetch jobs
                            assignJobButton.visibility = View.GONE
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        Log.d("CHAT_FRAGMENT", "Jobs API Response: $body")

                        try {
                            if (response.isSuccessful && body != null) {
                                val json = JSONObject(body)
                                if (json.optBoolean("success")) {
                                    val data = json.optJSONObject("data")
                                    val jobsArray = data?.optJSONArray("jobs") ?: JSONArray()

                                    Log.d("CHAT_FRAGMENT", "Found ${jobsArray.length()} total jobs")

                                    availableJobs.clear()
                                    for (i in 0 until jobsArray.length()) {
                                        val job = jobsArray.getJSONObject(i)
                                        val title = job.optString("title", "")
                                        val jobId = job.optString("id", "")

                                        Log.d("CHAT_FRAGMENT", "Job $i: id='$jobId', title='$title'")

                                        // Since your jobs collection doesn't have status field,
                                        // all jobs from this user are available for assignment
                                        availableJobs.add(
                                            JobItem(
                                                id = jobId,
                                                title = title,
                                                description = job.optString("description", ""),
                                                budget = job.optInt("price", 0), // Using 'price' from your database
                                                location = job.optString("location", ""),
                                                category = "", // Your jobs don't have category field
                                                status = "available", // Default status since no status field exists
                                                imageUrls = job.optJSONArray("imageUrls")?.let { jsonArray ->
                                                    List(jsonArray.length()) { index ->
                                                        jsonArray.optString(index)
                                                    }
                                                } ?: emptyList() // Extract image URLs if available
                                            )
                                        )
                                        Log.d("CHAT_FRAGMENT", "Added job to available list: $title")
                                    }

                                    Log.d("CHAT_FRAGMENT", "Final available jobs count: ${availableJobs.size}")

                                    // Show/hide assign job button based on whether user has available jobs
                                    activity?.runOnUiThread {
                                        if (availableJobs.isNotEmpty()) {
                                            assignJobButton.visibility = View.VISIBLE
                                            assignJobButton.text = "Assign Job"
                                            Log.d("CHAT_FRAGMENT", "Showing assign button - ${availableJobs.size} jobs available")
                                        } else {
                                            assignJobButton.visibility = View.GONE
                                            Log.d("CHAT_FRAGMENT", "Hiding assign button - no available jobs")
                                        }
                                    }
                                } else {
                                    Log.e("CHAT_FRAGMENT", "API returned success=false: ${json.optString("message")}")
                                    activity?.runOnUiThread {
                                        assignJobButton.visibility = View.GONE
                                    }
                                }
                            } else {
                                Log.e("CHAT_FRAGMENT", "API call failed with response code: ${response.code}")
                                activity?.runOnUiThread {
                                    assignJobButton.visibility = View.GONE
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CHAT_FRAGMENT", "Error parsing jobs response", e)
                            activity?.runOnUiThread {
                                assignJobButton.visibility = View.GONE
                            }
                        }
                    }
                })
            } else {
                Log.e("CHAT_FRAGMENT", "Failed to get Firebase ID token")
                assignJobButton.visibility = View.GONE
            }
        }
    }

    private fun showJobSelectionDialog() {
        if (availableJobs.isEmpty()) {
            showNoJobsMessage()
            return
        }

        // Create custom dialog
        val dialog = AlertDialog.Builder(requireContext())
            .setView(R.layout.dialog_job_selection)
            .create()

        dialog.show()

        // Get views from the dialog
        val loadingLayout = dialog.findViewById<View>(R.id.loadingLayout)
        val jobsRecyclerView = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.jobsRecyclerView)
        val emptyLayout = dialog.findViewById<View>(R.id.emptyLayout)
        val cancelButton = dialog.findViewById<android.widget.Button>(R.id.cancelButton)

        // Set up RecyclerView
        jobsRecyclerView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        // Show loading initially
        loadingLayout?.visibility = View.VISIBLE
        jobsRecyclerView?.visibility = View.GONE
        emptyLayout?.visibility = View.GONE

        // Convert JobItem to JobSelectionItem with image URL
        val jobSelectionItems = availableJobs.map { job ->
            JobSelectionItem(
                id = job.id,
                title = job.title,
                description = job.description,
                price = job.budget,
                location = job.location,
                imageUrl = getJobImageUrl(job) // We'll get the first image from imageUrls
            )
        }

        // Set up adapter with click listener
        val adapter = JobSelectionAdapter(jobSelectionItems) { selectedJob ->
            dialog.dismiss()
            // Convert back to JobItem for compatibility
            val jobItem = availableJobs.find { it.id == selectedJob.id }
            if (jobItem != null) {
                confirmJobAssignment(jobItem)
            }
        }

        // Simulate loading delay (remove this in production, just for demonstration)
        Handler(Looper.getMainLooper()).postDelayed({
            loadingLayout?.visibility = View.GONE

            if (jobSelectionItems.isNotEmpty()) {
                jobsRecyclerView?.visibility = View.VISIBLE
                jobsRecyclerView?.adapter = adapter
            } else {
                emptyLayout?.visibility = View.VISIBLE
            }
        }, 500) // 500ms delay to show loading

        // Cancel button
        cancelButton?.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun getJobImageUrl(job: JobItem): String? {
        // Return the first image URL from the job's imageUrls list
        // If no images are available, return null to show placeholder
        return if (job.imageUrls.isNotEmpty()) {
            job.imageUrls.first()
        } else {
            null
        }
    }

    private fun confirmJobAssignment(job: JobItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Job Assignment")
            .setMessage("Assign '${job.title}' to ${username ?: "this seller"}?\n\nBudget: $${job.budget}\nLocation: ${job.location}")
            .setPositiveButton("Assign") { _, _ ->
                assignJobToSeller(job)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assignJobToSeller(job: JobItem) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || recipientId.isNullOrEmpty()) {
            Log.e("CHAT_FRAGMENT", "Assignment failed - user: $user, recipientId: $recipientId")
            showError("Unable to assign job. Please try again.")
            return
        }

        Log.d("CHAT_FRAGMENT", "Assigning job ${job.id} to recipientId: $recipientId, username: $username")

        user.getIdToken(false).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                val backendUrl = getBackendUrlFromEnv().ifBlank { "https://real-pakistan-backend.onrender.com" }

                val requestBody = JSONObject().apply {
                    put("workerId", recipientId) // This should be the user's uid who will receive the job
                    put("workerName", username ?: "Worker")
                }.toString().toRequestBody("application/json".toMediaType())

                Log.d("CHAT_FRAGMENT", "Making request to: $backendUrl/api/jobs/${job.id}/assign")
                Log.d("CHAT_FRAGMENT", "Request body: workerId=$recipientId, workerName=$username")

                val request = Request.Builder()
                    .url("$backendUrl/api/jobs/${job.id}/assign")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("CHAT_FRAGMENT", "Network failure during job assignment", e)
                        activity?.runOnUiThread {
                            showError("Failed to assign job. Please check your connection.")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        Log.d("CHAT_FRAGMENT", "Assignment response code: ${response.code}")
                        Log.d("CHAT_FRAGMENT", "Assignment response body: $body")

                        activity?.runOnUiThread {
                            try {
                                if (response.isSuccessful && body != null) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        showSuccess("Job '${job.title}' assigned successfully!")
                                        // Refresh available jobs
                                        fetchUserJobs()
                                        // Send a message about the assignment
                                        sendJobAssignmentMessage(job)
                                    } else {
                                        val errorMessage = json.optString("message", "Failed to assign job")
                                        Log.e("CHAT_FRAGMENT", "Backend error: $errorMessage")
                                        showError(errorMessage)
                                    }
                                } else {
                                    Log.e("CHAT_FRAGMENT", "HTTP error: ${response.code}, body: $body")
                                    showError("Failed to assign job. Please try again.")
                                }
                            } catch (e: Exception) {
                                Log.e("CHAT_FRAGMENT", "Error parsing assignment response", e)
                                showError("Error processing assignment. Please try again.")
                            }
                        }
                    }
                })
            } else {
                Log.e("CHAT_FRAGMENT", "Failed to get Firebase token", task.exception)
                showError("Authentication failed. Please try again.")
            }
        }
    }

    private fun sendJobAssignmentMessage(job: JobItem) {
        val assignmentMessage = "🎉 Job Assignment: I've assigned you the job '${job.title}' for $${job.budget}. Check your Orders page for details!"
        sendMessage(assignmentMessage, null)
    }

    private fun showNoJobsMessage() {
        AlertDialog.Builder(requireContext())
            .setTitle("No Available Jobs")
            .setMessage("You don't have any available jobs to assign. Create a new job posting first.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSuccess(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Success")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        chatRecyclerView.visibility = View.GONE
        messageInput.visibility = View.GONE
        sendArrow.visibility = View.GONE
    }

    private fun showChatUI() {
        loadingLayout.visibility = View.GONE
        chatRecyclerView.visibility = View.VISIBLE
        messageInput.visibility = View.VISIBLE
        sendArrow.visibility = View.VISIBLE
        val nameView = view?.findViewById<android.widget.TextView>(R.id.chatUserName)
        if (nameView != null) {
            nameView.visibility = View.VISIBLE
        }
    }

    private fun getBackendUrl(): String {
        // Use the same logic as InboxFragment for .env
        return "https://real-pakistan-backend.onrender.com" // Replace with .env reading if needed
    }

    private fun fetchMessages() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                fetchMessagesWithToken(idToken)
            } else {
                Handler(Looper.getMainLooper()).post { showChatUI() }
            }
        }
    }

    private fun fetchCurrentUserProfile(onComplete: () -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: return onComplete()
        val userId = user.uid
        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/profile/$userId"
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener onComplete()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        onComplete()
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
                        val data = json.optJSONObject("data")
                        currentUserProfilePicUrl = data?.optString("profilePicUrl", "") ?: ""
                        Log.d("CHAT_FRAGMENT", "Current user profilePicUrl: $currentUserProfilePicUrl")
                        Handler(Looper.getMainLooper()).post { onComplete() }
                    }
                })
            } else {
                onComplete()
            }
        }
    }

    private fun fetchMessagesWithToken(idToken: String?) {
        if (idToken == null) {
            Handler(Looper.getMainLooper()).post { showChatUI() }
            return
        }
        val backendUrl = getBackendUrl()
        val url = "$backendUrl/api/inbox/chat/$chatId/messages"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $idToken")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CHAT_FRAGMENT", "fetchMessages failed: ${e.message}")
                Handler(Looper.getMainLooper()).post { showChatUI() }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d("CHAT_FRAGMENT", "fetchMessages response: $body")
                val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
                val arr = json.optJSONArray("messages") ?: JSONArray()
                val newMessages = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    Log.d("CHAT_FRAGMENT", "Message object: $msg") // Log full message object
                    newMessages.add(
                        ChatMessage(
                            id = msg.optString("id", ""),
                            chatId = chatId ?: "",
                            senderId = msg.optString("senderId", ""),
                            recipientId = msg.optString("recipientId", ""), // Use recipientId consistently
                            content = msg.optString("content", ""),
                            timestamp = dateFormat.format(Date(msg.optJSONObject("createdAt")?.optLong("_seconds", 0)?.times(1000) ?: 0)),
                            imageUrl = if (msg.has("imageUrl")) msg.optString("imageUrl") else null,
                            jobDetails = if (msg.has("jobDetails")) {
                                val jd = msg.getJSONObject("jobDetails")
                                com.example.newconstructionappwithlocationtracking.models.JobDetails(
                                    jobId = jd.optString("jobId", ""),
                                    title = jd.optString("title", ""),
                                    description = jd.optString("description", ""),
                                    imageUrl = jd.optString("imageUrl", ""),
                                    price = jd.optInt("price", 0),
                                    buyerId = jd.optString("buyerId", "")
                                )
                            } else null
                        )
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    messages.clear()
                    messages.addAll(newMessages)
                    val chatItems = buildChatItems(messages)
                    chatAdapter.updateItems(chatItems, userProfilePicUrl, currentUserProfilePicUrl, otherUserFullName)
                    isMessagesLoaded = true

                    // recipientId should already be set from arguments
                    // If not set, try to extract from messages as fallback
                    if (recipientId.isNullOrEmpty() && messages.isNotEmpty()) {
                        Log.w("CHAT_FRAGMENT", "recipientId not set, extracting from messages")
                        for (message in messages) {
                            // Find the user ID that is NOT the current user
                            when {
                                message.senderId != currentUserId -> recipientId = message.senderId
                                message.recipientId != currentUserId -> recipientId = message.recipientId
                            }
                            if (!recipientId.isNullOrEmpty()) break
                        }
                    }

                    // Fetch user profile for the recipient
                    if (!recipientId.isNullOrEmpty()) {
                        Log.d("CHAT_FRAGMENT", "Fetching profile for recipientId: $recipientId")
                        fetchUserProfile(recipientId!!)
                    } else {
                        Log.e("CHAT_FRAGMENT", "Could not determine recipientId!")
                        isProfileLoaded = true
                        showProfileErrorUI()
                        checkAndShowChatUI()
                    }
                }
            }
        })
    }

    /**
     * Check permissions before sending message
     * Enforces location permission requirement for chat messaging
     */
    private fun checkPermissionsAndSendMessage(content: String, imageUrl: String?, jobDetails: JSONObject?) {
        // Store pending message data
        pendingMessageContent = content
        pendingMessageImageUrl = imageUrl
        pendingMessageJobDetails = jobDetails

        // Get comprehensive permission status
        val status = permissionManager.getComprehensivePermissionStatus(activity)

        Log.d("CHAT_PERMISSION", "Checking permissions - Fine: ${status.hasFineLocation}, Background: ${status.hasBackgroundLocation}")

        // If both permissions granted, send immediately
        if (status.hasFineLocation && status.hasBackgroundLocation) {
            Log.d("CHAT_PERMISSION", "✅ Both permissions granted, sending message")
            sendMessage(content, imageUrl, jobDetails)
            messageInput.setText("")
            clearImagePreview()
            hideJobPreview()
            clearPendingMessage()
            return
        }

        // Show loading dialog
        showChatPermissionLoadingDialog()

        // Delay to show loading briefly
        Handler(Looper.getMainLooper()).postDelayed({
            dismissChatPermissionLoadingDialog()

            when {
                // No fine location permission
                !status.hasFineLocation -> {
                    // Check global denial count
                    val denialCount = permissionManager.getGlobalFineLocationDenialCount()
                    val isAndroidBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

                    Log.d("CHAT_PERMISSION", "Fine location denied before: count=$denialCount, androidBlocking=$isAndroidBlocking")

                    // ALWAYS show initial dialog first (with Okay button)
                    // The initial dialog will decide what to do next based on denial count
                    Log.d("CHAT_PERMISSION", "📱 Showing initial permission dialog")
                    showChatPermissionInitialDialog()
                }
                // Fine granted but no background
                !status.hasBackgroundLocation -> {
                    Log.d("CHAT_PERMISSION", "✅ Fine granted, need background - showing background dialog")
                    showChatPermissionBackgroundDialog()
                }
            }
        }, 500)
    }


    private var chatPermissionLoadingDialog: AlertDialog? = null

    private fun showChatPermissionLoadingDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_permission_loading, null)
        chatPermissionLoadingDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        chatPermissionLoadingDialog?.show()
    }

    private fun dismissChatPermissionLoadingDialog() {
        chatPermissionLoadingDialog?.dismiss()
        chatPermissionLoadingDialog = null
    }

    private fun sendMessage(content: String, imageUrl: String?, jobDetails: JSONObject? = null) {
        Log.d("CHAT_FRAGMENT", "sendMessage called with chatId=$chatId, senderId=$currentUserId, recipientId=$recipientId, content=$content, imageUrl=$imageUrl, hasJobDetails=${jobDetails != null}")

        // Validate required parameters
        if (recipientId.isNullOrEmpty()) {
            Log.e("CHAT_FRAGMENT", "Cannot send message: recipientId is null or empty")
            return
        }

        val json = JSONObject().apply {
            put("chatId", chatId)
            put("senderId", currentUserId)
            put("recipientId", recipientId)
            put("content", content)
            if (imageUrl != null) put("imageUrl", imageUrl)
            if (jobDetails != null) {
                // Convert the JSONObject to a proper nested object for backend
                val jobDetailsObj = JSONObject().apply {
                    put("jobId", jobDetails.optString("jobId", ""))
                    put("title", jobDetails.optString("title", ""))
                    put("description", jobDetails.optString("description", ""))
                    put("imageUrl", jobDetails.optString("imageUrl", ""))
                    put("price", jobDetails.optInt("price", 0))
                    put("buyerId", jobDetails.optString("buyerId", ""))
                }
                put("jobDetails", jobDetailsObj)
            }
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/inbox"
                val body = RequestBody.create("application/json".toMediaType(), json.toString())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()
                // Add message to UI immediately (optimistic update)
                val tempTimestamp = dateFormat.format(Date())
                val tempMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId ?: "",
                    senderId = currentUserId ?: "",
                    recipientId = recipientId ?: "",
                    content = content,
                    timestamp = tempTimestamp,
                    imageUrl = imageUrl,
                    jobDetails = jobDetails?.let { jd ->
                        com.example.newconstructionappwithlocationtracking.models.JobDetails(
                            jobId = jd.optString("jobId", ""),
                            title = jd.optString("title", ""),
                            description = jd.optString("description", ""),
                            imageUrl = jd.optString("imageUrl", ""),
                            price = jd.optInt("price", 0),
                            buyerId = jd.optString("buyerId", "")
                        )
                    }
                )

                // Immediately show message in UI
                Handler(Looper.getMainLooper()).post {
                    messages.add(tempMessage)
                    val chatItems = buildChatItems(messages)
                    chatAdapter.updateItems(chatItems, userProfilePicUrl, currentUserProfilePicUrl, otherUserFullName)
                    chatRecyclerView.scrollToPosition(chatItems.size - 1)
                }

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("CHAT_FRAGMENT", "sendMessage failed: ${e.message}")
                        // Message already shown in UI, just log the error
                        // In production, you might want to mark the message as "failed to send"
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: ""
                        Log.d("CHAT_FRAGMENT", "sendMessage response: $responseBody")
                        val jsonResponse = if (responseBody.isNotEmpty()) JSONObject(responseBody) else JSONObject()
                        val success = jsonResponse.optBoolean("success", false)
                        if (success) {
                            Log.d("CHAT_FRAGMENT", "✅ Message sent successfully")
                            // Message already shown in UI via optimistic update
                            // Could update message ID from server response if needed
                        } else {
                            Log.e("CHAT_FRAGMENT", "❌ Message send failed: ${jsonResponse.optString("message", "Unknown error")}")
                            // Message already shown in UI, in production you might mark it as failed
                        }
                    }
                })
            }
        }
    }

    private fun fetchUserProfile(userId: String?) {
        Log.d("CHAT_FRAGMENT", "fetchUserProfile called with userId (recipientId): $userId")
        if (userId.isNullOrEmpty()) {
            isProfileLoaded = true
            checkAndShowChatUI()
            return
        }
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile/$userId"
                Log.d("CHAT_FRAGMENT", "Profile fetch URL: $url for recipientId: $userId")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("CHAT_FRAGMENT", "fetchUserProfile failed: ${e.message}")
                        isProfileLoaded = true
                        Handler(Looper.getMainLooper()).post {
                            // Try to use username from arguments as fallback instead of "User"
                            otherUserFullName = username ?: "User"

                            // Update adapter even on profile fetch failure to use the fallback name
                            if (messages.isNotEmpty()) {
                                val chatItems = buildChatItems(messages)
                                chatAdapter.updateItems(chatItems, userProfilePicUrl, currentUserProfilePicUrl, otherUserFullName)
                                Log.d("CHAT_FRAGMENT", "Updated adapter with fallback name: $otherUserFullName")
                            }

                            showProfileErrorUI()
                            checkAndShowChatUI()
                        }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        Log.d("CHAT_FRAGMENT", "Profile API response for recipientId $userId: $body")
                        val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
                        val data = json.optJSONObject("data")
                        val firstName = data?.optString("firstName", "") ?: ""
                        val lastName = data?.optString("lastName", "") ?: ""
                        userProfilePicUrl = data?.optString("profilePicUrl", "") ?: ""
                        val fullName = if ((firstName + " " + lastName).length > 18) firstName else (firstName + " " + lastName).trim()
                        otherUserFullName = fullName // Store the full name for use in chat messages

                        // Get lastLogin and calculate relative time
                        var lastLoginStatus = "Last seen recently"
                        val lastLoginObj = data?.optJSONObject("lastLogin")
                        if (lastLoginObj != null && lastLoginObj.has("_seconds")) {
                            val lastLoginSeconds = lastLoginObj.getLong("_seconds")
                            lastLoginStatus = calculateLastSeenStatus(lastLoginSeconds)
                        }

                        isProfileLoaded = true
                        Handler(Looper.getMainLooper()).post {
                            val nameView = view?.findViewById<android.widget.TextView>(R.id.chatUserName)
                            val lastActiveView = view?.findViewById<android.widget.TextView>(R.id.chatUserLastActive)

                            if (nameView != null) {
                                nameView.text = fullName
                                nameView.visibility = View.VISIBLE
                            }

                            if (lastActiveView != null) {
                                lastActiveView.text = lastLoginStatus
                                lastActiveView.visibility = View.VISIBLE
                            }

                            // Load profile picture into header avatar
                            if (!userProfilePicUrl.isNullOrEmpty()) {
                                com.bumptech.glide.Glide.with(this@ChatFragment)
                                    .load(userProfilePicUrl)
                                    .placeholder(R.drawable.ic_avatar_placeholder)
                                    .error(R.drawable.ic_avatar_placeholder)
                                    .circleCrop()
                                    .into(chatUserAvatar)
                            } else {
                                // Set placeholder avatar if no profile picture
                                chatUserAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
                            }

                            // Setup click listeners for header elements to navigate to user profile
                            setupHeaderClickListeners()

                            // ...existing code...
                            if (messages.isNotEmpty()) {
                                val chatItems = buildChatItems(messages)
                                chatAdapter.updateItems(chatItems, userProfilePicUrl, currentUserProfilePicUrl, otherUserFullName)
                                Log.d("CHAT_FRAGMENT", "Updated adapter with fetched user name: $fullName")
                            }

                            checkAndShowChatUI()
                        }
                    }
                })
            }
        }
    }

    private fun showProfileErrorUI() {
        val nameView = view?.findViewById<android.widget.TextView>(R.id.chatUserName)
        if (nameView != null) {
            nameView.text = "Profile not available"
        }
    }

    private fun showMessagingErrorUI() {
        messageInput.isEnabled = false
        sendArrow.isEnabled = false
        messageInput.hint = "Messaging not available"
    }

    private fun checkAndShowChatUI() {
        if (isMessagesLoaded && isProfileLoaded) {
            showChatUI()
        }
    }

    private fun buildChatItems(messages: List<ChatMessage>): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        var lastDate: String? = null
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        for (msg in messages) {
            val date = try {
                val parts = msg.timestamp.split(",")
                if (parts.isNotEmpty()) parts[0].trim() else ""
            } catch (e: Exception) { "" }
            if (date != lastDate) {
                items.add(ChatItem.DateSeparator(date))
                lastDate = date
            }
            items.add(ChatItem.Message(msg))
        }
        return items
    }

    private fun showImagePreview(uri: Uri) {
        imagePreviewContainer.visibility = View.VISIBLE
        imagePreview.setImageURI(uri)
        imageUploadSpinner.visibility = View.VISIBLE
        isImageUploading = true
        Log.d("CHAT_FRAGMENT", "Image selected for upload: $uri")
        updateSendButtonState()
    }

    private fun clearImagePreview() {
        imagePreviewContainer.visibility = View.GONE
        imagePreview.setImageDrawable(null)
        imageUploadSpinner.visibility = View.GONE
        selectedImageUri = null
        uploadedImageUrl = null
        isImageUploading = false
        Log.d("CHAT_FRAGMENT", "Image preview cleared")
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        val hasText = !messageInput.text.isNullOrEmpty()
        val hasUploadedImage = !uploadedImageUrl.isNullOrEmpty()
        // CRITICAL: Never enable send while image is uploading, even if there is text
        val canSend = !isImageUploading && (hasText || hasUploadedImage)
        Log.d("CHAT_FRAGMENT", "updateSendButtonState: hasText=$hasText, isImageUploading=$isImageUploading, uploadedImageUrl=$uploadedImageUrl, hasUploadedImage=$hasUploadedImage, canSend=$canSend")
        sendArrow.isEnabled = canSend
        val bgColor = if (canSend) "#8264fa" else "#CCCCCC"
        sendArrow.background?.setTint(android.graphics.Color.parseColor(bgColor))
        if (sendArrow is ImageView) {
            val iconColor = if (canSend) "#FFFFFF" else "#888888"
            (sendArrow as ImageView).setColorFilter(android.graphics.Color.parseColor(iconColor))
        }
    }

    private fun uploadImageToBackend(uri: Uri) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/upload-image"
                val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
                val fileBytes = inputStream?.readBytes()
                inputStream?.close()
                if (fileBytes == null) {
                    clearImagePreview()
                    Log.d("CHAT_FRAGMENT", "Image upload failed: fileBytes is null")
                    return@addOnCompleteListener
                }
                Log.d("CHAT_FRAGMENT", "Uploading image with chatId=$chatId, recipientId=$recipientId, senderId=$currentUserId")
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "upload.jpg",
                        RequestBody.create("image/jpeg".toMediaTypeOrNull(), fileBytes))
                    .addFormDataPart("chatId", chatId ?: "")
                    .addFormDataPart("recipientId", recipientId ?: "")
                    .addFormDataPart("senderId", currentUserId ?: "")
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                Log.d("CHAT_FRAGMENT", "Image upload request: ${requestBody.toString()}")
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Handler(Looper.getMainLooper()).post {
                            clearImagePreview()
                            Log.d("CHAT_FRAGMENT", "Image upload failed: ${e.message}")
                        }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        val json = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
                        // New format: { success: true, imageUrl: "..." }
                        // Old format: { success: true, message: { imageUrl: "..." } }
                        var imageUrl = json.optString("imageUrl", null)
                        if (imageUrl.isNullOrEmpty()) {
                            val messageObj = json.optJSONObject("message")
                            imageUrl = messageObj?.optString("imageUrl", null)
                        }
                        if (imageUrl.isNullOrEmpty()) imageUrl = null
                        Log.d("CHAT_FRAGMENT", "Image upload response: $body, imageUrl: $imageUrl")
                        Handler(Looper.getMainLooper()).post {
                            imageUploadSpinner.visibility = View.GONE
                            isImageUploading = false
                            uploadedImageUrl = imageUrl
                            if (imageUrl == null) {
                                Log.e("CHAT_FRAGMENT", "❌ Image upload succeeded but no imageUrl returned!")
                            }
                            updateSendButtonState()
                        }
                    }
                })
            }
        }
    }

    private fun setupMessageInput() {
        sendArrow.isEnabled = false
        sendArrow.background?.setTint(android.graphics.Color.parseColor("#CCCCCC"))
        if (sendArrow is ImageView) {
            (sendArrow as ImageView).setColorFilter(android.graphics.Color.parseColor("#888888"))
        }

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSendButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle keyboard showing when message input is focused
        messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboard()
            }
        }

        messageInput.setOnClickListener {
            messageInput.requestFocus()
            showKeyboard()
        }

        sendArrow.setOnClickListener {
            val content = messageInput.text.toString().trim()
            if (content.isNotEmpty() || !uploadedImageUrl.isNullOrEmpty()) {
                // Check permissions before sending message
                checkPermissionsAndSendMessage(content, uploadedImageUrl, pendingJobDetails)
            }
        }
    }

    private fun setupNavigationAndActions() {
        val nameView = view?.findViewById<TextView>(R.id.chatUserName)
        nameView?.visibility = View.INVISIBLE // Hide username during loading

        val backArrow = view?.findViewById<ImageView>(R.id.backArrow)
        backArrow?.setOnClickListener {
            // Properly handle back navigation to avoid crashes
            val fragmentManager = parentFragmentManager
            if (fragmentManager.backStackEntryCount > 0) {
                // Pop from back stack to return to previous fragment
                fragmentManager.popBackStack()
                // Update tab to inbox since we're going back from chat
                (activity as? NavigationHost)?.updateSelectedTab(R.id.nav_inbox)
            } else {
                // If no back stack, navigate to InboxFragment using NavigationHost
                (activity as? NavigationHost)?.navigateToTab(R.id.nav_inbox)
            }
        }

        val plusIcon = view?.findViewById<ImageView>(R.id.plusIcon)
        plusIcon?.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun setupJobPreview() {
        // Show job preview if pending job details exist
        pendingJobDetails?.let { jobDetails ->
            showJobPreview(jobDetails)
        }

        // Setup remove job preview button
        if (::removeJobPreview.isInitialized) {
            removeJobPreview.setOnClickListener {
                hideJobPreview()
            }
        }
    }

    private fun setupKeyboardHiding(view: View) {
        // Hide keyboard when user taps outside the message input area

        // Set touch listeners on chat areas to hide keyboard
        chatRecyclerView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (messageInput.hasFocus()) {
                    hideKeyboard()
                }
            }
            false // Don't consume the touch event
        }

        // Set on the job preview container
        jobPreviewContainer?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (messageInput.hasFocus()) {
                    hideKeyboard()
                }
            }
            false // Don't consume the touch event
        }

        // Set on image preview container
        imagePreviewContainer.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (messageInput.hasFocus()) {
                    hideKeyboard()
                }
            }
            false // Don't consume the touch event
        }

        // Set on loading layout
        loadingLayout.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (messageInput.hasFocus()) {
                    hideKeyboard()
                }
            }
            false // Don't consume the touch event
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            view?.let { v ->
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            // Clear focus from message input
            messageInput.clearFocus()
        } catch (e: Exception) {
            Log.e("CHAT_FRAGMENT", "Error hiding keyboard", e)
        }
    }

    private fun showKeyboard() {
        try {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            messageInput.requestFocus()
            // Small delay to ensure the view is ready
            messageInput.postDelayed({
                imm.showSoftInput(messageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        } catch (e: Exception) {
            Log.e("CHAT_FRAGMENT", "Error showing keyboard", e)
        }
    }

    private fun showJobPreview(jobDetails: JSONObject) {
        try {
            if (!::jobPreviewContainer.isInitialized) return

            val title = jobDetails.optString("title", "Job Title")
            val description = jobDetails.optString("description", "Job description...")
            val price = jobDetails.optInt("price", 0)
            val imageUrl = jobDetails.optString("imageUrl", "")

            jobPreviewTitle.text = title
            jobPreviewDescription.text = description
            jobPreviewPrice.text = "$$price"

            // Load job image if available
            if (imageUrl.isNotEmpty()) {
                com.bumptech.glide.Glide.with(this)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_placeholder)
                    .centerCrop()
                    .into(jobPreviewImage)
            }

            jobPreviewContainer.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("CHAT_FRAGMENT", "Error showing job preview", e)
        }
    }

    private fun hideJobPreview() {
        if (::jobPreviewContainer.isInitialized) {
            jobPreviewContainer.visibility = View.GONE
        }
        pendingJobDetails = null
    }

    private fun getBackendUrlFromEnv(): String {
        return try {
            val assetManager = requireContext().assets
            assetManager.open(".env").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("BACKEND_URL=")) {
                        return line.substringAfter("=").trim()
                    }
                }
            }
            ""
        } catch (ignored: Exception) {
            Log.w("CHAT_FRAGMENT", ".env not found in assets")
            ""
        }
    }

    private fun setupHeaderClickListeners() {
        // Make header elements clickable to navigate to user profile
        val nameView = view?.findViewById<TextView>(R.id.chatUserName)
        val lastActiveView = view?.findViewById<TextView>(R.id.chatUserLastActive)

        val clickListener = View.OnClickListener {
            navigateToUserProfile()
        }

        // Set click listeners on all header elements
        chatUserAvatar.setOnClickListener(clickListener)
        nameView?.setOnClickListener(clickListener)
        lastActiveView?.setOnClickListener(clickListener)

        // Also make the entire header container clickable for better UX
        val headerContainer = view?.findViewById<View>(R.id.headerInfoContainer)
        headerContainer?.setOnClickListener(clickListener)
    }

    private fun navigateToUserProfile() {
        if (recipientId.isNullOrEmpty()) {
            Log.e("CHAT_FRAGMENT", "Cannot navigate to profile: recipientId is null or empty")
            return
        }

        try {
            val context = requireContext()
            val intent = android.content.Intent(context, com.example.newconstructionappwithlocationtracking.BuyerProfileActivity::class.java)
            intent.putExtra("uid", recipientId) // Use recipientId as the user ID
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CHAT_FRAGMENT", "Error navigating to user profile", e)
            showError("Unable to open user profile")
        }
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

    // Function to get the display name for a message sender
    private fun getDisplayNameForMessage(senderId: String): String {
        return if (senderId == currentUserId) {
            "Me"
        } else {
            otherUserFullName ?: senderId
        }
    }

    /**
     * Clear the message input field
     */
    private fun clearMessageInput() {
        messageInput.setText("")
    }

    /**
     * Clear pending message data
     */
    private fun clearPendingMessage() {
        pendingMessageContent = null
        pendingMessageImageUrl = null
        pendingMessageJobDetails = null
    }

    /**
     * Handle permission results for chat permissions
     * This is called when user grants/denies permissions from system dialog
     *
     * KEY: This callback ONLY fires if Android showed the system permission dialog
     * If this callback fires → Android showed dialog → User made a choice → NO "Continue" popup
     * If this callback NEVER fires → Android blocked dialog → Show "Continue" popup (detected in onResume)
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.d("CHAT_PERMISSION", "===== onRequestPermissionsResult called =====")
        Log.d("CHAT_PERMISSION", "Request code: $requestCode")
        Log.d("CHAT_PERMISSION", "Permissions: ${permissions.joinToString()}")
        Log.d("CHAT_PERMISSION", "Grant results: ${grantResults.joinToString()}")

        when (requestCode) {
            LocationPermissionManager.REQUEST_CODE_JOB_ACTION_FINE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d("CHAT_PERMISSION", "✅ Fine location GRANTED, showing background dialog")

                    // 🚀 START LOCATION TRACKING SERVICE WHEN PERMISSION GRANTED
                    Log.d("CHAT_PERMISSION", "🌍 Starting LocationTrackingService after permission grant...")
                    try {
                        com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(requireContext())
                        Log.d("CHAT_PERMISSION", "✅ LocationTrackingService start command sent")
                    } catch (e: Exception) {
                        Log.e("CHAT_PERMISSION", "❌ Failed to start LocationTrackingService", e)
                    }

                    // Reset global denial counter on grant
                    permissionManager.resetGlobalFineLocationDenialCount()
                    showChatPermissionBackgroundDialog()
                } else {
                    Log.d("CHAT_PERMISSION", "❌ Fine location DENIED by user")
                    // Increment global denial counter
                    permissionManager.incrementGlobalFineLocationDenialCount()

                    // Check denial count
                    val denialCount = permissionManager.getGlobalFineLocationDenialCount()

                    Log.d("CHAT_PERMISSION", "Denial count after increment: $denialCount")
                    Log.d("CHAT_PERMISSION", "User can click Send again to continue")

                    // Clear pending message - user stays on chat
                    // Next time they click Send, the Initial dialog will check the count and show Continue dialog
                    clearPendingMessage()
                }
            }
        }
    }

    /**
     * Handle when returning from settings
     */
    override fun onResume() {
        super.onResume()

        Log.d("CHAT_PERMISSION", "═════════════════════════════════════")
        Log.d("CHAT_PERMISSION", "📱 ChatFragment.onResume() - Checking permissions...")

        // Silently check if user granted permissions from settings
        // Do NOT show any popups here - only when user clicks SEND again
        if (hasCheckedPermissionsOnce && (pendingMessageContent != null || pendingMessageImageUrl != null)) {
            val status = permissionManager.getComprehensivePermissionStatus(activity)

            Log.d("CHAT_PERMISSION", "onResume - silently checking permissions. Fine: ${status.hasFineLocation}, Background: ${status.hasBackgroundLocation}")

            // 🚀 START SERVICE IF BOTH PERMISSIONS ARE GRANTED
            if (status.hasFineLocation && status.hasBackgroundLocation) {
                Log.d("CHAT_PERMISSION", "✅ Both permissions granted!")
                Log.d("CHAT_PERMISSION", "🌍 Starting LocationTrackingService...")

                try {
                    com.example.newconstructionappwithlocationtracking.services.LocationTrackingService.start(requireContext())
                    Log.d("CHAT_PERMISSION", "✅ LocationTrackingService start command sent")
                } catch (e: Exception) {
                    Log.e("CHAT_PERMISSION", "❌ Failed to start LocationTrackingService", e)
                }

                Log.d("CHAT_PERMISSION", "Both permissions granted - ready to send on next SEND click")
                // Keep pending message data - it will be sent when checkPermissionsAndSendMessage is called next
            } else if (status.hasFineLocation && !status.hasBackgroundLocation) {
                Log.d("CHAT_PERMISSION", "Only fine location granted - will prompt for background on next SEND click")
                // Keep pending message data - will show background dialog on next send click
            } else {
                Log.d("CHAT_PERMISSION", "Permissions still not granted - will continue flow on next SEND click")
                // Keep pending message data - will continue permission flow on next send click
            }
        }

        hasCheckedPermissionsOnce = true
        Log.d("CHAT_PERMISSION", "═════════════════════════════════════")
    }

    private fun showChatPermissionInitialDialog() {
        Log.d("CHAT_PERMISSION", "===== showChatPermissionInitialDialog CALLED =====")

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_permission_initial, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val btnOkay = dialogView.findViewById<android.widget.Button>(R.id.btnOkay)
        btnOkay?.setOnClickListener {
            Log.d("CHAT_PERMISSION", "===== Okay button CLICKED =====")
            dialog.dismiss()
            Log.d("CHAT_PERMISSION", "Initial dialog dismissed")

            // Check current permission status
            val hasFineLocation = permissionManager.hasFineLocationPermission()
            val shouldShowRationale = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )

            Log.d("CHAT_PERMISSION", "Permission check: hasFineLocation=$hasFineLocation, shouldShowRationale=$shouldShowRationale")

            // Check if Android is blocking (denial count >= 2)
            val denialCount = permissionManager.getGlobalFineLocationDenialCount()
            val isAndroidBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

            Log.d("CHAT_PERMISSION", "📱 Okay button clicked - denialCount=$denialCount, androidBlocking=$isAndroidBlocking")

            // If permission denied AND shouldShowRationale is FALSE, Android is blocking
            if (!hasFineLocation && !shouldShowRationale && denialCount > 0) {
                // Android has blocked the permission dialog - increment counter and show Continue dialog
                Log.d("CHAT_PERMISSION", "🚫 Android blocking detected (shouldShowRationale=false)!")
                permissionManager.incrementGlobalFineLocationDenialCount()
                val newCount = permissionManager.getGlobalFineLocationDenialCount()
                Log.d("CHAT_PERMISSION", "Incremented counter: $denialCount → $newCount")
                showChatPermissionContinueDialog()
            } else if (isAndroidBlocking) {
                // After 2+ denials - show Continue dialog to go to Settings
                Log.d("CHAT_PERMISSION", "🚫 Showing Continue dialog (denial count >= 2)")
                showChatPermissionContinueDialog()
            } else {
                // First or second attempt - request permission normally
                Log.d("CHAT_PERMISSION", "📱 Requesting fine location permission")
                // Request from Fragment, not Activity, so onRequestPermissionsResult is called in Fragment
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                        LocationPermissionManager.REQUEST_CODE_JOB_ACTION_FINE
                    )
                    Log.d("CHAT_PERMISSION", "Permission request sent from Fragment")
                }
            }
        }

        Log.d("CHAT_PERMISSION", "Showing initial dialog now...")
        dialog.show()
        Log.d("CHAT_PERMISSION", "Initial dialog shown")
    }

    private fun showChatPermissionBackgroundDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_permission_background, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val btnContinueToSettings = dialogView.findViewById<android.widget.Button>(R.id.btnContinueToSettings)
        btnContinueToSettings?.setOnClickListener {
            dialog.dismiss()
            Log.d("CHAT_PERMISSION", "📱 Continue to Settings clicked - opening background location permission settings")

            // Open the EXACT background location permission settings page
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ - can request background directly
                permissionManager.requestBackgroundLocationPermission(requireActivity())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 - must open settings to allow "Allow all the time"
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", requireActivity().packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }

        dialog.show()
    }

    /**
     * Show dialog when Android has silently blocked permission requests
     * Uses "Continue" button (not "Continue to Settings") to open app settings
     */
    private fun showChatPermissionSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_permission_settings, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btnContinue)
        btnContinue?.setOnClickListener {
            dialog.dismiss()
            Log.d("CHAT_PERMISSION", "📱 Continue button clicked - opening app settings for location permission")

            // Open app settings where user can grant location permission
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = android.net.Uri.fromParts("package", requireActivity().packageName, null)
            intent.data = uri
            startActivity(intent)
        }

        dialog.show()
    }

    /**
     * Show Continue dialog when Android has silently blocked permission requests
     * This is shown when global denial count >= 2
     */
    private fun showChatPermissionContinueDialog() {
        Log.d("CHAT_PERMISSION", "===== showChatPermissionContinueDialog CALLED =====")

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_chat_permission_settings, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btnContinue)
        btnContinue?.setOnClickListener {
            Log.d("CHAT_PERMISSION", "===== Continue button CLICKED =====")
            dialog.dismiss()

            // Open app settings where user can grant location permission
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = android.net.Uri.fromParts("package", requireActivity().packageName, null)
            intent.data = uri
            startActivity(intent)
        }

        dialog.show()
        Log.d("CHAT_PERMISSION", "✅ Continue dialog SHOWN")
    }
}
