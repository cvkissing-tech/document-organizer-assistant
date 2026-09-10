package com.wenxu.app.core.index

import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.model.IndexReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

data class IndexBatchReport(
    val indexed: Int,
    val added: Int,
    val changed: Int,
    val rejected: Int,
    val failed: Int,
)

data class ScanFinishReport(
    val removedDocumentIds: List<Long>,
)

class DocumentIndexer(
    private val store: DocumentIndexStore,
) {
    suspend fun indexBatch(
        sourceId: Long,
        scanId: String,
        documents: List<DiscoveredDocument>,
    ): IndexBatchReport {
        val accepted = documents.filter { candidate ->
            candidate.displayName.isNotBlank() && DocumentTypePolicy.supports(candidate.displayName)
        }
        val uniqueDocuments = accepted.distinctBy { it.uri }

        return store.inTransaction {
            val existingByUri = store.findByUris(uniqueDocuments.map { it.uri }).associateBy { it.uri }
            var added = 0
            var changed = 0
            val entities = uniqueDocuments.map { candidate ->
                val existing = existingByUri[candidate.uri]
                val metadataChanged = existing != null && (
                    existing.displayName != candidate.displayName ||
                        existing.mimeType != candidate.mimeType ||
                        existing.sizeBytes != candidate.sizeBytes ||
                        existing.modifiedAt != candidate.modifiedAt ||
                        existing.parentUri != candidate.parentUri ||
                        existing.sourceId != sourceId
                    )
                val shouldInvalidateHash = existing != null && (
                    existing.sizeBytes != candidate.sizeBytes ||
                        existing.modifiedAt != candidate.modifiedAt
                    )
                when {
                    existing == null -> added++
                    metadataChanged || existing.indexStatus != DocumentIndexStatus.ACTIVE -> changed++
                }
                candidate.toEntity(
                    sourceId = sourceId,
                    scanId = scanId,
                    existing = existing,
                    shouldInvalidateHash = shouldInvalidateHash,
                )
            }
            store.upsertAll(entities)
            IndexBatchReport(
                indexed = entities.size,
                added = added,
                changed = changed,
                rejected = documents.size - accepted.size,
                failed = 0,
            )
        }
    }

    suspend fun finishScan(sourceId: Long, scanId: String): ScanFinishReport =
        store.inTransaction {
            val missing = store.unseenAfterScan(sourceId, scanId)
            val missingIds = missing.map { it.id }
            store.markMissing(missingIds)
            ScanFinishReport(removedDocumentIds = missingIds)
        }

    suspend fun index(
        sourceId: Long,
        scanId: String,
        batches: Flow<List<DiscoveredDocument>>,
        onBatch: suspend (acceptedCount: Int) -> Unit = {},
    ): IndexReport {
        val seenUris = mutableSetOf<String>()
        var added = 0
        var changed = 0
        batches.collect { batch ->
            val accepted = batch.filter { candidate ->
                candidate.displayName.isNotBlank() && DocumentTypePolicy.supports(candidate.displayName)
            }
            onBatch(accepted.size)
            val report = indexBatch(
                sourceId = sourceId,
                scanId = scanId,
                documents = accepted.filter { seenUris.add(it.uri) },
            )
            added += report.added
            changed += report.changed
        }

        val finishReport = finishScan(sourceId, scanId)
        return IndexReport(
            added = added,
            changed = changed,
            missingIds = finishReport.removedDocumentIds,
        )
    }

    private fun DiscoveredDocument.toEntity(
        sourceId: Long,
        scanId: String,
        existing: DocumentEntity?,
        shouldInvalidateHash: Boolean,
    ) = DocumentEntity(
        id = existing?.id ?: 0,
        uri = uri,
        displayName = displayName,
        normalizedName = DocumentTypePolicy.normalizedBaseName(displayName),
        mimeType = mimeType,
        extension = DocumentTypePolicy.extensionOf(displayName),
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        lastOpenedAt = existing?.lastOpenedAt,
        sourceId = sourceId,
        parentUri = parentUri,
        contentHash = existing?.contentHash?.takeUnless { shouldInvalidateHash },
        hashBasisSize = existing?.hashBasisSize?.takeUnless { shouldInvalidateHash },
        hashBasisModifiedAt = existing?.hashBasisModifiedAt?.takeUnless { shouldInvalidateHash },
        indexStatus = DocumentIndexStatus.ACTIVE,
        lastSeenScanId = scanId,
    )
}
