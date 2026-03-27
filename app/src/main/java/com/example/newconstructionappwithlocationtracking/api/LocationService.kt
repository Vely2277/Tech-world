/*
 * ============================================================================
 * LOCATION SERVICE API INTERFACE - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Retrofit API interface for all location-related backend endpoints.
 * Defines contract between Android app and backend server.
 *
 * KEY FEATURES:
 * - Location upload (single and batch)
 * - Location history retrieval
 * - Health check endpoint
 * - Settings synchronization
 * - Proper response models
 * - Authentication header support
 * - Coroutine suspend functions
 * - Error handling ready
 *
 * ENDPOINTS:
 * POST   /api/location/upload       - Upload single location
 * POST   /api/location/batch        - Upload multiple locations
 * GET    /api/location/history      - Get user's location history
 * GET    /api/health                - Backend health check
 * GET    /api/location-settings/:id - Get user's tracking settings
 *
 * USAGE WITH RETROFIT:
 * val retrofit = Retrofit.Builder()
 *     .baseUrl("https://real-pakistan-backend.onrender.com")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build()
 *
 * val locationService = retrofit.create(LocationService::class.java)
 * val response = locationService.uploadLocation("Bearer token", locationData)
 *
 * INTEGRATION:
 * - Used by BatchUploader for reliable uploads
 * - Used by LocationTrackingService for immediate uploads
 * - Used by LocationSettingsManager for settings sync
 * - Authentication via Bearer token
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.api

import com.example.newconstructionappwithlocationtracking.models.location.LocationData
import retrofit2.Response
import retrofit2.http.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION SERVICE API INTERFACE
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * Retrofit interface for location tracking backend API
 */
interface LocationService {

    /**
     * Upload single location to backend
     *
     * @param authorization Bearer token (format: "Bearer <token>")
     * @param location LocationData object to upload
     * @return Response with UploadResponse containing locationId and status
     */
    @POST("/api/location/upload")
    suspend fun uploadLocation(
        @Header("Authorization") authorization: String,
        @Body location: LocationData
    ): Response<UploadResponse>

    /**
     * Upload multiple locations in batch
     *
     * @param authorization Bearer token (format: "Bearer <token>")
     * @param locations List of LocationData objects
     * @return Response with BatchUploadResponse containing success count
     */
    @POST("/api/location/batch")
    suspend fun uploadLocations(
        @Header("Authorization") authorization: String,
        @Body locations: List<LocationData>
    ): Response<BatchUploadResponse>

    /**
     * Get user's location history
     *
     * @param authorization Bearer token (format: "Bearer <token>")
     * @param limit Number of locations to retrieve (default: 50)
     * @param offset Pagination offset (default: 0)
     * @return Response with LocationHistoryResponse containing locations array
     */
    @GET("/api/location/history")
    suspend fun getLocationHistory(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<LocationHistoryResponse>

    /**
     * Check backend health status
     *
     * @return Response with HealthResponse indicating backend status
     */
    @GET("/api/health")
    suspend fun checkHealth(): Response<HealthResponse>

    /**
     * Get user's location tracking settings from backend
     *
     * @param authorization Bearer token (format: "Bearer <token>")
     * @param userId User ID to fetch settings for
     * @return Response with LocationSettingsResponse
     */
    @GET("/api/location-settings/{userId}")
    suspend fun getLocationSettings(
        @Header("Authorization") authorization: String,
        @Path("userId") userId: String
    ): Response<LocationSettingsResponse>
}

/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * RESPONSE MODELS
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

/**
 * Response for single location upload
 */
data class UploadResponse(
    val success: Boolean,
    val message: String? = null,
    val locationId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Response for batch location upload
 */
data class BatchUploadResponse(
    val success: Boolean,
    val message: String? = null,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val errors: List<String>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Response for location history retrieval
 */
data class LocationHistoryResponse(
    val success: Boolean,
    val message: String? = null,
    val locations: List<LocationData>? = null,
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0
)

/**
 * Response for health check
 */
data class HealthResponse(
    val success: Boolean,
    val status: String,  // "healthy", "degraded", "down"
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val version: String? = null
)

/**
 * Response for location settings retrieval
 */
data class LocationSettingsResponse(
    val success: Boolean,
    val message: String? = null,
    val settings: Map<String, Any>? = null,
    val timestamp: Long = System.currentTimeMillis()
)
