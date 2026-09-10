package com.wenxu.app.core.index

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.database.entity.ScanVisitedDirectoryEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

const val SHARED_STORAGE_SOURCE_URI = "content://com.wenxu.app/source/shared-storage"

data class ScanChunkResult(
    val sessionId: String,
    val scanId: String,
    val complete: Boolean,
    val status: ScanSessionStatus,
    val checkedDirectories: Int,
    val checkedFiles: Int,
    val discoveredDocuments: Int,
)

interface ProgressiveScanStore {
    suspend fun activeOrCreate(): ScanSessionEntity
    fun observeLatest(): Flow<ScanSessionEntity?>
    fun observeDirectoryCount(sessionId: String): Flow<Int>
    suspend fun findSession(sessionId: String): ScanSessionEntity?
    suspend fun markRunning(sessionId: String): ScanSessionEntity
    suspend fun updatePhase(sessionId: String, phase: ScanPhase): ScanSessionEntity
    suspend fun commitPage(
        sessionId: String,
        page: DocumentPage,
        report: IndexBatchReport,
    ): ScanSessionEntity

    suspend fun markCompleted(sessionId: String): ScanSessionEntity
    suspend fun markFailed(sessionId: String, message: String): ScanSessionEntity
    suspend fun pause(): ScanSessionEntity?
    suspend fun resume(): ScanSessionEntity?
    suspend fun cancel(): ScanSessionEntity?
    suspend fun countDirectories(sessionId: String): Int
}

fun interface ProgressiveChunkRunner {
    suspend fun runChunk(sessionId: String, maxPages: Int): ScanChunkResult
}

class ProgressiveScanEngine(
    private val pageSource: SharedDocumentPageSource,
    private val indexer: DocumentIndexer,
    private val store: ProgressiveScanStore,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ProgressiveChunkRunner {
    init {
        require(pageSize > 0) { "pageSize 必须大于 0" }
    }

    suspend fun runChunk(maxPages: Int): ScanChunkResult =
        runChunk(store.activeOrCreate().id, maxPages)

    override suspend fun runChunk(sessionId: String, maxPages: Int): ScanChunkResult {
        require(maxPages > 0) { "maxPages 必须大于 0" }

        var session = checkNotNull(store.findSession(sessionId)) {
            "扫描会话已不存在: $sessionId"
        }
        if (session.status != ScanSessionStatus.QUEUED &&
            session.status != ScanSessionStatus.RUNNING
        ) {
            return session.toResult()
        }

        session = store.markRunning(session.id)
        if (session.status != ScanSessionStatus.RUNNING) return session.toResult()
        if (session.phase == ScanPhase.FINALIZING) return finalizeScan(session)

        repeat(maxPages) {
            session = requireSession(session.id)
            if (session.status != ScanSessionStatus.RUNNING) return session.toResult()

            val page = pageSource.readPage(afterId = session.cursorId, limit = pageSize)
            validatePage(previousCursor = session.cursorId, page = page)

            session = requireSession(session.id)
            if (session.status != ScanSessionStatus.RUNNING) return session.toResult()
            session = store.updatePhase(session.id, ScanPhase.INDEXING)
            if (session.status != ScanSessionStatus.RUNNING) return session.toResult()

            val report = indexer.indexBatch(
                sourceId = session.sourceId,
                scanId = session.scanId,
                documents = page.documents,
            )
            session = store.commitPage(
                sessionId = session.id,
                page = page,
                report = report,
            )

            if (session.status != ScanSessionStatus.RUNNING) return session.toResult()
            if (page.isComplete) return finalizeScan(session)
        }

        return session.toResult()
    }

    suspend fun pause(): ScanChunkResult? = store.pause()?.toResult()

    suspend fun resume(): ScanChunkResult? = store.resume()?.toResult()

    suspend fun cancel(): ScanChunkResult? = store.cancel()?.toResult()

    private suspend fun finalizeScan(startingSession: ScanSessionEntity): ScanChunkResult {
        var session = requireSession(startingSession.id)
        if (session.status != ScanSessionStatus.RUNNING) return session.toResult()

        session = store.updatePhase(session.id, ScanPhase.FINALIZING)
        if (session.status != ScanSessionStatus.RUNNING) return session.toResult()

        indexer.finishScan(sourceId = session.sourceId, scanId = session.scanId)
        session = store.markCompleted(session.id)
        return session.toResult()
    }

    private suspend fun requireSession(sessionId: String): ScanSessionEntity =
        checkNotNull(store.findSession(sessionId)) { "扫描会话已不存在: $sessionId" }

    private fun validatePage(previousCursor: Long?, page: DocumentPage) {
        require(page.checkedFiles >= 0) { "checkedFiles 不能为负数" }
        if (!page.isComplete) {
            require(page.checkedFiles > 0) { "未完成的扫描页必须推进文件计数" }
        }
        if (page.checkedFiles > 0) {
            val nextCursor = requireNotNull(page.nextCursor) { "非空扫描页缺少下一游标" }
            require(previousCursor == null || nextCursor > previousCursor) {
                "扫描页游标必须向前推进"
            }
        }
    }

    private suspend fun ScanSessionEntity.toResult(): ScanChunkResult = ScanChunkResult(
        sessionId = id,
        scanId = scanId,
        complete = status == ScanSessionStatus.COMPLETED,
        status = status,
        checkedDirectories = store.countDirectories(id),
        checkedFiles = checkedFiles,
        discoveredDocuments = discoveredDocuments,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 200
    }
}

class RoomProgressiveScanStore(
    private val database: WenxuDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProgressiveScanStore {
    private val sourceDao = database.sourceDao()
    private val sessionDao = database.scanSessionDao()
    private val visitedDirectoryDao = database.scanVisitedDirectoryDao()

    override suspend fun activeOrCreate(): ScanSessionEntity = database.withTransaction {
        val sourceId = ensureSharedStorageSource()
        sessionDao.activeForSource(sourceId) ?: createSession(sourceId)
    }

    override fun observeLatest(): Flow<ScanSessionEntity?> = sessionDao.observeLatest()

    override fun observeDirectoryCount(sessionId: String): Flow<Int> =
        visitedDirectoryDao.observeCount(sessionId)

    override suspend fun findSession(sessionId: String): ScanSessionEntity? =
        sessionDao.findById(sessionId)

    override suspend fun markRunning(sessionId: String): ScanSessionEntity =
        updateSession(sessionId) { current ->
            if (current.status == ScanSessionStatus.QUEUED) {
                current.copy(status = ScanSessionStatus.RUNNING)
            } else {
                current
            }
        }

    override suspend fun updatePhase(
        sessionId: String,
        phase: ScanPhase,
    ): ScanSessionEntity = updateSession(sessionId) { current -> current.copy(phase = phase) }

    override suspend fun commitPage(
        sessionId: String,
        page: DocumentPage,
        report: IndexBatchReport,
    ): ScanSessionEntity = database.withTransaction {
        val current = requireSession(sessionId)
        visitedDirectoryDao.insertAll(
            page.directoryPaths.map { path ->
                ScanVisitedDirectoryEntity(sessionId = sessionId, directoryPath = path)
            },
        )
        current.copy(
            phase = if (page.isComplete) ScanPhase.FINALIZING else ScanPhase.DISCOVERING,
            cursorId = page.nextCursor ?: current.cursorId,
            checkedFiles = current.checkedFiles.saturatedPlus(page.checkedFiles),
            discoveredDocuments = current.discoveredDocuments.saturatedPlus(report.indexed),
            failedFiles = current.failedFiles.saturatedPlus(report.failed),
            updatedAt = now(),
        ).also { sessionDao.upsert(it) }
    }

    override suspend fun markCompleted(sessionId: String): ScanSessionEntity =
        database.withTransaction {
            val current = requireSession(sessionId)
            if (current.status != ScanSessionStatus.RUNNING) return@withTransaction current

            val completedAt = now()
            val completed = current.copy(
                status = ScanSessionStatus.COMPLETED,
                phase = ScanPhase.COMPLETE,
                updatedAt = completedAt,
                errorMessage = null,
            )
            sessionDao.upsert(completed)
            sourceDao.updateLastScan(current.sourceId, completedAt)
            completed
        }

    override suspend fun markFailed(
        sessionId: String,
        message: String,
    ): ScanSessionEntity = database.withTransaction {
        val current = requireSession(sessionId)
        if (current.status != ScanSessionStatus.QUEUED &&
            current.status != ScanSessionStatus.RUNNING
        ) {
            return@withTransaction current
        }
        current.copy(
            status = ScanSessionStatus.FAILED,
            updatedAt = now(),
            errorMessage = message.take(MAX_ERROR_MESSAGE_LENGTH),
        ).also { sessionDao.upsert(it) }
    }

    override suspend fun pause(): ScanSessionEntity? = database.withTransaction {
        val active = activeSharedStorageSession() ?: return@withTransaction null
        if (active.phase == ScanPhase.FINALIZING) return@withTransaction null
        active.copy(
            status = ScanSessionStatus.PAUSED,
            updatedAt = now(),
        ).also { sessionDao.upsert(it) }
    }

    override suspend fun resume(): ScanSessionEntity? = database.withTransaction {
        val paused = activeSharedStorageSession()
            ?.takeIf { it.status == ScanSessionStatus.PAUSED }
            ?: return@withTransaction null
        paused.copy(
            status = ScanSessionStatus.QUEUED,
            updatedAt = now(),
        ).also { sessionDao.upsert(it) }
    }

    override suspend fun cancel(): ScanSessionEntity? = database.withTransaction {
        val active = activeSharedStorageSession() ?: return@withTransaction null
        if (active.phase == ScanPhase.FINALIZING) return@withTransaction null
        active.copy(
            status = ScanSessionStatus.CANCELLED,
            updatedAt = now(),
        ).also { sessionDao.upsert(it) }
    }

    override suspend fun countDirectories(sessionId: String): Int =
        visitedDirectoryDao.count(sessionId)

    private suspend fun updateSession(
        sessionId: String,
        transform: (ScanSessionEntity) -> ScanSessionEntity,
    ): ScanSessionEntity = database.withTransaction {
        transform(requireSession(sessionId))
            .copy(updatedAt = now())
            .also { sessionDao.upsert(it) }
    }

    private suspend fun activeSharedStorageSession(): ScanSessionEntity? {
        val source = sourceDao.findByTreeUri(SHARED_STORAGE_SOURCE_URI) ?: return null
        return sessionDao.activeForSource(source.id)
    }

    private suspend fun ensureSharedStorageSource(): Long {
        val existing = sourceDao.findByTreeUri(SHARED_STORAGE_SOURCE_URI)
        if (existing != null) {
            if (existing.sourceKind != ScanSourceKind.SHARED_STORAGE) {
                sourceDao.upsert(existing.copy(sourceKind = ScanSourceKind.SHARED_STORAGE))
            }
            return existing.id
        }
        return sourceDao.upsert(
            ScanSourceEntity(
                treeUri = SHARED_STORAGE_SOURCE_URI,
                displayName = "共享存储",
                sourceKind = ScanSourceKind.SHARED_STORAGE,
                sortOrder = SHARED_STORAGE_SORT_ORDER,
            ),
        )
    }

    private suspend fun createSession(sourceId: Long): ScanSessionEntity {
        val timestamp = now()
        return ScanSessionEntity(
            id = newId(),
            sourceId = sourceId,
            scanId = newId(),
            status = ScanSessionStatus.QUEUED,
            phase = ScanPhase.DISCOVERING,
            cursorId = null,
            checkedFiles = 0,
            discoveredDocuments = 0,
            failedFiles = 0,
            startedAt = timestamp,
            updatedAt = timestamp,
        ).also { sessionDao.upsert(it) }
    }

    private suspend fun requireSession(sessionId: String): ScanSessionEntity =
        checkNotNull(sessionDao.findById(sessionId)) { "扫描会话已不存在: $sessionId" }

    private fun Int.saturatedPlus(value: Int): Int =
        (toLong() + value.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val SHARED_STORAGE_SORT_ORDER = -100
        const val MAX_ERROR_MESSAGE_LENGTH = 500
    }
}
