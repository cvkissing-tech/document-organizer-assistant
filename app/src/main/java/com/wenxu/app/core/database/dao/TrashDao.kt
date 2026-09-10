package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.wenxu.app.core.database.entity.TrashCategorySnapshotEntity
import com.wenxu.app.core.database.entity.TrashRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {
    @Insert
    suspend fun insert(record: TrashRecordEntity): Long

    @Insert
    suspend fun insertCategorySnapshots(snapshots: List<TrashCategorySnapshotEntity>)

    @Query("SELECT * FROM trash_records ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashRecordEntity>>

    @Query("SELECT * FROM trash_records WHERE documentId = :documentId")
    suspend fun findByDocumentId(documentId: Long): TrashRecordEntity?

    @Query("SELECT * FROM trash_records WHERE id = :trashId")
    suspend fun findById(trashId: Long): TrashRecordEntity?

    @Query("SELECT * FROM trash_records WHERE expiresAt <= :now AND storageKind = 'APP_TRASH'")
    suspend fun findExpired(now: Long): List<TrashRecordEntity>

    @Query(
        "UPDATE trash_records SET trashedUri = :uri, trashedParentUri = :parentUri " +
            "WHERE id = :trashId AND storageKind = 'APP_TRASH'",
    )
    suspend fun updateAppTrashLocation(trashId: Long, uri: String, parentUri: String): Int

    @Delete
    suspend fun delete(record: TrashRecordEntity)
}
