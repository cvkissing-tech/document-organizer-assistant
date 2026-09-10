package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity

@Dao
interface PendingTrashTargetStateDao {
    @Upsert
    suspend fun upsert(state: PendingTrashTargetStateEntity)

    @Query("SELECT * FROM pending_trash_target_states WHERE operationId = :operationId ORDER BY targetId")
    suspend fun findForOperation(operationId: Long): List<PendingTrashTargetStateEntity>

    @Query("DELETE FROM pending_trash_target_states WHERE operationId = :operationId AND targetId = :targetId")
    suspend fun deleteTarget(operationId: Long, targetId: Long)
}
