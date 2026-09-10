package com.wenxu.app.core.relations

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.dao.RelationAnalysisDao
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

const val RELATION_WORK_NAME = "document-assistant-relation-analysis"

enum class RelationAnalysisReason {
    SCAN_COMPLETED,
    IMPORT,
    TRASH,
    RESTORE,
    DELETE_FOREVER,
    USER_FEEDBACK,
}

interface RelationAnalysisScheduleStore {
    suspend fun queue(reason: RelationAnalysisReason): Long
    suspend fun currentState(): RelationAnalysisStateEntity?
    suspend fun latestGeneration(): Long?
    suspend fun queuedGeneration(): Long?
    suspend fun queueCurrentRunForRetry(
        generation: Long,
        runToken: String,
        errorMessage: String,
    ): Boolean
    suspend fun cancelCurrentRun(generation: Long, runToken: String): Boolean
    suspend fun markEnqueueFailed(generation: Long, message: String): Boolean
    suspend fun failCurrentRun(generation: Long, runToken: String, message: String): Boolean
}

internal interface RelationWorkController {
    suspend fun enqueueUnique(workName: String, mode: RelationEnqueueMode)
}

internal enum class RelationEnqueueMode {
    REPLACE,
    KEEP,
}

internal object CancellableWorkOperationAwaiter {
    suspend fun await(future: ListenableFuture<*>) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) future.cancel(true)
            }
            future.addListener(
                {
                    if (!completed.compareAndSet(false, true)) return@addListener
                    try {
                        future.get()
                        continuation.resume(Unit)
                    } catch (cancelled: FutureCancellationException) {
                        continuation.cancel(cancelled)
                    } catch (wrapped: ExecutionException) {
                        val error = wrapped.cause ?: wrapped
                        continuation.resumeWithException(error)
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                },
                DIRECT_EXECUTOR,
            )
        }
    }

    private val DIRECT_EXECUTOR = Executor { command -> command.run() }
}

internal class WorkManagerRelationWorkController(
    private val workManager: () -> WorkManager,
) : RelationWorkController {
    override suspend fun enqueueUnique(workName: String, mode: RelationEnqueueMode) {
        val request = OneTimeWorkRequestBuilder<RelationAnalysisWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, MINIMUM_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(workName)
            .build()
        val operation = workManager().enqueueUniqueWork(
                workName,
                when (mode) {
                    RelationEnqueueMode.REPLACE -> ExistingWorkPolicy.REPLACE
                    RelationEnqueueMode.KEEP -> ExistingWorkPolicy.KEEP
                },
                request,
            )
        withTimeout(ENQUEUE_TIMEOUT_MILLIS) {
            CancellableWorkOperationAwaiter.await(operation.result)
        }
    }

    private companion object {
        const val MINIMUM_BACKOFF_SECONDS = 10L
        const val ENQUEUE_TIMEOUT_MILLIS = 5_000L
    }
}

class RelationAnalysisScheduler internal constructor(
    private val store: RelationAnalysisScheduleStore,
    private val workController: RelationWorkController,
) {
    constructor(context: Context, database: WenxuDatabase) : this(
        store = RoomRelationAnalysisScheduleStore(database),
        workController = WorkManagerRelationWorkController {
            WorkManager.getInstance(context.applicationContext)
        },
    )

    suspend fun request(reason: RelationAnalysisReason): Long {
        val generation = store.queue(reason)
        enqueueOrPreserveQueued(generation, RelationEnqueueMode.REPLACE)
        return generation
    }

    suspend fun reconcileQueued(): Boolean {
        val generation = store.queuedGeneration() ?: return false
        enqueueOrPreserveQueued(generation, RelationEnqueueMode.KEEP)
        return true
    }

    suspend fun enqueueExistingQueued(expectedGeneration: Long): Boolean {
        val generation = store.queuedGeneration()
        if (generation == null) {
            // The worker may have claimed (or even completed) this generation
            // between the feedback transaction and this enqueue call. In that
            // case the durable request has already been observed.
            val state = store.currentState() ?: return false
            return state.generation >= expectedGeneration &&
                state.status in setOf(
                    RelationAnalysisStatus.RUNNING,
                    RelationAnalysisStatus.COMPLETED,
                )
        }
        if (generation < expectedGeneration) return false
        // Feedback creates a new generation while an older unique worker may
        // still be running. REPLACE guarantees that the new generation gets a
        // successor instead of being discarded by WorkManager's KEEP policy.
        enqueueOrPreserveQueued(generation, RelationEnqueueMode.REPLACE)
        return true
    }

    suspend fun retryQueued(): Boolean {
        val generation = store.queuedGeneration() ?: return false
        enqueueOrPreserveQueued(generation, RelationEnqueueMode.REPLACE)
        return true
    }

    private suspend fun enqueueOrPreserveQueued(
        generation: Long,
        mode: RelationEnqueueMode,
    ) {
        try {
            workController.enqueueUnique(RELATION_WORK_NAME, mode)
        } catch (cancelled: CancellationException) {
            preserveQueuedAfterEnqueueFailure(generation)
            throw cancelled
        } catch (error: Throwable) {
            preserveQueuedAfterEnqueueFailure(generation)
            throw error
        }
    }

    private suspend fun preserveQueuedAfterEnqueueFailure(generation: Long) {
        withContext(NonCancellable) {
            runCatching { store.markEnqueueFailed(generation, ENQUEUE_ERROR) }
        }
    }

    private companion object {
        const val ENQUEUE_ERROR = "relation_analysis_enqueue_failed"
    }
}

class RoomRelationAnalysisScheduleStore(
    private val database: WenxuDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : RelationAnalysisScheduleStore {
    private val dao = database.relationAnalysisDao()

    override suspend fun queue(reason: RelationAnalysisReason): Long = database.withTransaction {
        queueRelationAnalysisState(dao, now())
    }

    override suspend fun currentState(): RelationAnalysisStateEntity? = dao.getState()

    override suspend fun latestGeneration(): Long? = dao.getState()?.generation

    override suspend fun queuedGeneration(): Long? = dao.getState()
        ?.takeIf { state -> state.status == RelationAnalysisStatus.QUEUED }
        ?.generation

    override suspend fun queueCurrentRunForRetry(
        generation: Long,
        runToken: String,
        errorMessage: String,
    ): Boolean = dao.queueCurrentRunForRetry(
        generation = generation,
        runMarker = relationRunMarker(runToken),
        errorMessage = errorMessage.take(MAX_ERROR_LENGTH),
        updatedAt = now(),
    ) == 1

    override suspend fun cancelCurrentRun(generation: Long, runToken: String): Boolean =
        dao.cancelIfCurrentRun(
            generation = generation,
            runMarker = relationRunMarker(runToken),
            updatedAt = now(),
        ) == 1

    override suspend fun markEnqueueFailed(generation: Long, message: String): Boolean =
        updateStatusIfGeneration(
            generation = generation,
            status = RelationAnalysisStatus.QUEUED,
            errorMessage = message.take(MAX_ERROR_LENGTH),
            expectedCurrentStatus = RelationAnalysisStatus.QUEUED,
        )

    override suspend fun failCurrentRun(
        generation: Long,
        runToken: String,
        message: String,
    ): Boolean = dao.failIfCurrentRun(
        generation = generation,
        runMarker = relationRunMarker(runToken),
        failedCount = dao.getState()?.failedCount ?: 0,
        errorMessage = message.take(MAX_ERROR_LENGTH),
        updatedAt = now(),
    ) == 1

    private suspend fun updateStatusIfGeneration(
        generation: Long,
        status: RelationAnalysisStatus,
        errorMessage: String?,
        expectedCurrentStatus: RelationAnalysisStatus? = null,
    ): Boolean = database.withTransaction {
        val current = dao.getState() ?: return@withTransaction false
        if (current.generation != generation) return@withTransaction false
        if (expectedCurrentStatus != null && current.status != expectedCurrentStatus) {
            return@withTransaction false
        }
        dao.updateStateIfGeneration(
            generation = generation,
            status = status,
            phase = current.phase,
            processedCount = current.processedCount,
            candidateCount = current.candidateCount,
            failedCount = current.failedCount,
            errorMessage = errorMessage,
            updatedAt = now(),
        ) == 1
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}

internal suspend fun queueRelationAnalysisState(
    dao: RelationAnalysisDao,
    updatedAt: Long,
): Long {
    val current = dao.getState()
    val nextGeneration = (current?.generation ?: 0L) + 1L
    dao.upsertState(
        RelationAnalysisStateEntity(
            status = RelationAnalysisStatus.QUEUED,
            phase = RelationAnalysisPhase.EXACT_DUPLICATES,
            processedCount = 0,
            candidateCount = 0,
            failedCount = 0,
            generation = nextGeneration,
            errorMessage = null,
            updatedAt = updatedAt,
        ),
    )
    return nextGeneration
}
