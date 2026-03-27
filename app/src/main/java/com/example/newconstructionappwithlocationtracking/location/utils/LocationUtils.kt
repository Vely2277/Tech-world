/*
 * ============================================================================
 * LOCATION UTILITIES
 * ============================================================================
 *
 * PURPOSE:
 * Collection of utility functions for location calculations, validation,
 * and data transformations used throughout the location tracking system.
 *
 * KEY FUNCTIONS:
 * - calculateDistance(): Haversine formula for distance between coordinates
 * - isAccuracyAcceptable(): Validate location accuracy against thresholds
 * - isLocationValid(): Validate coordinate ranges and data integrity
 * - detectMovement(): Determine if user has moved significantly
 * - calculateSpeed(): Calculate speed between two location points
 * - formatCoordinates(): Format coordinates for display
 * - getBatteryLevel(): Get current device battery percentage
 * - getNetworkQuality(): Assess current network connection quality
 * - isLocationRecent(): Check if location timestamp is recent
 * - shouldClusterLocation(): Determine if locations are too close to store separately
 *
 * USAGE:
 * Used by LocationManager, services, and various tracking components
 * for location data processing and decision making.
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.example.newconstructionappwithlocationtracking.models.location.LocationData
import kotlin.math.*

object LocationUtils {

    /**
     * Calculate distance between two coordinate points using Haversine formula
     * @param lat1 Latitude of first point
     * @param lon1 Longitude of first point
     * @param lat2 Latitude of second point
     * @param lon2 Longitude of second point
     * @return Distance in meters
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // Earth radius in meters

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }

    /**
     * Calculate distance between two LocationData objects
     */
    fun calculateDistance(location1: LocationData, location2: LocationData): Float {
        return calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
    }

    /**
     * Calculate distance between LocationData and Android Location
     */
    fun calculateDistance(location1: LocationData, location2: Location): Float {
        return calculateDistance(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
    }

    /**
     * Check if location accuracy is acceptable for the given provider
     * @param accuracy Accuracy in meters
     * @param provider Location provider (gps, network, etc)
     * @return true if accuracy meets threshold
     */
    fun isAccuracyAcceptable(accuracy: Float?, provider: String?): Boolean {
        if (accuracy == null) return false

        return when (provider?.lowercase()) {
            LocationConstants.PROVIDER_GPS, LocationConstants.PROVIDER_FUSED -> {
                accuracy <= LocationConstants.GPS_OKAY_ACCURACY
            }
            LocationConstants.PROVIDER_NETWORK -> {
                accuracy <= LocationConstants.NETWORK_ACCEPTABLE_ACCURACY
            }
            else -> {
                accuracy <= LocationConstants.NETWORK_ACCEPTABLE_ACCURACY
            }
        }
    }

    /**
     * Validate location coordinates are within valid ranges
     */
    fun isLocationValid(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /**
     * Validate LocationData object
     */
    fun isLocationValid(location: LocationData): Boolean {
        return isLocationValid(location.latitude, location.longitude)
    }

    /**
     * Detect if user has moved significantly since last location
     * @param lastLocation Previous location
     * @param currentLocation Current location
     * @param thresholdMeters Minimum distance to consider as movement
     * @return true if movement detected
     */
    fun detectMovement(
        lastLocation: LocationData?,
        currentLocation: LocationData,
        thresholdMeters: Float = LocationConstants.MOVEMENT_THRESHOLD_METERS
    ): Boolean {
        if (lastLocation == null) return true

        val distance = calculateDistance(lastLocation, currentLocation)
        return distance >= thresholdMeters
    }

    /**
     * Calculate speed between two locations
     * @return Speed in meters per second, or null if calculation not possible
     */
    fun calculateSpeed(location1: LocationData, location2: LocationData): Float? {
        val distance = calculateDistance(location1, location2)
        val timeDiff = (location2.clientTimestamp - location1.clientTimestamp) / 1000.0 // seconds

        return if (timeDiff > 0) {
            (distance / timeDiff).toFloat()
        } else {
            null
        }
    }

    /**
     * Determine movement status based on speed and distance
     */
    fun determineMovementStatus(
        lastLocation: LocationData?,
        currentLocation: LocationData
    ): String {
        if (lastLocation == null) return LocationConstants.MOVEMENT_UNKNOWN

        val distance = calculateDistance(lastLocation, currentLocation)
        val speed = calculateSpeed(lastLocation, currentLocation)

        return when {
            distance < LocationConstants.MOVEMENT_THRESHOLD_METERS -> {
                LocationConstants.MOVEMENT_STATIONARY
            }
            speed != null && speed > LocationConstants.HIGH_SPEED_THRESHOLD_MPS -> {
                LocationConstants.MOVEMENT_HIGH_SPEED
            }
            else -> {
                LocationConstants.MOVEMENT_MOVING
            }
        }
    }

    /**
     * Check if two locations should be clustered (too close to store separately)
     */
    fun shouldClusterLocation(location1: LocationData, location2: LocationData): Boolean {
        val distance = calculateDistance(location1, location2)
        return distance < LocationConstants.LOCATION_CLUSTERING_RADIUS
    }

    /**
     * Format coordinates for display
     */
    fun formatCoordinates(latitude: Double, longitude: Double): String {
        return String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    /**
     * Format location for display
     */
    fun formatLocation(location: LocationData): String {
        return formatCoordinates(location.latitude, location.longitude)
    }

    /**
     * Get current device battery level
     * @return Battery percentage (0-100), or null if unavailable
     */
    fun getBatteryLevel(context: Context): Int? {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt()
            } else {
                null
            }
        }
    }

    /**
     * Check if device is charging
     */
    fun isDeviceCharging(context: Context): Boolean {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return batteryIntent?.let { intent ->
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
    }

    /**
     * Get battery optimization category based on level
     */
    fun getBatteryCategory(batteryLevel: Int?): String {
        return when {
            batteryLevel == null -> "unknown"
            batteryLevel > LocationConstants.BATTERY_PROTECTION_THRESHOLD -> "high"  // > 44%
            batteryLevel > 20 -> "medium"  // 20-44%
            batteryLevel > 10 -> "low"     // 10-20%
            else -> "critical"             // < 10%
        }
    }

    /**
     * Get current network quality
     */
    fun getNetworkQuality(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return LocationConstants.NETWORK_OFFLINE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return LocationConstants.NETWORK_OFFLINE
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return LocationConstants.NETWORK_OFFLINE

            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    LocationConstants.NETWORK_EXCELLENT
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // Signal strength requires API 29+, fallback to GOOD for older versions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val signalStrength = capabilities.signalStrength
                        when {
                            signalStrength >= -70 -> LocationConstants.NETWORK_GOOD
                            signalStrength >= -90 -> LocationConstants.NETWORK_POOR
                            else -> LocationConstants.NETWORK_POOR
                        }
                    } else {
                        LocationConstants.NETWORK_GOOD  // Assume good for API < 29
                    }
                }
                else -> LocationConstants.NETWORK_POOR
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return if (networkInfo?.isConnected == true) {
                when (networkInfo.type) {
                    ConnectivityManager.TYPE_WIFI -> LocationConstants.NETWORK_EXCELLENT
                    ConnectivityManager.TYPE_MOBILE -> LocationConstants.NETWORK_GOOD
                    else -> LocationConstants.NETWORK_POOR
                }
            } else {
                LocationConstants.NETWORK_OFFLINE
            }
        }
    }

    /**
     * Check if network is available
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val quality = getNetworkQuality(context)
        return quality != LocationConstants.NETWORK_OFFLINE
    }

    /**
     * Check if WiFi is connected
     */
    fun isWiFiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }

    /**
     * Check if location timestamp is recent
     * @param timestamp Timestamp to check
     * @param maxAgeMs Maximum age in milliseconds
     * @return true if timestamp is within max age
     */
    fun isLocationRecent(timestamp: Long, maxAgeMs: Long = 3600000): Boolean {
        return System.currentTimeMillis() - timestamp <= maxAgeMs
    }

    /**
     * Check if LocationData is recent
     */
    fun isLocationRecent(location: LocationData, maxAgeMs: Long = 3600000): Boolean {
        return isLocationRecent(location.clientTimestamp, maxAgeMs)
    }

    /**
     * Convert Android Location to LocationData
     */
    fun toLocationData(
        location: Location,
        userId: String?,
        batteryLevel: Int?,
        networkQuality: String?,
        movementStatus: String? = null
    ): LocationData {
        return LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            provider = location.provider,
            clientTimestamp = location.time,
            batteryLevel = batteryLevel,
            networkQuality = networkQuality,
            movementStatus = movementStatus,
            userId = userId
        )
    }

    /**
     * Get time ago string for display
     */
    fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> "$minutes minute${if (minutes != 1L) "s" else ""} ago"
            hours < 24 -> "$hours hour${if (hours != 1L) "s" else ""} ago"
            days < 7 -> "$days day${if (days != 1L) "s" else ""} ago"
            else -> "${days / 7} week${if (days / 7 != 1L) "s" else ""} ago"
        }
    }

    /**
     * Calculate exponential backoff delay (deprecated - use 3-tier retry system instead)
     * @deprecated Use LocationData retry tier system instead
     */
    @Deprecated("Use LocationData.getNextRetryInterval() instead")
    fun calculateBackoffDelay(retryCount: Int): Long {
        val baseDelay = 60000L // 1 minute
        val delay = baseDelay * (2.0.pow(retryCount)).toLong()
        return minOf(delay, 600000L)  // Max 10 minutes
    }

    /**
     * Generate unique location ID
     */
    fun generateLocationId(): String {
        return "${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    // ============================================================================
    // ACCURACY LEVEL CLASSIFICATION (4-TIER SYSTEM) - CRITICAL!
    // ============================================================================

    /**
     * Classify accuracy into 4-tier system
     * Excellent: 0-20m, Better: 20-50m, Good: 50-100m, Okay: >100m
     */
    fun getAccuracyLevel(accuracy: Float?): String {
        return when {
            accuracy == null -> LocationData.ACCURACY_OKAY
            accuracy <= LocationConstants.GPS_EXCELLENT_ACCURACY -> LocationData.ACCURACY_EXCELLENT
            accuracy <= LocationConstants.GPS_BETTER_ACCURACY -> LocationData.ACCURACY_BETTER
            accuracy <= LocationConstants.GPS_GOOD_ACCURACY -> LocationData.ACCURACY_GOOD
            else -> LocationData.ACCURACY_OKAY
        }
    }

    /**
     * Get accuracy level from meters
     */
    fun getAccuracyLevelFromMeters(meters: Float): String {
        return getAccuracyLevel(meters)
    }

    /**
     * Check if accuracy is high quality (Excellent or Better)
     */
    fun isHighAccuracy(accuracyLevel: String): Boolean {
        return accuracyLevel == LocationData.ACCURACY_EXCELLENT ||
               accuracyLevel == LocationData.ACCURACY_BETTER
    }

    /**
     * Check if accuracy is acceptable for GPS provider
     */
    fun isGpsAccuracyAcceptable(accuracy: Float?): Boolean {
        return accuracy != null && accuracy <= LocationConstants.GPS_OKAY_ACCURACY
    }

    /**
     * Check if accuracy is acceptable for Network provider
     */
    fun isNetworkAccuracyAcceptable(accuracy: Float?): Boolean {
        return accuracy != null && accuracy <= LocationConstants.NETWORK_ACCEPTABLE_ACCURACY
    }

    // ============================================================================
    // PROVIDER UTILITIES
    // ============================================================================

    /**
     * Normalize Android provider name to our constants
     */
    fun normalizeProviderName(androidProvider: String?): String {
        return when (androidProvider?.lowercase()) {
            "gps", "gps_provider" -> LocationConstants.PROVIDER_GPS
            "network", "network_provider" -> LocationConstants.PROVIDER_NETWORK
            "passive", "passive_provider" -> LocationConstants.PROVIDER_PASSIVE
            "fused" -> LocationConstants.PROVIDER_FUSED
            else -> LocationConstants.PROVIDER_UNKNOWN
        }
    }

    /**
     * Get display name for provider
     */
    fun getProviderDisplayName(provider: String): String {
        return when (provider) {
            LocationConstants.PROVIDER_GPS -> "GPS"
            LocationConstants.PROVIDER_NETWORK -> "Network"
            LocationConstants.PROVIDER_PASSIVE -> "Passive"
            LocationConstants.PROVIDER_FUSED -> "Fused"
            else -> "Unknown"
        }
    }

    /**
     * Check if provider is GPS
     */
    fun isGpsProvider(provider: String): Boolean {
        return provider == LocationConstants.PROVIDER_GPS
    }

    /**
     * Check if provider is Network
     */
    fun isNetworkProvider(provider: String): Boolean {
        return provider == LocationConstants.PROVIDER_NETWORK
    }

    /**
     * Check if provider is high accuracy (GPS or Fused)
     */
    fun isHighAccuracyProvider(provider: String): Boolean {
        return provider == LocationConstants.PROVIDER_GPS ||
               provider == LocationConstants.PROVIDER_FUSED
    }

    // ============================================================================
    // MOVEMENT UTILITIES (EXTENDED)
    // ============================================================================

    /**
     * Check if location indicates stationary
     */
    fun isStationary(movementStatus: String?): Boolean {
        return movementStatus == LocationConstants.MOVEMENT_STATIONARY
    }

    /**
     * Check if location indicates high speed
     */
    fun isHighSpeed(movementStatus: String?): Boolean {
        return movementStatus == LocationConstants.MOVEMENT_HIGH_SPEED
    }

    /**
     * Check if speed indicates high speed movement
     */
    fun isHighSpeedValue(speed: Float?): Boolean {
        return speed != null && speed > LocationConstants.HIGH_SPEED_THRESHOLD_MPS
    }

    /**
     * Get speed category
     */
    fun getSpeedCategory(speed: Float?): String {
        return when {
            speed == null -> "unknown"
            speed < 1.0f -> "stationary"
            speed < 5.0f -> "slow"
            speed < LocationConstants.HIGH_SPEED_THRESHOLD_MPS -> "normal"
            else -> "fast"
        }
    }

    // ============================================================================
    // LOCATION COMPARISON
    // ============================================================================

    /**
     * Check if two locations are the same (within threshold)
     */
    fun isSameLocation(
        loc1: LocationData,
        loc2: LocationData,
        thresholdMeters: Float = LocationConstants.MOVEMENT_THRESHOLD_METERS
    ): Boolean {
        val distance = calculateDistance(loc1, loc2)
        return distance < thresholdMeters
    }

    /**
     * Check if loc1 has better accuracy than loc2
     */
    fun isBetterAccuracy(loc1: LocationData, loc2: LocationData): Boolean {
        val acc1 = loc1.accuracy ?: Float.MAX_VALUE
        val acc2 = loc2.accuracy ?: Float.MAX_VALUE
        return acc1 < acc2
    }

    /**
     * Check if loc1 is newer than loc2
     */
    fun isNewerLocation(loc1: LocationData, loc2: LocationData): Boolean {
        return loc1.clientTimestamp > loc2.clientTimestamp
    }

    /**
     * Select best location from list (most accurate and recent)
     */
    fun selectBestLocation(locations: List<LocationData>): LocationData? {
        if (locations.isEmpty()) return null

        return locations.maxWithOrNull(compareBy(
            { it.accuracy?.let { acc -> -acc } ?: Float.MIN_VALUE },  // Better accuracy (lower value)
            { it.clientTimestamp }  // More recent
        ))
    }

    /**
     * Check if locations are nearby (within clustering radius)
     */
    fun areLocationsNearby(loc1: LocationData, loc2: LocationData): Boolean {
        return shouldClusterLocation(loc1, loc2)
    }

    /**
     * Get clustering radius
     */
    fun getClusteringRadius(): Float {
        return LocationConstants.LOCATION_CLUSTERING_RADIUS
    }

    // ============================================================================
    // TIME UTILITIES (EXTENDED)
    // ============================================================================

    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return format.format(date)
    }

    /**
     * Format duration in milliseconds to readable string
     */
    fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            milliseconds < 1000 -> "${milliseconds}ms"
            seconds < 60 -> "${seconds}s"
            minutes < 60 -> "${minutes}m ${seconds % 60}s"
            hours < 24 -> "${hours}h ${minutes % 60}m"
            else -> "${days}d ${hours % 24}h"
        }
    }

    /**
     * Get age of location in minutes
     */
    fun getAgeInMinutes(location: LocationData): Long {
        val ageMs = System.currentTimeMillis() - location.clientTimestamp
        return ageMs / (60 * 1000)
    }

    /**
     * Get age of location in seconds
     */
    fun getAgeInSeconds(location: LocationData): Long {
        val ageMs = System.currentTimeMillis() - location.clientTimestamp
        return ageMs / 1000
    }

    /**
     * Format interval description
     */
    fun getIntervalDescription(intervalMs: Long): String {
        val seconds = intervalMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            seconds < 60 -> "$seconds seconds"
            minutes < 60 -> "$minutes minutes"
            hours < 24 -> "$hours hours"
            else -> "${hours / 24} days"
        }
    }

    // ============================================================================
    // DATA CONVERSION (EXTENDED)
    // ============================================================================

    /**
     * Convert Android Location to LocationData (alias for clarity)
     */
    fun fromAndroidLocation(
        location: Location,
        userId: String?,
        batteryLevel: Int?,
        networkQuality: String?,
        movementStatus: String? = null
    ): LocationData {
        return toLocationData(location, userId, batteryLevel, networkQuality, movementStatus)
    }

    /**
     * Get quick location summary for logging
     */
    fun getLocationSummary(location: LocationData): String {
        return "Location(${formatCoordinates(location.latitude, location.longitude)}, " +
               "accuracy=${location.accuracy}m (${location.accuracyLevel}), " +
               "provider=${location.providerType})"
    }

    // ============================================================================
    // DEVICE INFORMATION (EXTENDED)
    // ============================================================================

    /**
     * Get device uptime in milliseconds
     */
    fun getDeviceUptime(): Long {
        return android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Get network type
     */
    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "none"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return "none"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"

            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return when (networkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> if (networkInfo?.isConnected == true) "other" else "none"
            }
        }
    }

    /**
     * Get app version
     */
    fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Get OS version
     */
    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    // ============================================================================
    // VALIDATION UTILITIES (PURE CHECKS)
    // ============================================================================

    /**
     * Validate accuracy value
     */
    fun isValidAccuracy(accuracy: Float?): Boolean {
        return accuracy != null && accuracy > 0
    }

    /**
     * Validate timestamp (not future, not too old)
     */
    fun isValidTimestamp(timestamp: Long, maxAgeMs: Long = 24 * 60 * 60 * 1000): Boolean {
        val now = System.currentTimeMillis()
        return timestamp in (now - maxAgeMs)..now
    }

    /**
     * Validate battery level
     */
    fun isValidBatteryLevel(level: Int?): Boolean {
        return level != null && level in 0..100
    }

    /**
     * Validate speed value (reasonable range)
     */
    fun isValidSpeed(speed: Float?): Boolean {
        return speed != null && speed >= 0 && speed < 200  // 200 m/s = ~720 km/h
    }

    /**
     * Validate complete LocationData object
     */
    fun validateLocationData(location: LocationData): Boolean {
        return isLocationValid(location.latitude, location.longitude) &&
               isValidTimestamp(location.clientTimestamp) &&
               (location.accuracy == null || isValidAccuracy(location.accuracy)) &&
               (location.batteryLevel == null || isValidBatteryLevel(location.batteryLevel)) &&
               (location.speed == null || isValidSpeed(location.speed))
    }

    // ============================================================================
    // FORMATTING UTILITIES (EXTENDED)
    // ============================================================================

    /**
     * Format accuracy with level
     */
    fun formatAccuracy(accuracy: Float?): String {
        return if (accuracy != null) {
            val level = getAccuracyLevel(accuracy)
            "±${accuracy.toInt()}m ($level)"
        } else {
            "Unknown"
        }
    }

    /**
     * Format speed for display
     */
    fun formatSpeed(speedMps: Float?): String {
        return if (speedMps != null) {
            val kmh = mpsToKmh(speedMps)
            "${kmh.toInt()} km/h"
        } else {
            "Unknown"
        }
    }

    /**
     * Format provider for display
     */
    fun formatProvider(provider: String): String {
        return getProviderDisplayName(provider)
    }

    /**
     * Format battery level
     */
    fun formatBatteryLevel(level: Int?): String {
        return if (level != null) {
            "$level%"
        } else {
            "Unknown"
        }
    }

    /**
     * Format location summary for display
     */
    fun formatLocationForDisplay(location: LocationData): String {
        return buildString {
            append(formatCoordinates(location.latitude, location.longitude))
            append("\n")
            append("Accuracy: ${formatAccuracy(location.accuracy)}")
            append("\n")
            append("Provider: ${formatProvider(location.providerType)}")
            location.speed?.let {
                append("\n")
                append("Speed: ${formatSpeed(it)}")
            }
            location.batteryLevel?.let {
                append("\n")
                append("Battery: ${formatBatteryLevel(it)}")
            }
        }
    }

    // ============================================================================
    // ERROR UTILITIES
    // ============================================================================

    /**
     * Get error message from error code
     */
    fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            LocationConstants.ERROR_NO_PERMISSION -> LocationConstants.MSG_NO_PERMISSION
            LocationConstants.ERROR_LOCATION_DISABLED -> LocationConstants.MSG_LOCATION_DISABLED
            LocationConstants.ERROR_PROVIDER_UNAVAILABLE -> LocationConstants.MSG_PROVIDER_UNAVAILABLE
            LocationConstants.ERROR_TIMEOUT -> LocationConstants.MSG_TIMEOUT
            LocationConstants.ERROR_NETWORK_FAILURE -> LocationConstants.MSG_NETWORK_FAILURE
            LocationConstants.ERROR_DATABASE_ERROR -> LocationConstants.MSG_DATABASE_ERROR
            LocationConstants.ERROR_SERVICE_CRASHED -> LocationConstants.MSG_SERVICE_CRASHED
            LocationConstants.ERROR_BATTERY_OPTIMIZED -> LocationConstants.MSG_BATTERY_OPTIMIZED
            LocationConstants.ERROR_SETTINGS_SYNC_FAILED -> LocationConstants.MSG_SETTINGS_SYNC_FAILED
            else -> "Unknown error"
        }
    }

    /**
     * Check if error is retryable
     */
    fun isRetryableError(errorCode: Int): Boolean {
        return when (errorCode) {
            LocationConstants.ERROR_TIMEOUT,
            LocationConstants.ERROR_NETWORK_FAILURE,
            LocationConstants.ERROR_PROVIDER_UNAVAILABLE,
            LocationConstants.ERROR_SETTINGS_SYNC_FAILED -> true
            else -> false
        }
    }

    // ============================================================================
    // UNIT CONVERSION UTILITIES
    // ============================================================================

    /**
     * Convert meters to kilometers
     */
    fun metersToKilometers(meters: Float): Float {
        return meters / 1000f
    }

    /**
     * Convert meters per second to kilometers per hour
     */
    fun mpsToKmh(mps: Float): Float {
        return mps * 3.6f
    }

    /**
     * Convert kilometers per hour to meters per second
     */
    fun kmhToMps(kmh: Float): Float {
        return kmh / 3.6f
    }

    /**
     * Convert seconds to milliseconds
     */
    fun secondsToMillis(seconds: Int): Long {
        return seconds * 1000L
    }

    /**
     * Convert milliseconds to seconds
     */
    fun millisToSeconds(millis: Long): Int {
        return (millis / 1000).toInt()
    }

    /**
     * Convert minutes to milliseconds
     */
    fun minutesToMillis(minutes: Int): Long {
        return minutes * 60 * 1000L
    }

    /**
     * Convert hours to milliseconds
     */
    fun hoursToMillis(hours: Int): Long {
        return hours * 60 * 60 * 1000L
    }

    // ============================================================================
    // DISTANCE & BEARING CALCULATIONS
    // ============================================================================

    /**
     * Calculate bearing between two points
     * @return Bearing in degrees (0-360)
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /**
     * Calculate bearing between two LocationData objects
     */
    fun calculateBearing(location1: LocationData, location2: LocationData): Float {
        return calculateBearing(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
    }

    /**
     * Get cardinal direction from bearing
     * @param bearing Bearing in degrees (0-360)
     * @return Cardinal direction (N, NE, E, SE, S, SW, W, NW)
     */
    fun getCardinalDirection(bearing: Float): String {
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "N"
            bearing < 67.5 -> "NE"
            bearing < 112.5 -> "E"
            bearing < 157.5 -> "SE"
            bearing < 202.5 -> "S"
            bearing < 247.5 -> "SW"
            bearing < 292.5 -> "W"
            bearing < 337.5 -> "NW"
            else -> "N"
        }
    }

    /**
     * Format distance for display
     */
    fun distanceToString(meters: Float): String {
        return when {
            meters < 1000 -> "${meters.toInt()}m"
            else -> {
                val km = metersToKilometers(meters)
                String.format("%.1fkm", km)
            }
        }
    }

    // ============================================================================
    // CONSTANT ACCESS HELPERS
    // ============================================================================

    /**
     * Get movement threshold
     */
    fun getMovementThreshold(): Float {
        return LocationConstants.MOVEMENT_THRESHOLD_METERS
    }

    /**
     * Get high speed threshold
     */
    fun getHighSpeedThreshold(): Float {
        return LocationConstants.HIGH_SPEED_THRESHOLD_MPS
    }

    /**
     * Get stationary timeout
     */
    fun getStationaryTimeout(): Long {
        return LocationConstants.STATIONARY_TIMEOUT_MS
    }
}

