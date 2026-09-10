package com.wenxu.app.feature.home

import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.index.ScanCoordinator
import com.wenxu.app.core.index.ScanProgress
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.SourcePermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class RelationData(
    val similarGroups: Int,
    val exactSets: Int,
    val documentIds: Set<Long>,
)

private data class DocumentData(
    val activeDocuments: List<DocumentEntity>,
    val recentDocuments: List<DocumentRecord>,
    val unclassifiedCount: Int,
)

interface HomeRepository {
    val data: Flow<HomeData>
    suspend fun rescan(): Result<Unit>
    suspend fun applyScanScope(scope: DocumentScanScope) = Unit
    suspend fun markOpened(documentId: Long)
}

class RoomHomeRepository(
    database: WenxuDatabase,
    private val scanCoordinator: ScanCoordinator,
) : HomeRepository {
    private val documentDao = database.documentDao()
    private val sourceDao = database.sourceDao()
    private val relationDao = database.relationDao()
    private val relationAnalysisDao = database.relationAnalysisDao()
    private val scanState = MutableStateFlow<ScanProgress?>(null)
    private val scanMutex = Mutex()

    private val relations = combine(
        relationDao.observeNameGroups(),
        relationDao.observeExactSets(),
        relationDao.observeRelatedDocumentIds(),
    ) { nameGroups, exactSets, relatedDocumentIds ->
        RelationData(
            similarGroups = nameGroups.size,
            exactSets = exactSets.size,
            documentIds = relatedDocumentIds.toSet(),
        )
    }

    private val documents = combine(
        documentDao.observeActive(),
        documentDao.observeUnclassifiedCount(),
    ) { active, unclassifiedCount ->
        val recent = active
            .filter { it.lastOpenedAt != null }
            .sortedByDescending { it.lastOpenedAt }
            .take(4)
            .map { document ->
                DocumentRecord(
                    id = document.id,
                    uri = document.uri,
                    displayName = document.displayName,
                    extension = document.extension,
                    sizeBytes = document.sizeBytes,
                    modifiedAt = document.modifiedAt,
                    sourceId = document.sourceId,
                    parentUri = document.parentUri,
                )
            }
        DocumentData(
            activeDocuments = active,
            recentDocuments = recent,
            unclassifiedCount = unclassifiedCount,
        )
    }

    override val data: Flow<HomeData> = combine(
        sourceDao.observeAll(),
        relations,
        documents,
        scanState,
        relationAnalysisDao.observeState(),
    ) { sources, relationCounts, documentData, progress, relationAnalysis ->
        val activeTreeSources = sources.filter { source ->
            source.sourceKind == ScanSourceKind.TREE &&
                source.permissionState == SourcePermissionState.ACTIVE
        }
        HomeData(
            hasAuthorizedSources = activeTreeSources.isNotEmpty(),
            sourceCount = activeTreeSources.size,
            similarGroups = relationCounts.similarGroups,
            exactSets = relationCounts.exactSets,
            relationPreviewNames = documentData.activeDocuments
                .asSequence()
                .filter { it.id in relationCounts.documentIds }
                .map { it.displayName }
                .take(2)
                .toList(),
            unclassifiedCount = documentData.unclassifiedCount,
            recentDocuments = documentData.recentDocuments,
            legacyScan = LegacyScanData(
                isRunning = progress is ScanProgress.Running,
                discoveredDocuments = when (progress) {
                    is ScanProgress.Running -> progress.scanned
                    is ScanProgress.Completed -> progress.report.scanned
                    is ScanProgress.Partial -> progress.report.scanned
                    null -> 0
                },
                failedSources = (progress as? ScanProgress.Partial)?.failedSourceIds?.size ?: 0,
                changedDocuments = when (progress) {
                    is ScanProgress.Running -> progress.added + progress.changed
                    is ScanProgress.Completed -> progress.report.added + progress.report.changed
                    is ScanProgress.Partial -> progress.report.added + progress.report.changed
                    null -> 0
                },
            ),
            relationAnalysis = relationAnalysis?.let { state ->
                RelationAnalysisData(
                    status = state.status,
                    phase = state.phase,
                    checked = state.processedCount,
                    candidates = state.candidateCount,
                    failures = state.failedCount,
                    updatedAt = state.updatedAt,
                )
            },
        )
    }

    override suspend fun rescan(): Result<Unit> = scanMutex.withLock {
        runCatching {
            scanCoordinator.scanAll().collect { progress -> scanState.value = progress }
            Unit
        }
    }

    override suspend fun applyScanScope(scope: DocumentScanScope) {
        val hiddenKind = when (scope) {
            DocumentScanScope.ALL_DOCUMENTS -> ScanSourceKind.TREE
            DocumentScanScope.SELECTED_FOLDERS -> ScanSourceKind.SHARED_STORAGE
        }
        documentDao.markMissingBySourceKind(hiddenKind)
    }

    override suspend fun markOpened(documentId: Long) {
        documentDao.updateLastOpened(documentId, System.currentTimeMillis())
    }
}
