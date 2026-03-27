package com.example.newconstructionappwithlocationtracking.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.MainActivity
import com.example.newconstructionappwithlocationtracking.api.Config
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class Login : Fragment() {
    private var navigationListener: LoginNavigationListener? = null
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: MaterialButton

    interface LoginNavigationListener {
        fun onSignUpClicked()
        fun onForgotPasswordClicked()
        fun onLoginSuccess()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is LoginNavigationListener) {
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
        val view = inflater.inflate(R.layout.login, container, false)

        emailEditText = view.findViewById(R.id.emailEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        loginButton = view.findViewById(R.id.loginButton)
        val signUpButton = view.findViewById<TextView>(R.id.signUpButton)
        val forgotPasswordText = view.findViewById<TextView>(R.id.forgotPasswordText)

        loginButton.setOnClickListener {
            performLogin()
        }

        signUpButton.setOnClickListener {
            navigationListener?.onSignUpClicked()
        }

        forgotPasswordText.setOnClickListener {
            navigationListener?.onForgotPasswordClicked()
        }

        return view
    }

    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (!validateInput(email, password)) {
            return
        }

        // Show loading indicator
        loginButton.isEnabled = false
        loginButton.text = "Logging in..."

        // Login.kt just triggers the login - Auth.kt handles everything else
        android.util.Log.d("LOGIN", "🔄 Login form submitted - letting Auth activity handle it")

        // Simply notify the parent activity (Auth.kt) to handle the login
        navigationListener?.onLoginSuccess()
    }

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            emailEditText.error = "Email is required"
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email"
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
        fun newInstance(listener: LoginNavigationListener): Login {
            val fragment = Login()
            fragment.navigationListener = listener
            return fragment
        }
    }
}