package com.example.newconstructionappwithlocationtracking.activities

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WithdrawalActivity : AppCompatActivity() {
    private lateinit var backButton: ImageView
    private lateinit var availableBalanceDisplay: TextView
    private lateinit var accountHolderNameInput: TextInputEditText
    private lateinit var bankNameInput: TextInputEditText
    private lateinit var accountNumberInput: TextInputEditText
    private lateinit var ibanInput: TextInputEditText
    private lateinit var bankBranchInput: TextInputEditText
    private lateinit var cityInput: TextInputEditText
    private lateinit var phoneNumberInput: TextInputEditText
    private lateinit var notesInput: TextInputEditText
    private lateinit var submitWithdrawalButton: MaterialButton

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "WithdrawalActivity"
    private var availableBalance = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdrawal)

        initializeViews()
        setupListeners()
        setupKeyboardHiding()

        // Get available balance from intent
        availableBalance = intent.getIntExtra("availableBalance", 0)
        availableBalanceDisplay.text = "$$availableBalance"
    }

    /**
     * Hide keyboard when tapping outside of input fields
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val view = currentFocus
            if (view is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                view.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    // Hide keyboard first, then clear focus
                    hideKeyboard(view)
                    view.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideKeyboard(view: android.view.View? = null) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val targetView = view ?: currentFocus ?: window.decorView
        imm.hideSoftInputFromWindow(targetView.windowToken, 0)
    }

    private fun setupKeyboardHiding() {
        // Set the root view to be focusable so it can receive focus when EditText loses it
        val rootView = findViewById<android.view.View>(android.R.id.content)
        rootView.isFocusable = true
        rootView.isFocusableInTouchMode = true
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        availableBalanceDisplay = findViewById(R.id.availableBalanceDisplay)
        accountHolderNameInput = findViewById(R.id.accountHolderNameInput)
        bankNameInput = findViewById(R.id.bankNameInput)
        accountNumberInput = findViewById(R.id.accountNumberInput)
        ibanInput = findViewById(R.id.ibanInput)
        bankBranchInput = findViewById(R.id.bankBranchInput)
        cityInput = findViewById(R.id.cityInput)
        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        notesInput = findViewById(R.id.notesInput)
        submitWithdrawalButton = findViewById(R.id.submitWithdrawalButton)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }

        submitWithdrawalButton.setOnClickListener {
            submitWithdrawalRequest()
        }
    }

    private fun submitWithdrawalRequest() {
        if (!validateInputs()) {
            return
        }

        // Disable button to prevent multiple submissions
        submitWithdrawalButton.isEnabled = false
        submitWithdrawalButton.text = "Submitting..."

        lifecycleScope.launch {
            try {
                val token = getFirebaseToken()
                if (token != null) {
                    val success = submitWithdrawal(token)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            showSuccessDialog()
                        } else {
                            Toast.makeText(this@WithdrawalActivity,
                                "Failed to submit withdrawal request. Please try again.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WithdrawalActivity,
                            "Authentication error. Please try again.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting withdrawal", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WithdrawalActivity,
                        "An error occurred. Please try again.",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    submitWithdrawalButton.isEnabled = true
                    submitWithdrawalButton.text = "Submit Withdrawal Request"
                }
            }
        }
    }

    private fun validateInputs(): Boolean {
        val accountHolderName = accountHolderNameInput.text?.toString()?.trim()
        val bankName = bankNameInput.text?.toString()?.trim()
        val accountNumber = accountNumberInput.text?.toString()?.trim()
        val bankBranch = bankBranchInput.text?.toString()?.trim()
        val city = cityInput.text?.toString()?.trim()
        val phoneNumber = phoneNumberInput.text?.toString()?.trim()

        if (accountHolderName.isNullOrBlank()) {
            accountHolderNameInput.error = "Account holder name is required"
            accountHolderNameInput.requestFocus()
            return false
        }

        if (bankName.isNullOrBlank()) {
            bankNameInput.error = "Bank name is required"
            bankNameInput.requestFocus()
            return false
        }

        if (accountNumber.isNullOrBlank()) {
            accountNumberInput.error = "Account number is required"
            accountNumberInput.requestFocus()
            return false
        }

        if (bankBranch.isNullOrBlank()) {
            bankBranchInput.error = "Bank branch is required"
            bankBranchInput.requestFocus()
            return false
        }

        if (city.isNullOrBlank()) {
            cityInput.error = "City is required"
            cityInput.requestFocus()
            return false
        }

        if (phoneNumber.isNullOrBlank()) {
            phoneNumberInput.error = "Phone number is required"
            phoneNumberInput.requestFocus()
            return false
        }

        if (availableBalance <= 0) {
            Toast.makeText(this, "No balance available for withdrawal", Toast.LENGTH_LONG).show()
            return false
        }

        return true
    }

    private suspend fun getFirebaseToken(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    val task = user.getIdToken(false)
                    var token: String? = null

                    val startTime = System.currentTimeMillis()
                    while (!task.isComplete && (System.currentTimeMillis() - startTime) < 5000) {
                        Thread.sleep(50)
                    }

                    if (task.isSuccessful) {
                        token = task.result?.token
                        Log.d(TAG, "Token obtained successfully")
                    } else {
                        Log.w(TAG, "Token failed: ${task.exception?.message}")
                    }
                    token
                } else {
                    Log.d(TAG, "No Firebase user")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token error: ${e.message}")
                null
            }
        }
    }

    private suspend fun submitWithdrawal(token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val backend = getBackendUrlFromEnv().ifBlank { "https://real-pakistan-backend.onrender.com" }
                val apiUrl = "$backend/api/withdrawals"

                Log.d(TAG, "Making withdrawal API call to: $apiUrl")
                Log.d(TAG, "Available balance to withdraw: $availableBalance")

                // Create withdrawal request JSON
                val withdrawalData = JSONObject().apply {
                    put("amount", availableBalance)
                    put("accountHolderName", accountHolderNameInput.text?.toString()?.trim())
                    put("bankName", bankNameInput.text?.toString()?.trim())
                    put("accountNumber", accountNumberInput.text?.toString()?.trim())
                    put("iban", ibanInput.text?.toString()?.trim())
                    put("bankBranch", bankBranchInput.text?.toString()?.trim())
                    put("city", cityInput.text?.toString()?.trim())
                    put("phoneNumber", phoneNumberInput.text?.toString()?.trim())
                    put("notes", notesInput.text?.toString()?.trim())
                }

                Log.d(TAG, "Withdrawal request data: $withdrawalData")

                val requestBody = withdrawalData.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                response.use {
                    val body = it.body?.string()
                    Log.d(TAG, "Withdrawal API response code: ${it.code}")
                    Log.d(TAG, "Withdrawal API response body: $body")

                    if (it.isSuccessful) {
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success")) {
                                Log.d(TAG, "Withdrawal request submitted successfully")
                                return@withContext true
                            } else {
                                Log.w(TAG, "Withdrawal request failed: ${json.optString("message")}")
                            }
                        }
                    } else {
                        Log.w(TAG, "Withdrawal API call failed with code: ${it.code}, body: $body")
                    }
                    false
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Withdrawal API timeout - server may be starting up", e)
                false
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Withdrawal API network error - no internet connection", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Withdrawal API call exception", e)
                false
            }
        }
    }

    private fun getBackendUrlFromEnv(): String {
        try {
            val assetManager = assets
            assetManager.open(".env").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("BACKEND_URL=")) {
                        return line.substringAfter("=").trim()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, ".env not found in assets: ${e.message}")
        }
        return ""
    }

    private fun showSuccessDialog() {
        // Create custom dialog
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_withdrawal_success)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Get views from dialog
        val successIcon = dialog.findViewById<ImageView>(R.id.successIcon)
        val doneButton = dialog.findViewById<Button>(R.id.doneButton)

        // Show dialog first
        dialog.show()

        // Start icon animation
        val animation = AnimationUtils.loadAnimation(this, R.anim.success_icon_animation)
        successIcon.startAnimation(animation)

        // Handle done button click
        doneButton.setOnClickListener {
            dialog.dismiss()

            // Set result to indicate successful withdrawal
            val resultIntent = Intent()
            resultIntent.putExtra("withdrawal_successful", true)
            resultIntent.putExtra("withdrawal_amount", availableBalance)
            setResult(RESULT_OK, resultIntent)

            // Finish activity
            finish()
        }
    }
}
