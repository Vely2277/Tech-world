package com.example.newconstructionappwithlocationtracking.api

import org.json.JSONObject

/**
 * Email Service - Handles email verification and related API calls
 */
class EmailService {

    companion object {
        private val baseUrl = "${Config.BACKEND_URL}/api/email"

        /**
         * Send verification code to user's email
         */
        suspend fun sendVerificationCode(token: String): ApiResponse {
            return HttpClient.post(
                "$baseUrl/send-verification",
                JSONObject(),
                token
            )
        }

        /**
         * Verify the code entered by user
         */
        suspend fun verifyCode(token: String, code: String): ApiResponse {
            val json = JSONObject().apply {
                put("code", code)
            }

            return HttpClient.post(
                "$baseUrl/verify-code",
                json,
                token
            )
        }

        /**
         * Resend verification code
         */
        suspend fun resendVerificationCode(token: String): ApiResponse {
            return HttpClient.post(
                "$baseUrl/resend-verification",
                JSONObject(),
                token
            )
        }

        /**
         * Check email verification status
         */
        suspend fun getVerificationStatus(token: String): ApiResponse {
            return HttpClient.get(
                "$baseUrl/verification-status",
                token
            )
        }
    }
}

