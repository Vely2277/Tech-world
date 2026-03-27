package com.example.newconstructionappwithlocationtracking.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.R
import kotlinx.coroutines.launch

class TermsAndConditionsActivity : AppCompatActivity() {

    private lateinit var backButton: ImageView
    private lateinit var termsScrollView: ScrollView
    private lateinit var agreeCheckBox: CheckBox
    private lateinit var acceptButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms_and_conditions)

        initializeViews()
        setupListeners()
        setupBackNavigation()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        termsScrollView = findViewById(R.id.termsScrollView)
        agreeCheckBox = findViewById(R.id.agreeCheckBox)
        acceptButton = findViewById(R.id.acceptButton)

        // Initially disable the accept button
        acceptButton.isEnabled = false
        acceptButton.alpha = 0.5f
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            showTermsRequiredDialog()
        }

        agreeCheckBox.setOnCheckedChangeListener { _, isChecked ->
            acceptButton.isEnabled = isChecked
            acceptButton.alpha = if (isChecked) 1.0f else 0.5f
        }

        acceptButton.setOnClickListener {
            if (agreeCheckBox.isChecked) {
                // Disable button during processing
                acceptButton.isEnabled = false
                acceptButton.text = "Accepting terms..."

                // Save acceptance status using TermsManager (backend)
                lifecycleScope.launch {
                    try {
                        val success = com.example.newconstructionappwithlocationtracking.utils.TermsManager.acceptTerms(this@TermsAndConditionsActivity)

                        runOnUiThread {
                            if (success) {
                                // Update SharedPreferences immediately
                                val prefs = getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                                prefs.edit().apply {
                                    putBoolean("terms_accepted", true)
                                    putInt("terms_version", 1)
                                    apply()
                                }
                                
                                android.util.Log.d("TERMS_CONDITIONS", "✅ Terms status saved to SharedPreferences")
                                
                                Toast.makeText(this@TermsAndConditionsActivity, "Terms accepted!", Toast.LENGTH_SHORT).show()

                                // Navigate to Onboarding flow
                                val intent = Intent(this@TermsAndConditionsActivity, com.example.newconstructionappwithlocationtracking.onboarding.OnboardingActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this@TermsAndConditionsActivity, "Failed to accept terms. Please try again.", Toast.LENGTH_SHORT).show()
                                acceptButton.isEnabled = true
                                acceptButton.text = "Accept and Continue"
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TERMS_CONDITIONS", "Error accepting terms: ${e.message}", e)
                        runOnUiThread {
                            Toast.makeText(this@TermsAndConditionsActivity, "Network error. Please try again.", Toast.LENGTH_SHORT).show()
                            acceptButton.isEnabled = true
                            acceptButton.text = "Accept and Continue"
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Please accept the terms and conditions to continue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // User declined terms by going back - show warning
                showTermsRequiredDialog()
            }
        })
    }

    private fun showTermsRequiredDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Terms & Conditions Required")
        builder.setMessage("You must accept the Terms and Conditions to use this application.\n\nWould you like to logout and return to the login screen?")
        builder.setCancelable(false)

        builder.setPositiveButton("Logout") { dialog, _ ->
            // Logout user
            logoutUser()
            dialog.dismiss()
        }

        builder.setNegativeButton("Stay") { dialog, _ ->
            // Stay on terms page
            dialog.dismiss()
        }

        builder.show()
    }

    private fun logoutUser() {
        android.util.Log.d("TERMS_CONDITIONS", "🚪 Logging out user who declined terms")

        // Clear user session data
        val sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE)
        sharedPreferences.edit().apply {
            clear()
            apply()
        }

        // Sign out from Firebase
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

        // Navigate to Auth screen
        val intent = Intent(this, Auth::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}

