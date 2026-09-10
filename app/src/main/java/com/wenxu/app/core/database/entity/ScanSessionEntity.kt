package com.wenxu.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ScanPhase {
    DISCOVERING,
    INDEXING,
    FINALIZING,
    COMPLETE,
}

enum class ScanSessionStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED,
}

@Entity(
    tableName = "scan_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ScanSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["status"]),
    ],
)
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val sourceId: Long,
    val scanId: String,
    val status: ScanSessionStatus,
    val phase: ScanPhase,
    val cursorId: Long?,
    val checkedFiles: Int,
    val discoveredDocuments: Int,
    val failedFiles: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
)
