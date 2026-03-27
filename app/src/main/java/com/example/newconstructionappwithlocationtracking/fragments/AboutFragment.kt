package com.example.newconstructionappwithlocationtracking.fragments

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

class AboutFragment : Fragment() {
    private var profileData: JSONObject? = null
    private var profileDataString: String? = null
    private lateinit var loadingIndicator: ProgressBar
    private val client = OkHttpClient()
    private var isUploading = false

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadProfileImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore profile data from savedInstanceState first, then from arguments
        profileDataString = savedInstanceState?.getString("profileData")
            ?: arguments?.getString("profileData")

        profileDataString?.let {
            if (it.isNotEmpty() && it != "{}") {
                try {
                    profileData = JSONObject(it)
                } catch (e: Exception) {
                    android.util.Log.e("AboutFragment", "Error parsing profile data in onCreate: ${e.message}")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save profile data string for restoration
        profileData?.toString()?.let { outState.putString("profileData", it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.d("AboutFragment", "=== onViewCreated called ===")

        // Scroll is now handled by outer ProfileActivity NestedScrollView
        loadingIndicator = view.findViewById(R.id.aboutLoadingIndicator)
        showLoading(true)

        // Profile data is already parsed in onCreate, check if we have it
        android.util.Log.d("AboutFragment", "📄 Profile data available: ${profileData != null}")

        if (profileData != null) {
            try {
                android.util.Log.d("AboutFragment", "✅ Profile data parsed successfully")
                android.util.Log.d("AboutFragment", "📊 Profile data keys: ${profileData?.keys()?.asSequence()?.toList()}")

                updateUI(view)
                setupEditListeners(view)
                showLoading(false)
                android.util.Log.d("AboutFragment", "✅ AboutFragment setup complete")

            } catch (e: Exception) {
                android.util.Log.e("AboutFragment", "❌ Error setting up UI: ${e.message}", e)
                showLoading(false)
                // Don't show toast, just fetch the data
                fetchProfileDataFromBackend(view)
            }
        } else {
            android.util.Log.w("AboutFragment", "⚠️ No profile data provided, attempting to fetch...")
            // Attempt to fetch the profile data directly from the backend
            fetchProfileDataFromBackend(view)
        }
    }

    private fun fetchProfileDataFromBackend(view: View) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showLoading(false)
            android.util.Log.e("AboutFragment", "❌ User not authenticated")
            return
        }

        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activity?.runOnUiThread {
                            showLoading(false)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        activity?.runOnUiThread {
                            try {
                                if (response.isSuccessful && body != null) {
                                    val json = JSONObject(body)
                                    val data = json.optJSONObject("data")
                                    if (data != null) {
                                        profileData = data
                                        updateUI(view)
                                        setupEditListeners(view)
                                    }
                                }
                            } catch (e: Exception) {
                                // Error parsing profile
                            }
                            showLoading(false)
                        }
                    }
                })
            } else {
                showLoading(false)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        android.util.Log.d("AboutFragment", "showLoading: $isLoading")
        if (::loadingIndicator.isInitialized) {
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun updateUI(view: View) {
        val data = profileData ?: return

        // Check if profile needs completion and show/hide hint
        val profileHintText = view.findViewById<LinearLayout>(R.id.profileHintText)
        val isProfileIncomplete = isProfileIncomplete(data)
        profileHintText?.visibility = if (isProfileIncomplete) View.VISIBLE else View.GONE

        val userNameText = view.findViewById<TextView>(R.id.userNameText)
        val descriptionText = view.findViewById<TextView>(R.id.descriptionText)
        val memberSinceText = view.findViewById<TextView>(R.id.memberSinceText)
        val memberSinceDetails = view.findViewById<TextView>(R.id.memberSinceText_details)
        val lastActiveText = view.findViewById<TextView>(R.id.lastActiveText)
        val locationText = view.findViewById<TextView>(R.id.locationText)
        val avgResponseText = view.findViewById<TextView>(R.id.avgResponseText)
        val languagesContainer = view.findViewById<FlexboxLayout>(R.id.languagesContainer)
        val skillsContainer = view.findViewById<FlexboxLayout>(R.id.skillsContainer)
        val avatarView = view.findViewById<ImageView>(R.id.profileAvatar)
        val ratingBar = view.findViewById<android.widget.RatingBar>(R.id.profileRatingBar)
        val totalReviewsText = view.findViewById<TextView>(R.id.totalReviewsText)

        // Name
        val firstName = data.optString("firstName", "")
        val lastName = data.optString("lastName", "")
        val fullName = "$firstName $lastName".trim()
        userNameText.text = if (fullName.isNotEmpty()) fullName else "User Name"

        // Avatar and Upload Indicators
        val profilePicUrl = data.optString("profilePicUrl", "")
        val avatarContainer = avatarView.parent as? FrameLayout
        val cameraOverlay = avatarContainer?.findViewById<View>(R.id.cameraOverlay)
        val cameraIcon = avatarContainer?.findViewById<ImageView>(R.id.cameraIcon)
        val editAvatarIcon = avatarContainer?.findViewById<ImageView>(R.id.editAvatarIcon)
        val uploadProgress = avatarContainer?.findViewById<ProgressBar>(R.id.uploadProgress)

        if (profilePicUrl.isNotEmpty()) {
            try {
                com.bumptech.glide.Glide.with(this)
                    .load(profilePicUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .error(R.drawable.ic_avatar_placeholder)
                    .into(avatarView)

                // Hide camera overlay and show small edit icon when profile picture is loaded
                cameraOverlay?.visibility = View.GONE
                cameraIcon?.visibility = View.GONE
                editAvatarIcon?.visibility = View.VISIBLE
                uploadProgress?.visibility = View.GONE
            } catch (_: Exception) {
                avatarView.setImageResource(R.drawable.ic_avatar_placeholder)
                // Show camera overlay for placeholder
                cameraOverlay?.visibility = View.VISIBLE
                cameraIcon?.visibility = View.VISIBLE
                editAvatarIcon?.visibility = View.GONE
                uploadProgress?.visibility = View.GONE
            }
        } else {
            avatarView.setImageResource(R.drawable.ic_avatar_placeholder)
            // Show camera overlay when no profile picture
            cameraOverlay?.visibility = View.VISIBLE
            cameraIcon?.visibility = View.VISIBLE
            editAvatarIcon?.visibility = View.GONE
            uploadProgress?.visibility = View.GONE
        }

        // Description
        val description = data.optString("description", "")
        if (description.isEmpty() || description == "New seller - profile coming soon!") {
            descriptionText.text = "Tap to add a description about yourself and your services"
            descriptionText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
            descriptionText.alpha = 0.7f
            descriptionText.isClickable = true
            descriptionText.isFocusable = true
        } else {
            descriptionText.text = description
            descriptionText.setTextColor(resources.getColor(android.R.color.black, null))
            descriptionText.alpha = 1.0f
        }

        // Member Since
        val createdAt = data.optJSONObject("createdAt")
        val memberSince = createdAt?.optLong("_seconds")?.let {
            java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it * 1000))
        } ?: "Recently joined"
        val memberLabel = getString(R.string.member_since_format, memberSince)
        memberSinceText.text = memberLabel
        memberSinceDetails?.text = memberLabel

        // Last Active / Active Status
        val activeStatus = data.optString("ActiveStatus", "")
        if (activeStatus.isEmpty()) {
            lastActiveText.text = "Recently active"
            lastActiveText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        } else {
            // Clean display of active status
            lastActiveText.text = activeStatus
            if (activeStatus.contains("Active now", ignoreCase = true)) {
                lastActiveText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            } else {
                lastActiveText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
            }
        }

        // Location
        val location = data.optString("location", "")
        if (location.isEmpty()) {
            locationText.text = "Tap to add your location"
            locationText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
            locationText.alpha = 0.7f
            locationText.isClickable = true
            locationText.isFocusable = true
        } else {
            locationText.text = getString(R.string.from_label, location).replace("From", "")
            locationText.setTextColor(resources.getColor(android.R.color.black, null))
            locationText.alpha = 1.0f
        }

        // Average Response Time
        val avgResponseTime = data.optString("AverageResponseTime", "1")
        avgResponseText.text = if (avgResponseTime == "1")
            getString(R.string.avg_response_hours_one, avgResponseTime)
        else getString(R.string.avg_response_hours_many, avgResponseTime)

        // Rating & Reviews
        val ratingValue = data.optDouble("rating", 0.0)
        ratingBar?.rating = ratingValue.toFloat()
        val totalReviews = data.optInt("totalReviews", 0)
        totalReviewsText?.text = if (totalReviews == 1)
            getString(R.string.total_reviews_one, totalReviews)
        else getString(R.string.total_reviews_many, totalReviews)

        // Languages
        val addLanguagesHint = view.findViewById<TextView>(R.id.addLanguagesHint)

        languagesContainer.removeAllViews()
        val languages = data.optJSONArray("Languages")

        if (languages != null && languages.length() > 0) {
            // Check if it's just the default "English" placeholder
            val hasRealLanguages = if (languages.length() == 1) {
                val firstLang = languages.optString(0)
                firstLang != "English" || data.optString("description", "").isNotEmpty() // If they have a description, assume English is real
            } else {
                true
            }

            if (hasRealLanguages) {
                for (i in 0 until languages.length()) {
                    val lang = languages.optString(i)
                    val langBox = layoutInflater.inflate(R.layout.language_box, languagesContainer, false)
                    val langText = langBox.findViewById<TextView>(R.id.languageText)
                    val langTag = langBox.findViewById<TextView>(R.id.languageTag)
                    langText.text = lang
                    langTag.text = getString(R.string.fluent_label)
                    languagesContainer.addView(langBox)
                }
                // Show "Add more" hint since there are existing languages
                addLanguagesHint?.visibility = View.VISIBLE
            } else {
                addEmptyTag(languagesContainer, "Tap to add languages you speak")
                addLanguagesHint?.visibility = View.GONE
            }
        } else {
            addEmptyTag(languagesContainer, "Tap to add languages you speak")
            addLanguagesHint?.visibility = View.GONE
        }

        // Skills
        val addSkillsHint = view.findViewById<TextView>(R.id.addSkillsHint)

        skillsContainer.removeAllViews()
        val skills = data.optJSONArray("skills")

        if (skills != null && skills.length() > 0) {
            for (i in 0 until skills.length()) {
                val skill = skills.optString(i)
                val skillBox = layoutInflater.inflate(R.layout.skill_box, skillsContainer, false)
                val skillText = skillBox.findViewById<TextView>(R.id.skillText)
                skillText.text = skill
                skillsContainer.addView(skillBox)
            }
            // Show "Add more" hint since there are existing skills
            addSkillsHint?.visibility = View.VISIBLE
        } else {
            addEmptyTag(skillsContainer, "Tap to add your skills and expertise")
            addSkillsHint?.visibility = View.GONE
        }
    }

    private fun isProfileIncomplete(data: JSONObject): Boolean {
        val description = data.optString("description", "")
        val location = data.optString("location", "")
        val profilePicUrl = data.optString("profilePicUrl", "")
        val skills = data.optJSONArray("skills")
        val languages = data.optJSONArray("Languages")

        // Profile is incomplete if missing key information
        return (description.isEmpty() ||
                location.isEmpty() ||
                profilePicUrl.isEmpty() ||
                skills == null || skills.length() == 0 ||
                languages == null || languages.length() == 0)
    }

    private fun setupEditListeners(view: View) {
        val editNameIcon = view.findViewById<ImageView>(R.id.editNameIcon)
        val editDescriptionIcon = view.findViewById<ImageView>(R.id.editDescriptionIcon)
        val editLanguagesIcon = view.findViewById<ImageView>(R.id.editLanguagesIcon)
        val editSkillsIcon = view.findViewById<ImageView>(R.id.editSkillsIcon)
        val avatarView = view.findViewById<ImageView>(R.id.profileAvatar)
        val avatarContainer = avatarView.parent as? FrameLayout
        val languagesContainer = view.findViewById<FlexboxLayout>(R.id.languagesContainer)
        val skillsContainer = view.findViewById<FlexboxLayout>(R.id.skillsContainer)
        val addLanguagesHint = view.findViewById<TextView>(R.id.addLanguagesHint)
        val addSkillsHint = view.findViewById<TextView>(R.id.addSkillsHint)
        val locationText = view.findViewById<TextView>(R.id.locationText)

        // Edit profile picture - click on the FrameLayout container
        avatarContainer?.setOnClickListener {
            if (!isUploading) {
                imagePickerLauncher.launch("image/*")
            }
        }

        // Also allow clicking directly on the avatar image
        avatarView.setOnClickListener {
            if (!isUploading) {
                imagePickerLauncher.launch("image/*")
            }
        }

        // ...existing code...

        // Edit name
        editNameIcon.setOnClickListener {
            showEditNameDialog()
        }

        // Edit description
        editDescriptionIcon.setOnClickListener {
            showEditTextDialog("Edit Description", "description", profileData?.optString("description", ""))
        }

        // Edit location
        locationText.setOnClickListener {
            showEditTextDialog("Edit Location", "location", profileData?.optString("location", ""))
        }

        // Edit description text (in addition to the icon)
        val descriptionText = view.findViewById<TextView>(R.id.descriptionText)
        descriptionText.setOnClickListener {
            showEditTextDialog("Edit Description", "description", profileData?.optString("description", ""))
        }

        // Edit languages - multiple ways to trigger
        editLanguagesIcon?.setOnClickListener {
            showEditArrayDialog("Edit Languages", "Languages")
        }

        languagesContainer.setOnClickListener {
            showEditArrayDialog("Edit Languages", "Languages")
        }

        addLanguagesHint?.setOnClickListener {
            showEditArrayDialog("Edit Languages", "Languages")
        }

        // Edit skills - multiple ways to trigger
        editSkillsIcon?.setOnClickListener {
            showEditArrayDialog("Edit Skills", "skills")
        }

        skillsContainer.setOnClickListener {
            showEditArrayDialog("Edit Skills", "skills")
        }

        addSkillsHint?.setOnClickListener {
            showEditArrayDialog("Edit Skills", "skills")
        }
    }

    private fun showEditNameDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_text, null)
        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        // Make dialog background transparent and remove default styling
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val textInputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputLayout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.editTextInput)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)

        titleText.text = "Edit Name"
        textInputLayout.hint = "Enter your full name"
        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        editText.maxLines = 1
        textInputLayout.isCounterEnabled = true
        textInputLayout.counterMaxLength = 50

        val firstName = profileData?.optString("firstName", "")
        val lastName = profileData?.optString("lastName", "")
        editText.setText("$firstName $lastName".trim())

        // Add validation on text change
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val fullName = s.toString().trim()
                val parts = fullName.split(" ")
                val firstNamePart = parts.getOrNull(0) ?: ""

                if (firstNamePart.length < 2) {
                    textInputLayout.error = "First name must be at least 2 characters"
                    saveButton.isEnabled = false
                    saveButton.alpha = 0.5f
                } else {
                    textInputLayout.error = null
                    saveButton.isEnabled = true
                    saveButton.alpha = 1.0f
                }
            }
        })

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val fullName = editText.text.toString().trim()
            val parts = fullName.split(" ")
            val newFirstName = parts.getOrNull(0) ?: ""
            val newLastName = parts.drop(1).joinToString(" ").trim()

            if (newFirstName.length < 2) {
                textInputLayout.error = "First name must be at least 2 characters"
                return@setOnClickListener
            }

            val updates = JSONObject()
            updates.put("firstName", newFirstName)
            // Only include lastName if it's not empty
            if (newLastName.isNotEmpty()) {
                updates.put("lastName", newLastName)
            } else {
                updates.put("lastName", "")
            }

            android.util.Log.d("ABOUT_FRAGMENT", "👤 Name update: firstName='$newFirstName', lastName='$newLastName'")

            dialog.dismiss()
            updateProfile(updates)
        }

        dialog.show()

        // Focus on input field and place cursor at end
        editText.requestFocus()
        editText.setSelection(editText.text?.length ?: 0)
    }

    private fun showEditTextDialog(title: String, field: String, currentValue: String?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_text, null)
        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        // Make dialog background transparent and remove default styling
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val textInputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputLayout)
        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextInput)
        val characterCounter = dialogView.findViewById<TextView>(R.id.characterCounter)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)

        titleText.text = title

        // Configure input based on field type
        when (field) {
            "description" -> {
                textInputLayout.hint = "Tell others about yourself, your experience, and services you offer..."
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                editText.maxLines = 6
                editText.minLines = 3
                textInputLayout.isCounterEnabled = true
                textInputLayout.counterMaxLength = 500
                characterCounter.visibility = View.VISIBLE
            }
            "location" -> {
                textInputLayout.hint = "Enter your city or location"
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                editText.maxLines = 1
                textInputLayout.isCounterEnabled = true
                textInputLayout.counterMaxLength = 50
            }
            else -> {
                textInputLayout.hint = "Enter value"
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                editText.maxLines = 3
            }
        }

        editText.setText(currentValue ?: "")

        // Add character counter functionality
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val length = s?.length ?: 0
                val maxLength = if (field == "description") 500 else if (field == "location") 50 else 200
                characterCounter.text = "$length/$maxLength"

                if (length > maxLength) {
                    characterCounter.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                } else {
                    characterCounter.setTextColor(resources.getColor(android.R.color.darker_gray, null))
                }
            }
        })

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val newValue = editText.text.toString().trim()

            // Validate input length
            val maxLength = if (field == "description") 500 else if (field == "location") 50 else 200
            if (newValue.length > maxLength) {
                editText.error = "Text is too long (max $maxLength characters)"
                return@setOnClickListener
            }

            val updates = JSONObject()
            updates.put(field, newValue)

            android.util.Log.d("ABOUT_FRAGMENT", "📝 Text field update: $field='$newValue'")

            dialog.dismiss()
            updateProfile(updates)
        }

        dialog.show()
    }

    private fun showEditArrayDialog(title: String, field: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_array, null)
        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        // Make dialog background transparent and remove default styling
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val addInput = dialogView.findViewById<TextInputEditText>(R.id.addItemInput)
        val addButton = dialogView.findViewById<Button>(R.id.addButton)
        val itemsContainer = dialogView.findViewById<LinearLayout>(R.id.itemsContainer)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.emptyStateText)
        val itemsLabel = dialogView.findViewById<TextView>(R.id.itemsLabel)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)

        titleText.text = title

        // Configure hint based on field type
        val addInputLayout = addInput.parent.parent as? com.google.android.material.textfield.TextInputLayout
        when (field) {
            "Languages" -> {
                addInputLayout?.hint = "Add language (e.g. English, Urdu)"
                itemsLabel?.text = "Languages you speak:"
            }
            "skills" -> {
                addInputLayout?.hint = "Add skill (e.g. Web Design, Construction)"
                itemsLabel?.text = "Your skills & expertise:"
            }
        }

        val items = mutableListOf<String>()

        // Load existing items
        val existingArray = profileData?.optJSONArray(field)
        if (existingArray != null) {
            for (i in 0 until existingArray.length()) {
                items.add(existingArray.optString(i))
            }
        }

        fun refreshChips() {
            itemsContainer.removeAllViews()

            if (items.isEmpty()) {
                emptyStateText?.visibility = View.VISIBLE
                emptyStateText?.text = "No ${if (field == "Languages") "languages" else "skills"} added yet"
            } else {
                emptyStateText?.visibility = View.GONE
                items.forEachIndexed { index, item ->
                    val chipView = LayoutInflater.from(requireContext()).inflate(R.layout.item_chip, itemsContainer, false)
                    val chipText = chipView.findViewById<TextView>(R.id.chipText)
                    chipText.text = item

                    // Add ripple effect and click listener
                    chipView.setOnClickListener {
                        // Add confirmation with animation
                        chipView.animate()
                            .alpha(0.3f)
                            .scaleX(0.8f)
                            .scaleY(0.8f)
                            .setDuration(200)
                            .withEndAction {
                                items.removeAt(items.indexOf(item))
                                refreshChips()

                                // Show feedback
                                android.widget.Toast.makeText(
                                    requireContext(),
                                    "\"$item\" removed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .start()
                    }

                    itemsContainer.addView(chipView)
                }
            }
        }

        refreshChips()

        // Handle Enter key in input field
        addInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val newItem = addInput.text.toString().trim()
                if (newItem.isNotEmpty() && !items.contains(newItem) && newItem.length >= 2) {
                    items.add(newItem)
                    addInput.setText("")
                    refreshChips()

                    // Show feedback
                    android.widget.Toast.makeText(
                        requireContext(),
                        "\"$newItem\" added",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else if (newItem.length < 2) {
                    addInput.error = "Too short (minimum 2 characters)"
                } else if (items.contains(newItem)) {
                    addInput.error = "Already added"
                }
                true
            } else {
                false
            }
        }

        addButton.setOnClickListener {
            val newItem = addInput.text.toString().trim()
            if (newItem.isNotEmpty() && !items.contains(newItem) && newItem.length >= 2) {
                items.add(newItem)
                addInput.setText("")
                refreshChips()

                // Show feedback
                android.widget.Toast.makeText(
                    requireContext(),
                    "\"$newItem\" added",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // Clear any error
                addInput.error = null
            } else if (newItem.isEmpty()) {
                addInput.error = "Please enter a value"
            } else if (newItem.length < 2) {
                addInput.error = "Too short (minimum 2 characters)"
            } else if (items.contains(newItem)) {
                addInput.error = "Already added"
            }
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val jsonArray = JSONArray()
            items.forEach { jsonArray.put(it) }

            val updates = JSONObject()
            updates.put(field, jsonArray)

            android.util.Log.d("ABOUT_FRAGMENT", "🏷️ Array field update: $field=${jsonArray.toString()}")
            android.util.Log.d("ABOUT_FRAGMENT", "📦 Items count: ${items.size}")

            dialog.dismiss()
            updateProfile(updates)
        }

        dialog.show()

        // Focus on input field and show keyboard
        addInput.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(addInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun uploadProfileImage(uri: Uri) {
        if (isUploading) return
        isUploading = true

        // Show upload progress on avatar
        val avatarView = view?.findViewById<ImageView>(R.id.profileAvatar)
        val avatarContainer = avatarView?.parent as? FrameLayout
        val cameraOverlay = avatarContainer?.findViewById<View>(R.id.cameraOverlay)
        val cameraIcon = avatarContainer?.findViewById<ImageView>(R.id.cameraIcon)
        val editAvatarIcon = avatarContainer?.findViewById<ImageView>(R.id.editAvatarIcon)
        val uploadProgress = avatarContainer?.findViewById<ProgressBar>(R.id.uploadProgress)

        // Show progress and hide all other icons
        cameraOverlay?.visibility = View.VISIBLE
        cameraIcon?.visibility = View.GONE
        editAvatarIcon?.visibility = View.GONE
        uploadProgress?.visibility = View.VISIBLE

        showLoading(true)

        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/upload-profile-image"

                val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
                val fileBytes = inputStream?.readBytes()
                inputStream?.close()

                if (fileBytes == null) {
                    showLoading(false)
                    isUploading = false
                    uploadProgress?.visibility = View.GONE
                    // Show appropriate icon based on current state
                    val currentProfilePic = profileData?.optString("profilePicUrl", "")
                    if (currentProfilePic.isNullOrEmpty()) {
                        cameraIcon?.visibility = View.VISIBLE
                    } else {
                        cameraOverlay?.visibility = View.GONE
                        editAvatarIcon?.visibility = View.VISIBLE
                    }
                    Toast.makeText(requireContext(), "Failed to read image file", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "profile.jpg",
                        RequestBody.create("image/jpeg".toMediaTypeOrNull(), fileBytes))
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Handler(Looper.getMainLooper()).post {
                            showLoading(false)
                            isUploading = false
                            uploadProgress?.visibility = View.GONE
                            // Show appropriate icon based on current state
                            val currentProfilePic = profileData?.optString("profilePicUrl", "")
                            if (currentProfilePic.isNullOrEmpty()) {
                                cameraIcon?.visibility = View.VISIBLE
                            } else {
                                cameraOverlay?.visibility = View.GONE
                                editAvatarIcon?.visibility = View.VISIBLE
                            }
                            Toast.makeText(requireContext(), "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        Handler(Looper.getMainLooper()).post {
                            showLoading(false)
                            isUploading = false
                            uploadProgress?.visibility = View.GONE

                            try {
                                if (response.isSuccessful && body != null) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        val data = json.optJSONObject("data")
                                        val imageUrl = data?.optString("imageUrl")
                                        if (imageUrl != null) {
                                            val updates = JSONObject()
                                            updates.put("profilePicUrl", imageUrl)
                                            updateProfile(updates)

                                            // Immediately update the avatar display with edit icon
                                            try {
                                                com.bumptech.glide.Glide.with(this@AboutFragment)
                                                    .load(imageUrl)
                                                    .circleCrop()
                                                    .into(avatarView!!)
                                                cameraOverlay?.visibility = View.GONE
                                                cameraIcon?.visibility = View.GONE
                                                editAvatarIcon?.visibility = View.VISIBLE
                                            } catch (_: Exception) {}
                                        }
                                    } else {
                                        // Show appropriate icon based on current state
                                        val currentProfilePic = profileData?.optString("profilePicUrl", "")
                                        if (currentProfilePic.isNullOrEmpty()) {
                                            cameraIcon?.visibility = View.VISIBLE
                                        } else {
                                            cameraOverlay?.visibility = View.GONE
                                            editAvatarIcon?.visibility = View.VISIBLE
                                        }
                                        Toast.makeText(requireContext(), "Upload failed: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Show appropriate icon based on current state
                                    val currentProfilePic = profileData?.optString("profilePicUrl", "")
                                    if (currentProfilePic.isNullOrEmpty()) {
                                        cameraIcon?.visibility = View.VISIBLE
                                    } else {
                                        cameraOverlay?.visibility = View.GONE
                                        editAvatarIcon?.visibility = View.VISIBLE
                                    }
                                    Toast.makeText(requireContext(), "Upload failed", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Log.e("ABOUT_FRAGMENT", "Error parsing upload response", e)
                                // Show appropriate icon based on current state
                                val currentProfilePic = profileData?.optString("profilePicUrl", "")
                                if (currentProfilePic.isNullOrEmpty()) {
                                    cameraIcon?.visibility = View.VISIBLE
                                } else {
                                    cameraOverlay?.visibility = View.GONE
                                    editAvatarIcon?.visibility = View.VISIBLE
                                }
                                Toast.makeText(requireContext(), "Error processing upload", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } else {
                showLoading(false)
                isUploading = false
                uploadProgress?.visibility = View.GONE
                // Show appropriate icon based on current state
                val currentProfilePic = profileData?.optString("profilePicUrl", "")
                if (currentProfilePic.isNullOrEmpty()) {
                    cameraIcon?.visibility = View.VISIBLE
                } else {
                    cameraOverlay?.visibility = View.GONE
                    editAvatarIcon?.visibility = View.VISIBLE
                }
                Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateProfile(updates: JSONObject) {
        showLoading(true)

        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token ?: return@addOnCompleteListener
                val backendUrl = getBackendUrl()
                val url = "$backendUrl/api/profile"

                val requestBody = RequestBody.create("application/json".toMediaType(), updates.toString())
                val request = Request.Builder()
                    .url(url)
                    .put(requestBody)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Handler(Looper.getMainLooper()).post {
                            showLoading(false)
                            Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        Handler(Looper.getMainLooper()).post {
                            showLoading(false)

                            try {
                                if (response.isSuccessful && body != null) {
                                    val json = JSONObject(body)
                                    if (json.optBoolean("success")) {
                                        // Update local profile data
                                        val keys = updates.keys()
                                        while (keys.hasNext()) {
                                            val key = keys.next()
                                            profileData?.put(key, updates.get(key))
                                        }

                                        // Refresh UI
                                        view?.let { updateUI(it) }
                                        Toast.makeText(requireContext(), "Profile updated successfully", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(requireContext(), "Update failed: ${json.optString("message")}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Log.e("ABOUT_FRAGMENT", "Error parsing update response", e)
                                Toast.makeText(requireContext(), "Error processing update", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } else {
                showLoading(false)
                Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addEmptyTag(container: ViewGroup, message: String) {
        val tv = TextView(requireContext()).apply {
            text = "✏️ $message"
            setPadding(16, 12, 16, 12)
            setTextColor(android.graphics.Color.parseColor("#6B46C1"))
            textSize = 14f
            background = resources.getDrawable(R.drawable.rounded_background_light, null)
            isClickable = true
            isFocusable = true

            // Add some margin
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            layoutParams = params
        }
        container.addView(tv)
    }

    private fun getBackendUrl(): String {
        return "https://real-pakistan-backend.onrender.com"
    }
}
