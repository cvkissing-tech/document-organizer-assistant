package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RelationAnalysisSchedulerTest {
    @Test
    fun consecutiveRequestsProduceOnePendingGeneration() = runTest {
        val store = FakeRelationAnalysisScheduleStore()
        val workController = RecordingRelationWorkController()
        val scheduler = RelationAnalysisScheduler(store, workController)

        scheduler.request(RelationAnalysisReason.IMPORT)
        scheduler.request(RelationAnalysisReason.RESTORE)

        assertThat(store.latestGeneration()).isEqualTo(2)
        assertThat(workController.pendingNames).containsExactly(RELATION_WORK_NAME)
        assertThat(workController.requestCount).isEqualTo(2)
        assertThat(workController.modes).containsExactly(
            RelationEnqueueMode.REPLACE,
            RelationEnqueueMode.REPLACE,
        ).inOrder()
    }

    @Test
    fun workerRunsOnlyTheLatestQueuedGeneration() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            queue(RelationAnalysisReason.TRASH)
        }
        val generations = mutableListOf<Long>()
        val runTokens = mutableListOf<String>()
        val runner = RelationAnalysisWorkerRunner(
            store = store,
            analysis = { generation, runToken ->
                generations += generation
                runTokens += runToken
                RelationAnalysisReport(true, 0, 0, 0, 0)
            },
        )

        val outcome = runner.run(runToken = "worker-uuid")

        assertThat(outcome).isEqualTo(RelationWorkerOutcome.SUCCESS)
        assertThat(generations).containsExactly(2L)
        assertThat(runTokens).containsExactly("worker-uuid")
    }

    @Test
    fun workerRetriesTemporaryFailuresButFailsInvalidStructure() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
        }

        val temporary = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw IOException("provider busy")
        }
        val invalid = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw IllegalArgumentException("invalid")
        }

        assertThat(temporary.run(runAttemptCount = 0, runToken = "temporary"))
            .isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(invalid.run(runAttemptCount = 0, runToken = "invalid"))
            .isEqualTo(RelationWorkerOutcome.FAILURE)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.FAILED)
    }

    @Test
    fun workerNeverSwallowsCancellation() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
        }
        val runner = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw CancellationException("stop")
        }

        val error = runCatching { runner.run(runToken = "cancelled") }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
    }

    @Test
    fun securityFailureRetriesBeforeThirdAttemptButUnknownFailureIsPermanent() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
        }

        val security = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw SecurityException("revoked")
        }
        val unknown = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw RuntimeException("broken")
        }

        assertThat(security.run(0, "security-1")).isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(security.run(2, "security-2")).isEqualTo(RelationWorkerOutcome.FAILURE)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.FAILED)
        assertThat(unknown.run(0, "unknown")).isEqualTo(RelationWorkerOutcome.FAILURE)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.FAILED)
    }

    @Test
    fun temporaryFailureStopsRetryingOnThirdAttempt() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
        }
        val runner = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw IOException("busy")
        }

        assertThat(runner.run(0, "attempt-1")).isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(runner.run(1, "attempt-2")).isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(runner.run(2, "attempt-3")).isEqualTo(RelationWorkerOutcome.FAILURE)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.FAILED)
    }

    @Test
    fun enqueueFailureKeepsGenerationQueuedForStartupRecoveryAndRethrows() = runTest {
        val store = FakeRelationAnalysisScheduleStore()
        val scheduler = RelationAnalysisScheduler(
            store,
            RecordingRelationWorkController(failure = IOException("enqueue failed")),
        )

        val error = runCatching {
            scheduler.request(RelationAnalysisReason.SCAN_COMPLETED)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(store.errorMessage).isEqualTo("relation_analysis_enqueue_failed")

        val controller = RecordingRelationWorkController()
        val recovered = RelationAnalysisScheduler(store, controller).reconcileQueued()

        assertThat(recovered).isTrue()
        assertThat(store.latestGeneration()).isEqualTo(1)
        assertThat(controller.modes).containsExactly(RelationEnqueueMode.KEEP)
    }

    @Test
    fun enqueueCancellationPreservesQueuedGenerationAndRethrowsCancellation() = runTest {
        val store = FakeRelationAnalysisScheduleStore()
        val scheduler = RelationAnalysisScheduler(
            store,
            RecordingRelationWorkController(failure = CancellationException("cancelled")),
        )

        val error = runCatching {
            scheduler.request(RelationAnalysisReason.SCAN_COMPLETED)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(store.errorMessage).isEqualTo("relation_analysis_enqueue_failed")
    }

    @Test
    fun startupReconcilesQueuedGenerationWithoutCreatingAnotherGeneration() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
        }
        val controller = RecordingRelationWorkController()
        val scheduler = RelationAnalysisScheduler(store, controller)

        val reconciled = scheduler.reconcileQueued()

        assertThat(reconciled).isTrue()
        assertThat(store.latestGeneration()).isEqualTo(1)
        assertThat(controller.pendingNames).containsExactly(RELATION_WORK_NAME)
        assertThat(controller.modes).containsExactly(RelationEnqueueMode.KEEP)
    }

    @Test
    fun feedbackEnqueuesItsAlreadyPersistedGenerationWithoutIncrementingIt() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
        }
        val controller = RecordingRelationWorkController()

        val enqueued = RelationAnalysisScheduler(store, controller)
            .enqueueExistingQueued(expectedGeneration = 1L)

        assertThat(enqueued).isTrue()
        assertThat(store.latestGeneration()).isEqualTo(1L)
        assertThat(controller.modes).containsExactly(RelationEnqueueMode.REPLACE)
    }

    @Test
    fun feedbackReplacesAnOlderUniqueWorkerSoItsGenerationIsNotLost() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            forceRunning("old-worker")
            queue(RelationAnalysisReason.USER_FEEDBACK)
        }
        val controller = RecordingRelationWorkController()

        val enqueued = RelationAnalysisScheduler(store, controller)
            .enqueueExistingQueued(expectedGeneration = 2L)

        assertThat(enqueued).isTrue()
        assertThat(controller.modes).containsExactly(RelationEnqueueMode.REPLACE)
    }

    @Test
    fun feedbackGenerationAlreadyClaimedByWorkerCountsAsScheduled() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
            forceRunning("new-worker")
        }
        val controller = RecordingRelationWorkController()

        val enqueued = RelationAnalysisScheduler(store, controller)
            .enqueueExistingQueued(expectedGeneration = 1L)

        assertThat(enqueued).isTrue()
        assertThat(controller.requestCount).isEqualTo(0)
    }

    @Test
    fun feedbackGenerationAlreadyCompletedCountsAsScheduled() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
            forceCompleted()
        }

        val enqueued = RelationAnalysisScheduler(store, RecordingRelationWorkController())
            .enqueueExistingQueued(expectedGeneration = 1L)

        assertThat(enqueued).isTrue()
    }

    @Test
    fun newerRunningGenerationCountsAsScheduledForOlderFeedback() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
            queue(RelationAnalysisReason.TRASH)
            forceRunning("newer-worker")
        }

        val enqueued = RelationAnalysisScheduler(store, RecordingRelationWorkController())
            .enqueueExistingQueued(expectedGeneration = 1L)

        assertThat(enqueued).isTrue()
    }

    @Test
    fun failedFeedbackGenerationIsNotReportedAsScheduled() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
            forceFailed()
        }

        val enqueued = RelationAnalysisScheduler(store, RecordingRelationWorkController())
            .enqueueExistingQueued(expectedGeneration = 1L)

        assertThat(enqueued).isFalse()
    }

    @Test
    fun explicitRetryReplacesPossiblyStaleUniqueWorkerWithoutNewGeneration() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.USER_FEEDBACK)
        }
        val controller = RecordingRelationWorkController()

        val retried = RelationAnalysisScheduler(store, controller).retryQueued()

        assertThat(retried).isTrue()
        assertThat(store.latestGeneration()).isEqualTo(1L)
        assertThat(controller.modes).containsExactly(RelationEnqueueMode.REPLACE)
    }

    @Test
    fun failedOldEnqueueCannotChangeNewerGenerationState() = runTest {
        val store = FakeRelationAnalysisScheduleStore()
        val controller = RecordingRelationWorkController(
            onEnqueue = {
                store.queue(RelationAnalysisReason.TRASH)
                throw IOException("old enqueue failed")
            },
        )

        runCatching {
            RelationAnalysisScheduler(store, controller)
                .request(RelationAnalysisReason.SCAN_COMPLETED)
        }

        assertThat(store.latestGeneration()).isEqualTo(2)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(store.errorMessage).isNull()
    }

    @Test
    fun enqueueFailureCannotOverwriteRunningStateOfSameGeneration() = runTest {
        val store = FakeRelationAnalysisScheduleStore()
        val controller = RecordingRelationWorkController(
            onEnqueue = {
                store.forceRunning()
                throw IOException("late enqueue failure")
            },
        )

        runCatching {
            RelationAnalysisScheduler(store, controller)
                .request(RelationAnalysisReason.SCAN_COMPLETED)
        }

        assertThat(store.latestGeneration()).isEqualTo(1)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.RUNNING)
        assertThat(store.errorMessage).isEqualTo(relationRunMarker("direct-worker"))
    }

    @Test
    fun workerOutcomeSurvivesStateWriteFailure() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            failStatusWrites = true
        }

        val retry = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw IOException("busy")
        }
        val failure = RelationAnalysisWorkerRunner(store) { _, token ->
            store.forceRunning(token)
            throw RuntimeException("broken")
        }

        assertThat(retry.run(0, "retry")).isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(failure.run(0, "failure")).isEqualTo(RelationWorkerOutcome.FAILURE)
    }

    @Test
    fun cancelledOldWorkerCannotMoveCompletedLatestGenerationBackToQueued() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            queue(RelationAnalysisReason.TRASH)
            forceCompleted()
        }
        val lateOldWorker = RelationAnalysisWorkerRunner(store) { _, _ ->
            throw CancellationException("replaced after latest generation completed")
        }

        val error = runCatching { lateOldWorker.run(0, "old-token") }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(store.latestGeneration()).isEqualTo(2)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.COMPLETED)
    }

    @Test
    fun cancellationCanQueueOnlyItsRunningToken() = runTest {
        val runningStore = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            forceRunning("running-token")
        }
        val cancelledWorker = RelationAnalysisWorkerRunner(runningStore) { _, _ ->
            throw CancellationException("replaced")
        }

        runCatching { cancelledWorker.run(0, "running-token") }

        assertThat(runningStore.status).isEqualTo(RelationAnalysisStatus.QUEUED)
    }

    @Test
    fun transientOldWorkerBecomesSuccessWhenNewTokenTakesOverBeforeRetryCas() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            forceRunning("token-A")
        }
        val oldWorker = RelationAnalysisWorkerRunner(store) { _, _ ->
            store.forceRunning("token-B")
            throw IOException("token-A finished late")
        }

        val outcome = oldWorker.run(runAttemptCount = 0, runToken = "token-A")

        assertThat(outcome).isEqualTo(RelationWorkerOutcome.SUCCESS)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.RUNNING)
        assertThat(store.errorMessage).isEqualTo(relationRunMarker("token-B"))
    }

    @Test
    fun transientCurrentWorkerQueuesAtomicallyAndRetries() = runTest {
        val store = FakeRelationAnalysisScheduleStore().apply {
            queue(RelationAnalysisReason.SCAN_COMPLETED)
            forceRunning("token-A")
        }
        val worker = RelationAnalysisWorkerRunner(store) { _, _ ->
            throw IOException("temporary")
        }

        val outcome = worker.run(runAttemptCount = 0, runToken = "token-A")

        assertThat(outcome).isEqualTo(RelationWorkerOutcome.RETRY)
        assertThat(store.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(store.errorMessage).isEqualTo("relation_analysis_io_failed")
    }

    @Test
    fun neverCompletingWorkOperationCanBeCancelledByTimeout() = runTest {
        val future = SettableFuture.create<Void>()

        val error = runCatching {
            withTimeout(1) {
                CancellableWorkOperationAwaiter.await(future)
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(future.isCancelled).isTrue()
    }

    @Test
    fun runningLeaseMarkerIsNeverExposedAsUiError() {
        val running = RelationAnalysisStateEntity(
            status = RelationAnalysisStatus.RUNNING,
            phase = RelationAnalysisPhase.NAME_CANDIDATES,
            processedCount = 1,
            candidateCount = 2,
            failedCount = 0,
            generation = 3,
            errorMessage = relationRunMarker("worker-uuid"),
            updatedAt = 4,
        )
        val failed = running.copy(
            status = RelationAnalysisStatus.FAILED,
            errorMessage = "relation_analysis_failed",
        )

        assertThat(running.userVisibleErrorMessage).isNull()
        assertThat(failed.userVisibleErrorMessage).isEqualTo("relation_analysis_failed")
    }
}

private class FakeRelationAnalysisScheduleStore : RelationAnalysisScheduleStore {
    private var generation: Long? = null
    var status: RelationAnalysisStatus? = null
        private set
    var errorMessage: String? = null
        private set
    var failStatusWrites: Boolean = false

    override suspend fun queue(reason: RelationAnalysisReason): Long =
        ((generation ?: 0L) + 1L).also {
            generation = it
            status = RelationAnalysisStatus.QUEUED
            errorMessage = null
        }

    override suspend fun currentState(): RelationAnalysisStateEntity? {
        val currentGeneration = generation ?: return null
        val currentStatus = status ?: return null
        return RelationAnalysisStateEntity(
            status = currentStatus,
            phase = RelationAnalysisPhase.EXACT_DUPLICATES,
            processedCount = 0,
            candidateCount = 0,
            failedCount = if (currentStatus == RelationAnalysisStatus.FAILED) 1 else 0,
            generation = currentGeneration,
            errorMessage = errorMessage,
            updatedAt = 0,
        )
    }

    override suspend fun latestGeneration(): Long? = generation

    override suspend fun queuedGeneration(): Long? =
        generation.takeIf { status == RelationAnalysisStatus.QUEUED }

    override suspend fun queueCurrentRunForRetry(
        generation: Long,
        runToken: String,
        errorMessage: String,
    ): Boolean {
        if (status != RelationAnalysisStatus.RUNNING ||
            this.errorMessage != relationRunMarker(runToken)
        ) {
            return false
        }
        return updateIfCurrent(generation, RelationAnalysisStatus.QUEUED, errorMessage)
    }

    override suspend fun cancelCurrentRun(generation: Long, runToken: String): Boolean {
        if (status != RelationAnalysisStatus.RUNNING || errorMessage != relationRunMarker(runToken)) {
            return false
        }
        return updateIfCurrent(generation, RelationAnalysisStatus.QUEUED, null)
    }

    override suspend fun markEnqueueFailed(generation: Long, message: String): Boolean {
        if (status != RelationAnalysisStatus.QUEUED) return false
        return updateIfCurrent(generation, RelationAnalysisStatus.QUEUED, message)
    }

    override suspend fun failCurrentRun(
        generation: Long,
        runToken: String,
        message: String,
    ): Boolean {
        if (status != RelationAnalysisStatus.RUNNING || errorMessage != relationRunMarker(runToken)) {
            return false
        }
        return updateIfCurrent(generation, RelationAnalysisStatus.FAILED, message)
    }

    private fun updateIfCurrent(
        generation: Long,
        newStatus: RelationAnalysisStatus,
        errorMessage: String?,
    ): Boolean {
        if (failStatusWrites) throw IOException("state write failed")
        if (this.generation != generation) return false
        status = newStatus
        this.errorMessage = errorMessage
        return true
    }

    fun forceRunning(runToken: String = "direct-worker") {
        status = RelationAnalysisStatus.RUNNING
        errorMessage = relationRunMarker(runToken)
    }

    fun forceCompleted() {
        status = RelationAnalysisStatus.COMPLETED
        errorMessage = null
    }

    fun forceFailed() {
        status = RelationAnalysisStatus.FAILED
        errorMessage = "relation_analysis_failed"
    }

}

private class RecordingRelationWorkController(
    private val failure: Throwable? = null,
    private val onEnqueue: suspend () -> Unit = {},
) : RelationWorkController {
    val pendingNames = linkedSetOf<String>()
    var requestCount = 0
    val modes = mutableListOf<RelationEnqueueMode>()

    override suspend fun enqueueUnique(workName: String, mode: RelationEnqueueMode) {
        onEnqueue()
        failure?.let { throw it }
        requestCount++
        pendingNames += workName
        modes += mode
    }
}
