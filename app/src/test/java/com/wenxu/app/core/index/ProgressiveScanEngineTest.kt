package com.wenxu.app.core.index

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.relations.RelationAnalysisReason
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

class ProgressiveScanEngineTest {
    @Test
    fun completedSessionRecoveryStillQueuesRelationWorkIdempotently() = runTest {
        val store = FakeProgressiveScanStore()
        val session = store.activeOrCreate()
        store.forceSession(
            session.copy(
                status = ScanSessionStatus.COMPLETED,
                phase = ScanPhase.COMPLETE,
            ),
        )
        val requests = mutableListOf<RelationAnalysisReason>()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = { error("completed session must not scan again") },
            accessState = { StorageAccessState.Granted },
            relationRequest = requests::add,
        )

        val result = runner.run(session.id)

        assertThat(result).isEqualTo(ProgressiveWorkerOutcome.SUCCESS)
        assertThat(requests).containsExactly(RelationAnalysisReason.SCAN_COMPLETED)
    }

    @Test
    fun realCommittedPageIsVisibleBeforeRelationWorkAndNeverAnalyzedSynchronously() = runTest {
        val events = mutableListOf<String>()
        val store = FakeProgressiveScanStore(events = events)
        val session = store.activeOrCreate()
        val source = FakeSharedDocumentPageSource(
            pages = listOf(
                page(cursor = 200, checkedFiles = 200, documentCount = 1),
                DocumentPage(
                    documents = emptyList(),
                    nextCursor = null,
                    isComplete = true,
                    checkedFiles = 0,
                    directoryPaths = emptySet(),
                ),
            ),
            events = events,
        )
        val engine = ProgressiveScanEngine(
            pageSource = source,
            indexer = DocumentIndexer(RecordingDocumentIndexStore(events)),
            store = store,
        )
        val requests = mutableListOf<RelationAnalysisReason>()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = {
                ProgressiveChunkRunner { sessionId, _ -> engine.runChunk(sessionId, maxPages = 1) }
            },
            accessState = { StorageAccessState.Granted },
            relationRequest = { reason ->
                requests += reason
                events += "relation-request"
            },
        )

        val first = runner.run(session.id)

        assertThat(first).isEqualTo(ProgressiveWorkerOutcome.RETRY)
        assertThat(store.latest().cursorId).isEqualTo(200)
        assertThat(events.indexOf("documents-committed"))
            .isLessThan(events.indexOf("cursor-committed"))
        assertThat(events).doesNotContain("relation-request")

        val completed = runner.run(session.id)

        assertThat(completed).isEqualTo(ProgressiveWorkerOutcome.SUCCESS)
        assertThat(requests).containsExactly(RelationAnalysisReason.SCAN_COMPLETED)
        assertThat(events.last()).isEqualTo("relation-request")
    }

    @Test
    fun scanCompletesBeforeRelationWorkRuns() = runTest {
        val events = mutableListOf<String>()
        val store = FakeProgressiveScanStore(events = events)
        val session = store.activeOrCreate()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = {
                ProgressiveChunkRunner { sessionId, _ ->
                    store.markRunning(sessionId)
                    val completed = store.markCompleted(sessionId)
                    events += "scan-completed"
                    ScanChunkResult(
                        sessionId = completed.id,
                        scanId = completed.scanId,
                        complete = true,
                        status = completed.status,
                        checkedDirectories = 0,
                        checkedFiles = 0,
                        discoveredDocuments = 0,
                    )
                }
            },
            accessState = { StorageAccessState.Granted },
            relationRequest = { reason -> events += "relation-$reason" },
        )

        val result = runner.run(session.id)

        assertThat(result).isEqualTo(ProgressiveWorkerOutcome.SUCCESS)
        assertThat(events).containsExactly(
            "scan-completed",
            "relation-${RelationAnalysisReason.SCAN_COMPLETED}",
        ).inOrder()
    }

    @Test
    fun relationQueueFailureNeverChangesCompletedScanToFailure() = runTest {
        val store = FakeProgressiveScanStore()
        val session = store.activeOrCreate()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = {
                ProgressiveChunkRunner { sessionId, _ ->
                    store.markRunning(sessionId)
                    val completed = store.markCompleted(sessionId)
                    ScanChunkResult(
                        sessionId = completed.id,
                        scanId = completed.scanId,
                        complete = true,
                        status = completed.status,
                        checkedDirectories = 0,
                        checkedFiles = 0,
                        discoveredDocuments = 0,
                    )
                }
            },
            accessState = { StorageAccessState.Granted },
            relationRequest = { throw IllegalStateException("scheduler unavailable") },
        )

        val result = runner.run(session.id)

        assertThat(result).isEqualTo(ProgressiveWorkerOutcome.SUCCESS)
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.COMPLETED)
    }

    @Test
    fun engineSavesCursorOnlyAfterEachCommittedPage() = runTest {
        val events = mutableListOf<String>()
        val store = FakeProgressiveScanStore(events = events)
        val pageSource = FakeSharedDocumentPageSource(
            pages = listOf(page(cursor = 200, checkedFiles = 200, documentCount = 14)),
            events = events,
        )
        val indexStore = RecordingDocumentIndexStore(events)
        val engine = ProgressiveScanEngine(
            pageSource = pageSource,
            indexer = DocumentIndexer(indexStore),
            store = store,
        )

        val result = engine.runChunk(maxPages = 1)

        assertThat(result.complete).isFalse()
        assertThat(result.checkedFiles).isEqualTo(200)
        assertThat(result.discoveredDocuments).isEqualTo(14)
        assertThat(store.latest().cursorId).isEqualTo(200)
        assertThat(events.indexOf("documents-committed"))
            .isLessThan(events.indexOf("cursor-committed"))
    }

    @Test
    fun pausedEngineDoesNotRequestAnotherPage() = runTest {
        val store = FakeProgressiveScanStore()
        val pageSource = FakeSharedDocumentPageSource(
            pages = listOf(page(cursor = 200, checkedFiles = 200, documentCount = 1)),
        )
        val engine = ProgressiveScanEngine(
            pageSource = pageSource,
            indexer = DocumentIndexer(RecordingDocumentIndexStore()),
            store = store,
        )

        store.activeOrCreate()
        engine.pause()
        val result = engine.runChunk(maxPages = 1)

        assertThat(result.status).isEqualTo(ScanSessionStatus.PAUSED)
        assertThat(pageSource.afterIds).isEmpty()
    }

    @Test
    fun failedDocumentBatchNeverAdvancesCursor() = runTest {
        val store = FakeProgressiveScanStore()
        val pageSource = FakeSharedDocumentPageSource(
            pages = listOf(page(cursor = 200, checkedFiles = 200, documentCount = 1)),
        )
        val engine = ProgressiveScanEngine(
            pageSource = pageSource,
            indexer = DocumentIndexer(
                RecordingDocumentIndexStore(failUpsert = true),
            ),
            store = store,
        )

        val error = runCatching { engine.runChunk(maxPages = 1) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(store.latest().cursorId).isNull()
        assertThat(store.latest().checkedFiles).isEqualTo(0)
    }

    @Test
    fun crashBeforeCursorCommitRereadsPageWithoutDuplicatingDocumentsOrCounts() = runTest {
        val store = FakeProgressiveScanStore(failNextPageCommit = true)
        val source = RepeatingSharedDocumentPageSource(
            page = page(cursor = 200, checkedFiles = 200, documentCount = 1),
        )
        val indexStore = RecordingDocumentIndexStore()
        val engine = ProgressiveScanEngine(
            pageSource = source,
            indexer = DocumentIndexer(indexStore),
            store = store,
        )

        val firstFailure = runCatching { engine.runChunk(maxPages = 1) }.exceptionOrNull()
        val recovered = engine.runChunk(maxPages = 1)

        assertThat(firstFailure).isInstanceOf(IOException::class.java)
        assertThat(source.afterIds).containsExactly(null, null).inOrder()
        assertThat(indexStore.visibleUris()).containsExactly("content://docs/1.pdf")
        assertThat(recovered.checkedFiles).isEqualTo(200)
        assertThat(recovered.discoveredDocuments).isEqualTo(1)
    }

    @Test
    fun cancelledScanKeepsIndexedDocumentsAndNextRunUsesNewScanId() = runTest {
        val store = FakeProgressiveScanStore()
        val source = RepeatingSharedDocumentPageSource(
            page = page(cursor = 200, checkedFiles = 200, documentCount = 1),
        )
        val indexStore = RecordingDocumentIndexStore()
        val engine = ProgressiveScanEngine(
            pageSource = source,
            indexer = DocumentIndexer(indexStore),
            store = store,
        )

        val first = engine.runChunk(maxPages = 1)
        engine.cancel()
        val cancelled = store.latest()
        val restarted = engine.runChunk(maxPages = 1)

        assertThat(cancelled.status).isEqualTo(ScanSessionStatus.CANCELLED)
        assertThat(indexStore.visibleUris()).containsExactly("content://docs/1.pdf")
        assertThat(restarted.scanId).isNotEqualTo(first.scanId)
    }

    @Test
    fun emptyCompletePageFinishesScanAndMarksSessionComplete() = runTest {
        val store = FakeProgressiveScanStore()
        val source = FakeSharedDocumentPageSource(
            pages = listOf(
                DocumentPage(
                    documents = emptyList(),
                    nextCursor = null,
                    isComplete = true,
                    checkedFiles = 0,
                    directoryPaths = emptySet(),
                ),
            ),
        )
        val indexStore = RecordingDocumentIndexStore(
            initialDocuments = listOf(indexedDocument()),
        )
        val engine = ProgressiveScanEngine(
            pageSource = source,
            indexer = DocumentIndexer(indexStore),
            store = store,
        )

        val result = engine.runChunk(maxPages = 1)

        assertThat(result.complete).isTrue()
        assertThat(result.status).isEqualTo(ScanSessionStatus.COMPLETED)
        assertThat(indexStore.statusOf("content://docs/old.pdf"))
            .isEqualTo(DocumentIndexStatus.MISSING)
    }

    @Test
    fun resumeContinuesPausedFinalizationWithoutReadingAnotherPage() = runTest {
        val store = FakeProgressiveScanStore()
        val session = store.activeOrCreate()
        store.forceSession(
            session.copy(
                status = ScanSessionStatus.PAUSED,
                phase = ScanPhase.FINALIZING,
                cursorId = 900,
            ),
        )
        val source = FakeSharedDocumentPageSource(emptyList())
        val engine = ProgressiveScanEngine(
            pageSource = source,
            indexer = DocumentIndexer(RecordingDocumentIndexStore()),
            store = store,
        )

        engine.resume()
        val result = engine.runChunk(maxPages = 1)

        assertThat(result.complete).isTrue()
        assertThat(source.afterIds).isEmpty()
    }

    @Test
    fun finalizingScanCannotBePausedOrCancelled() = runTest {
        val store = FakeProgressiveScanStore()
        val session = store.activeOrCreate()
        store.forceSession(
            session.copy(
                status = ScanSessionStatus.RUNNING,
                phase = ScanPhase.FINALIZING,
            ),
        )
        val engine = ProgressiveScanEngine(
            pageSource = FakeSharedDocumentPageSource(emptyList()),
            indexer = DocumentIndexer(RecordingDocumentIndexStore()),
            store = store,
        )

        val paused = engine.pause()
        val cancelled = engine.cancel()

        assertThat(paused).isNull()
        assertThat(cancelled).isNull()
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.RUNNING)
    }
}

private fun page(
    cursor: Long,
    checkedFiles: Int,
    documentCount: Int,
    complete: Boolean = false,
) = DocumentPage(
    documents = (1..documentCount).map { number ->
        DiscoveredDocument(
            uri = "content://docs/$number.pdf",
            parentUri = "content://docs",
            displayName = "$number.pdf",
            mimeType = "application/pdf",
            sizeBytes = number.toLong(),
            modifiedAt = number.toLong(),
        )
    },
    nextCursor = cursor,
    isComplete = complete,
    checkedFiles = checkedFiles,
    directoryPaths = setOf("Download/", "Documents/"),
)

private class FakeSharedDocumentPageSource(
    pages: List<DocumentPage>,
    private val events: MutableList<String> = mutableListOf(),
) : SharedDocumentPageSource {
    private val queue = ArrayDeque(pages)
    val afterIds = mutableListOf<Long?>()

    override suspend fun readPage(afterId: Long?, limit: Int): DocumentPage {
        afterIds += afterId
        events += "page-read"
        return queue.removeFirst()
    }
}

private class RepeatingSharedDocumentPageSource(
    private val page: DocumentPage,
) : SharedDocumentPageSource {
    val afterIds = mutableListOf<Long?>()

    override suspend fun readPage(afterId: Long?, limit: Int): DocumentPage {
        afterIds += afterId
        return page
    }
}

private class FakeProgressiveScanStore(
    private val events: MutableList<String> = mutableListOf(),
    private var failNextPageCommit: Boolean = false,
) : ProgressiveScanStore {
    private val sessions = linkedMapOf<String, ScanSessionEntity>()
    private var nextId = 1

    override suspend fun activeOrCreate(): ScanSessionEntity {
        sessions.values.lastOrNull { it.status.isActive() }?.let { return it }
        val number = nextId++
        return ScanSessionEntity(
            id = "session-$number",
            sourceId = 7,
            scanId = "scan-$number",
            status = ScanSessionStatus.QUEUED,
            phase = ScanPhase.DISCOVERING,
            cursorId = null,
            checkedFiles = 0,
            discoveredDocuments = 0,
            failedFiles = 0,
            startedAt = number.toLong(),
            updatedAt = number.toLong(),
        ).also { sessions[it.id] = it }
    }

    override fun observeLatest(): Flow<ScanSessionEntity?> = flowOf(sessions.values.lastOrNull())

    override fun observeDirectoryCount(sessionId: String): Flow<Int> = flowOf(2)

    override suspend fun findSession(sessionId: String): ScanSessionEntity? = sessions[sessionId]

    override suspend fun markRunning(sessionId: String): ScanSessionEntity =
        update(sessionId) { current ->
            if (current.status == ScanSessionStatus.QUEUED) {
                current.copy(status = ScanSessionStatus.RUNNING)
            } else {
                current
            }
        }

    override suspend fun updatePhase(
        sessionId: String,
        phase: ScanPhase,
    ): ScanSessionEntity = update(sessionId) { it.copy(phase = phase) }

    override suspend fun commitPage(
        sessionId: String,
        page: DocumentPage,
        report: IndexBatchReport,
    ): ScanSessionEntity {
        if (failNextPageCommit) {
            failNextPageCommit = false
            throw IOException("process stopped before cursor commit")
        }
        events += "cursor-committed"
        return update(sessionId) { current ->
            current.copy(
                phase = if (page.isComplete) ScanPhase.FINALIZING else ScanPhase.DISCOVERING,
                cursorId = page.nextCursor ?: current.cursorId,
                checkedFiles = current.checkedFiles + page.checkedFiles,
                discoveredDocuments = current.discoveredDocuments + report.indexed,
                failedFiles = current.failedFiles + report.failed,
            )
        }
    }

    override suspend fun markCompleted(sessionId: String): ScanSessionEntity =
        update(sessionId) { current ->
            if (current.status == ScanSessionStatus.RUNNING) {
                current.copy(
                    status = ScanSessionStatus.COMPLETED,
                    phase = ScanPhase.COMPLETE,
                )
            } else {
                current
            }
        }

    override suspend fun markFailed(
        sessionId: String,
        message: String,
    ): ScanSessionEntity = update(sessionId) { current ->
        current.copy(status = ScanSessionStatus.FAILED, errorMessage = message)
    }

    override suspend fun pause(): ScanSessionEntity? =
        latestActive()?.takeUnless { it.phase == ScanPhase.FINALIZING }?.let { active ->
            update(active.id) { current -> current.copy(status = ScanSessionStatus.PAUSED) }
        }

    override suspend fun resume(): ScanSessionEntity? =
        sessions.values.lastOrNull { it.status == ScanSessionStatus.PAUSED }?.let { paused ->
            update(paused.id) { current -> current.copy(status = ScanSessionStatus.QUEUED) }
        }

    override suspend fun cancel(): ScanSessionEntity? =
        latestActive()?.takeUnless { it.phase == ScanPhase.FINALIZING }?.let { active ->
            update(active.id) { current -> current.copy(status = ScanSessionStatus.CANCELLED) }
        }

    override suspend fun countDirectories(sessionId: String): Int =
        if (sessions.containsKey(sessionId)) 2 else 0

    fun latest(): ScanSessionEntity = sessions.values.last()

    fun forceSession(session: ScanSessionEntity) {
        sessions[session.id] = session
    }

    private fun latestActive(): ScanSessionEntity? =
        sessions.values.lastOrNull { it.status.isActive() }

    private fun update(
        sessionId: String,
        transform: (ScanSessionEntity) -> ScanSessionEntity,
    ): ScanSessionEntity {
        val updated = transform(checkNotNull(sessions[sessionId]))
        sessions[sessionId] = updated
        return updated
    }
}

private class RecordingDocumentIndexStore(
    private val events: MutableList<String> = mutableListOf(),
    initialDocuments: List<DocumentEntity> = emptyList(),
    private val failUpsert: Boolean = false,
) : DocumentIndexStore {
    private val records = initialDocuments.associateBy { it.uri }.toMutableMap()

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val snapshot = records.toMap()
        return try {
            block().also { events += "documents-committed" }
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
        if (failUpsert) throw IOException("document write failed")
        val id = document.id.takeIf { it != 0L } ?: ((records.values.maxOfOrNull { it.id } ?: 0) + 1)
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
        records.replaceAll { _, document ->
            if (document.id in ids) document.copy(indexStatus = DocumentIndexStatus.MISSING) else document
        }
    }

    fun visibleUris(): List<String> = records.values.map { it.uri }

    fun statusOf(uri: String): DocumentIndexStatus = checkNotNull(records[uri]).indexStatus
}

private fun indexedDocument() = DocumentEntity(
    id = 1,
    uri = "content://docs/old.pdf",
    displayName = "old.pdf",
    normalizedName = "old",
    mimeType = "application/pdf",
    extension = "pdf",
    sizeBytes = 10,
    modifiedAt = 10,
    sourceId = 7,
    parentUri = "content://docs",
    lastSeenScanId = "older-scan",
)

private fun ScanSessionStatus.isActive(): Boolean =
    this == ScanSessionStatus.QUEUED ||
        this == ScanSessionStatus.RUNNING ||
        this == ScanSessionStatus.PAUSED
