package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.model.SourcePermissionState
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: ScanSourceEntity): Long

    @Query("SELECT * FROM scan_sources ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ScanSourceEntity>>

    @Query("SELECT * FROM scan_sources ORDER BY sortOrder, id")
    suspend fun listAll(): List<ScanSourceEntity>

    @Query("SELECT * FROM scan_sources WHERE id = :id")
    suspend fun findById(id: Long): ScanSourceEntity?

    @Query("SELECT * FROM scan_sources WHERE treeUri = :treeUri")
    suspend fun findByTreeUri(treeUri: String): ScanSourceEntity?

    @Query("UPDATE scan_sources SET permissionState = :state WHERE id = :sourceId")
    suspend fun updatePermissionState(sourceId: Long, state: SourcePermissionState)

    @Query("UPDATE scan_sources SET lastScanAt = :scannedAt WHERE id = :sourceId")
    suspend fun updateLastScan(sourceId: Long, scannedAt: Long)

    @Query("SELECT COUNT(*) FROM documents WHERE sourceId = :sourceId AND indexStatus = 'TRASHED'")
    suspend fun countTrashedDocuments(sourceId: Long): Int

    @Delete
    suspend fun delete(source: ScanSourceEntity)
}
