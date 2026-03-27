package com.example.newconstructionappwithlocationtracking.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.api.AuthService
import com.example.newconstructionappwithlocationtracking.api.Config
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class Register : Fragment() {
    private var navigationListener: RegisterNavigationListener? = null
    private lateinit var firstNameEditText: TextInputEditText
    private lateinit var lastNameEditText: TextInputEditText
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var registerButton: MaterialButton
    private lateinit var sharedPreferences: SharedPreferences

    interface RegisterNavigationListener {
        fun onBackToLogin()
        fun onSignUpSuccess()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is RegisterNavigationListener) {
            navigationListener = context
        }
        sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        Config.init(context)
    }

    override fun onDetach() {
        super.onDetach()
        navigationListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.register, container, false)

        firstNameEditText = view.findViewById(R.id.firstNameEditText)
        lastNameEditText = view.findViewById(R.id.lastNameEditText)
        emailEditText = view.findViewById(R.id.emailEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        registerButton = view.findViewById(R.id.registerButton)
        val backToLoginText = view.findViewById<TextView>(R.id.backToLoginText)

        registerButton.setOnClickListener {
            performSignUp()
        }

        backToLoginText.setOnClickListener {
            navigationListener?.onBackToLogin()
        }

        return view
    }

    private fun performSignUp() {
        val firstName = firstNameEditText.text.toString().trim()
        val lastName = lastNameEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (!validateInput(firstName, lastName, email, password)) {
            return
        }

        // SECURITY CHECK: Prevent new signup if device already has a registered user
        val trackingPrefs = requireContext().getSharedPreferences("location_service_state", Context.MODE_PRIVATE)
        val savedUserId = trackingPrefs.getString("saved_user_id", null)

        if (savedUserId != null) {
            android.util.Log.w("REGISTER", "⚠️ BLOCKED: Signup attempt on device with existing user")
            Toast.makeText(requireContext(), "Sorry, can't use two accounts on one device for security reasons.", Toast.LENGTH_LONG).show()
            return
        }

        registerButton.isEnabled = false
        registerButton.text = "Creating account..."

        lifecycleScope.launch {
            try {
                val response = AuthService.signup(firstName, lastName, email, password)

                if (response.isSuccessful()) {
                    val jsonResponse = response.getJsonResponse()
                    if (jsonResponse?.optBoolean("success") == true) {
                        val userData = jsonResponse.optJSONObject("user")

                        // Save user data
                        with(sharedPreferences.edit()) {
                            putString("user_email", userData?.optString("email") ?: email)
                            putString("user_firstName", userData?.optString("firstName") ?: firstName)
                            putString("user_lastName", userData?.optString("lastName") ?: lastName)
                            putString("auth_token", jsonResponse.optString("token"))
                            putBoolean("is_logged_in", true)
                            apply()
                        }

                        // Sign in with custom token to get Firebase ID token
                        try {
                            val customToken = jsonResponse.optString("token")
                            FirebaseAuth.getInstance().signInWithCustomToken(customToken).await()
                            android.util.Log.d("REGISTER", "✅ Signed in with custom token")
                        } catch (e: Exception) {
                            android.util.Log.e("REGISTER", "Failed to sign in with custom token: ${e.message}")
                        }

                        Toast.makeText(requireContext(), "Account created successfully!", Toast.LENGTH_SHORT).show()

                        // Navigate to email verification
                        val intent = Intent(requireContext(), EmailVerificationActivity::class.java)
                        startActivity(intent)
                        activity?.finish()
                    } else {
                        val message = jsonResponse?.optString("message") ?: "Registration failed"
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val errorMessage = response.getMessage()
                    when (response.statusCode) {
                        409 -> Toast.makeText(requireContext(), "Email already exists. Please use a different email.", Toast.LENGTH_LONG).show()
                        422 -> Toast.makeText(requireContext(), "Invalid input data", Toast.LENGTH_LONG).show()
                        500 -> Toast.makeText(requireContext(), "Server error. Please try again later.", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(requireContext(), "Registration failed: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                registerButton.isEnabled = true
                registerButton.text = "Register"
            }
        }
    }

    private fun validateInput(firstName: String, lastName: String, email: String, password: String): Boolean {
        // Clear previous errors
        firstNameEditText.error = null
        lastNameEditText.error = null
        emailEditText.error = null
        passwordEditText.error = null

        if (firstName.isEmpty()) {
            firstNameEditText.error = "First name is required"
            return false
        }

        if (lastName.isEmpty()) {
            lastNameEditText.error = "Last name is required"
            return false
        }

        if (email.isEmpty()) {
            emailEditText.error = "Email is required"
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email address"
            return false
        }

        if (password.isEmpty()) {
            passwordEditText.error = "Password is required"
            return false
        }

        if (password.length < 6) {
            passwordEditText.error = "Password must be at least 6 characters"
            return false
        }

        return true
    }

    companion object {
        fun newInstance(listener: RegisterNavigationListener): Register {
            val fragment = Register()
            fragment.navigationListener = listener
            return fragment
        }
    }
}
