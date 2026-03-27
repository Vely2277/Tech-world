/**
 * ═══════════════════════════════════════════════════════════════════════════════════
 * LOCATION LOGGER - CENTRALIZED LOGGING SYSTEM
 * ═══════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Centralized, structured logging system for all location tracking components.
 * Provides consistent log format, log levels, and audit trail capabilities.
 *
 * CORE RESPONSIBILITIES:
 * 1. Structured Logging (consistent format across all components)
 * 2. Log Level Management (DEBUG, INFO, WARNING, ERROR)
 * 3. Audit Trail (critical events logged for compliance/debugging)
 * 4. Log Rotation (prevent unbounded disk usage)
 * 5. Performance Monitoring (track operation durations)
 * 6. Error Tracking (capture stack traces)
 *
 * KEY FEATURES:
 * - Thread-safe logging
 * - Automatic timestamp inclusion
 * - Structured log entries (JSON-ready)
 * - Log level filtering
 * - Performance metrics
 * - Audit trail for critical events
 * - Log rotation & size limits
 *
 * ═══════════════════════════════════════════════════════════════════════════════════
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*

/**
 * LocationLogger - Centralized logging for location tracking
 */
class LocationLogger(private val context: Context) {

    companion object {
        private const val LOG_TAG = "LocationTracking"

        // Log levels
        const val LEVEL_DEBUG = 0
        const val LEVEL_INFO = 1
        const val LEVEL_WARNING = 2
        const val LEVEL_ERROR = 3

        // Log file settings
        private const val MAX_LOG_SIZE = 1024 * 1024 // 1 MB
        private const val MAX_LOG_FILES = 3

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentLogLevel = LEVEL_INFO

    init {
        // Start log processor
        startLogProcessor()
    }

    /**
     * Log debug message
     */
    fun logDebug(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_DEBUG) {
            log(LEVEL_DEBUG, tag, message, null)
        }
    }

    /**
     * Log info message
     */
    fun logInfo(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_INFO) {
            log(LEVEL_INFO, tag, message, null)
        }
    }

    /**
     * Log warning message
     */
    fun logWarning(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_WARNING) {
            log(LEVEL_WARNING, tag, message, null)
        }
    }

    /**
     * Log error message
     */
    fun logError(tag: String, message: String, throwable: Exception? = null) {
        log(LEVEL_ERROR, tag, message, throwable)
    }

    /**
     * Log audit event (critical events that must be tracked)
     */
    fun logAudit(event: String, details: Map<String, Any> = emptyMap()) {
        val message = "AUDIT: $event | ${details.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        log(LEVEL_INFO, "AUDIT", message, null)
    }

    /**
     * Measure and log operation duration
     */
    inline fun <T> measureAndLog(tag: String, operation: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val duration = System.currentTimeMillis() - startTime
            logInfo(tag, "$operation completed in ${duration}ms")
        }
    }

    /**
     * Core logging function
     */
    private fun log(level: Int, tag: String, message: String, throwable: Exception?) {
        val timestamp = System.currentTimeMillis()
        val formattedTimestamp = dateFormat.format(Date(timestamp))

        // Log to Logcat
        val fullMessage = "[$formattedTimestamp] $message"
        when (level) {
            LEVEL_DEBUG -> Log.d(LOG_TAG, "[$tag] $fullMessage")
            LEVEL_INFO -> Log.i(LOG_TAG, "[$tag] $fullMessage")
            LEVEL_WARNING -> Log.w(LOG_TAG, "[$tag] $fullMessage")
            LEVEL_ERROR -> {
                if (throwable != null) {
                    Log.e(LOG_TAG, "[$tag] $fullMessage", throwable)
                } else {
                    Log.e(LOG_TAG, "[$tag] $fullMessage")
                }
            }
        }

        // Queue for file logging
        val logEntry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        logQueue.offer(logEntry)
    }

    /**
     * Start background log processor
     */
    private fun startLogProcessor() {
        loggerScope.launch {
            while (isActive) {
                try {
                    processLogQueue()
                    delay(5000) // Process every 5 seconds
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error processing log queue", e)
                }
            }
        }
    }

    /**
     * Process queued logs and write to file
     */
    private suspend fun processLogQueue() {
        val entries = mutableListOf<LogEntry>()

        // Drain queue
        while (logQueue.isNotEmpty()) {
            logQueue.poll()?.let { entries.add(it) }
        }

        if (entries.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                writeLogsToFile(entries)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to write logs to file", e)
            }
        }
    }

    /**
     * Write logs to file with rotation
     */
    private fun writeLogsToFile(entries: List<LogEntry>) {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val logFile = File(logDir, "location_tracking.log")

        // Check if rotation needed
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            rotateLogFiles(logDir)
        }

        // Append logs
        logFile.appendText(entries.joinToString("\n") { it.format() } + "\n")
    }

    /**
     * Rotate log files
     */
    private fun rotateLogFiles(logDir: File) {
        try {
            // Delete oldest log if we have too many
            val logFiles = logDir.listFiles { file -> file.name.startsWith("location_tracking") }
                ?.sortedByDescending { it.lastModified() }

            if (logFiles != null && logFiles.size >= MAX_LOG_FILES) {
                logFiles.last().delete()
            }

            // Rename current log
            val currentLog = File(logDir, "location_tracking.log")
            if (currentLog.exists()) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val archivedLog = File(logDir, "location_tracking_$timestamp.log")
                currentLog.renameTo(archivedLog)
            }

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to rotate log files", e)
        }
    }

    /**
     * Get log file for debugging
     */
    fun getLogFile(): File {
        val logDir = File(context.filesDir, "logs")
        return File(logDir, "location_tracking.log")
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        val logDir = File(context.filesDir, "logs")
        logDir.listFiles()?.forEach { it.delete() }
        logInfo("LocationLogger", "All logs cleared")
    }

    /**
     * Set log level
     */
    fun setLogLevel(level: Int) {
        currentLogLevel = level
        logInfo("LocationLogger", "Log level set to $level")
    }
}

/**
 * Log entry data class
 */
private data class LogEntry(
    val timestamp: Long,
    val level: Int,
    val tag: String,
    val message: String,
    val throwable: Exception?
) {
    fun format(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val levelStr = when (level) {
            LocationLogger.LEVEL_DEBUG -> "DEBUG"
            LocationLogger.LEVEL_INFO -> "INFO"
            LocationLogger.LEVEL_WARNING -> "WARN"
            LocationLogger.LEVEL_ERROR -> "ERROR"
            else -> "UNKNOWN"
        }

        val base = "${dateFormat.format(Date(timestamp))} [$levelStr] [$tag] $message"

        return if (throwable != null) {
            "$base\n${throwable.stackTraceToString()}"
        } else {
            base
        }
    }
}

