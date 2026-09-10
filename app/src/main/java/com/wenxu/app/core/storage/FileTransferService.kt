package com.wenxu.app.core.storage

import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import java.util.UUID

interface FileTransferStore {
    suspend fun findDocument(documentId: Long): DocumentEntity?
    suspend fun findSource(sourceId: Long): ScanSourceEntity?
    suspend fun updateMovedDocument(
        documentId: Long,
        uri: String,
        parentUri: String,
        sourceId: Long,
        scanId: String,
    )
}

class RoomFileTransferStore(database: WenxuDatabase) : FileTransferStore {
    private val documentDao = database.documentDao()
    private val sourceDao = database.sourceDao()

    override suspend fun findDocument(documentId: Long): DocumentEntity? = documentDao.findById(documentId)
    override suspend fun findSource(sourceId: Long): ScanSourceEntity? = sourceDao.findById(sourceId)
    override suspend fun updateMovedDocument(
        documentId: Long,
        uri: String,
        parentUri: String,
        sourceId: Long,
        scanId: String,
    ) = documentDao.updateMovedDocument(documentId, uri, parentUri, sourceId, scanId)
}

interface FileTransferController {
    suspend fun move(documentId: Long, targetTreeUri: String): Result<Unit>
}

class FileTransferService(
    private val store: FileTransferStore,
    private val sourceRepository: ScanSourceRepository,
    private val gateway: DocumentGateway,
    private val scanIdProvider: () -> String = { UUID.randomUUID().toString() },
) : FileTransferController {
    override suspend fun move(documentId: Long, targetTreeUri: String): Result<Unit> = runCatching {
        val document = requireNotNull(store.findDocument(documentId)) { "找不到这份文档" }
        val originalSource = requireNotNull(store.findSource(document.sourceId)) { "原扫描位置不存在" }
        val targetSourceId = sourceRepository.add(targetTreeUri).getOrThrow()
        val moved = gateway.moveToFolder(
            documentUri = document.uri,
            sourceParentUri = document.parentUri,
            targetTreeUri = targetTreeUri,
            displayName = document.displayName,
            mimeType = document.mimeType,
            expectedSize = document.sizeBytes,
        ).getOrThrow()

        val updated = runCatching {
            store.updateMovedDocument(
                documentId = document.id,
                uri = moved.documentUri,
                parentUri = moved.parentUri,
                sourceId = targetSourceId,
                scanId = scanIdProvider(),
            )
        }
        if (updated.isFailure) {
            gateway.moveToFolder(
                documentUri = moved.documentUri,
                sourceParentUri = moved.parentUri,
                targetTreeUri = originalSource.treeUri,
                displayName = document.displayName,
                mimeType = document.mimeType,
                expectedSize = document.sizeBytes,
            )
            throw updated.exceptionOrNull() ?: IllegalStateException("无法更新文档位置")
        }
    }
}
