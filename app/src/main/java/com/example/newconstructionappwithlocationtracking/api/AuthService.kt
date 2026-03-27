package com.example.newconstructionappwithlocationtracking.api

import org.json.JSONObject

class AuthService {

    companion object {
        private val baseUrl = "${Config.BACKEND_URL}/api/auth"
        private val profileUrl = "${Config.BACKEND_URL}/api/profile"

        suspend fun signup(firstName: String, lastName: String, email: String, password: String): ApiResponse {
            val json = JSONObject().apply {
                put("firstName", firstName)
                put("lastName", lastName)
                put("email", email)
                put("password", password)
            }

            return HttpClient.post("$baseUrl/signup", json)
        }

        suspend fun login(email: String, password: String): ApiResponse {
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            return HttpClient.post("$baseUrl/login", json)
        }

        suspend fun forgotPassword(email: String): ApiResponse {
            val json = JSONObject().apply {
                put("email", email)
            }

            return HttpClient.post("$baseUrl/forgot-password", json)
        }

        suspend fun verifyToken(idToken: String): ApiResponse {
            val json = JSONObject().apply {
                put("idToken", idToken)
            }

            return HttpClient.post("$baseUrl/verify-token", json)
        }

        /**
         * Get user profile using Firebase ID token
         * Uses shared HttpClient for connection reuse (faster!)
         */
        suspend fun getProfile(firebaseIdToken: String): ApiResponse {
            return HttpClient.get(profileUrl, firebaseIdToken)
        }
    }
}
