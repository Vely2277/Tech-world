package com.example.newconstructionappwithlocationtracking.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.api.ApiResponse
import com.example.newconstructionappwithlocationtracking.api.AuthService
import com.example.newconstructionappwithlocationtracking.api.Config
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Auth : AppCompatActivity() {

    private lateinit var loginForm: CardView
    private lateinit var signupForm: CardView
    private lateinit var forgotPasswordForm: CardView
    private lateinit var loginEmail: TextInputEditText
    private lateinit var loginPassword: TextInputEditText
    private lateinit var loginSubmitButton: MaterialButton
    private lateinit var forgotPassword: TextView
    private lateinit var signUpLink: TextView

    // Signup form fields
    private lateinit var signupFirstName: TextInputEditText
    private lateinit var signupLastName: TextInputEditText
    private lateinit var signupEmail: TextInputEditText
    private lateinit var signupPassword: TextInputEditText
    private lateinit var signupSubmitButton: MaterialButton
    private lateinit var backToLoginFromSignup: TextView

    // Forgot password form fields
    private lateinit var forgotPasswordEmail: TextInputEditText
    private lateinit var forgotPasswordSubmitButton: MaterialButton
    private lateinit var backToLoginFromForgot: TextView

    // Permission manager for checking location permissions
    private lateinit var permissionManager: LocationPermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.auth)

        // Initialize Config with context
        Config.init(this)

        // Initialize permission manager
        permissionManager = LocationPermissionManager(this)

        initializeViews()
        setupClickListeners()
    }

    private fun initializeViews() {
        // Forms
        loginForm = findViewById(R.id.loginForm)
        signupForm = findViewById(R.id.signupForm)
        forgotPasswordForm = findViewById(R.id.forgotPasswordForm)

        // Login form fields
        loginEmail = findViewById(R.id.loginEmail)
        loginPassword = findViewById(R.id.loginPassword)
        loginSubmitButton = findViewById(R.id.loginSubmitButton)
        forgotPassword = findViewById(R.id.forgotPassword)
        signUpLink = findViewById(R.id.signUpLink)

        // Signup form fields
        signupFirstName = findViewById(R.id.signupFirstName)
        signupLastName = findViewById(R.id.signupLastName)
        signupEmail = findViewById(R.id.signupEmail)
        signupPassword = findViewById(R.id.signupPassword)
        signupSubmitButton = findViewById(R.id.signupSubmitButton)
        backToLoginFromSignup = findViewById(R.id.backToLoginFromSignup)

        // Forgot password form fields
        forgotPasswordEmail = findViewById(R.id.forgotPasswordEmail)
        forgotPasswordSubmitButton = findViewById(R.id.forgotPasswordSubmitButton)
        backToLoginFromForgot = findViewById(R.id.backToLoginFromForgot)
    }

    private fun setupClickListeners() {
        loginSubmitButton.setOnClickListener { handleLogin() }
        forgotPassword.setOnClickListener { showForgotPasswordForm() }
        signUpLink.setOnClickListener { showSignupForm() }
        signupSubmitButton.setOnClickListener { handleSignup() }
        forgotPasswordSubmitButton.setOnClickListener { handleForgotPassword() }
        backToLoginFromForgot.setOnClickListener { showLoginForm() }
        // Wire up the signup "Back to Login" button
        backToLoginFromSignup.setOnClickListener { showLoginForm() }
    }

    private fun showSignupForm() {
        loginForm.visibility = View.GONE
        signupForm.visibility = View.VISIBLE
        forgotPasswordForm.visibility = View.GONE
    }

    private fun showForgotPasswordForm() {
        loginForm.visibility = View.GONE
        signupForm.visibility = View.GONE
        forgotPasswordForm.visibility = View.VISIBLE
    }

    private fun showLoginForm() {
        signupForm.visibility = View.GONE
        forgotPasswordForm.visibility = View.GONE
        loginForm.visibility = View.VISIBLE
    }

    private fun handleLogin() {
        val email = loginEmail.text.toString().trim()
        val password = loginPassword.text.toString().trim()

        // Clear previous errors
        loginEmail.error = null
        loginPassword.error = null

        // Validate email
        if (email.isEmpty()) {
            loginEmail.error = "Email is required"
            return
        }

        if (!isValidEmail(email)) {
            loginEmail.error = "Please enter a valid email address"
            return
        }

        // Validate password
        if (password.isEmpty()) {
            loginPassword.error = "Password is required"
            return
        }

        if (password.length < 6) {
            loginPassword.error = "Password must be at least 6 characters"
            return
        }

        // SECURITY CHECK: Prevent different account login on same device
        val trackingPrefs = getSharedPreferences("location_service_state", Context.MODE_PRIVATE)
        val savedUserId = trackingPrefs.getString("saved_user_id", null)
        val userPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedEmail = userPrefs.getString("email", null)

        if (savedUserId != null && savedEmail != null && !savedEmail.equals(email, ignoreCase = true)) {
            android.util.Log.w("AUTH_LOGIN", "⚠️ BLOCKED: Different email attempting login on device with existing user")
            android.util.Log.w("AUTH_LOGIN", "   Saved email: $savedEmail, Attempted: $email")
            showLoginError("Sorry, can't use two accounts on one device for security reasons.")
            return
        }

        // Disable button to prevent multiple requests
        loginSubmitButton.isEnabled = false
        loginSubmitButton.text = "Signing in..."

        android.util.Log.d("AUTH_LOGIN", "=== ANDROID LOGIN STARTED ===")

        // STEP 1: Use Firebase Authentication for login (FAST - validates credentials instantly)
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.d("AUTH_LOGIN", "✅ Firebase login successful")
                    val user = FirebaseAuth.getInstance().currentUser

                    // Get ID token and fetch profile
                    user?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        if (tokenTask.isSuccessful) {
                            val idToken = tokenTask.result?.token
                            android.util.Log.d("AUTH_LOGIN", "🔑 Firebase token obtained")

                            // Fetch profile using shared HttpClient (FAST - connection reuse)
                            lifecycleScope.launch {
                                try {
                                    android.util.Log.d("AUTH_LOGIN", "📡 Fetching profile...")
                                    val profileResponse = AuthService.getProfile(idToken!!)
                                    handleLoginProfileResponse(profileResponse, email)
                                } catch (e: Exception) {
                                    android.util.Log.e("AUTH_LOGIN", "❌ Profile fetch failed: ${e.message}")
                                    // Backend failed but Firebase auth succeeded - allow login with basic data
                                    handleLoginWithoutBackendProfile(email)
                                }
                            }
                        } else {
                            android.util.Log.e("AUTH_LOGIN", "❌ Failed to get Firebase token")
                            handleLoginError("Failed to get authentication token")
                        }
                    }
                } else {
                    android.util.Log.e("AUTH_LOGIN", "❌ Firebase login failed: ${task.exception?.message}")
                    val errorMessage = when {
                        task.exception?.message?.contains("password is invalid") == true -> "Invalid email or password"
                        task.exception?.message?.contains("no user record") == true -> "No account found with this email"
                        task.exception?.message?.contains("badly formatted") == true -> "Invalid email format"
                        task.exception?.message?.contains("blocked") == true -> "Too many attempts. Try again later."
                        else -> "Login failed. Please try again."
                    }
                    handleLoginError(errorMessage)
                }
            }
    }

    /**
     * Handle login when backend profile fetch fails - still allow login with basic data
     */
    private suspend fun handleLoginWithoutBackendProfile(email: String) {
        android.util.Log.d("AUTH_LOGIN", "⚠️ Proceeding with login without backend profile...")

        withContext(Dispatchers.Main) {
            // Save basic user data
            val sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE)
            sharedPreferences.edit().apply {
                putString("email", email)
                putBoolean("is_logged_in", true)
                putBoolean("is_seller_mode", true)
                putLong("login_timestamp", System.currentTimeMillis())
                apply()
            }

            Toast.makeText(this@Auth, "Login successful!", Toast.LENGTH_SHORT).show()

            // Navigate to MainActivity - profile will sync later
            navigateToMainActivity()

            // Re-enable button
            loginSubmitButton.isEnabled = true
            loginSubmitButton.text = "Sign In"
        }
    }

    private suspend fun handleLoginProfileResponse(response: ApiResponse, email: String) {
        android.util.Log.d("AUTH_LOGIN", "🔄 Handling login profile response...")

        withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (response.isSuccessful()) {
                val jsonResponse = response.getJsonResponse()
                val userData = jsonResponse?.optJSONObject("data")

                if (userData != null) {
                    android.util.Log.d("AUTH_LOGIN", "✅ Profile data received")

                    // STEP 4: Save complete user data to SharedPreferences
                    saveLoginUserDataToPrefs(userData, email)

                    // STEP 5: Show success message
                    Toast.makeText(this@Auth, "Login successful!", Toast.LENGTH_SHORT).show()

                    // STEP 6: Check terms and onboarding status from backend data
                    val termsAccepted = userData.optBoolean("termsAccepted", false)
                    val onboardingCompleted = userData.optBoolean("onboardingCompleted", false)
                    val termsVersion = userData.optInt("termsVersion", 0)

                    android.util.Log.d("AUTH_LOGIN", "╔════════════════════════════════════════════════════════")
                    android.util.Log.d("AUTH_LOGIN", "║ BACKEND STATUS CHECK:")
                    android.util.Log.d("AUTH_LOGIN", "║   termsAccepted = $termsAccepted")
                    android.util.Log.d("AUTH_LOGIN", "║   onboardingCompleted = $onboardingCompleted")
                    android.util.Log.d("AUTH_LOGIN", "║   termsVersion = $termsVersion")
                    android.util.Log.d("AUTH_LOGIN", "╚════════════════════════════════════════════════════════")

                    // Navigate based on BACKEND status ONLY (not permissions!)
                    when {
                        !termsAccepted -> {
                            // Case 1: Terms not accepted → Show Terms
                            android.util.Log.d("AUTH_LOGIN", "🔴 DECISION: Terms NOT accepted")
                            android.util.Log.d("AUTH_LOGIN", "➡️  Navigating to Terms & Conditions")
                            navigateToTermsAndConditions()
                        }
                        !onboardingCompleted -> {
                            // Case 2: Terms accepted BUT onboarding not completed → Show Onboarding
                            android.util.Log.d("AUTH_LOGIN", "🟡 DECISION: Terms accepted, Onboarding NOT completed")
                            android.util.Log.d("AUTH_LOGIN", "➡️  Navigating to Onboarding")
                            navigateToOnboarding()
                        }
                        else -> {
                            // Case 3: Both completed → Go to MainActivity (HOME PAGE)
                            android.util.Log.d("AUTH_LOGIN", "🟢 DECISION: Both Terms AND Onboarding completed")
                            android.util.Log.d("AUTH_LOGIN", "➡️  Navigating to MainActivity (HOME PAGE)")
                            navigateToMainActivity()
                        }
                    }
                } else {
                    android.util.Log.e("AUTH_LOGIN", "❌ No profile data in response")
                    handleLoginError("Failed to load user profile")
                }
            } else {
                android.util.Log.e("AUTH_LOGIN", "❌ Profile fetch failed: ${response.statusCode}")
                handleLoginError("Failed to sync user data")
            }

            // Re-enable button
            loginSubmitButton.isEnabled = true
            loginSubmitButton.text = "Sign In"
        }
    }

    private fun handleLoginError(errorMessage: String) {
        android.util.Log.e("AUTH_LOGIN", "🔄 Handling login error: $errorMessage")
        runOnUiThread {
            showLoginError(errorMessage)
            loginSubmitButton.isEnabled = true
            loginSubmitButton.text = "Sign In"

            // IMPORTANT: Do NOT call FirebaseAuth.signOut() on login error
            // Firebase must stay authenticated for background tracking service
        }
    }

    private fun saveLoginUserDataToPrefs(userData: JSONObject, email: String) {
        android.util.Log.d("AUTH_LOGIN", "💾 Saving complete user data to SharedPreferences...")

        val sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("first_name", userData.optString("firstName", ""))
            putString("last_name", userData.optString("lastName", ""))
            putString("email", email)
            putString("description", userData.optString("description", ""))
            putString("location", userData.optString("location", ""))
            putString("profile_pic_url", userData.optString("profilePicUrl", ""))
            putInt("level", userData.optInt("level", 1))
            putInt("total_reviews", userData.optInt("totalReviews", 0))
            putFloat("rating", userData.optDouble("rating", 5.0).toFloat())
            putInt("available_balance", userData.optInt("availableBalance", 0))
            putBoolean("is_logged_in", true)
            putBoolean("is_seller_mode", true)
            putLong("login_timestamp", System.currentTimeMillis())

            // Save terms acceptance status
            putBoolean("terms_accepted", userData.optBoolean("termsAccepted", false))
            putInt("terms_version", userData.optInt("termsVersion", 0))

            // Save skills array
            val skills = userData.optJSONArray("skills")
            if (skills != null) {
                val skillsList = mutableListOf<String>()
                for (i in 0 until skills.length()) {
                    skillsList.add(skills.getString(i))
                }
                putStringSet("skills", skillsList.toSet())
            }

            // Save languages array
            val languages = userData.optJSONArray("Languages")
            if (languages != null) {
                val languagesList = mutableListOf<String>()
                for (i in 0 until languages.length()) {
                    languagesList.add(languages.getString(i))
                }
                putStringSet("languages", languagesList.toSet())
            }

            apply()
        }

        android.util.Log.d("AUTH_LOGIN", "✅ Complete user data saved successfully")
    }

    private fun handleSignup() {
        val firstName = signupFirstName.text.toString().trim()
        val lastName = signupLastName.text.toString().trim()
        val email = signupEmail.text.toString().trim()
        val password = signupPassword.text.toString().trim()

        // Clear previous errors
        signupFirstName.error = null
        signupLastName.error = null
        signupEmail.error = null
        signupPassword.error = null

        // SECURITY CHECK: Prevent new signup if device already has a registered user
        val trackingPrefs = getSharedPreferences("location_service_state", Context.MODE_PRIVATE)
        val savedUserId = trackingPrefs.getString("saved_user_id", null)

        if (savedUserId != null) {
            android.util.Log.w("AUTH_SIGNUP", "⚠️ BLOCKED: Signup attempt on device with existing user")
            showSignupError("Sorry, can't use two accounts on one device for security reasons.")
            return
        }

        // Validate fields
        if (firstName.isEmpty()) {
            signupFirstName.error = "First name is required"
            return
        }

        if (lastName.isEmpty()) {
            signupLastName.error = "Last name is required"
            return
        }

        if (email.isEmpty()) {
            signupEmail.error = "Email is required"
            return
        }

        if (!isValidEmail(email)) {
            signupEmail.error = "Please enter a valid email address"
            return
        }

        if (password.isEmpty()) {
            signupPassword.error = "Password is required"
            return
        }

        if (password.length < 6) {
            signupPassword.error = "Password must be at least 6 characters"
            return
        }

        // Disable button to prevent multiple requests
        signupSubmitButton.isEnabled = false
        signupSubmitButton.text = "Creating account..."

        // STEP 1: Clear any existing user data first
        clearAllUserData()

        // STEP 2: Call backend to create user (backend will handle Firebase creation)
        lifecycleScope.launch {
            try {
                android.util.Log.d("AUTH_SIGNUP", "=== ANDROID SIGNUP STARTED ===")
                android.util.Log.d("AUTH_SIGNUP", "First Name: $firstName")
                android.util.Log.d("AUTH_SIGNUP", "Last Name: $lastName")
                android.util.Log.d("AUTH_SIGNUP", "Email: $email")
                android.util.Log.d("AUTH_SIGNUP", "Password Length: ${password.length}")

                // Update button text for backend call
                runOnUiThread { signupSubmitButton.text = "Setting up account..." }

                // STEP 3: Call backend first - it will create Firebase user
                android.util.Log.d("AUTH_SIGNUP", "📡 Calling backend to create account...")

                // Quick health check first
                android.util.Log.d("AUTH_SIGNUP", "🏥 Performing backend health check...")
                runOnUiThread { signupSubmitButton.text = "Connecting to server..." }

                val healthCheck = performHealthCheck()

                if (healthCheck) {
                    android.util.Log.d("AUTH_SIGNUP", "✅ Backend is healthy, proceeding with signup")
                    runOnUiThread { signupSubmitButton.text = "Creating account..." }

                    val response = callBackendSignup(firstName, lastName, email, password)
                    handleBackendSignupResponse(response, firstName, lastName, email, password)
                } else {
                    android.util.Log.w("AUTH_SIGNUP", "⚠️ Backend health check failed")
                    handleSignupError("Server unavailable. Please try again later.")
                }
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("AUTH_SIGNUP", "⏰ Backend timeout")
                handleSignupError("Server is taking too long to respond. Please try again.")
            } catch (e: Exception) {
                android.util.Log.e("AUTH_SIGNUP", "❌ Signup failed: ${e.message}", e)
                handleSignupError("Failed to create account. Please try again.")
            }
        }
    }

    private suspend fun performHealthCheck(): Boolean {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("https://real-pakistan-backend.onrender.com/health")
                    .build()

                val response = client.newCall(request).execute()
                val isHealthy = response.isSuccessful
                android.util.Log.d("AUTH_SIGNUP", "🏥 Health check result: $isHealthy (${response.code})")
                isHealthy
            }
        } catch (e: Exception) {
            android.util.Log.e("AUTH_SIGNUP", "🏥 Health check failed: ${e.message}")
            false
        }
    }

    private suspend fun callBackendSignup(firstName: String, lastName: String, email: String, password: String): ApiResponse {
        android.util.Log.d("AUTH_SIGNUP", "📡 Making backend API call...")

        val json = JSONObject().apply {
            put("firstName", firstName)
            put("lastName", lastName)
            put("email", email)
            put("password", password)
        }

        val backendUrl = "https://real-pakistan-backend.onrender.com/api/auth/signup"
        val request = okhttp3.Request.Builder()
            .url(backendUrl)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            // Use withContext to execute network call on IO thread with increased timeout
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Create OkHttpClient with increased timeouts for backend operations
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // 60 seconds for backend operations
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                android.util.Log.d("AUTH_SIGNUP", "📥 Backend response: ${response.code}")
                android.util.Log.d("AUTH_SIGNUP", "📥 Backend body: $responseBody")

                ApiResponse(response.code, responseBody ?: "")
            }
        } catch (e: Exception) {
            android.util.Log.e("AUTH_SIGNUP", "❌ Backend call exception: ${e.message}", e)
            throw e
        }
    }

    private fun handleBackendSignupResponse(response: ApiResponse, firstName: String, lastName: String, email: String, password: String) {
        android.util.Log.d("AUTH_SIGNUP", "🔄 Handling backend signup response...")

        runOnUiThread {
            if (response.isSuccessful()) {
                val jsonResponse = response.getJsonResponse()
                if (jsonResponse?.optBoolean("success") == true) {
                    android.util.Log.d("AUTH_SIGNUP", "✅ Backend signup successful!")

                    // STEP 4: Now authenticate with Firebase using the credentials
                    runOnUiThread { signupSubmitButton.text = "Authenticating..." }

                    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                android.util.Log.d("AUTH_SIGNUP", "✅ Firebase authentication successful")

                                // STEP 5: Save user data to SharedPreferences
                                saveUserDataToPrefs(firstName, lastName, email)

                                // STEP 6: Show success message
                                Toast.makeText(this@Auth, "Account created successfully!", Toast.LENGTH_SHORT).show()

                                // STEP 7: Navigate to Terms and Conditions
                                val intent = Intent(this@Auth, TermsAndConditionsActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            } else {
                                android.util.Log.e("AUTH_SIGNUP", "❌ Firebase authentication failed: ${authTask.exception?.message}")
                                handleSignupError("Account created but authentication failed. Please try logging in.")
                            }
                        }
                } else {
                    // Backend signup failed - show error message from server
                    val errorMessage = jsonResponse?.optString("message") ?: "Signup failed"
                    android.util.Log.e("AUTH_SIGNUP", "❌ Backend signup failed: $errorMessage")
                    handleSignupError(errorMessage)
                }
            } else {
                // Backend HTTP error
                val errorMessage = response.getMessage()
                android.util.Log.e("AUTH_SIGNUP", "❌ Backend HTTP Error ${response.statusCode}: $errorMessage")

                when (response.statusCode) {
                    409 -> handleSignupError("Email already exists. Please use a different email or login.")
                    422 -> handleSignupError("Invalid input data")
                    500 -> handleSignupError("Server error. Please try again later.")
                    else -> handleSignupError("Signup failed: $errorMessage")
                }
            }

            // Re-enable button
            signupSubmitButton.isEnabled = true
            signupSubmitButton.text = "Sign Up"
        }
    }

    private fun handleSignupError(errorMessage: String) {
        android.util.Log.e("AUTH_SIGNUP", "🔄 Handling signup error: $errorMessage")
        runOnUiThread {
            showSignupError(errorMessage)
            signupSubmitButton.isEnabled = true
            signupSubmitButton.text = "Sign Up"
        }
    }

    private fun clearAllUserData() {
        android.util.Log.d("AUTH_SIGNUP", "🧹 Clearing all existing user data...")

        // Clear UI SharedPreferences only
        val sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().apply()

        // IMPORTANT: Do NOT call FirebaseAuth.signOut()
        // Firebase must stay authenticated for background tracking service
        android.util.Log.d("AUTH_SIGNUP", "⚠️ Firebase auth preserved (background tracking)")

        android.util.Log.d("AUTH_SIGNUP", "✅ UI user data cleared")
    }

    private fun saveUserDataToPrefs(firstName: String, lastName: String, email: String) {
        android.util.Log.d("AUTH_SIGNUP", "💾 Saving user data to SharedPreferences...")

        val sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("first_name", firstName)
            putString("last_name", lastName)
            putString("email", email)
            putBoolean("is_logged_in", true)
            putBoolean("is_seller_mode", true) // Default to seller mode
            putLong("login_timestamp", System.currentTimeMillis())
            apply()
        }

        android.util.Log.d("AUTH_SIGNUP", "✅ User data saved successfully")
    }

    private fun handleBackendTimeout(firstName: String, lastName: String, email: String) {
        android.util.Log.d("AUTH_SIGNUP", "🔄 Handling backend timeout - creating local account")

        runOnUiThread {
            // Since Firebase user is already created, proceed with local account setup
            android.util.Log.d("AUTH_SIGNUP", "⚠️ Backend timed out but Firebase user exists - proceeding with local setup")

            // STEP 6: Save user data to SharedPreferences (basic info)
            saveUserDataToPrefs(firstName, lastName, email)

            // STEP 7: Show partial success message
            Toast.makeText(this@Auth, "Account created! Profile sync may take a moment.", Toast.LENGTH_SHORT).show()

            // STEP 8: Navigate to MainActivity with profile completion flag
            val intent = Intent(this@Auth, MainActivity::class.java)
            intent.putExtra("show_profile_completion", true)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()

            // Re-enable button
            signupSubmitButton.isEnabled = true
            signupSubmitButton.text = "Sign Up"
        }
    }

    private fun deleteFirebaseUser() {
        android.util.Log.d("AUTH_SIGNUP", "🗑️ Deleting Firebase user due to backend failure...")
        val user = FirebaseAuth.getInstance().currentUser
        user?.delete()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                android.util.Log.d("AUTH_SIGNUP", "✅ Firebase user deleted successfully")
            } else {
                android.util.Log.e("AUTH_SIGNUP", "❌ Failed to delete Firebase user: ${task.exception?.message}")
            }
        }
    }

    private fun handleForgotPassword() {
        val email = forgotPasswordEmail.text.toString().trim()

        // Clear previous errors
        forgotPasswordEmail.error = null

        if (email.isEmpty()) {
            forgotPasswordEmail.error = "Email is required"
            return
        }

        if (!isValidEmail(email)) {
            forgotPasswordEmail.error = "Please enter a valid email address"
            return
        }

        // Disable button to prevent multiple requests
        forgotPasswordSubmitButton.isEnabled = false
        forgotPasswordSubmitButton.text = "Sending..."

        // Make API call
        lifecycleScope.launch {
            try {
                val response = AuthService.forgotPassword(email)

                if (response.isSuccessful()) {
                    val jsonResponse = response.getJsonResponse()
                    if (jsonResponse?.optBoolean("success") == true) {
                        Toast.makeText(this@Auth, "Password reset instructions sent to your email.", Toast.LENGTH_LONG).show()
                        showLoginForm()
                    } else {
                        val errorMessage = jsonResponse?.optString("message") ?: "Failed to send reset email"
                        showForgotPasswordError(errorMessage)
                    }
                } else {
                    val errorMessage = response.getMessage()
                    when (response.statusCode) {
                        404 -> showForgotPasswordError("Email not found. Please check your email or sign up.")
                        500 -> showForgotPasswordError("Server error. Please try again later.")
                        else -> showForgotPasswordError("Failed to send reset email: $errorMessage")
                    }
                }
            } catch (e: Exception) {
                showForgotPasswordError("Network error. Please check your connection and try again.")
            } finally {
                // Re-enable button
                forgotPasswordSubmitButton.isEnabled = true
                forgotPasswordSubmitButton.text = "Reset Password"
            }
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun showLoginError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showSignupError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showForgotPasswordError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun saveUserSession(token: String, userData: JSONObject?) {
        // Save to SharedPreferences
        val sharedPref = getSharedPreferences("user_session", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("auth_token", token)
            putString("user_data", userData?.toString() ?: "")
            apply()
        }
    }

    private fun navigateToMainActivity() {
        android.util.Log.d("AUTH_NAVIGATION", "✅ Navigating to MainActivity (HOME PAGE)")
        android.util.Log.d("AUTH_NAVIGATION", "📍 Note: Permissions will be checked by MainActivity if needed")

        // Go directly to MainActivity - don't check permissions here!
        // If onboarding is complete, user has already been through permission flow
        // MainActivity will start location service if permissions are granted
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToOnboarding() {
        android.util.Log.d("AUTH_NAVIGATION", "🎯 Navigating to Onboarding (starting from beginning)")
        val intent = Intent(this, com.example.newconstructionappwithlocationtracking.onboarding.OnboardingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToTermsAndConditions() {
        android.util.Log.d("AUTH_NAVIGATION", "📋 Navigating to Terms and Conditions")
        val intent = Intent(this, TermsAndConditionsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}