package com.wenxu.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wenxu.app.core.trash.TrashFailureReason

enum class PendingTrashAction {
    TRASH,
    RESTORE,
    DELETE_FOREVER,
}

enum class PendingTrashTargetKind {
    DOCUMENT,
    TRASH_RECORD,
}

enum class PendingTrashStatus {
    AWAITING_CONFIRMATION,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
}

data class PendingTrashSeedSuccess(
    val targetId: Long,
    val documentId: Long,
    val trashId: Long? = null,
)

data class PendingTrashSeedFailure(
    val targetId: Long,
    val reason: TrashFailureReason,
)

@Entity(
    tableName = "pending_trash_operations",
    indices = [Index("status"), Index("updatedAt")],
)
data class PendingTrashOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: PendingTrashAction,
    val targetKind: PendingTrashTargetKind,
    val targetIds: Set<Long>,
    @ColumnInfo(defaultValue = "'[]'") val eligibleTargetIds: Set<Long> = emptySet(),
    @ColumnInfo(defaultValue = "'[]'") val seedSuccesses: List<PendingTrashSeedSuccess> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val seedFailures: List<PendingTrashSeedFailure> = emptyList(),
    val fallbackParentUri: String? = null,
    val status: PendingTrashStatus,
    val createdAt: Long,
    val updatedAt: Long,
)
