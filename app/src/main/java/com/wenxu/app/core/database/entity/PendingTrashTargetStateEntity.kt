package com.wenxu.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

enum class PendingTrashTargetStage { STARTED, RESTORED, COMPENSATED, DELETED }

@Entity(
    tableName = "pending_trash_target_states",
    primaryKeys = ["operationId", "targetId"],
    foreignKeys = [ForeignKey(
        entity = PendingTrashOperationEntity::class,
        parentColumns = ["id"],
        childColumns = ["operationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PendingTrashTargetStateEntity(
    val operationId: Long,
    val targetId: Long,
    val stage: PendingTrashTargetStage,
    val documentUri: String,
    val parentUri: String,
)
