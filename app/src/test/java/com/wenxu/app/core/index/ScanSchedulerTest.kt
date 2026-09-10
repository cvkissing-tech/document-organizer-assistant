package com.wenxu.app.core.index

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.permission.StorageAccessState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanSchedulerTest {
    @Test
    fun `start twice keeps one unique progressive scan`() = runTest {
        val store = SchedulerScanStore()
        val work = RecordingProgressiveWorkController()
        val scheduler = WorkManagerScanScheduler(
            store = store,
            workController = work,
            accessState = { StorageAccessState.Granted },
        )

        assertThat(scheduler.start()).isTrue()
        assertThat(scheduler.start()).isTrue()

        assertThat(work.activeRequests).containsExactly(
            PROGRESSIVE_SCAN_WORK_NAME to "session-1",
        )
        assertThat(store.createCalls).isEqualTo(1)
    }

    @Test
    fun `start refuses legacy or missing all files access without creating a session`() = runTest {
        listOf(StorageAccessState.Legacy, StorageAccessState.NeedsPermission).forEach { access ->
            val store = SchedulerScanStore()
            val work = RecordingProgressiveWorkController()
            val scheduler = WorkManagerScanScheduler(
                store = store,
                workController = work,
                accessState = { access },
            )

            assertThat(scheduler.start()).isFalse()
            assertThat(store.createCalls).isEqualTo(0)
            assertThat(work.activeRequests).isEmpty()
        }
    }

    @Test
    fun `pause cancels unique work and resume only restarts an existing paused session`() = runTest {
        val store = SchedulerScanStore()
        val work = RecordingProgressiveWorkController()
        val scheduler = WorkManagerScanScheduler(
            store = store,
            workController = work,
            accessState = { StorageAccessState.Granted },
        )
        scheduler.start()

        assertThat(scheduler.pause()).isTrue()
        assertThat(work.cancelCalls).isEqualTo(1)
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.PAUSED)

        assertThat(scheduler.resume()).isTrue()
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.QUEUED)
        assertThat(work.replaceCalls).isEqualTo(1)

        assertThat(scheduler.resume()).isFalse()
        assertThat(work.replaceCalls).isEqualTo(1)
    }

    @Test
    fun `cancel marks session before cancelling unique work`() = runTest {
        val events = mutableListOf<String>()
        val store = SchedulerScanStore(events)
        val work = RecordingProgressiveWorkController(events)
        val scheduler = WorkManagerScanScheduler(
            store = store,
            workController = work,
            accessState = { StorageAccessState.Granted },
        )
        scheduler.start()

        assertThat(scheduler.cancel()).isTrue()

        assertThat(events.takeLast(2)).containsExactly("session-cancelled", "work-cancelled").inOrder()
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.CANCELLED)
    }

    @Test
    fun `worker retries only when queued or running chunk remains incomplete`() = runTest {
        val store = SchedulerScanStore()
        val session = store.activeOrCreate()
        val chunkRunner = RecordingChunkRunner(
            result = session.toChunkResult(
                status = ScanSessionStatus.RUNNING,
                complete = false,
            ),
        )
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = { chunkRunner },
            accessState = { StorageAccessState.Granted },
        )

        assertThat(runner.run(session.id)).isEqualTo(ProgressiveWorkerOutcome.RETRY)
        assertThat(chunkRunner.sessionIds).containsExactly(session.id)
    }

    @Test
    fun `worker succeeds without running engine for paused cancelled or completed sessions`() = runTest {
        listOf(
            ScanSessionStatus.PAUSED,
            ScanSessionStatus.CANCELLED,
            ScanSessionStatus.COMPLETED,
        ).forEach { status ->
            val store = SchedulerScanStore().apply {
                activeOrCreate()
                forceStatus(status)
            }
            val chunkRunner = RecordingChunkRunner()
            val runner = ProgressiveScanWorkerRunner(
                store = store,
                chunkRunnerProvider = { chunkRunner },
                accessState = { StorageAccessState.Granted },
            )

            assertThat(runner.run(store.latest().id)).isEqualTo(ProgressiveWorkerOutcome.SUCCESS)
            assertThat(chunkRunner.sessionIds).isEmpty()
            assertThat(store.createCalls).isEqualTo(1)
        }
    }

    @Test
    fun `unrecoverable worker error marks exact session failed`() = runTest {
        val store = SchedulerScanStore()
        val session = store.activeOrCreate()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = {
                RecordingChunkRunner(failure = IOException("媒体库读取失败"))
            },
            accessState = { StorageAccessState.Granted },
        )

        assertThat(runner.run(session.id)).isEqualTo(ProgressiveWorkerOutcome.FAILURE)
        assertThat(store.latest().status).isEqualTo(ScanSessionStatus.FAILED)
        assertThat(store.latest().errorMessage).isEqualTo("媒体库读取失败")
    }

    @Test(expected = CancellationException::class)
    fun `worker cancellation is not converted to failed session`() = runTest {
        val store = SchedulerScanStore()
        val session = store.activeOrCreate()
        val runner = ProgressiveScanWorkerRunner(
            store = store,
            chunkRunnerProvider = {
                RecordingChunkRunner(failure = CancellationException("paused"))
            },
            accessState = { StorageAccessState.Granted },
        )

        runner.run(session.id)
    }
}

private class SchedulerScanStore(
    private val events: MutableList<String> = mutableListOf(),
) : ProgressiveScanStore {
    private val latestFlow = MutableStateFlow<ScanSessionEntity?>(null)
    var createCalls: Int = 0
        private set

    override suspend fun activeOrCreate(): ScanSessionEntity {
        latestFlow.value?.takeIf { it.status.isActive() }?.let { return it }
        createCalls++
        return scanSession().also { latestFlow.value = it }
    }

    override fun observeLatest(): Flow<ScanSessionEntity?> = latestFlow

    override fun observeDirectoryCount(sessionId: String): Flow<Int> = MutableStateFlow(3)

    override suspend fun findSession(sessionId: String): ScanSessionEntity? =
        latestFlow.value?.takeIf { it.id == sessionId }

    override suspend fun markRunning(sessionId: String): ScanSessionEntity =
        update { current ->
            if (current.status == ScanSessionStatus.QUEUED) {
                current.copy(status = ScanSessionStatus.RUNNING)
            } else {
                current
            }
        }

    override suspend fun updatePhase(sessionId: String, phase: ScanPhase): ScanSessionEntity =
        update { it.copy(phase = phase) }

    override suspend fun commitPage(
        sessionId: String,
        page: DocumentPage,
        report: IndexBatchReport,
    ): ScanSessionEntity = update { it }

    override suspend fun markCompleted(sessionId: String): ScanSessionEntity =
        update { it.copy(status = ScanSessionStatus.COMPLETED, phase = ScanPhase.COMPLETE) }

    override suspend fun markFailed(sessionId: String, message: String): ScanSessionEntity =
        update { it.copy(status = ScanSessionStatus.FAILED, errorMessage = message) }

    override suspend fun pause(): ScanSessionEntity? = latestFlow.value
        ?.takeIf { it.status == ScanSessionStatus.QUEUED || it.status == ScanSessionStatus.RUNNING }
        ?.let {
            events += "session-paused"
            update { current -> current.copy(status = ScanSessionStatus.PAUSED) }
        }

    override suspend fun resume(): ScanSessionEntity? = latestFlow.value
        ?.takeIf { it.status == ScanSessionStatus.PAUSED }
        ?.let {
            events += "session-resumed"
            update { current -> current.copy(status = ScanSessionStatus.QUEUED) }
        }

    override suspend fun cancel(): ScanSessionEntity? = latestFlow.value
        ?.takeIf { it.status.isActive() }
        ?.let {
            events += "session-cancelled"
            update { current -> current.copy(status = ScanSessionStatus.CANCELLED) }
        }

    override suspend fun countDirectories(sessionId: String): Int = 3

    fun latest(): ScanSessionEntity = checkNotNull(latestFlow.value)

    fun forceStatus(status: ScanSessionStatus) {
        latestFlow.value = latest().copy(status = status)
    }

    private fun update(transform: (ScanSessionEntity) -> ScanSessionEntity): ScanSessionEntity =
        transform(latest()).also { latestFlow.value = it }
}

private class RecordingProgressiveWorkController(
    private val events: MutableList<String> = mutableListOf(),
) : ProgressiveWorkController {
    private val requests = linkedMapOf<String, String>()
    var replaceCalls = 0
        private set
    var cancelCalls = 0
        private set

    val activeRequests: List<Pair<String, String>>
        get() = requests.map { it.key to it.value }

    override fun enqueueUnique(workName: String, sessionId: String, replace: Boolean) {
        if (replace) {
            replaceCalls++
            requests[workName] = sessionId
        } else {
            requests.putIfAbsent(workName, sessionId)
        }
    }

    override fun cancelUnique(workName: String) {
        cancelCalls++
        events += "work-cancelled"
        requests.remove(workName)
    }
}

private class RecordingChunkRunner(
    private val result: ScanChunkResult? = null,
    private val failure: Throwable? = null,
) : ProgressiveChunkRunner {
    val sessionIds = mutableListOf<String>()

    override suspend fun runChunk(sessionId: String, maxPages: Int): ScanChunkResult {
        sessionIds += sessionId
        failure?.let { throw it }
        return checkNotNull(result)
    }
}

private fun scanSession() = ScanSessionEntity(
    id = "session-1",
    sourceId = 7,
    scanId = "scan-1",
    status = ScanSessionStatus.QUEUED,
    phase = ScanPhase.DISCOVERING,
    cursorId = null,
    checkedFiles = 0,
    discoveredDocuments = 0,
    failedFiles = 0,
    startedAt = 1,
    updatedAt = 1,
)

private fun ScanSessionEntity.toChunkResult(
    status: ScanSessionStatus,
    complete: Boolean,
) = ScanChunkResult(
    sessionId = id,
    scanId = scanId,
    complete = complete,
    status = status,
    checkedDirectories = 3,
    checkedFiles = checkedFiles,
    discoveredDocuments = discoveredDocuments,
)

private fun ScanSessionStatus.isActive(): Boolean =
    this == ScanSessionStatus.QUEUED ||
        this == ScanSessionStatus.RUNNING ||
        this == ScanSessionStatus.PAUSED
