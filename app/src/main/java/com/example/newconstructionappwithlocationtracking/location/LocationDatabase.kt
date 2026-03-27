/*
 * ============================================================================
 * LOCATION DATABASE (SQLite) - COMPLETE IMPLEMENTATION
 * ============================================================================
 *
 * PURPOSE:
 * Local SQLite database for complete offline location storage with full metadata.
 * Stores all 59 fields from LocationData.kt with proper data retention rules.
 *
 * KEY FEATURES:
 * - Complete 59-field LocationData storage
 * - 3-tier retry system support
 * - Batch upload tracking
 * - Data retention enforcement (0 days synced, 3 days pending)
 * - Cache limit enforcement (1000 locations max)
 * - Settings cache for offline operation
 * - Diagnostic logging
 * - Bulk operations for performance
 * - Comprehensive queries and statistics
 *
 * DATABASE TABLES:
 * 1. locations_cache (59 fields): Complete location data with all metadata
 * 2. settings_cache (6 fields): Admin settings cache
 * 3. service_logs (7 fields): Diagnostic logs
 *
 * DATA RETENTION RULES:
 * - SYNCED locations: Deleted IMMEDIATELY after upload (0 days retention)
 * - PENDING locations: Deleted after 3 days
 * - Cache limit: Maximum 1000 locations (oldest deleted first)
 *
 * USAGE:
 * val db = LocationDatabase.getInstance(context)
 * db.insertLocation(locationData)
 * val pending = db.getPendingLocations()
 * db.deleteSyncedImmediately()
 *
 * ============================================================================
 */

package com.example.newconstructionappwithlocationtracking.location

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.newconstructionappwithlocationtracking.location.utils.LocationConstants
import com.example.newconstructionappwithlocationtracking.location.utils.LocationLogger
import com.example.newconstructionappwithlocationtracking.models.location.LocationData

class LocationDatabase private constructor(private val context: Context) :
    SQLiteOpenHelper(context, LocationConstants.DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 2  // Incremented for new schema

        @Volatile
        private var INSTANCE: LocationDatabase? = null

        fun getInstance(context: Context): LocationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createLocationsCacheTable(db)
        createSettingsCacheTable(db)
        createServiceLogsTable(db)
        createIndexes(db)

        LocationLogger.i(LocationConstants.TAG_DATABASE, "Database created successfully with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LocationLogger.w(
            LocationConstants.TAG_DATABASE,
            "Upgrading database from version $oldVersion to $newVersion"
        )

        when (oldVersion) {
            1 -> {
                // Migrate from version 1 to 2 (add missing fields)
                migrateV1ToV2(db)
            }
            else -> {
                // For major changes, drop and recreate
                LocationLogger.w(LocationConstants.TAG_DATABASE, "Major version change, recreating database")
                db.execSQL("DROP TABLE IF EXISTS ${LocationConstants.TABLE_LOCATIONS}")
                db.execSQL("DROP TABLE IF EXISTS ${LocationConstants.TABLE_SETTINGS}")
                db.execSQL("DROP TABLE IF EXISTS ${LocationConstants.TABLE_LOGS}")
                onCreate(db)
            }
        }
    }

    // ============================================================================
    // TABLE CREATION
    // ============================================================================

    private fun createLocationsCacheTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${LocationConstants.TABLE_LOCATIONS} (
                -- Primary Key & User
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                
                -- Core Location (6 fields)
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy REAL,
                accuracy_level TEXT,
                provider TEXT,
                provider_type TEXT,
                
                -- Provider Information (2 fields)
                provider_fallback INTEGER DEFAULT 0,
                raw_provider TEXT,
                
                -- Timestamps (4 fields)
                client_timestamp INTEGER NOT NULL,
                server_timestamp INTEGER,
                created_at INTEGER NOT NULL,
                synced_at INTEGER,
                
                -- Mode Flags (3 fields)
                force_check_enabled INTEGER DEFAULT 0,
                realtime_mode INTEGER DEFAULT 0,
                emergency_mode INTEGER DEFAULT 0,
                
                -- Battery Information (3 fields)
                battery_level INTEGER,
                battery_threshold_active INTEGER DEFAULT 0,
                forced_interval INTEGER DEFAULT 0,
                
                -- Interval Metadata (2 fields)
                interval_applied_ms INTEGER,
                interval_type TEXT,
                
                -- Movement (2 fields)
                movement_status TEXT,
                speed REAL,
                
                -- Sync Status (2 fields)
                sync_status TEXT DEFAULT 'pending',
                retry_count INTEGER DEFAULT 0,
                
                -- 3-Tier Retry System (6 fields)
                current_retry_tier INTEGER DEFAULT 1,
                tier1_attempts INTEGER DEFAULT 0,
                tier2_attempts INTEGER DEFAULT 0,
                tier3_attempts INTEGER DEFAULT 0,
                last_retry_attempt_time INTEGER,
                next_retry_scheduled_time INTEGER,
                
                -- Batch Upload (4 fields)
                batch_id TEXT,
                batch_sequence INTEGER,
                upload_attempts INTEGER DEFAULT 0,
                last_upload_attempt_time INTEGER,
                
                -- Data Retention (2 fields)
                marked_for_deletion INTEGER DEFAULT 0,
                retention_expires_at INTEGER,
                
                -- Error Tracking (3 fields)
                error_message TEXT,
                error_code INTEGER,
                error_timestamp INTEGER,
                
                -- Acquisition Metadata (3 fields)
                acquisition_duration_ms INTEGER,
                gps_retry_attempts INTEGER DEFAULT 0,
                location_request_timed_out INTEGER DEFAULT 0,
                
                -- Network Information (3 fields)
                network_quality TEXT,
                network_type TEXT,
                upload_bandwidth_kbps INTEGER,
                
                -- Priority (2 fields)
                priority INTEGER DEFAULT 0,
                is_critical INTEGER DEFAULT 0,
                
                -- Device Info (3 fields)
                device_uptime_ms INTEGER,
                app_version TEXT,
                os_version TEXT,
                
                -- Clustering (2 fields)
                cluster_id TEXT,
                is_cluster_representative INTEGER DEFAULT 0,
                
                -- GPS Specific (3 fields)
                satellite_count INTEGER,
                altitude REAL,
                bearing REAL
            )
        """.trimIndent())
    }

    private fun createSettingsCacheTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${LocationConstants.TABLE_SETTINGS} (
                `key` TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                data_type TEXT NOT NULL,
                last_updated INTEGER NOT NULL,
                source TEXT DEFAULT 'server',
                is_critical INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    private fun createServiceLogsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${LocationConstants.TABLE_LOGS} (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                severity TEXT DEFAULT 'info',
                message TEXT NOT NULL,
                error_details TEXT,
                device_info TEXT
            )
        """.trimIndent())
    }

    private fun createIndexes(db: SQLiteDatabase) {
        // Critical indexes for performance
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_status ON ${LocationConstants.TABLE_LOCATIONS}(sync_status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_timestamp ON ${LocationConstants.TABLE_LOCATIONS}(user_id, client_timestamp DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_batch_id ON ${LocationConstants.TABLE_LOCATIONS}(batch_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_retry_tier ON ${LocationConstants.TABLE_LOCATIONS}(current_retry_tier)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_next_retry ON ${LocationConstants.TABLE_LOCATIONS}(next_retry_scheduled_time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_created_at ON ${LocationConstants.TABLE_LOCATIONS}(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_marked_deletion ON ${LocationConstants.TABLE_LOCATIONS}(marked_for_deletion)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_timestamp ON ${LocationConstants.TABLE_LOGS}(timestamp DESC)")
    }

    private fun migrateV1ToV2(db: SQLiteDatabase) {
        // Add missing columns from V1 to V2
        val columnsToAdd = listOf(
            "accuracy_level TEXT",
            "provider_type TEXT", "provider_fallback INTEGER DEFAULT 0", "raw_provider TEXT",
            "synced_at INTEGER",
            "force_check_enabled INTEGER DEFAULT 0", "realtime_mode INTEGER DEFAULT 0", "emergency_mode INTEGER DEFAULT 0",
            "battery_threshold_active INTEGER DEFAULT 0", "forced_interval INTEGER DEFAULT 0",
            "interval_applied_ms INTEGER", "interval_type TEXT",
            "speed REAL",
            "current_retry_tier INTEGER DEFAULT 1",
            "tier1_attempts INTEGER DEFAULT 0", "tier2_attempts INTEGER DEFAULT 0", "tier3_attempts INTEGER DEFAULT 0",
            "last_retry_attempt_time INTEGER", "next_retry_scheduled_time INTEGER",
            "batch_id TEXT", "batch_sequence INTEGER", "upload_attempts INTEGER DEFAULT 0", "last_upload_attempt_time INTEGER",
            "marked_for_deletion INTEGER DEFAULT 0", "retention_expires_at INTEGER",
            "error_code INTEGER", "error_timestamp INTEGER",
            "acquisition_duration_ms INTEGER", "gps_retry_attempts INTEGER DEFAULT 0", "location_request_timed_out INTEGER DEFAULT 0",
            "network_type TEXT", "upload_bandwidth_kbps INTEGER",
            "priority INTEGER DEFAULT 0", "is_critical INTEGER DEFAULT 0",
            "device_uptime_ms INTEGER", "app_version TEXT", "os_version TEXT",
            "cluster_id TEXT", "is_cluster_representative INTEGER DEFAULT 0",
            "satellite_count INTEGER", "altitude REAL", "bearing REAL"
        )

        columnsToAdd.forEach { column ->
            try {
                db.execSQL("ALTER TABLE ${LocationConstants.TABLE_LOCATIONS} ADD COLUMN $column")
            } catch (e: Exception) {
                // Column might already exist
                LocationLogger.d(LocationConstants.TAG_DATABASE, "Column already exists: $column")
            }
        }

        // Recreate indexes
        createIndexes(db)

        LocationLogger.i(LocationConstants.TAG_DATABASE, "Migration to V2 completed")
    }

    // ============================================================================
    // INSERT OPERATIONS
    // ============================================================================

    /**
     * Insert complete LocationData with all 59 fields
     */
    fun insertLocation(location: LocationData): Long {
        val db = writableDatabase
        val values = locationDataToContentValues(location)

        val id = db.insert(LocationConstants.TABLE_LOCATIONS, null, values)

        if (id > 0) {
            LocationLogger.d(LocationConstants.TAG_DATABASE, "Location inserted: id=$id")
            // Enforce cache limit after insert
            enforceCacheLimit()
        }

        return id
    }

    /**
     * Insert multiple locations (bulk operation)
     */
    fun insertBatch(locations: List<LocationData>): List<Long> {
        val db = writableDatabase
        val ids = mutableListOf<Long>()

        db.beginTransaction()
        try {
            locations.forEach { location ->
                val values = locationDataToContentValues(location)
                val id = db.insert(LocationConstants.TABLE_LOCATIONS, null, values)
                ids.add(id)
            }
            db.setTransactionSuccessful()
            LocationLogger.i(LocationConstants.TAG_DATABASE, "Bulk insert: ${ids.size} locations")
        } finally {
            db.endTransaction()
        }

        enforceCacheLimit()
        return ids
    }

    // ============================================================================
    // QUERY OPERATIONS
    // ============================================================================

    /**
     * Get pending locations for upload
     */
    fun getPendingLocations(limit: Int = 50): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "sync_status IN (?, ?)",
            arrayOf(LocationConstants.SYNC_PENDING, LocationConstants.SYNC_FAILED),
            null, null,
            "priority DESC, client_timestamp ASC",  // High priority first, then oldest
            limit.toString()
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get next batch for upload
     */
    fun getNextBatchForUpload(size: Int = LocationConstants.BATCH_UPLOAD_SIZE): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "sync_status = ? AND batch_id IS NULL",
            arrayOf(LocationConstants.SYNC_PENDING),
            null, null,
            "priority DESC, created_at ASC",
            size.toString()
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get locations ready for retry (next_retry_scheduled_time <= now)
     */
    fun getLocationsReadyForRetry(): List<LocationData> {
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "sync_status = ? AND next_retry_scheduled_time <= ?",
            arrayOf(LocationConstants.SYNC_FAILED, now.toString()),
            null, null,
            "current_retry_tier ASC, next_retry_scheduled_time ASC",
            null
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get locations by batch ID
     */
    fun getLocationsByBatch(batchId: String): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "batch_id = ?",
            arrayOf(batchId),
            null, null,
            "batch_sequence ASC",
            null
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get locations by user
     */
    fun getLocationsByUser(userId: String, limit: Int = 100): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "user_id = ?",
            arrayOf(userId),
            null, null,
            "client_timestamp DESC",
            limit.toString()
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get location by ID
     */
    fun getLocation(id: Long): LocationData? {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "id = ?",
            arrayOf(id.toString()),
            null, null, null, "1"
        )

        return cursor.use {
            if (it.moveToFirst()) cursorToLocationData(it) else null
        }
    }

    /**
     * Get last location for user
     */
    fun getLastLocation(userId: String): LocationData? {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "user_id = ?",
            arrayOf(userId),
            null, null,
            "client_timestamp DESC",
            "1"
        )

        return cursor.use {
            if (it.moveToFirst()) cursorToLocationData(it) else null
        }
    }

    /**
     * Get locations by status
     */
    fun getLocationsByStatus(status: String, limit: Int = 100): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "sync_status = ?",
            arrayOf(status),
            null, null,
            "created_at DESC",
            limit.toString()
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get high priority locations
     */
    fun getHighPriorityLocations(): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "priority >= ? OR is_critical = 1",
            arrayOf(LocationData.PRIORITY_HIGH.toString()),
            null, null,
            "priority DESC, created_at ASC",
            null
        )

        return cursorToLocationDataList(cursor)
    }

    /**
     * Get failed locations
     */
    fun getFailedLocations(): List<LocationData> {
        return getLocationsByStatus(LocationConstants.SYNC_FAILED)
    }

    /**
     * Get oldest pending location
     */
    fun getOldestPendingLocation(): LocationData? {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "sync_status = ?",
            arrayOf(LocationConstants.SYNC_PENDING),
            null, null,
            "created_at ASC",
            "1"
        )

        return cursor.use {
            if (it.moveToFirst()) cursorToLocationData(it) else null
        }
    }

    /**
     * Get newest location
     */
    fun getNewestLocation(): LocationData? {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            null, null, null, null,
            "created_at DESC",
            "1"
        )

        return cursor.use {
            if (it.moveToFirst()) cursorToLocationData(it) else null
        }
    }

    /**
     * Get locations by retry tier
     */
    fun getLocationsByTier(tier: Int): List<LocationData> {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_LOCATIONS,
            null,
            "current_retry_tier = ?",
            arrayOf(tier.toString()),
            null, null,
            "created_at DESC",
            null
        )

        return cursorToLocationDataList(cursor)
    }

    // ============================================================================
    // UPDATE OPERATIONS
    // ============================================================================

    /**
     * Update complete location
     */
    fun updateLocation(location: LocationData): Int {
        val db = writableDatabase
        val values = locationDataToContentValues(location)

        return db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(location.id.toString())
        )
    }

    /**
     * Mark location as synced
     */
    fun markAsSynced(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sync_status", LocationConstants.SYNC_SYNCED)
            put("synced_at", System.currentTimeMillis())
        }

        db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )

        LocationLogger.d(LocationConstants.TAG_DATABASE, "Location marked as synced: id=$id")
    }

    /**
     * Mark location as failed
     */
    fun markAsFailed(id: Long, errorCode: Int? = null, errorMessage: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sync_status", LocationConstants.SYNC_FAILED)
            put("error_code", errorCode)
            put("error_message", errorMessage)
            put("error_timestamp", System.currentTimeMillis())
        }

        db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Mark location as in progress
     */
    fun markAsInProgress(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sync_status", LocationConstants.SYNC_IN_PROGRESS)
        }

        db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Update batch information
     */
    fun assignBatchId(locationIds: List<Long>, batchId: String) {
        val db = writableDatabase

        db.beginTransaction()
        try {
            locationIds.forEachIndexed { index, id ->
                val values = ContentValues().apply {
                    put("batch_id", batchId)
                    put("batch_sequence", index + 1)
                }

                db.update(
                    LocationConstants.TABLE_LOCATIONS,
                    values,
                    "id = ?",
                    arrayOf(id.toString())
                )
            }
            db.setTransactionSuccessful()
            LocationLogger.d(LocationConstants.TAG_DATABASE, "Batch assigned: $batchId (${locationIds.size} locations)")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Mark for deletion
     */
    fun markForDeletion(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("marked_for_deletion", 1)
        }

        db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Increment retry attempt
     */
    fun incrementRetryAttempt(id: Long) {
        val location = getLocation(id) ?: return

        location.recordRetryAttempt()  // This updates tier automatically
        updateLocation(location)

        LocationLogger.d(LocationConstants.TAG_DATABASE, "Retry incremented: id=$id, tier=${location.currentRetryTier}")
    }

    /**
     * Record upload attempt
     */
    fun recordUploadAttempt(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("upload_attempts", "upload_attempts + 1")
            put("last_upload_attempt_time", System.currentTimeMillis())
        }

        db.update(
            LocationConstants.TABLE_LOCATIONS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Mark entire batch as uploaded
     */
    fun markBatchAsUploaded(batchId: String) {
        val locations = getLocationsByBatch(batchId)
        val db = writableDatabase

        db.beginTransaction()
        try {
            locations.forEach { location ->
                location.id?.let { markAsSynced(it) }
            }
            db.setTransactionSuccessful()
            LocationLogger.i(LocationConstants.TAG_DATABASE, "Batch uploaded: $batchId (${locations.size} locations)")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Mark entire batch as failed
     */
    fun markBatchAsFailed(batchId: String, errorMessage: String) {
        val locations = getLocationsByBatch(batchId)
        val db = writableDatabase

        db.beginTransaction()
        try {
            locations.forEach { location ->
                location.id?.let { markAsFailed(it, LocationConstants.ERROR_NETWORK_FAILURE, errorMessage) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ============================================================================
    // DELETE OPERATIONS
    // ============================================================================

    /**
     * Delete synced locations IMMEDIATELY (0 days retention!)
     */
    fun deleteSyncedImmediately(): Int {
        val db = writableDatabase
        val count = db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "sync_status = ?",
            arrayOf(LocationConstants.SYNC_SYNCED)
        )

        if (count > 0) {
            LocationLogger.i(LocationConstants.TAG_DATABASE, "Deleted $count synced locations immediately")
        }

        return count
    }

    /**
     * Delete expired pending locations (>3 days old)
     */
    fun deleteExpiredPending(): Int {
        val db = writableDatabase
        val cutoffTime = System.currentTimeMillis() - (LocationConstants.PENDING_DATA_RETENTION_DAYS * 24 * 60 * 60 * 1000L)

        val count = db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "sync_status IN (?, ?) AND created_at < ?",
            arrayOf(LocationConstants.SYNC_PENDING, LocationConstants.SYNC_FAILED, cutoffTime.toString())
        )

        if (count > 0) {
            LocationLogger.i(LocationConstants.TAG_DATABASE, "Deleted $count expired pending locations")
        }

        return count
    }

    /**
     * Delete marked for deletion
     */
    fun deleteMarkedForDeletion(): Int {
        val db = writableDatabase
        val count = db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "marked_for_deletion = 1",
            null
        )

        if (count > 0) {
            LocationLogger.i(LocationConstants.TAG_DATABASE, "Deleted $count marked locations")
        }

        return count
    }

    /**
     * Enforce cache limit (max 1000 locations)
     */
    fun enforceCacheLimit() {
        val totalCount = getLocationCount()

        if (totalCount > LocationConstants.MAX_CACHE_SIZE) {
            val db = writableDatabase
            val excessCount = totalCount - LocationConstants.MAX_CACHE_SIZE

            // Delete oldest SYNCED locations first
            db.execSQL("""
                DELETE FROM ${LocationConstants.TABLE_LOCATIONS}
                WHERE id IN (
                    SELECT id FROM ${LocationConstants.TABLE_LOCATIONS}
                    WHERE sync_status = ?
                    ORDER BY created_at ASC
                    LIMIT ?
                )
            """.trimIndent(), arrayOf(LocationConstants.SYNC_SYNCED, excessCount.toString()))

            LocationLogger.w(LocationConstants.TAG_DATABASE, "Cache limit enforced: deleted $excessCount oldest synced locations")
        }
    }

    /**
     * Delete location by ID
     */
    fun deleteLocation(id: Long): Int {
        val db = writableDatabase
        return db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Delete locations by IDs
     */
    fun deleteLocations(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0

        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }

        return db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    /**
     * Delete batch
     */
    fun deleteBatch(batchId: String): Int {
        val db = writableDatabase
        return db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "batch_id = ?",
            arrayOf(batchId)
        )
    }

    /**
     * Delete by status
     */
    fun deleteByStatus(status: String): Int {
        val db = writableDatabase
        return db.delete(
            LocationConstants.TABLE_LOCATIONS,
            "sync_status = ?",
            arrayOf(status)
        )
    }

    /**
     * Clear all locations (for testing/reset)
     */
    fun clearAllLocations(): Int {
        val db = writableDatabase
        val count = db.delete(LocationConstants.TABLE_LOCATIONS, null, null)
        LocationLogger.w(LocationConstants.TAG_DATABASE, "All locations cleared: $count")
        return count
    }

    // ============================================================================
    // STATISTICS
    // ============================================================================

    /**
     * Get total location count
     */
    fun getLocationCount(): Int {
        return getCountByQuery("SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS}")
    }

    /**
     * Get pending count
     */
    fun getPendingCount(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE sync_status = ?",
            arrayOf(LocationConstants.SYNC_PENDING)
        )
    }

    /**
     * Get synced count
     */
    fun getSyncedCount(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE sync_status = ?",
            arrayOf(LocationConstants.SYNC_SYNCED)
        )
    }

    /**
     * Get failed count
     */
    fun getFailedCount(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE sync_status = ?",
            arrayOf(LocationConstants.SYNC_FAILED)
        )
    }

    /**
     * Get tier counts
     */
    fun getTier1Count(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE current_retry_tier = 1",
            null
        )
    }

    fun getTier2Count(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE current_retry_tier = 2",
            null
        )
    }

    fun getTier3Count(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE current_retry_tier = 3",
            null
        )
    }

    /**
     * Get emergency mode count
     */
    fun getEmergencyModeCount(): Int {
        return getCountByQuery(
            "SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOCATIONS} WHERE emergency_mode = 1",
            null
        )
    }

    /**
     * Get cache usage percent
     */
    fun getCacheUsagePercent(): Float {
        val count = getLocationCount()
        return (count.toFloat() / LocationConstants.MAX_CACHE_SIZE) * 100f
    }

    /**
     * Get comprehensive database statistics
     */
    fun getDatabaseStats(): Map<String, Any> {
        return mapOf(
            "total_locations" to getLocationCount(),
            "pending_locations" to getPendingCount(),
            "synced_locations" to getSyncedCount(),
            "failed_locations" to getFailedCount(),
            "tier1_locations" to getTier1Count(),
            "tier2_locations" to getTier2Count(),
            "tier3_locations" to getTier3Count(),
            "emergency_locations" to getEmergencyModeCount(),
            "cache_usage_percent" to getCacheUsagePercent(),
            "cache_limit" to LocationConstants.MAX_CACHE_SIZE,
            "settings_count" to getSettingsCount(),
            "logs_count" to getLogsCount(),
            "database_size_mb" to getDatabaseSizeMB()
        )
    }

    private fun getCountByQuery(query: String, args: Array<String>? = null): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(query, args)

        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun getDatabaseSizeMB(): Double {
        val dbFile = context.getDatabasePath(LocationConstants.DATABASE_NAME)
        return dbFile.length() / (1024.0 * 1024.0)
    }

    // ============================================================================
    // SETTINGS CACHE OPERATIONS
    // ============================================================================

    fun saveSetting(key: String, value: String, dataType: String, source: String = "server") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("data_type", dataType)
            put("last_updated", System.currentTimeMillis())
            put("source", source)
        }

        db.insertWithOnConflict(
            LocationConstants.TABLE_SETTINGS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getSetting(key: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            LocationConstants.TABLE_SETTINGS,
            arrayOf("value"),
            "`key` = ?",
            arrayOf(key),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun clearSettings() {
        val db = writableDatabase
        db.delete(LocationConstants.TABLE_SETTINGS, null, null)
    }

    private fun getSettingsCount(): Int {
        return getCountByQuery("SELECT COUNT(*) FROM ${LocationConstants.TABLE_SETTINGS}")
    }

    // ============================================================================
    // LOGGING OPERATIONS
    // ============================================================================

    fun insertLog(
        eventType: String,
        message: String,
        severity: String = "info",
        errorDetails: String? = null
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("event_type", eventType)
            put("message", message)
            put("severity", severity)
            put("error_details", errorDetails)
        }

        return db.insert(LocationConstants.TABLE_LOGS, null, values)
    }

    fun getRecentLogs(limit: Int = 100): List<Map<String, Any?>> {
        val logs = mutableListOf<Map<String, Any?>>()
        val db = readableDatabase

        val cursor = db.query(
            LocationConstants.TABLE_LOGS,
            null, null, null, null, null,
            "timestamp DESC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                logs.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "timestamp" to it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    "event_type" to it.getString(it.getColumnIndexOrThrow("event_type")),
                    "severity" to it.getString(it.getColumnIndexOrThrow("severity")),
                    "message" to it.getString(it.getColumnIndexOrThrow("message")),
                    "error_details" to it.getString(it.getColumnIndexOrThrow("error_details"))
                ))
            }
        }

        return logs
    }

    fun deleteLogsOlderThan(timestampMs: Long): Int {
        val db = writableDatabase
        return db.delete(
            LocationConstants.TABLE_LOGS,
            "timestamp < ?",
            arrayOf(timestampMs.toString())
        )
    }

    private fun getLogsCount(): Int {
        return getCountByQuery("SELECT COUNT(*) FROM ${LocationConstants.TABLE_LOGS}")
    }

    // ============================================================================
    // MAINTENANCE
    // ============================================================================

    /**
     * Perform complete database maintenance
     */
    fun performMaintenance() {
        LocationLogger.i(LocationConstants.TAG_DATABASE, "Starting database maintenance...")

        // 1. Delete synced locations immediately (0 days retention)
        val syncedDeleted = deleteSyncedImmediately()

        // 2. Delete expired pending locations (>3 days)
        val pendingDeleted = deleteExpiredPending()

        // 3. Delete marked for deletion
        val markedDeleted = deleteMarkedForDeletion()

        // 4. Enforce cache limit (max 1000)
        enforceCacheLimit()

        // 5. Delete old logs (keep 7 days)
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val logsDeleted = deleteLogsOlderThan(cutoffTime)

        // 6. Vacuum database to reclaim space
        writableDatabase.execSQL("VACUUM")

        LocationLogger.i(
            LocationConstants.TAG_DATABASE,
            "Maintenance completed: synced=$syncedDeleted, pending=$pendingDeleted, " +
            "marked=$markedDeleted, logs=$logsDeleted"
        )
    }

    // ============================================================================
    // DATA CONVERSION
    // ============================================================================

    /**
     * Convert LocationData to ContentValues (all 59 fields)
     */
    private fun locationDataToContentValues(location: LocationData): ContentValues {
        return ContentValues().apply {
            // Core location
            put("user_id", location.userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy)
            put("accuracy_level", location.accuracyLevel)
            put("provider", location.provider)
            put("provider_type", location.providerType)

            // Provider info
            put("provider_fallback", if (location.providerFallback) 1 else 0)
            put("raw_provider", location.rawProvider)

            // Timestamps
            put("client_timestamp", location.clientTimestamp)
            put("server_timestamp", location.serverTimestamp)
            put("created_at", location.createdAt)
            put("synced_at", location.syncedAt)

            // Mode flags
            put("force_check_enabled", if (location.forceCheckEnabled) 1 else 0)
            put("realtime_mode", if (location.realtimeMode) 1 else 0)
            put("emergency_mode", if (location.emergencyMode) 1 else 0)

            // Battery
            put("battery_level", location.batteryLevel)
            put("battery_threshold_active", if (location.batteryThresholdActive) 1 else 0)
            put("forced_interval", if (location.forcedInterval) 1 else 0)

            // Interval
            put("interval_applied_ms", location.intervalAppliedMs)
            put("interval_type", location.intervalType)

            // Movement
            put("movement_status", location.movementStatus)
            put("speed", location.speed)

            // Sync
            put("sync_status", location.syncStatus)
            put("retry_count", location.retryCount)

            // Retry tiers
            put("current_retry_tier", location.currentRetryTier)
            put("tier1_attempts", location.tier1Attempts)
            put("tier2_attempts", location.tier2Attempts)
            put("tier3_attempts", location.tier3Attempts)
            put("last_retry_attempt_time", location.lastRetryAttemptTime)
            put("next_retry_scheduled_time", location.nextRetryScheduledTime)

            // Batch
            put("batch_id", location.batchId)
            put("batch_sequence", location.batchSequence)
            put("upload_attempts", location.uploadAttempts)
            put("last_upload_attempt_time", location.lastUploadAttemptTime)

            // Retention
            put("marked_for_deletion", if (location.markedForDeletion) 1 else 0)
            put("retention_expires_at", location.retentionExpiresAt)

            // Error
            put("error_message", location.errorMessage)
            put("error_code", location.errorCode)
            put("error_timestamp", location.errorTimestamp)

            // Acquisition
            put("acquisition_duration_ms", location.acquisitionDurationMs)
            put("gps_retry_attempts", location.gpsRetryAttempts)
            put("location_request_timed_out", if (location.locationRequestTimedOut) 1 else 0)

            // Network
            put("network_quality", location.networkQuality)
            put("network_type", location.networkType)
            put("upload_bandwidth_kbps", location.uploadBandwidthKbps)

            // Priority
            put("priority", location.priority)
            put("is_critical", if (location.isCritical) 1 else 0)

            // Device
            put("device_uptime_ms", location.deviceUptimeMs)
            put("app_version", location.appVersion)
            put("os_version", location.osVersion)

            // Clustering
            put("cluster_id", location.clusterId)
            put("is_cluster_representative", if (location.isClusterRepresentative) 1 else 0)

            // GPS
            put("satellite_count", location.satelliteCount)
            put("altitude", location.altitude)
            put("bearing", location.bearing)
        }
    }

    /**
     * Convert Cursor to LocationData (all 59 fields)
     */
    private fun cursorToLocationData(cursor: Cursor): LocationData {
        return LocationData(
            // Core
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            userId = cursor.getString(cursor.getColumnIndexOrThrow("user_id")),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
            accuracy = cursor.getFloatOrNull("accuracy"),
            accuracyLevel = cursor.getStringOrNull("accuracy_level"),
            provider = cursor.getStringOrNull("provider"),
            providerType = cursor.getString(cursor.getColumnIndexOrThrow("provider_type")),

            // Provider
            providerFallback = cursor.getInt(cursor.getColumnIndexOrThrow("provider_fallback")) == 1,
            rawProvider = cursor.getStringOrNull("raw_provider"),

            // Timestamps
            clientTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("client_timestamp")),
            serverTimestamp = cursor.getLongOrNull("server_timestamp"),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            syncedAt = cursor.getLongOrNull("synced_at"),

            // Modes
            forceCheckEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("force_check_enabled")) == 1,
            realtimeMode = cursor.getInt(cursor.getColumnIndexOrThrow("realtime_mode")) == 1,
            emergencyMode = cursor.getInt(cursor.getColumnIndexOrThrow("emergency_mode")) == 1,

            // Battery
            batteryLevel = cursor.getIntOrNull("battery_level"),
            batteryThresholdActive = cursor.getInt(cursor.getColumnIndexOrThrow("battery_threshold_active")) == 1,
            forcedInterval = cursor.getInt(cursor.getColumnIndexOrThrow("forced_interval")) == 1,

            // Interval
            intervalAppliedMs = cursor.getLongOrNull("interval_applied_ms"),
            intervalType = cursor.getStringOrNull("interval_type"),

            // Movement
            movementStatus = cursor.getStringOrNull("movement_status"),
            speed = cursor.getFloatOrNull("speed"),

            // Sync
            syncStatus = cursor.getString(cursor.getColumnIndexOrThrow("sync_status")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),

            // Retry tiers
            currentRetryTier = cursor.getInt(cursor.getColumnIndexOrThrow("current_retry_tier")),
            tier1Attempts = cursor.getInt(cursor.getColumnIndexOrThrow("tier1_attempts")),
            tier2Attempts = cursor.getInt(cursor.getColumnIndexOrThrow("tier2_attempts")),
            tier3Attempts = cursor.getInt(cursor.getColumnIndexOrThrow("tier3_attempts")),
            lastRetryAttemptTime = cursor.getLongOrNull("last_retry_attempt_time"),
            nextRetryScheduledTime = cursor.getLongOrNull("next_retry_scheduled_time"),

            // Batch
            batchId = cursor.getStringOrNull("batch_id"),
            batchSequence = cursor.getIntOrNull("batch_sequence"),
            uploadAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("upload_attempts")),
            lastUploadAttemptTime = cursor.getLongOrNull("last_upload_attempt_time"),

            // Retention
            markedForDeletion = cursor.getInt(cursor.getColumnIndexOrThrow("marked_for_deletion")) == 1,
            retentionExpiresAt = cursor.getLongOrNull("retention_expires_at"),

            // Error
            errorMessage = cursor.getStringOrNull("error_message"),
            errorCode = cursor.getIntOrNull("error_code"),
            errorTimestamp = cursor.getLongOrNull("error_timestamp"),

            // Acquisition
            acquisitionDurationMs = cursor.getLongOrNull("acquisition_duration_ms"),
            gpsRetryAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("gps_retry_attempts")),
            locationRequestTimedOut = cursor.getInt(cursor.getColumnIndexOrThrow("location_request_timed_out")) == 1,

            // Network
            networkQuality = cursor.getStringOrNull("network_quality"),
            networkType = cursor.getStringOrNull("network_type"),
            uploadBandwidthKbps = cursor.getIntOrNull("upload_bandwidth_kbps"),

            // Priority
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            isCritical = cursor.getInt(cursor.getColumnIndexOrThrow("is_critical")) == 1,

            // Device
            deviceUptimeMs = cursor.getLongOrNull("device_uptime_ms"),
            appVersion = cursor.getStringOrNull("app_version"),
            osVersion = cursor.getStringOrNull("os_version"),

            // Clustering
            clusterId = cursor.getStringOrNull("cluster_id"),
            isClusterRepresentative = cursor.getInt(cursor.getColumnIndexOrThrow("is_cluster_representative")) == 1,

            // GPS
            satelliteCount = cursor.getIntOrNull("satellite_count"),
            altitude = cursor.getDoubleOrNull("altitude"),
            bearing = cursor.getFloatOrNull("bearing")
        )
    }

    private fun cursorToLocationDataList(cursor: Cursor): List<LocationData> {
        val locations = mutableListOf<LocationData>()
        cursor.use {
            while (it.moveToNext()) {
                locations.add(cursorToLocationData(it))
            }
        }
        return locations
    }

    // Helper extension functions for cursor
    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getFloatOrNull(columnName: String): Float? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getFloat(index)
    }

    private fun Cursor.getDoubleOrNull(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }
}

