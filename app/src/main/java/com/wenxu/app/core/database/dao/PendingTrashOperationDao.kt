package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashStatus

@Dao
interface PendingTrashOperationDao {
    @Insert
    suspend fun insert(operation: PendingTrashOperationEntity): Long

    @Query("SELECT * FROM pending_trash_operations WHERE id = :id")
    suspend fun findById(id: Long): PendingTrashOperationEntity?

    @Query(
        "SELECT * FROM pending_trash_operations " +
            "WHERE status IN ('AWAITING_CONFIRMATION','CONFIRMED','CANCELLED') ORDER BY createdAt",
    )
    suspend fun findUnfinished(): List<PendingTrashOperationEntity>

    @Update
    suspend fun update(operation: PendingTrashOperationEntity): Int

    @Query(
        "UPDATE pending_trash_operations SET status = :status, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateStatus(id: Long, status: PendingTrashStatus, updatedAt: Long): Int

    @Query("DELETE FROM pending_trash_operations WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
