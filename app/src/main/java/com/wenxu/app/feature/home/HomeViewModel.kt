package com.wenxu.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.index.ScanScheduler
import com.wenxu.app.core.index.ScanSessionSnapshot
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.storage.ScanSourceRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: HomeRepository,
    private val sourceRepository: ScanSourceRepository? = null,
    private val scanPreferences: ScanPreferences? = null,
    private val scanScheduler: ScanScheduler? = null,
    private val storageAccessController: StorageAccessController? = null,
) : ViewModel() {
    private val progressiveSession: Flow<ScanSessionSnapshot?> =
        scanScheduler?.observeSession() ?: flowOf(null)
    private val configuredScope: Flow<DocumentScanScope> =
        scanPreferences?.scanScope ?: flowOf(defaultScope())

    init {
        viewModelScope.launch {
            val scanOnFirstEmission = scanPreferences?.scanOnLaunch?.first() != false
            var isFirstEmission = true
            configuredScope
                .map(::effectiveScope)
                .distinctUntilChanged()
                .collectLatest { scope ->
                    if (scope == DocumentScanScope.SELECTED_FOLDERS) {
                        scanScheduler?.cancel()
                    }
                    repository.applyScanScope(scope)
                    if (!isFirstEmission || scanOnFirstEmission) {
                        startScan(scope, onlyIfStale = isFirstEmission)
                    }
                    isFirstEmission = false
                }
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        repository.data,
        progressiveSession,
        configuredScope,
    ) { data, session, configured ->
        val scope = effectiveScope(configured)
        val usesAllDocuments = scope == DocumentScanScope.ALL_DOCUMENTS
        val scanUiState = if (usesAllDocuments) {
            session.toProgressUiState()
        } else {
            data.legacyScan.toProgressUiState(hasSource = data.hasAuthorizedSources)
        }
        HomeUiState(
            hasAuthorizedSources = usesAllDocuments || data.hasAuthorizedSources,
            sourceCount = if (usesAllDocuments) 1 else data.sourceCount,
            relatedSummary = RelatedSummary(
                nameSimilarGroups = data.similarGroups,
                exactDuplicateSets = data.exactSets,
                previewNames = data.relationPreviewNames,
            ),
            unclassifiedCount = data.unclassifiedCount,
            recentDocuments = data.recentDocuments,
            scan = scanUiState,
            relationAnalysis = data.relationAnalysis.toUiState(
                scanCompletedAt = scanUiState.updatedAt
                    ?.takeIf { scanUiState.status == ScanSessionStatus.COMPLETED },
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(),
    )

    fun rescan() {
        viewModelScope.launch {
            startScan(currentScope(), onlyIfStale = false)
        }
    }

    fun onFolderSelected(uri: String?) {
        if (uri == null) return
        val sources = sourceRepository ?: return
        viewModelScope.launch {
            if (currentScope() != DocumentScanScope.SELECTED_FOLDERS) return@launch
            sources.add(uri).onSuccess { repository.rescan() }
        }
    }

    fun pauseScan() {
        viewModelScope.launch { scanScheduler?.pause() }
    }

    fun resumeScan() {
        viewModelScope.launch { scanScheduler?.resume() }
    }

    fun cancelScan() {
        viewModelScope.launch { scanScheduler?.cancel() }
    }

    fun retryScan() {
        rescan()
    }

    fun markOpened(documentId: Long) {
        viewModelScope.launch { repository.markOpened(documentId) }
    }

    private suspend fun startLegacyScanIfStale() {
        val sources = sourceRepository ?: return
        val now = System.currentTimeMillis()
        val shouldRefresh = sources.observeSources().first().any { source ->
            source.sourceKind == ScanSourceKind.TREE &&
                source.permissionState == SourcePermissionState.ACTIVE &&
                (source.lastScanAt == null || now - source.lastScanAt > AUTO_SCAN_COOLDOWN_MS)
        }
        if (shouldRefresh) repository.rescan()
    }

    private suspend fun startScan(scope: DocumentScanScope, onlyIfStale: Boolean) {
        when (scope) {
            DocumentScanScope.ALL_DOCUMENTS -> scanScheduler?.start()
            DocumentScanScope.SELECTED_FOLDERS -> {
                if (onlyIfStale) {
                    startLegacyScanIfStale()
                } else if (!repository.data.first().legacyScan.isRunning) {
                    repository.rescan()
                }
            }
        }
    }

    private suspend fun currentScope(): DocumentScanScope =
        effectiveScope(configuredScope.first())

    private fun effectiveScope(configured: DocumentScanScope): DocumentScanScope =
        if (
            configured == DocumentScanScope.ALL_DOCUMENTS &&
            currentAccessState() == StorageAccessState.Granted
        ) {
            DocumentScanScope.ALL_DOCUMENTS
        } else {
            DocumentScanScope.SELECTED_FOLDERS
        }

    private fun defaultScope(): DocumentScanScope =
        if (currentAccessState() == StorageAccessState.Granted) {
            DocumentScanScope.ALL_DOCUMENTS
        } else {
            DocumentScanScope.SELECTED_FOLDERS
        }

    private fun currentAccessState(): StorageAccessState =
        storageAccessController?.state() ?: StorageAccessState.Legacy

    companion object {
        private const val AUTO_SCAN_COOLDOWN_MS = 60_000L

        fun factory(
            repository: HomeRepository,
            sourceRepository: ScanSourceRepository,
            scanPreferences: ScanPreferences,
            scanScheduler: ScanScheduler,
            storageAccessController: StorageAccessController,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = repository,
                    sourceRepository = sourceRepository,
                    scanPreferences = scanPreferences,
                    scanScheduler = scanScheduler,
                    storageAccessController = storageAccessController,
                )
            }
        }
    }
}

private fun RelationAnalysisData?.toUiState(scanCompletedAt: Long?): RelationAnalysisUiState {
    if (scanCompletedAt != null && (this == null || updatedAt < scanCompletedAt)) {
        return RelationAnalysisUiState(
            visible = true,
            phase = UiText.Resource(R.string.home_relation_exact_duplicates),
        )
    }
    val data = this ?: return RelationAnalysisUiState()
    if (data.status == RelationAnalysisStatus.COMPLETED) return RelationAnalysisUiState()
    val visible = data.status == RelationAnalysisStatus.QUEUED ||
        data.status == RelationAnalysisStatus.RUNNING ||
        data.status == RelationAnalysisStatus.FAILED
    if (!visible) return RelationAnalysisUiState()
    val phaseText = if (data.status == RelationAnalysisStatus.FAILED) {
        UiText.Resource(R.string.home_relation_retry_later)
    } else {
        UiText.Resource(
            when (data.phase) {
                RelationAnalysisPhase.EXACT_DUPLICATES -> R.string.home_relation_exact_duplicates
                RelationAnalysisPhase.NAME_CANDIDATES -> R.string.home_relation_name_candidates
                RelationAnalysisPhase.CONTENT_VERIFICATION ->
                    R.string.home_relation_content_verification
                RelationAnalysisPhase.SAVING -> R.string.home_relation_saving
            },
        )
    }
    return RelationAnalysisUiState(
        visible = true,
        phase = phaseText,
        checked = data.checked.coerceAtLeast(0),
        candidates = data.candidates.coerceAtLeast(0),
        failures = data.failures.coerceAtLeast(0),
        isFailure = data.status == RelationAnalysisStatus.FAILED,
    )
}

private fun ScanSessionSnapshot?.toProgressUiState(): ScanProgressUiState {
    if (this == null) {
        return ScanProgressUiState(
            visible = true,
            title = UiText.Resource(R.string.home_scan_initial_title),
            detail = UiText.Resource(R.string.home_scan_initial_detail),
            scope = HomeScanScope.ALL_DOCUMENTS,
            primaryAction = ScanProgressAction.START,
        )
    }

    val counts = countDetail(checkedDirectories, checkedFiles, discoveredDocuments)
    val title = when (status) {
        ScanSessionStatus.QUEUED -> UiText.Resource(R.string.home_scan_queued)
        ScanSessionStatus.RUNNING -> when (phase) {
            ScanPhase.DISCOVERING -> UiText.Resource(R.string.home_scan_discovering)
            ScanPhase.INDEXING -> UiText.Resource(R.string.home_scan_indexing)
            ScanPhase.FINALIZING -> UiText.Resource(R.string.home_scan_finalizing)
            ScanPhase.COMPLETE -> UiText.Resource(R.string.home_scan_updated)
        }
        ScanSessionStatus.PAUSED -> UiText.Resource(R.string.home_scan_paused)
        ScanSessionStatus.COMPLETED -> UiText.Resource(R.string.home_scan_updated)
        ScanSessionStatus.CANCELLED -> UiText.Resource(R.string.home_scan_stopped)
        ScanSessionStatus.FAILED -> UiText.Resource(R.string.home_scan_failed)
    }
    val action = when (status) {
        ScanSessionStatus.QUEUED,
        ScanSessionStatus.RUNNING,
        -> ScanProgressAction.PAUSE
        ScanSessionStatus.PAUSED -> ScanProgressAction.RESUME
        ScanSessionStatus.COMPLETED -> ScanProgressAction.RESTART
        ScanSessionStatus.CANCELLED -> ScanProgressAction.RESTART
        ScanSessionStatus.FAILED -> ScanProgressAction.RETRY
    }
    val detail = when (status) {
        ScanSessionStatus.COMPLETED -> UiText.Plural(
            id = R.plurals.home_scan_completed_detail,
            quantity = discoveredDocuments,
            args = listOf(formatUpdatedAt(updatedAt), discoveredDocuments),
        )
        ScanSessionStatus.CANCELLED -> if (discoveredDocuments > 0) {
            UiText.Plural(
                id = R.plurals.home_scan_cancelled_kept,
                quantity = discoveredDocuments,
            )
        } else {
            UiText.Resource(R.string.home_scan_cancelled_empty)
        }
        else -> counts
    }
    return ScanProgressUiState(
        visible = true,
        title = title,
        detail = detail,
        scope = HomeScanScope.ALL_DOCUMENTS,
        status = status,
        phase = phase,
        checkedDirectories = checkedDirectories,
        checkedFiles = checkedFiles,
        discoveredDocuments = discoveredDocuments,
        failedFiles = failedFiles,
        updatedAt = updatedAt,
        errorMessage = errorMessage?.takeIf(String::isNotBlank)?.let {
            UiText.Resource(R.string.home_scan_error_detail)
        },
        primaryAction = action,
        canCancel = status == ScanSessionStatus.QUEUED ||
            status == ScanSessionStatus.RUNNING ||
            status == ScanSessionStatus.PAUSED,
        isCompactCompletion = status == ScanSessionStatus.COMPLETED,
    )
}

private fun LegacyScanData.toProgressUiState(hasSource: Boolean): ScanProgressUiState {
    if (!hasSource) return ScanProgressUiState()
    val title = when {
        isRunning -> UiText.Resource(R.string.home_scan_selected_running)
        failedSources > 0 -> UiText.Resource(R.string.home_scan_selected_needs_attention)
        discoveredDocuments > 0 -> UiText.Resource(R.string.home_scan_selected_updated)
        else -> UiText.Resource(R.string.home_scan_selected_waiting)
    }
    val detail = when {
        isRunning -> UiText.Plural(
            R.plurals.home_scan_documents_found,
            discoveredDocuments,
        )
        failedSources > 0 -> UiText.Plural(
            R.plurals.home_scan_folders_need_authorization,
            failedSources,
        )
        discoveredDocuments > 0 -> UiText.Plural(
            R.plurals.home_scan_documents_found,
            discoveredDocuments,
        )
        else -> UiText.Resource(R.string.home_scan_authorized_only)
    }
    return ScanProgressUiState(
        visible = true,
        title = title,
        detail = detail,
        scope = HomeScanScope.SELECTED_FOLDERS,
        discoveredDocuments = discoveredDocuments,
        failedFiles = failedSources,
        primaryAction = if (isRunning) ScanProgressAction.NONE else ScanProgressAction.RESTART,
    )
}

private fun countDetail(
    checkedDirectories: Int,
    checkedFiles: Int,
    discoveredDocuments: Int,
): UiText = UiText.Resource(
    id = R.string.home_scan_count_detail,
    args = listOf(checkedDirectories, checkedFiles, discoveredDocuments),
)

private fun formatUpdatedAt(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestamp))
