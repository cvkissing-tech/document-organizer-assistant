package com.wenxu.app.core.relations

import android.content.Context
import android.os.DeadObjectException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class RelationWorkerOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

private enum class RunStateWriteResult {
    APPLIED,
    STALE,
    ERROR,
}

class RelationAnalysisWorkerRunner(
    private val store: RelationAnalysisScheduleStore,
    private val analysis: suspend (Long, String) -> RelationAnalysisReport,
) {
    suspend fun run(
        runAttemptCount: Int = 0,
        runToken: String,
    ): RelationWorkerOutcome {
        val generation = store.latestGeneration() ?: return RelationWorkerOutcome.SUCCESS
        return try {
            analysis(generation, runToken)
            RelationWorkerOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            cancelCurrentRunSafely(generation, runToken)
            throw cancelled
        } catch (error: IOException) {
            temporaryFailure(generation, runToken, runAttemptCount, error)
        } catch (error: DeadObjectException) {
            temporaryFailure(generation, runToken, runAttemptCount, error)
        } catch (error: SecurityException) {
            temporaryFailure(generation, runToken, runAttemptCount, error)
        } catch (error: Exception) {
            permanentFailure(generation, runToken, error)
        }
    }

    private suspend fun temporaryFailure(
        generation: Long,
        runToken: String,
        runAttemptCount: Int,
        error: Exception,
    ): RelationWorkerOutcome {
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            when (queueCurrentRunForRetrySafely(generation, runToken, error.safeCode())) {
                RunStateWriteResult.APPLIED -> RelationWorkerOutcome.RETRY
                RunStateWriteResult.STALE -> RelationWorkerOutcome.SUCCESS
                RunStateWriteResult.ERROR -> RelationWorkerOutcome.RETRY
            }
        } else {
            permanentFailure(generation, runToken, error)
        }
    }

    private suspend fun permanentFailure(
        generation: Long,
        runToken: String,
        error: Exception,
    ): RelationWorkerOutcome = when (
        failCurrentRunSafely(generation, runToken, error.safeCode())
    ) {
        RunStateWriteResult.APPLIED, RunStateWriteResult.ERROR -> RelationWorkerOutcome.FAILURE
        RunStateWriteResult.STALE -> RelationWorkerOutcome.SUCCESS
    }

    private suspend fun cancelCurrentRunSafely(generation: Long, runToken: String) {
        writeStateSafely {
            store.cancelCurrentRun(generation, runToken)
        }
    }

    private suspend fun queueCurrentRunForRetrySafely(
        generation: Long,
        runToken: String,
        errorCode: String,
    ): RunStateWriteResult = writeStateSafely {
        store.queueCurrentRunForRetry(generation, runToken, errorCode)
    }

    private suspend fun failCurrentRunSafely(
        generation: Long,
        runToken: String,
        errorCode: String,
    ): RunStateWriteResult = writeStateSafely {
        store.failCurrentRun(generation, runToken, errorCode)
    }

    private suspend fun writeStateSafely(
        write: suspend () -> Boolean,
    ): RunStateWriteResult = withContext(NonCancellable) {
        try {
            if (write()) RunStateWriteResult.APPLIED else RunStateWriteResult.STALE
        } catch (_: Exception) {
            RunStateWriteResult.ERROR
        }
    }

    private fun Exception.safeCode(): String = when (this) {
        is IOException -> "relation_analysis_io_failed"
        is DeadObjectException -> "relation_analysis_system_interrupted"
        is SecurityException -> "relation_analysis_permission_denied"
        is IllegalArgumentException -> "relation_analysis_invalid_argument"
        is IllegalStateException -> "relation_analysis_invalid_state"
        else -> "relation_analysis_failed"
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

class RelationAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runner: RelationAnalysisWorkerRunner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        runner.run(runAttemptCount = runAttemptCount, runToken = id.toString())
    ) {
        RelationWorkerOutcome.SUCCESS -> Result.success()
        RelationWorkerOutcome.RETRY -> Result.retry()
        RelationWorkerOutcome.FAILURE -> Result.failure()
    }
}
