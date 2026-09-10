package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.model.DocumentIndexStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsert(entity: DocumentEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<DocumentEntity>): List<Long>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri")
    suspend fun findByUri(uri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri IN (:uris)")
    suspend fun findByUris(uris: List<String>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE indexStatus = 'ACTIVE' ORDER BY modifiedAt DESC")
    fun observeActive(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC, id")
    fun observeAllIndexed(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM documents AS d
        WHERE d.indexStatus = 'ACTIVE'
          AND NOT EXISTS (
              SELECT 1 FROM document_categories AS dc WHERE dc.documentId = d.id
          )
        """,
    )
    fun observeUnclassifiedCount(): Flow<Int>

    @Query("SELECT * FROM documents WHERE indexStatus = 'ACTIVE' AND sizeBytes > 0 ORDER BY sizeBytes, id")
    suspend fun activeWithPositiveSize(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE indexStatus = 'ACTIVE' ORDER BY modifiedAt DESC, id")
    suspend fun listActive(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE indexStatus = 'ACTIVE' AND id IN (:ids) ORDER BY id")
    suspend fun findActiveByIds(ids: List<Long>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE sourceId = :sourceId AND indexStatus = 'ACTIVE' AND lastSeenScanId != :scanId")
    suspend fun unseenAfterScan(sourceId: Long, scanId: String): List<DocumentEntity>

    @Query("UPDATE documents SET indexStatus = :status WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, status: DocumentIndexStatus)

    @Query(
        """
        UPDATE documents
        SET indexStatus = 'MISSING'
        WHERE indexStatus = 'ACTIVE'
          AND sourceId IN (
              SELECT id FROM scan_sources WHERE sourceKind = :sourceKind
          )
        """,
    )
    suspend fun markMissingBySourceKind(sourceKind: ScanSourceKind)

    @Query("UPDATE documents SET lastOpenedAt = :openedAt WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: Long, openedAt: Long)

    @Query(
        """
        UPDATE documents
        SET contentHash = :hash,
            hashBasisSize = :sizeBytes,
            hashBasisModifiedAt = :modifiedAt
        WHERE id = :documentId
        """,
    )
    suspend fun updateContentHash(
        documentId: Long,
        hash: String,
        sizeBytes: Long,
        modifiedAt: Long,
    )

    @Query(
        """
        UPDATE documents
        SET uri = :uri,
            parentUri = :parentUri,
            indexStatus = :status,
            lastOpenedAt = NULL
        WHERE id = :documentId
        """,
    )
    suspend fun updateLocationAndStatus(
        documentId: Long,
        uri: String,
        parentUri: String,
        status: DocumentIndexStatus,
    )

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteById(documentId: Long)

    @Query(
        """
        UPDATE documents
        SET uri = :uri,
            parentUri = :parentUri,
            sourceId = :sourceId,
            lastSeenScanId = :scanId,
            contentHash = NULL,
            hashBasisSize = NULL,
            hashBasisModifiedAt = NULL
        WHERE id = :documentId
        """,
    )
    suspend fun updateMovedDocument(
        documentId: Long,
        uri: String,
        parentUri: String,
        sourceId: Long,
        scanId: String,
    )
}
