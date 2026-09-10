package com.wenxu.app.feature.home

import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord

data class RelatedSummary(
    val nameSimilarGroups: Int = 0,
    val exactDuplicateSets: Int = 0,
    val previewNames: List<String> = emptyList(),
) {
    val totalGroups: Int
        get() = nameSimilarGroups + exactDuplicateSets
}

data class HomeData(
    val hasAuthorizedSources: Boolean = false,
    val sourceCount: Int = 0,
    val similarGroups: Int = 0,
    val exactSets: Int = 0,
    val relationPreviewNames: List<String> = emptyList(),
    val unclassifiedCount: Int = 0,
    val recentDocuments: List<DocumentRecord> = emptyList(),
    val legacyScan: LegacyScanData = LegacyScanData(),
    val relationAnalysis: RelationAnalysisData? = null,
)

data class RelationAnalysisData(
    val status: RelationAnalysisStatus,
    val phase: RelationAnalysisPhase,
    val checked: Int = 0,
    val candidates: Int = 0,
    val failures: Int = 0,
    val updatedAt: Long = 0L,
)

data class LegacyScanData(
    val isRunning: Boolean = false,
    val discoveredDocuments: Int = 0,
    val failedSources: Int = 0,
    val changedDocuments: Int = 0,
)

enum class HomeScanScope {
    ALL_DOCUMENTS,
    SELECTED_FOLDERS,
}

enum class ScanProgressAction {
    START,
    PAUSE,
    RESUME,
    RETRY,
    RESTART,
    NONE,
}

data class ScanProgressUiState(
    val visible: Boolean = false,
    val title: UiText = UiText.Plain(""),
    val detail: UiText = UiText.Plain(""),
    val scope: HomeScanScope = HomeScanScope.SELECTED_FOLDERS,
    val status: ScanSessionStatus? = null,
    val phase: ScanPhase? = null,
    val checkedDirectories: Int = 0,
    val checkedFiles: Int = 0,
    val discoveredDocuments: Int = 0,
    val failedFiles: Int = 0,
    val updatedAt: Long? = null,
    val errorMessage: UiText? = null,
    val primaryAction: ScanProgressAction = ScanProgressAction.NONE,
    val canCancel: Boolean = false,
    val isCompactCompletion: Boolean = false,
)

data class RelationAnalysisUiState(
    val visible: Boolean = false,
    val phase: UiText = UiText.Plain(""),
    val checked: Int = 0,
    val candidates: Int = 0,
    val failures: Int = 0,
    val isFailure: Boolean = false,
)

data class HomeUiState(
    val hasAuthorizedSources: Boolean = false,
    val sourceCount: Int = 0,
    val relatedSummary: RelatedSummary = RelatedSummary(),
    val unclassifiedCount: Int = 0,
    val recentDocuments: List<DocumentRecord> = emptyList(),
    val scan: ScanProgressUiState = ScanProgressUiState(),
    val relationAnalysis: RelationAnalysisUiState = RelationAnalysisUiState(),
)
