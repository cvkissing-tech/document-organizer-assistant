package com.wenxu.app.core.storage

import android.net.Uri
import com.wenxu.app.core.model.DiscoveredDocument
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

data class TrashLocation(
    val documentUri: String,
    val parentUri: String,
)

/**
 * Converts failures that happen before a document mutation starts into an explicit unchanged result.
 * Once [mutationStarted] is called, failures stay unwrapped because the provider result may be unknown.
 */
internal inline fun <T> runDocumentMutation(block: (mutationStarted: () -> Unit) -> T): Result<T> {
    var mayHaveChanged = false
    return try {
        Result.success(block { mayHaveChanged = true })
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Result.failure(
            if (!mayHaveChanged && error !is DocumentOperationUnchangedException) {
                DocumentOperationUnchangedException(error)
            } else {
                error
            },
        )
    }
}

interface DocumentGateway {
    suspend fun persistTreePermission(uri: Uri): Result<Unit>
    fun hasReadPermission(treeUri: String): Boolean
    fun discover(treeUri: String, batchSize: Int = 50): Flow<List<DiscoveredDocument>>
    suspend fun openInput(uri: String): InputStream
    suspend fun moveToTrash(
        sourceTreeUri: String,
        documentUri: String,
        sourceParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation>
    suspend fun restoreFromTrash(
        trashedUri: String,
        trashedParentUri: String,
        targetParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation>
    /**
     * Idempotent deletion. [deletePreviouslyAcknowledged] is durable evidence that this provider
     * already accepted this exact deletion, so a retry verifies only and never deletes again.
     * [VerifiedDeleteOutcome.providerAcknowledged] must be journaled before metadata is committed.
     */
    suspend fun deleteDocument(uri: String): Result<Unit>
    suspend fun deleteDocumentVerified(
        uri: String,
        sourceTreeUri: String,
        parentUri: String,
        deletePreviouslyAcknowledged: Boolean = false,
    ): Result<VerifiedDeleteOutcome> = Result.failure(
        UnsupportedOperationException("此文件提供方不支持可核验的永久删除"),
    )
    suspend fun moveToFolder(
        documentUri: String,
        sourceParentUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation>
    suspend fun copyToFolderVerified(
        sourceUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = Result.failure(UnsupportedOperationException("此位置不支持安全导入"))
}
