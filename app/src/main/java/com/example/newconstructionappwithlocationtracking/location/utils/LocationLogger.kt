/*
 * ============================================================================
 * LOCATION LOGGER
 * ============================================================================
 *
 * PURPOSE:
 * Centralized logging system for location tracking with severity levels,
 * silent operation, and remote error reporting capabilities.
 *
 * KEY FEATURES:
 * - Multiple log levels (DEBUG, INFO, WARNING, ERROR, CRITICAL)
 * - Silent operation (no user-facing logs)
 * - Database persistence for debugging
 * - Remote error reporting to backend
 * - Log rotation and cleanup
 * - Performance monitoring
 *
 * USAGE:
 * LocationLogger.d(TAG, "Debug message")
 * LocationLogger.e(TAG, "Error occurred", exception)
 * LocationLogger.logToDatabase(context, event)
 *
 * LOG LEVELS:
 * - DEBUG: Development debugging information
 * - INFO: General informational messages
 * - WARNING: Warning conditions that should be monitored
 * - ERROR: Error conditions that need attention
 * - CRITICAL: Critical failures requiring immediate action
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location.utils

import android.content.Context
import android.util.Log
import com.example.newconstructionappwithlocationtracking.location.LocationDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

object LocationLogger {

    // Log levels
    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARNING = 2
    const val LEVEL_ERROR = 3
    const val LEVEL_CRITICAL = 4

    // Current log level (set to INFO for production, DEBUG for development)
    private var currentLogLevel = LEVEL_INFO

    // Enable/disable database logging
    private var databaseLoggingEnabled = true

    // Enable/disable remote logging
    private var remoteLoggingEnabled = false

    /**
     * Set current log level
     */
    fun setLogLevel(level: Int) {
        currentLogLevel = level
    }

    /**
     * Enable or disable database logging
     */
    fun setDatabaseLogging(enabled: Boolean) {
        databaseLoggingEnabled = enabled
    }

    /**
     * Enable or disable remote logging
     */
    fun setRemoteLogging(enabled: Boolean) {
        remoteLoggingEnabled = enabled
    }

    /**
     * Debug log
     */
    fun d(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * Info log
     */
    fun i(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_INFO) {
            Log.i(tag, message)
        }
    }

    /**
     * Warning log
     */
    fun w(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_WARNING) {
            Log.w(tag, message)
        }
    }

    /**
     * Error log
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (currentLogLevel <= LEVEL_ERROR) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

    /**
     * Critical error log
     */
    fun critical(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, "CRITICAL: $message", throwable)
    }

    /**
     * Log event to database
     */
    fun logToDatabase(
        context: Context,
        eventType: String,
        message: String,
        severity: String = "info",
        errorDetails: String? = null
    ) {
        if (!databaseLoggingEnabled) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = LocationDatabase.getInstance(context)
                database.insertLog(eventType, message, severity, errorDetails)
            } catch (e: Exception) {
                Log.e(LocationConstants.TAG_DATABASE, "Failed to log to database", e)
            }
        }
    }

    /**
     * Log service event
     */
    fun logServiceEvent(
        context: Context,
        serviceName: String,
        event: String,
        details: String? = null
    ) {
        val message = if (details != null) {
            "$serviceName: $event - $details"
        } else {
            "$serviceName: $event"
        }

        i(serviceName, message)
        logToDatabase(context, "service_event", message, "info")
    }

    /**
     * Log location update
     */
    fun logLocationUpdate(
        context: Context,
        latitude: Double,
        longitude: Double,
        accuracy: Float?,
        provider: String?
    ) {
        val message = "Location updated: (${String.format("%.6f", latitude)}, " +
                     "${String.format("%.6f", longitude)}) " +
                     "Accuracy: ${accuracy?.let { "${it}m" } ?: "unknown"} " +
                     "Provider: ${provider ?: "unknown"}"

        d(LocationConstants.TAG_SERVICE, message)
        logToDatabase(context, "location_update", message, "info")
    }

    /**
     * Log error with exception
     */
    fun logError(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        e(tag, message, throwable)

        val errorDetails = throwable?.let { getStackTraceString(it) }
        logToDatabase(context, "error", message, "error", errorDetails)

        // Optionally report to remote logging service
        if (remoteLoggingEnabled && throwable != null) {
            reportErrorRemotely(context, tag, message, throwable)
        }
    }

    /**
     * Log critical error
     */
    fun logCriticalError(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        critical(tag, message, throwable)

        val errorDetails = throwable?.let { getStackTraceString(it) }
        logToDatabase(context, "critical_error", message, "critical", errorDetails)

        // Always report critical errors remotely
        if (throwable != null) {
            reportErrorRemotely(context, tag, message, throwable)
        }
    }

    /**
     * Log performance metric
     */
    fun logPerformance(
        context: Context,
        operation: String,
        durationMs: Long,
        success: Boolean = true
    ) {
        val message = "Performance: $operation took ${durationMs}ms - " +
                     "${if (success) "SUCCESS" else "FAILED"}"

        d(LocationConstants.TAG_MANAGER, message)

        // Only log slow operations to database
        if (durationMs > 5000) {
            logToDatabase(context, "performance", message, "warning")
        }
    }

    /**
     * Log settings change
     */
    fun logSettingsChange(
        context: Context,
        settingName: String,
        oldValue: Any?,
        newValue: Any?
    ) {
        val message = "Setting changed: $settingName from $oldValue to $newValue"
        i(LocationConstants.TAG_SETTINGS, message)
        logToDatabase(context, "settings_change", message, "info")
    }

    /**
     * Log sync event
     */
    fun logSyncEvent(
        context: Context,
        itemCount: Int,
        success: Boolean,
        errorMessage: String? = null
    ) {
        val message = if (success) {
            "Successfully synced $itemCount location(s)"
        } else {
            "Failed to sync $itemCount location(s): $errorMessage"
        }

        if (success) {
            i(LocationConstants.TAG_SYNC, message)
            logToDatabase(context, "sync_success", message, "info")
        } else {
            e(LocationConstants.TAG_SYNC, message)
            logToDatabase(context, "sync_failed", message, "error", errorMessage)
        }
    }

    /**
     * Log recovery action
     */
    fun logRecoveryAction(
        context: Context,
        recoveryLevel: Int,
        reason: String,
        success: Boolean
    ) {
        val levelName = when (recoveryLevel) {
            LocationConstants.RECOVERY_GENTLE_RESTART -> "GENTLE_RESTART"
            LocationConstants.RECOVERY_FORCE_RESTART -> "FORCE_RESTART"
            LocationConstants.RECOVERY_FULL_RESET -> "FULL_RESET"
            LocationConstants.RECOVERY_EMERGENCY_MODE -> "EMERGENCY_MODE"
            else -> "UNKNOWN"
        }

        val message = "Recovery action ($levelName): $reason - " +
                     "${if (success) "SUCCESS" else "FAILED"}"

        w(LocationConstants.TAG_WATCHDOG, message)
        logToDatabase(context, "recovery_action", message, "warning", reason)
    }

    /**
     * Get stack trace as string
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    /**
     * Report error remotely (stub for future implementation)
     */
    private fun reportErrorRemotely(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // TODO: Implement remote error reporting to backend
                // This would send critical errors to the backend for monitoring
                val errorReport = JSONObject().apply {
                    put("tag", tag)
                    put("message", message)
                    put("stackTrace", getStackTraceString(throwable))
                    put("timestamp", System.currentTimeMillis())
                    put("deviceInfo", getDeviceInfo())
                }

                // Send to backend (implementation pending)
                d(LocationConstants.TAG_MANAGER, "Error report prepared: ${errorReport.toString()}")

            } catch (e: Exception) {
                e(LocationConstants.TAG_MANAGER, "Failed to report error remotely", e)
            }
        }
    }

    /**
     * Get device information for error reports
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("device", android.os.Build.DEVICE)
            put("version", android.os.Build.VERSION.RELEASE)
        }
    }

    /**
     * Clean old logs from database
     */
    fun cleanOldLogs(context: Context, daysToKeep: Int = 7) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = LocationDatabase.getInstance(context)
                val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
                database.deleteLogsOlderThan(cutoffTime)
                i(LocationConstants.TAG_DATABASE, "Cleaned logs older than $daysToKeep days")
            } catch (e: Exception) {
                e(LocationConstants.TAG_DATABASE, "Failed to clean old logs", e)
            }
        }
    }
}

