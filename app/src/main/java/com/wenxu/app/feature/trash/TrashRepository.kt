package com.wenxu.app.feature.trash

import com.wenxu.app.core.trash.TrashController
import com.wenxu.app.core.trash.TrashOperationResult
import com.wenxu.app.core.trash.TrashStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TrashItem(
    val id: Long,
    val documentId: Long,
    val displayName: String,
    val extension: String,
    val sizeBytes: Long,
    val originalParentUri: String,
    val deletedAt: Long,
    val expiresAt: Long,
)

interface TrashRepository {
    val items: Flow<List<TrashItem>>

    /** New batch API. Defaults retain compatibility with older test and settings repositories. */
    suspend fun requestRestore(ids: Set<Long>, fallbackParentUri: String? = null): TrashOperationResult {
        val targetIds = ids.sorted()
        val results = restore(ids, fallbackParentUri)
        return TrashOperationResult.Completed(
            successes = targetIds.zip(results).mapNotNull { (trashId, result) ->
                result.getOrNull()?.let { documentId ->
                    com.wenxu.app.core.trash.TrashSuccess(trashId, documentId)
                }
            },
            failures = targetIds.zip(results).filter { it.second.isFailure }.map { (trashId, _) ->
                com.wenxu.app.core.trash.TrashFailure(trashId, com.wenxu.app.core.trash.TrashFailureReason.PROVIDER_REJECTED)
            },
        )
    }

    suspend fun requestDeleteForever(ids: Set<Long>): TrashOperationResult {
        val targetIds = ids.sorted()
        val results = deleteForever(ids)
        return TrashOperationResult.Completed(
            successes = targetIds.zip(results).filter { it.second.isSuccess }.map { (trashId, _) ->
                com.wenxu.app.core.trash.TrashSuccess(trashId, trashId)
            },
            failures = targetIds.zip(results).filter { it.second.isFailure }.map { (trashId, _) ->
                com.wenxu.app.core.trash.TrashFailure(trashId, com.wenxu.app.core.trash.TrashFailureReason.PROVIDER_REJECTED)
            },
        )
    }

    suspend fun completeConfirmation(operationId: Long, approved: Boolean): TrashOperationResult =
        TrashOperationResult.Completed(
            emptyList(),
            listOf(com.wenxu.app.core.trash.TrashFailure(operationId, com.wenxu.app.core.trash.TrashFailureReason.NOT_FOUND)),
        )

    @Deprecated("Use requestRestore")
    suspend fun restore(ids: Set<Long>, fallbackParentUri: String? = null): List<Result<Long>> = emptyList()

    @Deprecated("Use requestDeleteForever")
    suspend fun deleteForever(ids: Set<Long>): List<Result<Unit>> = emptyList()
}

class RoomTrashRepository(
    store: TrashStore,
    private val controller: TrashController,
) : TrashRepository {
    override val items: Flow<List<TrashItem>> = store.observeEntries().map { entries ->
        entries.map { entry ->
            TrashItem(
                id = entry.record.id,
                documentId = entry.document.id,
                displayName = entry.document.displayName,
                extension = entry.document.extension,
                sizeBytes = entry.document.sizeBytes,
                originalParentUri = entry.record.originalParentUri,
                deletedAt = entry.record.deletedAt,
                expiresAt = entry.record.expiresAt,
            )
        }
    }

    override suspend fun requestRestore(ids: Set<Long>, fallbackParentUri: String?): TrashOperationResult =
        controller.requestRestore(ids, fallbackParentUri)

    override suspend fun requestDeleteForever(ids: Set<Long>): TrashOperationResult =
        controller.requestDeleteForever(ids)

    override suspend fun completeConfirmation(operationId: Long, approved: Boolean): TrashOperationResult =
        controller.completeConfirmation(operationId, approved)

    override suspend fun restore(ids: Set<Long>, fallbackParentUri: String?): List<Result<Long>> =
        ids.sorted().map { controller.restore(it, fallbackParentUri) }

    override suspend fun deleteForever(ids: Set<Long>): List<Result<Unit>> =
        ids.sorted().map { controller.deleteForever(it) }
}
