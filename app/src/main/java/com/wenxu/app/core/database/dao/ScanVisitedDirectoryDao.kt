package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wenxu.app.core.database.entity.ScanVisitedDirectoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanVisitedDirectoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(directories: List<ScanVisitedDirectoryEntity>)

    @Query("SELECT COUNT(*) FROM scan_visited_directories WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM scan_visited_directories WHERE sessionId = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>
}
