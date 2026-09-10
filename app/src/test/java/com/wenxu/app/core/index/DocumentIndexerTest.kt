package com.wenxu.app.core.index

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.model.DocumentIndexStatus
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DocumentIndexerTest {
    @Test
    fun indexBatchPersistsBeforeTheScanFinishes() = runTest {
        val store = FakeDocumentIndexStore(
            documents = listOf(indexedDocument(id = 41, uri = "content://docs/old.pdf")),
        )
        val indexer = DocumentIndexer(store)

        val result = indexer.indexBatch(
            sourceId = 7,
            scanId = "scan-2",
            documents = listOf(discovered("课程讲义.pdf", "content://docs/new.pdf")),
        )

        assertThat(result.indexed).isEqualTo(1)
        assertThat(result.added).isEqualTo(1)
        assertThat(result.changed).isEqualTo(0)
        assertThat(result.rejected).isEqualTo(0)
        assertThat(store.documents.map { it.uri }).contains("content://docs/new.pdf")
        assertThat(store.removeMissingCalls).isEqualTo(0)
        assertThat(store.statusOf(41L)).isEqualTo(DocumentIndexStatus.ACTIVE)
    }

    @Test
    fun legacyFlowIndexesEachBatchWithoutWaitingForDiscoveryToComplete() = runTest {
        val store = FakeDocumentIndexStore()
        val indexer = DocumentIndexer(store)

        val result = indexer.index(
            sourceId = 7,
            scanId = "scan-2",
            batches = flow {
                emit(listOf(discovered("第一批.pdf", "content://docs/first.pdf")))
                assertThat(store.documents.map { it.uri }).containsExactly("content://docs/first.pdf")
                assertThat(store.removeMissingCalls).isEqualTo(0)
                emit(listOf(discovered("第二批.docx", "content://docs/second.docx")))
            },
        )

        assertThat(result.added).isEqualTo(2)
        assertThat(store.documents.map { it.uri })
            .containsExactly("content://docs/first.pdf", "content://docs/second.docx")
        assertThat(store.removeMissingCalls).isEqualTo(1)
    }

    @Test
    fun finishScanMarksOnlyDocumentsNotSeenInCurrentScanMissing() = runTest {
        val store = FakeDocumentIndexStore(
            documents = listOf(
                indexedDocument(id = 41, uri = "content://docs/old.pdf"),
                indexedDocument(id = 42, uri = "content://docs/current.pdf")
                    .copy(lastSeenScanId = "scan-2"),
            ),
        )

        val result = DocumentIndexer(store).finishScan(sourceId = 7, scanId = "scan-2")

        assertThat(result.removedDocumentIds).containsExactly(41L)
        assertThat(store.statusOf(41L)).isEqualTo(DocumentIndexStatus.MISSING)
        assertThat(store.statusOf(42L)).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(store.removeMissingArguments).containsExactly(7L to "scan-2")
    }

    @Test
    fun failedBatchDoesNotPersistPartiallyOrMarkOldDocumentsMissing() = runTest {
        val old = indexedDocument(id = 41, uri = "content://docs/old.pdf")
        val store = FakeDocumentIndexStore(
            documents = listOf(old),
            failUpsertForUri = "content://docs/failure.pdf",
        )

        val failure = runCatching {
            DocumentIndexer(store).indexBatch(
                sourceId = 7,
                scanId = "scan-2",
                documents = listOf(
                    discovered("新讲义.pdf", "content://docs/new.pdf"),
                    discovered("损坏讲义.pdf", "content://docs/failure.pdf"),
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(store.documents).containsExactly(old)
        assertThat(store.removeMissingCalls).isEqualTo(0)
        assertThat(store.statusOf(41L)).isEqualTo(DocumentIndexStatus.ACTIVE)
    }

    @Test
    fun repeatedUriKeepsExistingUserDataAndInvalidatesHashOnlyWhenFileChanges() = runTest {
        val old = indexedDocument(
            id = 9,
            uri = "content://docs/a",
            sizeBytes = 10,
            contentHash = "old-hash",
        ).copy(
            lastOpenedAt = 8_000,
            modifiedAt = 2_000,
            hashBasisModifiedAt = 2_000,
        )
        val store = FakeDocumentIndexStore(listOf(old))
        val indexer = DocumentIndexer(store)

        indexer.indexBatch(
            sourceId = 7,
            scanId = "scan-2",
            documents = listOf(discovered("讲义.pdf", old.uri, sizeBytes = 10)),
        )
        val unchanged = store.documents.single()

        assertThat(unchanged.id).isEqualTo(9)
        assertThat(unchanged.lastOpenedAt).isEqualTo(8_000)
        assertThat(unchanged.contentHash).isEqualTo("old-hash")

        indexer.indexBatch(
            sourceId = 7,
            scanId = "scan-3",
            documents = listOf(discovered("讲义.pdf", old.uri, sizeBytes = 20)),
        )
        val changed = store.documents.single()

        assertThat(changed.id).isEqualTo(9)
        assertThat(changed.lastOpenedAt).isEqualTo(8_000)
        assertThat(changed.contentHash).isNull()
    }

    @Test
    fun completedScanMarksUnseenRecordMissing() = runTest {
        val store = FakeDocumentIndexStore(
            documents = listOf(indexedDocument(id = 41, uri = "content://docs/old.pdf")),
        )
        val indexer = DocumentIndexer(store)

        val result = indexer.index(
            sourceId = 7,
            scanId = "scan-2",
            batches = flowOf(emptyList()),
        )

        assertThat(result.missingIds).containsExactly(41L)
        assertThat(store.statusOf(41L)).isEqualTo(DocumentIndexStatus.MISSING)
    }

    @Test
    fun scanIgnoresImagesAndIndexesSupportedDocuments() = runTest {
        val store = FakeDocumentIndexStore()
        val indexer = DocumentIndexer(store)

        val result = indexer.index(
            sourceId = 7,
            scanId = "scan-2",
            batches = flowOf(
                listOf(
                    discovered("课程讲义.pdf", "content://docs/a"),
                    discovered("课程封面.jpg", "content://docs/b"),
                    discovered("答辩.PPTX", "content://docs/c"),
                ),
            ),
        )

        assertThat(result.added).isEqualTo(2)
        assertThat(store.documents.map { it.displayName })
            .containsExactly("课程讲义.pdf", "答辩.PPTX")
    }

    @Test
    fun changedSizeInvalidatesCachedHash() = runTest {
        val old = indexedDocument(
            id = 9,
            uri = "content://docs/a",
            sizeBytes = 10,
            contentHash = "old-hash",
        )
        val store = FakeDocumentIndexStore(listOf(old))

        val result = DocumentIndexer(store).index(
            sourceId = 7,
            scanId = "scan-2",
            batches = flowOf(listOf(discovered("讲义.pdf", old.uri, sizeBytes = 20))),
        )

        assertThat(result.changed).isEqualTo(1)
        assertThat(store.documents.single().contentHash).isNull()
    }

    @Test
    fun failedDiscoveryDoesNotMarkPreviousDocumentsMissing() = runTest {
        val store = FakeDocumentIndexStore(
            documents = listOf(indexedDocument(id = 41, uri = "content://docs/old.pdf")),
        )

        val failure = runCatching {
            DocumentIndexer(store).index(
                sourceId = 7,
                scanId = "scan-2",
                batches = flow { throw IOException("provider failed") },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(store.statusOf(41L)).isEqualTo(DocumentIndexStatus.ACTIVE)
    }
}

private fun discovered(
    name: String,
    uri: String,
    sizeBytes: Long = 10,
) = DiscoveredDocument(
    uri = uri,
    parentUri = "content://docs/tree",
    displayName = name,
    mimeType = "application/octet-stream",
    sizeBytes = sizeBytes,
    modifiedAt = 2_000,
)

private fun indexedDocument(
    id: Long,
    uri: String,
    sizeBytes: Long = 10,
    contentHash: String? = null,
) = DocumentEntity(
    id = id,
    uri = uri,
    displayName = "旧讲义.pdf",
    normalizedName = "旧讲义",
    mimeType = "application/pdf",
    extension = "pdf",
    sizeBytes = sizeBytes,
    modifiedAt = 1_000,
    sourceId = 7,
    parentUri = "content://docs/tree",
    contentHash = contentHash,
    hashBasisSize = if (contentHash == null) null else sizeBytes,
    hashBasisModifiedAt = if (contentHash == null) null else 1_000,
    lastSeenScanId = "scan-1",
)

private class FakeDocumentIndexStore(
    documents: List<DocumentEntity> = emptyList(),
    private val failUpsertForUri: String? = null,
) : DocumentIndexStore {
    private val records = documents.associateBy { it.uri }.toMutableMap()
    var removeMissingCalls = 0
        private set
    val removeMissingArguments = mutableListOf<Pair<Long, String>>()
    val documents: List<DocumentEntity>
        get() = records.values.toList()

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val snapshot = records.toMap()
        return try {
            block()
        } catch (error: Throwable) {
            records.clear()
            records.putAll(snapshot)
            throw error
        }
    }

    override suspend fun findByUri(uri: String): DocumentEntity? = records[uri]

    override suspend fun findByUris(uris: List<String>): List<DocumentEntity> =
        uris.mapNotNull(records::get)

    override suspend fun upsert(document: DocumentEntity): Long {
        if (document.uri == failUpsertForUri) {
            throw IOException("database write failed")
        }
        val id = document.id.takeIf { it != 0L } ?: ((records.values.maxOfOrNull { it.id } ?: 0) + 1)
        records[document.uri] = document.copy(id = id)
        return id
    }

    override suspend fun upsertAll(documents: List<DocumentEntity>): List<Long> =
        documents.map { upsert(it) }

    override suspend fun unseenAfterScan(sourceId: Long, scanId: String): List<DocumentEntity> {
        removeMissingArguments += sourceId to scanId
        return records.values.filter {
            it.sourceId == sourceId &&
                it.indexStatus == DocumentIndexStatus.ACTIVE &&
                it.lastSeenScanId != scanId
        }
    }

    override suspend fun markMissing(ids: List<Long>) {
        removeMissingCalls++
        records.replaceAll { _, value ->
            if (value.id in ids) value.copy(indexStatus = DocumentIndexStatus.MISSING) else value
        }
    }

    fun statusOf(id: Long): DocumentIndexStatus = records.values.single { it.id == id }.indexStatus
}
