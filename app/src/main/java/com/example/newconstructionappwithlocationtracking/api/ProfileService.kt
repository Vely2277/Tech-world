package com.example.newconstructionappwithlocationtracking.api

import org.json.JSONObject

class ProfileService {

    companion object {
        private val baseUrl = "${Config.BACKEND_URL}/api/profile"

        suspend fun getProfile(authToken: String): ApiResponse {
            return HttpClient.get(baseUrl, authToken)
        }

        suspend fun updateProfile(authToken: String, firstName: String?, lastName: String?, profilePicUrl: String?): ApiResponse {
            val json = JSONObject()
            firstName?.let { json.put("firstName", it) }
            lastName?.let { json.put("lastName", it) }
            profilePicUrl?.let { json.put("profilePicUrl", it) }

            return HttpClient.put(baseUrl, json, authToken)
        }
    }
}
