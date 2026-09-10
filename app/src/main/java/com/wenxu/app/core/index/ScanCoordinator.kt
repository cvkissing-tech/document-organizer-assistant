package com.wenxu.app.core.index

import com.wenxu.app.core.database.dao.SourceDao
import com.wenxu.app.core.model.ScanReport
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.storage.DocumentGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.UUID
import com.wenxu.app.core.relations.RelationAnalysisReason

sealed interface ScanProgress {
    data class Running(
        val scanned: Int,
        val added: Int,
        val changed: Int,
    ) : ScanProgress

    data class Completed(val report: ScanReport) : ScanProgress

    data class Partial(
        val report: ScanReport,
        val failedSourceIds: List<Long>,
    ) : ScanProgress
}

class ScanCoordinator(
    private val sourceDao: SourceDao,
    private val gateway: DocumentGateway,
    private val indexer: DocumentIndexer,
    private val scanIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val relationRequest: suspend (RelationAnalysisReason) -> Unit = {},
) {
    fun scanAll(): Flow<ScanProgress> = flow {
        var scanned = 0
        var added = 0
        var changed = 0
        val failedSourceIds = mutableListOf<Long>()

        sourceDao.listAll()
            .filter { source -> source.sourceKind != ScanSourceKind.SHARED_STORAGE }
            .forEach { source ->
            try {
                val treeUri = source.treeUri
                if (!gateway.hasReadPermission(treeUri)) {
                    sourceDao.updatePermissionState(source.id, SourcePermissionState.NEEDS_ATTENTION)
                    failedSourceIds += source.id
                    return@forEach
                }

                val report = indexer.index(
                    sourceId = source.id,
                    scanId = scanIdProvider(),
                    batches = gateway.discover(treeUri),
                    onBatch = { acceptedCount ->
                        scanned += acceptedCount
                        emit(ScanProgress.Running(scanned, added, changed))
                    },
                )
                added += report.added
                changed += report.changed
                sourceDao.updatePermissionState(source.id, SourcePermissionState.ACTIVE)
                sourceDao.updateLastScan(source.id, clock())
                emit(ScanProgress.Running(scanned, added, changed))
            } catch (_: SecurityException) {
                sourceDao.updatePermissionState(source.id, SourcePermissionState.NEEDS_ATTENTION)
                failedSourceIds += source.id
            } catch (_: IOException) {
                sourceDao.updatePermissionState(source.id, SourcePermissionState.NEEDS_ATTENTION)
                failedSourceIds += source.id
            }
        }

        val report = ScanReport(
            scanned = scanned,
            added = added,
            changed = changed,
            exactSets = 0,
        )
        if (failedSourceIds.isEmpty()) {
            emit(ScanProgress.Completed(report))
        } else {
            emit(ScanProgress.Partial(report, failedSourceIds))
        }
        requestRelationsAfterSuccessfulScan()
    }

    private suspend fun requestRelationsAfterSuccessfulScan() {
        try {
            relationRequest(RelationAnalysisReason.SCAN_COMPLETED)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 索引已经完成；关系分析排队失败不能让已完成扫描变成失败。
        }
    }
}
