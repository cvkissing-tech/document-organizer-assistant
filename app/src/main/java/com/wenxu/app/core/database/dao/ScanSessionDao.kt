package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.ScanSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Upsert
    suspend fun upsert(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions ORDER BY updatedAt DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun latest(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun findById(sessionId: String): ScanSessionEntity?

    @Query(
        """
        SELECT * FROM scan_sessions
        WHERE sourceId = :sourceId
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun activeForSource(sourceId: Long): ScanSessionEntity?
}
