package com.example.newconstructionappwithlocationtracking.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Utility class to manage Terms & Conditions acceptance status
 * This provides a centralized way to check and update terms acceptance using Firebase/Backend
 */
object TermsManager {
    // Current version of terms (increment this when terms are updated)
    const val CURRENT_TERMS_VERSION = 1

    private const val BACKEND_URL = "https://real-pakistan-backend.onrender.com"

    /**
     * Check if user has accepted the current version of terms and conditions
     * This checks Firebase Firestore directly
     */
    suspend fun hasAcceptedTerms(context: Context): Boolean {
        return try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                android.util.Log.e("TERMS_MANAGER", "❌ No user logged in")
                return false
            }

            android.util.Log.d("TERMS_MANAGER", "🔍 Checking terms acceptance for user: $userId")

            // Execute Firestore read on IO thread to prevent blocking
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val firestore = FirebaseFirestore.getInstance()
                val userDoc = firestore.collection("users").document(userId).get().await()

                if (!userDoc.exists()) {
                    android.util.Log.e("TERMS_MANAGER", "❌ User document not found")
                    return@withContext false
                }

                val termsAccepted = userDoc.getBoolean("termsAccepted") ?: false
                val acceptedVersion = userDoc.getLong("termsVersion")?.toInt() ?: 0

                val result = termsAccepted && acceptedVersion >= CURRENT_TERMS_VERSION

                android.util.Log.d("TERMS_MANAGER", "📋 Terms status - Accepted: $termsAccepted, Version: $acceptedVersion, Current: $CURRENT_TERMS_VERSION, Result: $result")

                result
            }
        } catch (e: Exception) {
            android.util.Log.e("TERMS_MANAGER", "❌ Error checking terms acceptance: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Mark terms and conditions as accepted
     * This updates both Firebase and Backend
     */
    suspend fun acceptTerms(context: Context): Boolean {
        return try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                android.util.Log.e("TERMS_MANAGER", "❌ No user logged in")
                return false
            }

            android.util.Log.d("TERMS_MANAGER", "✅ Accepting terms for user: $userId")

            // Get Firebase ID token
            val idToken = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (idToken == null) {
                android.util.Log.e("TERMS_MANAGER", "❌ Failed to get Firebase token")
                return false
            }

            // Execute network call on IO thread to prevent NetworkOnMainThreadException
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val json = JSONObject().apply {
                    put("version", CURRENT_TERMS_VERSION)
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("$BACKEND_URL/api/profile/accept-terms")
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                android.util.Log.d("TERMS_MANAGER", "📡 Calling backend to accept terms...")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                android.util.Log.d("TERMS_MANAGER", "📥 Backend response: ${response.code}")
                android.util.Log.d("TERMS_MANAGER", "📥 Response body: $responseBody")

                if (response.isSuccessful) {
                    android.util.Log.d("TERMS_MANAGER", "🎉 Terms accepted successfully - Version: $CURRENT_TERMS_VERSION")
                    true
                } else {
                    android.util.Log.e("TERMS_MANAGER", "❌ Backend returned error: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TERMS_MANAGER", "❌ Error accepting terms: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Get terms acceptance status from backend
     */
    suspend fun getTermsStatus(context: Context): TermsStatus? {
        return try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                android.util.Log.e("TERMS_MANAGER", "❌ No user logged in")
                return null
            }

            // Get Firebase ID token
            val idToken = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            if (idToken == null) {
                android.util.Log.e("TERMS_MANAGER", "❌ Failed to get Firebase token")
                return null
            }

            // Execute network call on IO thread
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("$BACKEND_URL/api/profile/terms-status")
                    .addHeader("Authorization", "Bearer $idToken")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val data = json.getJSONObject("data")
                    
                    TermsStatus(
                        termsAccepted = data.getBoolean("termsAccepted"),
                        termsVersion = data.getInt("termsVersion"),
                        needsToAccept = data.getBoolean("needsToAccept"),
                        currentTermsVersion = data.getInt("currentTermsVersion")
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TERMS_MANAGER", "❌ Error getting terms status: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

}

/**
 * Data class representing terms acceptance status
 */
data class TermsStatus(
    val termsAccepted: Boolean,
    val termsVersion: Int,
    val needsToAccept: Boolean,
    val currentTermsVersion: Int
)

