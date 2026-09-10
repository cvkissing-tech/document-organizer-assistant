package com.wenxu.app.core.trash

import android.content.IntentSender
import com.wenxu.app.core.database.entity.PendingTrashAction

enum class TrashFailureReason {
    NOT_FOUND, INACTIVE, SOURCE_REMOVED, UNSUPPORTED, PROVIDER_REJECTED,
    VERIFICATION_FAILED, DATABASE_FAILED,
}

data class TrashFailure(val targetId: Long, val reason: TrashFailureReason)

data class TrashSuccess(val targetId: Long, val documentId: Long, val trashId: Long? = null)

data class SystemConfirmationRequest(
    val operationId: Long,
    val intentSender: IntentSender,
    val action: PendingTrashAction,
    val targetCount: Int,
)

sealed interface TrashOperationResult {
    data class Completed(
        val successes: List<TrashSuccess>,
        val failures: List<TrashFailure>,
    ) : TrashOperationResult

    data class RequiresConfirmation(val request: SystemConfirmationRequest) : TrashOperationResult

    data object Cancelled : TrashOperationResult
}
