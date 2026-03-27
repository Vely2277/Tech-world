package com.example.newconstructionappwithlocationtracking.auth

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.api.AuthService
import com.example.newconstructionappwithlocationtracking.api.Config
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ForgotPassword : Fragment() {
    private var navigationListener: ForgotPasswordNavigationListener? = null
    private lateinit var emailEditText: TextInputEditText
    private lateinit var resetPasswordButton: MaterialButton

    interface ForgotPasswordNavigationListener {
        fun onBackToLogin()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ForgotPasswordNavigationListener) {
            navigationListener = context
        }
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
        val view = inflater.inflate(R.layout.forgot_password, container, false)

        emailEditText = view.findViewById(R.id.emailEditText)
        resetPasswordButton = view.findViewById(R.id.resetPasswordButton)
        val backToLoginText = view.findViewById<TextView>(R.id.backToLoginText)

        resetPasswordButton.setOnClickListener {
            performPasswordReset()
        }

        backToLoginText.setOnClickListener {
            navigationListener?.onBackToLogin()
        }

        return view
    }

    private fun performPasswordReset() {
        val email = emailEditText.text.toString().trim()

        if (!validateInput(email)) {
            return
        }

        resetPasswordButton.isEnabled = false
        resetPasswordButton.text = "Sending reset link..."

        lifecycleScope.launch {
            try {
                val response = AuthService.forgotPassword(email)

                if (response.isSuccessful()) {
                    val jsonResponse = response.getJsonResponse()
                    if (jsonResponse?.optBoolean("success") == true) {
                        Toast.makeText(requireContext(),
                            "Password reset link has been sent to your email",
                            Toast.LENGTH_LONG).show()

                        // Navigate back to login
                        navigationListener?.onBackToLogin()
                    } else {
                        val message = jsonResponse?.optString("message") ?: "Failed to send reset link"
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val errorMessage = response.getMessage()
                    when (response.statusCode) {
                        404 -> Toast.makeText(requireContext(), "Email not found. Please check your email or sign up.", Toast.LENGTH_LONG).show()
                        500 -> Toast.makeText(requireContext(), "Server error. Please try again later.", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(requireContext(), "Failed to send reset email: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                resetPasswordButton.isEnabled = true
                resetPasswordButton.text = "Send Reset Link"
            }
        }
    }

    private fun validateInput(email: String): Boolean {
        if (email.isEmpty()) {
            emailEditText.error = "Email is required"
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email"
            return false
        }

        return true
    }

    companion object {
        fun newInstance(listener: ForgotPasswordNavigationListener): ForgotPassword {
            val fragment = ForgotPassword()
            fragment.navigationListener = listener
            return fragment
        }
    }
}
