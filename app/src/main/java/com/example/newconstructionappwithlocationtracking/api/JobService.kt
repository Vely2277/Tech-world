package com.example.newconstructionappwithlocationtracking.api

import org.json.JSONObject

class JobService {

    companion object {
        private val baseUrl = "${Config.BACKEND_URL}/api/jobs"

        suspend fun createJob(
            authToken: String,
            title: String,
            description: String,
            budget: Double,
            location: String,
            latitude: Double?,
            longitude: Double?,
            category: String,
            imageUrls: List<String>?
        ): ApiResponse {
            val json = JSONObject().apply {
                put("title", title)
                put("description", description)
                put("budget", budget)
                put("location", location)
                put("category", category)
                latitude?.let { put("latitude", it) }
                longitude?.let { put("longitude", it) }
                imageUrls?.let {
                    put("imageUrls", JSONObject().apply {
                        imageUrls.forEachIndexed { index, url ->
                            put(index.toString(), url)
                        }
                    })
                }
            }

            return HttpClient.post(baseUrl, json, authToken)
        }

        suspend fun getJobs(authToken: String?, page: Int = 1, limit: Int = 10): ApiResponse {
            val url = "$baseUrl?page=$page&limit=$limit"
            return HttpClient.get(url, authToken)
        }

        suspend fun getJobById(authToken: String?, jobId: String): ApiResponse {
            return HttpClient.get("$baseUrl/$jobId", authToken)
        }

        suspend fun getMyJobs(authToken: String): ApiResponse {
            return HttpClient.get("$baseUrl/my-jobs", authToken)
        }

        suspend fun applyToJob(authToken: String, jobId: String, proposal: String, proposedBudget: Double): ApiResponse {
            val json = JSONObject().apply {
                put("proposal", proposal)
                put("proposedBudget", proposedBudget)
            }

            return HttpClient.post("$baseUrl/$jobId/apply", json, authToken)
        }
    }
}
