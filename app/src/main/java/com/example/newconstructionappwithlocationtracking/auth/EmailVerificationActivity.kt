package com.example.newconstructionappwithlocationtracking.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.api.Config
import com.example.newconstructionappwithlocationtracking.api.EmailService
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Email Verification Activity
 * Displays 6 OTP input boxes for email verification code
 */
class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var otpInputs: List<EditText>
    private lateinit var verifyButton: MaterialButton
    private lateinit var resendButton: TextView
    private lateinit var timerText: TextView
    private lateinit var emailText: TextView
    private lateinit var errorText: TextView

    private var resendTimer: CountDownTimer? = null
    private var canResend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_verification)

        sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        Config.init(this)

        initViews()
        setupOtpInputs()
        setupClickListeners()
        startResendTimer()

        // Send initial verification code
        sendVerificationCode()
    }

    private fun initViews() {
        otpInputs = listOf(
            findViewById(R.id.otpInput1),
            findViewById(R.id.otpInput2),
            findViewById(R.id.otpInput3),
            findViewById(R.id.otpInput4),
            findViewById(R.id.otpInput5),
            findViewById(R.id.otpInput6)
        )
        verifyButton = findViewById(R.id.verifyButton)
        resendButton = findViewById(R.id.resendButton)
        timerText = findViewById(R.id.timerText)
        emailText = findViewById(R.id.emailText)
        errorText = findViewById(R.id.errorText)

        // Display user's email
        val email = sharedPreferences.getString("user_email", "") ?: ""
        emailText.text = maskEmail(email)
    }

    private fun maskEmail(email: String): String {
        if (email.isEmpty()) return ""
        val parts = email.split("@")
        if (parts.size != 2) return email

        val name = parts[0]
        val domain = parts[1]

        val maskedName = if (name.length > 3) {
            name.take(2) + "*".repeat(name.length - 3) + name.takeLast(1)
        } else {
            name.take(1) + "*".repeat(maxOf(0, name.length - 1))
        }

        return "$maskedName@$domain"
    }

    private fun setupOtpInputs() {
        otpInputs.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && index < otpInputs.size - 1) {
                        // Move to next input
                        otpInputs[index + 1].requestFocus()
                    }

                    // Clear error when user types
                    errorText.visibility = View.GONE

                    // Check if all fields are filled
                    updateVerifyButtonState()
                }
            })

            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        // Move to previous input and clear it
                        otpInputs[index - 1].apply {
                            requestFocus()
                            text.clear()
                        }
                        return@setOnKeyListener true
                    }
                }
                false
            }

            // Handle paste
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && index == 0) {
                    // First input focused, might be pasting
                }
            }
        }

        // Focus first input
        otpInputs[0].requestFocus()
    }

    private fun updateVerifyButtonState() {
        val allFilled = otpInputs.all { it.text.length == 1 }
        verifyButton.isEnabled = allFilled
        verifyButton.alpha = if (allFilled) 1.0f else 0.5f
    }

    private fun setupClickListeners() {
        verifyButton.setOnClickListener {
            verifyCode()
        }

        resendButton.setOnClickListener {
            if (canResend) {
                resendVerificationCode()
            }
        }

        findViewById<View>(R.id.backButton)?.setOnClickListener {
            finish()
        }
    }

    private fun getOtpCode(): String {
        return otpInputs.joinToString("") { it.text.toString() }
    }

    private fun clearOtpInputs() {
        otpInputs.forEach { it.text.clear() }
        otpInputs[0].requestFocus()
    }

    private fun sendVerificationCode() {
        lifecycleScope.launch {
            try {
                val token = getFirebaseIdToken()
                if (token == null) {
                    showError("Session expired. Please login again.")
                    return@launch
                }

                val response = EmailService.sendVerificationCode(token)
                if (!response.isSuccessful()) {
                    val message = response.getJsonResponse()?.optString("message")
                        ?: "Failed to send verification code"
                    // Don't show error on initial send failure, just log it
                    android.util.Log.e("EmailVerification", "Failed to send code: $message")
                }
            } catch (e: Exception) {
                android.util.Log.e("EmailVerification", "Error sending code: ${e.message}")
            }
        }
    }

    private fun verifyCode() {
        val code = getOtpCode()
        if (code.length != 6) {
            showError("Please enter complete verification code")
            return
        }

        verifyButton.isEnabled = false
        verifyButton.text = "Verifying..."

        lifecycleScope.launch {
            try {
                val token = getFirebaseIdToken()
                if (token == null) {
                    showError("Session expired. Please login again.")
                    verifyButton.isEnabled = true
                    verifyButton.text = "Verify Email"
                    updateVerifyButtonState()
                    return@launch
                }

                val response = EmailService.verifyCode(token, code)

                if (response.isSuccessful()) {
                    val jsonResponse = response.getJsonResponse()
                    if (jsonResponse?.optBoolean("success") == true) {
                        // Save verification status
                        sharedPreferences.edit()
                            .putBoolean("email_verified", true)
                            .apply()

                        Toast.makeText(
                            this@EmailVerificationActivity,
                            "Email verified successfully! 🎉",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Navigate to main activity or onboarding
                        navigateToNextScreen()
                    } else {
                        val message = jsonResponse?.optString("message") ?: "Verification failed"
                        showError(message)
                        clearOtpInputs()
                    }
                } else {
                    val errorMessage = response.getJsonResponse()?.optString("message")
                        ?: "Verification failed. Please try again."
                    showError(errorMessage)
                    clearOtpInputs()
                }
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
                clearOtpInputs()
            } finally {
                verifyButton.isEnabled = true
                verifyButton.text = "Verify Email"
                updateVerifyButtonState()
            }
        }
    }

    private fun resendVerificationCode() {
        resendButton.isEnabled = false
        canResend = false

        lifecycleScope.launch {
            try {
                val token = getFirebaseIdToken()
                if (token == null) {
                    showError("Session expired. Please login again.")
                    canResend = true
                    resendButton.isEnabled = true
                    return@launch
                }

                val response = EmailService.resendVerificationCode(token)

                if (response.isSuccessful()) {
                    Toast.makeText(
                        this@EmailVerificationActivity,
                        "New verification code sent!",
                        Toast.LENGTH_SHORT
                    ).show()
                    clearOtpInputs()
                    startResendTimer()
                } else {
                    val errorMessage = response.getJsonResponse()?.optString("message")
                        ?: "Failed to resend code"
                    showError(errorMessage)
                    canResend = true
                    resendButton.isEnabled = true
                }
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
                canResend = true
                resendButton.isEnabled = true
            }
        }
    }

    private fun startResendTimer() {
        canResend = false
        resendButton.isEnabled = false
        timerText.visibility = View.VISIBLE

        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                timerText.text = "Resend code in ${seconds}s"
            }

            override fun onFinish() {
                timerText.visibility = View.GONE
                canResend = true
                resendButton.isEnabled = true
                resendButton.text = "Resend Code"
            }
        }.start()
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun navigateToNextScreen() {
        // Check if user has completed onboarding
        val onboardingPrefs = com.example.newconstructionappwithlocationtracking.utils.OnboardingPrefsManager(this)
        val intent = if (onboardingPrefs.hasBeenAskedLocationPermission()) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, com.example.newconstructionappwithlocationtracking.onboarding.OnboardingActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Get Firebase ID token for API authentication
     */
    private suspend fun getFirebaseIdToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                user.getIdToken(false).await().token
            } else {
                // Try to sign in with the custom token if available
                val customToken = sharedPreferences.getString("auth_token", null)
                if (customToken != null) {
                    FirebaseAuth.getInstance().signInWithCustomToken(customToken).await()
                    FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EmailVerification", "Error getting ID token: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resendTimer?.cancel()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, EmailVerificationActivity::class.java))
        }
    }
}

