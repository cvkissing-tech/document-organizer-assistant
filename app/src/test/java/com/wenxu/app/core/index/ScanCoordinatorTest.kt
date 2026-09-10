package com.wenxu.app.core.index

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.dao.SourceDao
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.core.storage.DocumentGateway
import com.wenxu.app.core.storage.TrashLocation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import com.wenxu.app.core.relations.RelationAnalysisReason
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanCoordinatorTest {
    @Test
    fun legacyScanEmitsCompletionBeforeQueuingRelationAnalysis() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ScanCoordinator(
            sourceDao = FakeSourceDao(emptyList()),
            gateway = ScanGateway(emptyMap(), emptyMap()),
            indexer = DocumentIndexer(CoordinatorIndexStore()),
            relationRequest = { reason -> events += "relation-$reason" },
        )

        coordinator.scanAll().collect { progress ->
            if (progress is ScanProgress.Completed) events += "scan-completed"
        }

        assertThat(events).containsExactly(
            "scan-completed",
            "relation-${RelationAnalysisReason.SCAN_COMPLETED}",
        ).inOrder()
    }

    @Test
    fun inaccessibleSourceDoesNotHideDocumentsFromOtherSources() = runTest {
        val inaccessibleSourceId = 1L
        val readableSourceId = 2L
        val sourceDao = FakeSourceDao(
            listOf(
                source(inaccessibleSourceId, "content://source/inaccessible"),
                source(readableSourceId, "content://source/readable"),
            ),
        )
        val readableDocument = discovered("课程资料.pdf", "content://source/readable/course.pdf")
        val gateway = ScanGateway(
            documentsBySource = mapOf("content://source/readable" to listOf(readableDocument)),
            failuresBySource = mapOf("content://source/inaccessible" to IOException("无法读取来源")),
        )
        val store = CoordinatorIndexStore()
        val coordinator = ScanCoordinator(
            sourceDao = sourceDao,
            gateway = gateway,
            indexer = DocumentIndexer(store),
            scanIdProvider = { "scan" },
            clock = { 5_000L },
        )

        val progress = coordinator.scanAll().toList()

        val partial = progress.last() as ScanProgress.Partial
        assertThat(partial.failedSourceIds).containsExactly(inaccessibleSourceId)
        assertThat(store.activeDocuments().map { it.displayName }).containsExactly("课程资料.pdf")
        assertThat(sourceDao.stateOf(inaccessibleSourceId)).isEqualTo(SourcePermissionState.NEEDS_ATTENTION)
        assertThat(sourceDao.stateOf(readableSourceId)).isEqualTo(SourcePermissionState.ACTIVE)
        assertThat(sourceDao.lastScanOf(readableSourceId)).isEqualTo(5_000L)
    }

    @Test
    fun safCoordinatorSkipsSharedStorageSourceOwnedByProgressiveWorker() = runTest {
        val gateway = ScanGateway(
            documentsBySource = mapOf("content://tree/download" to emptyList()),
            failuresBySource = emptyMap(),
        )
        val coordinator = ScanCoordinator(
            sourceDao = FakeSourceDao(
                listOf(
                    source(1, "content://tree/download"),
                    source(2, SHARED_STORAGE_SOURCE_URI).copy(
                        sourceKind = ScanSourceKind.SHARED_STORAGE,
                    ),
                ),
            ),
            gateway = gateway,
            indexer = DocumentIndexer(CoordinatorIndexStore()),
        )

        coordinator.scanAll().toList()

        assertThat(gateway.discoveredSources).containsExactly("content://tree/download")
    }

}

private fun source(id: Long, uri: String) = ScanSourceEntity(
    id = id,
    treeUri = uri,
    displayName = "来源$id",
)

private fun discovered(name: String, uri: String) = DiscoveredDocument(
    uri = uri,
    parentUri = uri.substringBeforeLast('/'),
    displayName = name,
    mimeType = "application/octet-stream",
    sizeBytes = 12,
    modifiedAt = 2_000,
)

private class ScanGateway(
    private val documentsBySource: Map<String, List<DiscoveredDocument>>,
    private val failuresBySource: Map<String, Throwable>,
) : DocumentGateway {
    val discoveredSources = mutableListOf<String>()
    override suspend fun persistTreePermission(uri: Uri): Result<Unit> = Result.success(Unit)
    override fun hasReadPermission(treeUri: String): Boolean = true

    override fun discover(treeUri: String, batchSize: Int): Flow<List<DiscoveredDocument>> = flow {
        discoveredSources += treeUri
        failuresBySource[treeUri]?.let { throw it }
        emit(documentsBySource[treeUri].orEmpty())
    }

    override suspend fun openInput(uri: String): InputStream = ByteArrayInputStream(ByteArray(0))

    override suspend fun moveToTrash(
        sourceTreeUri: String,
        documentUri: String,
        sourceParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = error("Not used")

    override suspend fun restoreFromTrash(
        trashedUri: String,
        trashedParentUri: String,
        targetParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = error("Not used")

    override suspend fun deleteDocument(uri: String): Result<Unit> = error("Not used")

    override suspend fun moveToFolder(
        documentUri: String,
        sourceParentUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = error("Not used")
}

private class FakeSourceDao(sources: List<ScanSourceEntity>) : SourceDao {
    private val records = sources.associateBy { it.id }.toMutableMap()

    override suspend fun upsert(source: ScanSourceEntity): Long {
        records[source.id] = source
        return source.id
    }

    override fun observeAll(): Flow<List<ScanSourceEntity>> = flowOf(records.values.toList())
    override suspend fun listAll(): List<ScanSourceEntity> = records.values.sortedBy { it.sortOrder }
    override suspend fun findById(id: Long): ScanSourceEntity? = records[id]
    override suspend fun findByTreeUri(treeUri: String): ScanSourceEntity? = records.values.find { it.treeUri == treeUri }

    override suspend fun updatePermissionState(sourceId: Long, state: SourcePermissionState) {
        records[sourceId] = requireNotNull(records[sourceId]).copy(permissionState = state)
    }

    override suspend fun updateLastScan(sourceId: Long, scannedAt: Long) {
        records[sourceId] = requireNotNull(records[sourceId]).copy(lastScanAt = scannedAt)
    }

    override suspend fun countTrashedDocuments(sourceId: Long): Int = 0
    override suspend fun delete(source: ScanSourceEntity) {
        records.remove(source.id)
    }

    fun stateOf(sourceId: Long): SourcePermissionState = requireNotNull(records[sourceId]).permissionState
    fun lastScanOf(sourceId: Long): Long? = requireNotNull(records[sourceId]).lastScanAt
}

private class CoordinatorIndexStore : DocumentIndexStore {
    private val records = mutableMapOf<String, DocumentEntity>()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun findByUri(uri: String): DocumentEntity? = records[uri]
    override suspend fun findByUris(uris: List<String>): List<DocumentEntity> =
        uris.mapNotNull(records::get)

    override suspend fun upsert(document: DocumentEntity): Long {
        val id = document.id.takeIf { it != 0L } ?: (records.size + 1L)
        records[document.uri] = document.copy(id = id)
        return id
    }

    override suspend fun upsertAll(documents: List<DocumentEntity>): List<Long> =
        documents.map { upsert(it) }

    override suspend fun unseenAfterScan(sourceId: Long, scanId: String): List<DocumentEntity> =
        records.values.filter {
            it.sourceId == sourceId &&
                it.indexStatus == DocumentIndexStatus.ACTIVE &&
                it.lastSeenScanId != scanId
        }

    override suspend fun markMissing(ids: List<Long>) {
        records.replaceAll { _, value ->
            if (value.id in ids) value.copy(indexStatus = DocumentIndexStatus.MISSING) else value
        }
    }

    fun activeDocuments(): List<DocumentEntity> =
        records.values.filter { it.indexStatus == DocumentIndexStatus.ACTIVE }
}
