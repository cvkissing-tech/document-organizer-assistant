package com.wenxu.app.core.index

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.DocumentIndexStatus

interface DocumentIndexStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun findByUri(uri: String): DocumentEntity?
    suspend fun findByUris(uris: List<String>): List<DocumentEntity>
    suspend fun upsert(document: DocumentEntity): Long
    suspend fun upsertAll(documents: List<DocumentEntity>): List<Long>
    suspend fun unseenAfterScan(sourceId: Long, scanId: String): List<DocumentEntity>
    suspend fun markMissing(ids: List<Long>)
}

class RoomDocumentIndexStore(
    private val database: WenxuDatabase,
) : DocumentIndexStore {
    private val documentDao = database.documentDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    override suspend fun findByUri(uri: String): DocumentEntity? = documentDao.findByUri(uri)

    override suspend fun findByUris(uris: List<String>): List<DocumentEntity> =
        if (uris.isEmpty()) emptyList() else documentDao.findByUris(uris)

    override suspend fun upsert(document: DocumentEntity): Long = documentDao.upsert(document)

    override suspend fun upsertAll(documents: List<DocumentEntity>): List<Long> =
        if (documents.isEmpty()) emptyList() else documentDao.upsertAll(documents)

    override suspend fun unseenAfterScan(sourceId: Long, scanId: String): List<DocumentEntity> =
        documentDao.unseenAfterScan(sourceId, scanId)

    override suspend fun markMissing(ids: List<Long>) {
        if (ids.isNotEmpty()) {
            documentDao.updateStatus(ids, DocumentIndexStatus.MISSING)
        }
    }
}
