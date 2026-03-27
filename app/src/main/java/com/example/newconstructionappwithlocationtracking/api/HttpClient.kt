package com.example.newconstructionappwithlocationtracking.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class HttpClient {

    companion object {
        private const val TIMEOUT = 30000 // 30 seconds

        suspend fun post(url: String, jsonBody: JSONObject, authToken: String? = null): ApiResponse {
            return withContext(Dispatchers.IO) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "application/json")

                    authToken?.let {
                        connection.setRequestProperty("Authorization", "Bearer $it")
                    }

                    connection.doOutput = true
                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT

                    // Write JSON body
                    val outputStream = connection.outputStream
                    val writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF-8"))
                    writer.write(jsonBody.toString())
                    writer.flush()
                    writer.close()
                    outputStream.close()

                    // Read response
                    val responseCode = connection.responseCode
                    val inputStream = if (responseCode >= 200 && responseCode < 300) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val response = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                    ApiResponse(responseCode, response)
                } catch (e: Exception) {
                    ApiResponse(0, "{\"success\":false,\"message\":\"${e.message}\"}")
                }
            }
        }

        suspend fun get(url: String, authToken: String? = null): ApiResponse {
            return withContext(Dispatchers.IO) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json")

                    authToken?.let {
                        connection.setRequestProperty("Authorization", "Bearer $it")
                    }

                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT

                    val responseCode = connection.responseCode
                    val inputStream = if (responseCode >= 200 && responseCode < 300) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val response = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                    ApiResponse(responseCode, response)
                } catch (e: Exception) {
                    ApiResponse(0, "{\"success\":false,\"message\":\"${e.message}\"}")
                }
            }
        }

        suspend fun put(url: String, jsonBody: JSONObject, authToken: String? = null): ApiResponse {
            return withContext(Dispatchers.IO) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "PUT"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "application/json")

                    authToken?.let {
                        connection.setRequestProperty("Authorization", "Bearer $it")
                    }

                    connection.doOutput = true
                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT

                    // Write JSON body
                    val outputStream = connection.outputStream
                    val writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF-8"))
                    writer.write(jsonBody.toString())
                    writer.flush()
                    writer.close()
                    outputStream.close()

                    // Read response
                    val responseCode = connection.responseCode
                    val inputStream = if (responseCode >= 200 && responseCode < 300) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val response = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                    ApiResponse(responseCode, response)
                } catch (e: Exception) {
                    ApiResponse(0, "{\"success\":false,\"message\":\"${e.message}\"}")
                }
            }
        }
    }
}
