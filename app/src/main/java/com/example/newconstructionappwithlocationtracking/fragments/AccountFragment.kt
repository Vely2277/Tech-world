package com.example.newconstructionappwithlocationtracking.fragments

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.ProfileActivity
import com.example.newconstructionappwithlocationtracking.auth.Auth
import com.example.newconstructionappwithlocationtracking.interfaces.NavigationHost
import com.example.newconstructionappwithlocationtracking.services.LocationTrackingService
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class AccountFragment : Fragment() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sellerModeText: TextView
    private lateinit var sellerModeSwitch: Switch

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_account, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferences = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        setupViews(view)
        view.findViewById<View>(R.id.accountContentContainer).visibility = View.GONE
        view.findViewById<View>(R.id.accountLoadingOverlay).visibility = View.VISIBLE
        fetchProfileData(view)

        // Check if we should show profile completion popup from new signup
        val showProfileCompletion = arguments?.getBoolean("show_profile_completion_popup", false) ?: false
        if (showProfileCompletion) {
            // Wait for UI to settle, then navigate to Edit Profile and show popup
            Handler(Looper.getMainLooper()).postDelayed({
                navigateToEditProfileWithPopup()
            }, 1500) // 1.5 second delay to let profile data load
        }
    }

    private fun setupViews(view: View) {
        // Initialize views
        sellerModeText = view.findViewById(R.id.sellerModeText)
        sellerModeSwitch = view.findViewById(R.id.sellerModeSwitch)

        // Bell icon - navigate to notifications
        val bellIcon = view.findViewById<ImageView>(R.id.bellIcon)
        bellIcon?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, NotificationFragment())
                .addToBackStack(null)
                .commit()
        }

        // Seller mode toggle
        sellerModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sellerModeText.text = "Seller mode"
                // Save seller mode preference
                sharedPreferences.edit().putBoolean("is_seller_mode", true).apply()
            } else {
                sellerModeText.text = "Buyer mode"
                // Save buyer mode preference
                sharedPreferences.edit().putBoolean("is_seller_mode", false).apply()
            }
        }

        // Earnings item - navigate to home page
        val earningsItem = view.findViewById<LinearLayout>(R.id.earningsItem)
        earningsItem?.setOnClickListener {
            navigateToHomePage()
        }

        // View Order item - navigate to orders page
        val viewOrderItem = view.findViewById<LinearLayout>(R.id.viewOrderItem)
        viewOrderItem?.setOnClickListener {
            navigateToOrdersPage()
        }

        // Edit Profile item - navigate to ProfileActivity
        val editProfileItem = view.findViewById<LinearLayout>(R.id.editProfileItem)
        editProfileItem?.setOnClickListener {
            val intent = Intent(requireContext(), ProfileActivity::class.java)
            startActivity(intent)
        }

        // Support item - show popup
        val supportItem = view.findViewById<LinearLayout>(R.id.supportItem)
        supportItem?.setOnClickListener {
            showSupportPopup()
        }

        // Logout item - perform logout
        val logoutItem = view.findViewById<LinearLayout>(R.id.logoutItem)
        logoutItem?.setOnClickListener {
            android.util.Log.d("AccountFragment", "Logout clicked!")
            showLogoutConfirmationDialog()
        }
    }

    private fun fetchProfileData(view: View) {
        val loadingOverlay = view.findViewById<View>(R.id.accountLoadingOverlay)
        val contentContainer = view.findViewById<View>(R.id.accountContentContainer)
        val profileImage = view.findViewById<ImageView>(R.id.profileImage)
        val userNameText = view.findViewById<TextView>(R.id.userNameText)
        val balanceText = view.findViewById<TextView>(R.id.balanceText)
        val earningsText = view.findViewById<TextView>(R.id.earningsText)
        loadingOverlay.visibility = View.VISIBLE
        contentContainer.visibility = View.GONE
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            loadingOverlay.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
            return
        }
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                val backendUrl = "https://real-pakistan-backend.onrender.com/api/profile"
                val request = Request.Builder()
                    .url(backendUrl)
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()
                OkHttpClient().newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activity?.runOnUiThread {
                            loadingOverlay.visibility = View.GONE
                            contentContainer.visibility = View.VISIBLE
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        activity?.runOnUiThread {
                            loadingOverlay.visibility = View.GONE
                            contentContainer.visibility = View.VISIBLE
                        }
                        if (!response.isSuccessful || body == null) return
                        val json = JSONObject(body)
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            val firstName = data.optString("firstName", "User")
                            val earnings = data.optInt("earnings", 0)
                            val balance = "Balance: $$earnings"
                            val profilePicUrl = data.optString("profilePicUrl", "")
                            activity?.runOnUiThread {
                                userNameText.text = firstName
                                balanceText.text = balance
                                earningsText.text = "$earnings"
                                if (profilePicUrl.isNotEmpty()) {
                                    Glide.with(requireContext())
                                        .load(profilePicUrl)
                                        .placeholder(R.drawable.ic_profile_placeholder)
                                        .error(R.drawable.ic_profile_placeholder)
                                        .into(profileImage)
                                } else {
                                    // Set default avatar placeholder when no profile picture
                                    profileImage.setImageResource(R.drawable.ic_profile_placeholder)
                                }
                            }
                        }
                    }
                })
            } else {
                loadingOverlay.visibility = View.GONE
                contentContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun loadUserData(view: View) {
        // Load user name from SharedPreferences
        val firstName = sharedPreferences.getString("first_name", "User") ?: "User"
        val userNameText = view.findViewById<TextView>(R.id.userNameText)
        userNameText?.text = firstName

        // Load seller mode preference (default to true)
        val isSellerMode = sharedPreferences.getBoolean("is_seller_mode", true)
        sellerModeSwitch.isChecked = isSellerMode
        sellerModeText.text = if (isSellerMode) "Seller mode" else "Buyer mode"
    }

    private fun navigateToHomePage() {
        // Use NavigationHost to properly update bottom navigation
        (activity as? NavigationHost)?.navigateToTab(R.id.nav_home)
    }

    private fun navigateToOrdersPage() {
        // Use NavigationHost to properly update bottom navigation
        (activity as? NavigationHost)?.navigateToTab(R.id.nav_orders)
    }

    private fun showSupportPopup() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Support")
        builder.setMessage("For support, please email us at test@gmail.com")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun showLogoutConfirmationDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Logout")
        builder.setMessage("Are you sure you want to logout?")

        builder.setPositiveButton("Yes") { dialog, _ ->
            dialog.dismiss()
            performLogout()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun performLogout() {
        android.util.Log.d("AccountFragment", "performLogout() called")

        // Show loading dialog
        val loadingDialog = createLoadingDialog()
        loadingDialog.show()

        try {
            // IMPORTANT: Do NOT stop location tracking service
            // Tracking must continue running in the background even after UI logout
            android.util.Log.d("AccountFragment", "⚠️ Keeping location tracking service alive (by design)")

            // IMPORTANT: Do NOT call FirebaseAuth.signOut()
            // Firebase must stay authenticated so the tracking service can still upload data
            android.util.Log.d("AccountFragment", "⚠️ Keeping Firebase signed in (for background tracking)")

            // Clear UI user data from SharedPreferences ONLY
            // This makes the app UI appear "logged out" while background services continue
            android.util.Log.d("AccountFragment", "Clearing UI SharedPreferences...")
            try {
                sharedPreferences.edit()
                    .putBoolean("is_logged_in", false)
                    .remove("first_name")
                    .remove("last_name")
                    .remove("description")
                    .remove("profile_image_url")
                    .remove("is_seller_mode")
                    .remove("login_timestamp")
                    .apply()
                android.util.Log.d("AccountFragment", "UI SharedPreferences cleared (email kept for re-login check)")
            } catch (e: Exception) {
                android.util.Log.e("AccountFragment", "Error clearing SharedPreferences: ${e.message}")
            }

            // Small delay to show the loading effect
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    loadingDialog.dismiss()

                    // Show success message
                    Toast.makeText(requireContext(), "Logged out successfully", Toast.LENGTH_SHORT).show()

                    // Navigate to auth screen
                    android.util.Log.d("AccountFragment", "Navigating to Auth activity...")
                    val intent = Intent(requireContext(), Auth::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                    android.util.Log.d("AccountFragment", "Navigation completed")
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    android.util.Log.e("AccountFragment", "Error during navigation: ${e.message}", e)
                    Toast.makeText(requireContext(), "Error during logout: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 1500) // 1.5 second delay to show loading effect

        } catch (e: Exception) {
            loadingDialog.dismiss()
            android.util.Log.e("AccountFragment", "Error during logout: ${e.message}", e)
            Toast.makeText(requireContext(), "Error during logout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createLoadingDialog(): AlertDialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_loading, null)

        val messageText = view.findViewById<TextView>(R.id.loadingMessage)
        messageText?.text = "Logging out..."

        builder.setView(view)
        builder.setCancelable(false)

        return builder.create()
    }

    private fun navigateToEditProfileWithPopup() {
        // First navigate to ProfileActivity (Edit Profile)
        val intent = Intent(requireContext(), ProfileActivity::class.java)
        intent.putExtra("show_profile_completion_popup", true)
        startActivity(intent)
    }

    private fun showProfileCompletionDialog() {
        // Create custom dialog
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_profile_completion)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Get views from dialog
        val completeNowButton = dialog.findViewById<Button>(R.id.completeNowButton)
        val skipForNowButton = dialog.findViewById<TextView>(R.id.skipForNowButton)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.profileProgressBar)
        val progressPercentage = dialog.findViewById<TextView>(R.id.progressPercentage)

        // Set initial progress (25% for basic signup completion)
        progressBar?.progress = 25
        progressPercentage?.text = "25%"

        // Show dialog
        dialog.show()

        // Handle Complete Now button
        completeNowButton?.setOnClickListener {
            dialog.dismiss()

            // Navigate to ProfileActivity to complete profile
            val intent = Intent(requireContext(), ProfileActivity::class.java)
            intent.putExtra("navigate_to_about", true)
            startActivity(intent)
        }

        // Handle Skip button
        skipForNowButton?.setOnClickListener {
            dialog.dismiss()

            // Save that user chose to skip (don't show again for a while)
            sharedPreferences.edit()
                .putLong("profile_completion_skip_timestamp", System.currentTimeMillis())
                .apply()

            Toast.makeText(requireContext(), "You can complete your profile anytime from Edit Profile", Toast.LENGTH_LONG).show()
        }
    }
}