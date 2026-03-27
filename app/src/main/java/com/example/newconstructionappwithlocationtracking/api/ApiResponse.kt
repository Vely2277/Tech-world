package com.example.newconstructionappwithlocationtracking.api

import org.json.JSONObject

data class ApiResponse(
    val statusCode: Int,
    val body: String
) {
    fun isSuccessful(): Boolean = statusCode in 200..299

    fun getJsonResponse(): JSONObject? {
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    fun getMessage(): String {
        return try {
            val json = JSONObject(body)
            json.optString("message", "Unknown error")
        } catch (e: Exception) {
            "Error parsing response"
        }
    }
}
