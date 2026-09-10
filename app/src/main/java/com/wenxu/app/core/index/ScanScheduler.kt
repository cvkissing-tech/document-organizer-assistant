package com.wenxu.app.core.index

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val PROGRESSIVE_SCAN_WORK_NAME = "wenxu-progressive-document-scan"
internal const val PROGRESSIVE_SCAN_SESSION_ID = "scan-session-id"

data class ScanSessionSnapshot(
    val sessionId: String,
    val scanId: String,
    val status: ScanSessionStatus,
    val phase: ScanPhase,
    val checkedDirectories: Int,
    val checkedFiles: Int,
    val discoveredDocuments: Int,
    val failedFiles: Int,
    val updatedAt: Long,
    val errorMessage: String?,
)

interface ScanScheduler {
    suspend fun start(): Boolean
    suspend fun pause(): Boolean
    suspend fun resume(): Boolean
    suspend fun cancel(): Boolean
    fun observeSession(): Flow<ScanSessionSnapshot?>
}

internal interface ProgressiveWorkController {
    fun enqueueUnique(workName: String, sessionId: String, replace: Boolean)
    fun cancelUnique(workName: String)
}

internal class WorkManagerProgressiveWorkController(
    private val workManager: () -> WorkManager,
) : ProgressiveWorkController {
    override fun enqueueUnique(workName: String, sessionId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<ProgressiveScanWorker>()
            .setInputData(
                Data.Builder()
                    .putString(PROGRESSIVE_SCAN_SESSION_ID, sessionId)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, MINIMUM_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(workName)
            .build()
        workManager().enqueueUniqueWork(
            workName,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelUnique(workName: String) {
        workManager().cancelUniqueWork(workName)
    }

    private companion object {
        const val MINIMUM_BACKOFF_SECONDS = 10L
    }
}

class WorkManagerScanScheduler internal constructor(
    private val store: ProgressiveScanStore,
    private val workController: ProgressiveWorkController,
    private val accessState: () -> StorageAccessState,
) : ScanScheduler {
    constructor(
        context: Context,
        store: ProgressiveScanStore,
        storageAccessController: StorageAccessController,
    ) : this(
        store = store,
        workController = WorkManagerProgressiveWorkController(
            workManager = { WorkManager.getInstance(context.applicationContext) },
        ),
        accessState = storageAccessController::state,
    )

    private val operationMutex = Mutex()

    override suspend fun start(): Boolean = operationMutex.withLock {
        if (accessState() != StorageAccessState.Granted) return@withLock false

        val session = store.activeOrCreate()
        if (!session.status.canRunInWorker()) return@withLock false
        workController.enqueueUnique(
            workName = PROGRESSIVE_SCAN_WORK_NAME,
            sessionId = session.id,
            replace = false,
        )
        true
    }

    override suspend fun pause(): Boolean = operationMutex.withLock {
        val paused = store.pause() ?: return@withLock false
        workController.cancelUnique(PROGRESSIVE_SCAN_WORK_NAME)
        paused.status == ScanSessionStatus.PAUSED
    }

    override suspend fun resume(): Boolean = operationMutex.withLock {
        if (accessState() != StorageAccessState.Granted) return@withLock false

        val resumed = store.resume() ?: return@withLock false
        if (resumed.status != ScanSessionStatus.QUEUED) return@withLock false
        workController.enqueueUnique(
            workName = PROGRESSIVE_SCAN_WORK_NAME,
            sessionId = resumed.id,
            replace = true,
        )
        true
    }

    override suspend fun cancel(): Boolean = operationMutex.withLock {
        val cancelled = store.cancel() ?: return@withLock false
        workController.cancelUnique(PROGRESSIVE_SCAN_WORK_NAME)
        cancelled.status == ScanSessionStatus.CANCELLED
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSession(): Flow<ScanSessionSnapshot?> =
        store.observeLatest().flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                store.observeDirectoryCount(session.id).map { directoryCount ->
                    session.toSnapshot(directoryCount)
                }
            }
        }
}

private fun ScanSessionEntity.toSnapshot(directoryCount: Int) = ScanSessionSnapshot(
    sessionId = id,
    scanId = scanId,
    status = status,
    phase = phase,
    checkedDirectories = directoryCount,
    checkedFiles = checkedFiles,
    discoveredDocuments = discoveredDocuments,
    failedFiles = failedFiles,
    updatedAt = updatedAt,
    errorMessage = errorMessage,
)

internal fun ScanSessionStatus.canRunInWorker(): Boolean =
    this == ScanSessionStatus.QUEUED || this == ScanSessionStatus.RUNNING
