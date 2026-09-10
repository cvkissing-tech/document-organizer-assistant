package com.wenxu.app.core.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.relations.RelationAnalysisReason
import com.wenxu.app.core.relations.RelationAnalysisWorker
import com.wenxu.app.core.relations.RelationAnalysisWorkerRunner
import kotlinx.coroutines.CancellationException

enum class ProgressiveWorkerOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

class ProgressiveScanWorkerRunner(
    private val store: ProgressiveScanStore,
    private val chunkRunnerProvider: () -> ProgressiveChunkRunner?,
    private val accessState: () -> StorageAccessState,
    private val relationRequest: suspend (RelationAnalysisReason) -> Unit = {},
) {
    suspend fun run(sessionId: String): ProgressiveWorkerOutcome {
        val session = store.findSession(sessionId) ?: return ProgressiveWorkerOutcome.SUCCESS
        if (session.status == ScanSessionStatus.COMPLETED) {
            requestRelationsAfterSuccessfulScan()
            return ProgressiveWorkerOutcome.SUCCESS
        }
        if (!session.status.canRunInWorker()) return session.status.toWorkerOutcome()
        if (accessState() != StorageAccessState.Granted) {
            store.markFailed(sessionId, STORAGE_ACCESS_ERROR)
            return ProgressiveWorkerOutcome.FAILURE
        }

        return try {
            val chunkRunner = checkNotNull(chunkRunnerProvider()) {
                "当前设备无法创建共享存储扫描器"
            }
            val result = chunkRunner.runChunk(sessionId, MAX_PAGES_PER_RUN)
            val persisted = store.findSession(sessionId) ?: return ProgressiveWorkerOutcome.SUCCESS
            when {
                persisted.status == ScanSessionStatus.FAILED -> ProgressiveWorkerOutcome.FAILURE
                persisted.status == ScanSessionStatus.COMPLETED && result.complete -> {
                    requestRelationsAfterSuccessfulScan()
                    ProgressiveWorkerOutcome.SUCCESS
                }
                !persisted.status.canRunInWorker() -> ProgressiveWorkerOutcome.SUCCESS
                !result.complete -> ProgressiveWorkerOutcome.RETRY
                else -> ProgressiveWorkerOutcome.SUCCESS
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            store.markFailed(sessionId, error.userMessage())
            ProgressiveWorkerOutcome.FAILURE
        }
    }

    private fun ScanSessionStatus.toWorkerOutcome(): ProgressiveWorkerOutcome =
        if (this == ScanSessionStatus.FAILED) {
            ProgressiveWorkerOutcome.FAILURE
        } else {
            ProgressiveWorkerOutcome.SUCCESS
        }

    private fun Throwable.userMessage(): String =
        message?.trim()?.takeIf(String::isNotEmpty) ?: "扫描任务执行失败"

    private suspend fun requestRelationsAfterSuccessfulScan() {
        try {
            relationRequest(RelationAnalysisReason.SCAN_COMPLETED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 扫描结果已经提交；关系任务排队失败不能反向把扫描标记为失败。
        }
    }

    private companion object {
        const val MAX_PAGES_PER_RUN = 20
        const val STORAGE_ACCESS_ERROR = "全部文件访问权限已失效"
    }
}

class ProgressiveScanWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runner: ProgressiveScanWorkerRunner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(PROGRESSIVE_SCAN_SESSION_ID)
            ?: return Result.failure(workDataOf(WORK_ERROR_MESSAGE to "扫描会话参数缺失"))
        return when (runner.run(sessionId)) {
            ProgressiveWorkerOutcome.SUCCESS -> Result.success()
            ProgressiveWorkerOutcome.RETRY -> Result.retry()
            ProgressiveWorkerOutcome.FAILURE -> Result.failure(
                workDataOf(WORK_ERROR_MESSAGE to "扫描任务执行失败"),
            )
        }
    }

    companion object {
        const val WORK_ERROR_MESSAGE = "message"
    }
}

class WenxuWorkerFactory(
    private val runnerProvider: () -> ProgressiveScanWorkerRunner,
    private val relationRunnerProvider: (() -> RelationAnalysisWorkerRunner)? = null,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ProgressiveScanWorker::class.java.name -> ProgressiveScanWorker(
            appContext = appContext,
            params = workerParameters,
            runner = runnerProvider(),
        )

        RelationAnalysisWorker::class.java.name -> relationRunnerProvider?.invoke()?.let { runner ->
            RelationAnalysisWorker(
                appContext = appContext,
                params = workerParameters,
                runner = runner,
            )
        }

        else -> null
    }
}
