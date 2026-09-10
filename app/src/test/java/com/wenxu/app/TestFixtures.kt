package com.wenxu.app

import android.net.Uri
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.storage.DocumentGateway
import com.wenxu.app.core.storage.TrashLocation
import com.wenxu.app.core.storage.DocumentStateUnknownException
import com.wenxu.app.core.storage.DocumentDeleteNotAttemptedException
import com.wenxu.app.core.storage.VerifiedDeleteOutcome
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

fun testDocument(
    displayName: String,
    id: Long = 1,
) = DocumentRecord(
    id = id,
    uri = "content://test/$displayName",
    displayName = displayName,
    extension = displayName.substringAfterLast('.').lowercase(),
    sizeBytes = 3,
    modifiedAt = 1_000,
    sourceId = 1,
    parentUri = "content://test/tree",
)

class FakeDocumentGateway : DocumentGateway {
    private val bytesByUri = mutableMapOf<String, ByteArray>()
    private val readsByUri = mutableMapOf<String, Int>()
    val operations = mutableListOf<String>()
    var moveToTrashResult: Result<TrashLocation>? = null
    var restoreResult: Result<TrashLocation>? = null
    var deleteResult: Result<Unit> = Result.success(Unit)
    var deleteVerifiedAbsent = true
    var deletePreflightFailure: Throwable? = null
    var moveToFolderResult: Result<TrashLocation>? = null
    var copyToFolderResult: Result<TrashLocation>? = null
    var requireTrackedFiles = false
    var lastTrashSourceTreeUri: String? = null
    var lastTrashSourceParentUri: String? = null
    var lastDeleteSourceTreeUri: String? = null
    var lastDeleteParentUri: String? = null
    val deleteAcknowledgementInputs = mutableListOf<Boolean>()
    var afterRestore: () -> Unit = {}
    var afterDelete: () -> Unit = {}

    fun put(uri: String, bytes: ByteArray) {
        bytesByUri[uri] = bytes
    }

    fun readCount(uri: String): Int = readsByUri[uri] ?: 0

    override suspend fun persistTreePermission(uri: Uri): Result<Unit> = Result.success(Unit)

    override fun hasReadPermission(treeUri: String): Boolean = true

    override fun discover(
        treeUri: String,
        batchSize: Int,
    ): Flow<List<DiscoveredDocument>> = emptyFlow()

    override suspend fun openInput(uri: String): InputStream {
        val bytes = bytesByUri[uri] ?: throw FileNotFoundException(uri)
        readsByUri[uri] = readCount(uri) + 1
        return ByteArrayInputStream(bytes)
    }

    override suspend fun moveToTrash(
        sourceTreeUri: String,
        documentUri: String,
        sourceParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> {
        operations += "trash:$documentUri"
        lastTrashSourceTreeUri = sourceTreeUri
        lastTrashSourceParentUri = sourceParentUri
        return moveTrackedFile(documentUri, moveToTrashResult ?: Result.success(TrashLocation(
            documentUri = "content://test/trash/$displayName",
            parentUri = "content://test/trash",
        )))
    }

    override suspend fun restoreFromTrash(
        trashedUri: String,
        trashedParentUri: String,
        targetParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> {
        operations += "restore:$trashedUri"
        return moveTrackedFile(trashedUri, restoreResult ?: Result.success(
            TrashLocation("$targetParentUri/$displayName", targetParentUri),
        )).also { afterRestore() }
    }

    private fun moveTrackedFile(sourceUri: String, result: Result<TrashLocation>): Result<TrashLocation> {
        if (requireTrackedFiles && sourceUri !in bytesByUri) {
            return Result.failure(DocumentStateUnknownException(FileNotFoundException(sourceUri)))
        }
        result.getOrNull()?.let { target ->
            bytesByUri.remove(sourceUri)?.let { bytesByUri[target.documentUri] = it }
        }
        return result
    }

    override suspend fun deleteDocument(uri: String): Result<Unit> {
        operations += "delete:$uri"
        if (deleteResult.isSuccess) bytesByUri.remove(uri)
        afterDelete()
        return deleteResult
    }

    override suspend fun deleteDocumentVerified(
        uri: String,
        sourceTreeUri: String,
        parentUri: String,
        deletePreviouslyAcknowledged: Boolean,
    ): Result<VerifiedDeleteOutcome> {
        lastDeleteSourceTreeUri = sourceTreeUri
        lastDeleteParentUri = parentUri
        deleteAcknowledgementInputs += deletePreviouslyAcknowledged
        deletePreflightFailure?.let { failure ->
            return Result.failure(DocumentDeleteNotAttemptedException(failure))
        }
        if (deletePreviouslyAcknowledged) {
            return Result.success(VerifiedDeleteOutcome(
                providerAcknowledged = true,
                verifiedAbsent = deleteVerifiedAbsent,
            ))
        }
        return deleteDocument(uri).map {
            VerifiedDeleteOutcome(providerAcknowledged = true, verifiedAbsent = deleteVerifiedAbsent)
        }
    }

    override suspend fun moveToFolder(
        documentUri: String,
        sourceParentUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> {
        operations += "move:$documentUri->$targetTreeUri"
        return moveToFolderResult ?: Result.success(TrashLocation("$targetTreeUri/$displayName", targetTreeUri))
    }

    override suspend fun copyToFolderVerified(
        sourceUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> {
        operations += "copy:$sourceUri->$targetTreeUri"
        return copyToFolderResult ?: Result.success(TrashLocation("$targetTreeUri/$displayName", targetTreeUri))
    }
}
