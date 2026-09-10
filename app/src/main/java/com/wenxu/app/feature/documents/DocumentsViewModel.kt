package com.wenxu.app.feature.documents

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.trash.TrashController
import com.wenxu.app.core.trash.SystemConfirmationRequest
import com.wenxu.app.core.trash.TrashOperationResult
import com.wenxu.app.core.storage.FileTransferController
import com.wenxu.app.core.relations.FeedbackResult
import com.wenxu.app.core.relations.SimilarityFeedbackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FormatFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.organization_filter_format_all),
    PDF(R.string.organization_filter_format_pdf),
    DOC(R.string.organization_filter_format_word),
    XLS(R.string.organization_filter_format_excel),
    PPT(R.string.organization_filter_format_ppt),
}

enum class OrganizationFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.organization_filter_all),
    UNCLASSIFIED(R.string.organization_filter_unclassified),
    CLASSIFIED(R.string.organization_filter_classified),
    RELATED(R.string.organization_filter_needs_review),
}

enum class DocumentSort(@param:StringRes val labelRes: Int) {
    MODIFIED(R.string.organization_sort_modified),
    NAME(R.string.organization_sort_name),
    SIZE(R.string.organization_sort_size),
}

data class DocumentsUiState(
    val query: String = "",
    val format: FormatFilter = FormatFilter.ALL,
    val organization: OrganizationFilter = OrganizationFilter.ALL,
    val selectedCategoryId: Long? = null,
    val sort: DocumentSort = DocumentSort.MODIFIED,
    val documents: List<DocumentRecord> = emptyList(),
    val totalCount: Int = 0,
    val categories: List<CategorySummary> = emptyList(),
    val categoryNamesByDocument: Map<Long, List<String>> = emptyMap(),
    val selectionMode: Boolean = false,
    val selectedDocumentIds: Set<Long> = emptySet(),
    val pendingConfirmation: SystemConfirmationRequest? = null,
    val isTrashWorking: Boolean = false,
)

data class DocumentsAction(
    val message: UiText,
    val undoTrashIds: Set<Long> = emptySet(),
    val targetRelatedIds: Set<Long> = emptySet(),
    val retryAnalysisIds: Set<Long> = emptySet(),
)

class DocumentsViewModel(
    private val repository: DocumentsRepository,
    private val trashController: TrashController? = null,
    private val fileTransferController: FileTransferController? = null,
    private val feedbackController: SimilarityFeedbackController? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val format = MutableStateFlow(FormatFilter.ALL)
    private val organization = MutableStateFlow(OrganizationFilter.ALL)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)
    private val sort = MutableStateFlow(DocumentSort.MODIFIED)
    private val selectionMode = MutableStateFlow(false)
    private val selectedDocumentIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingAction = MutableStateFlow<DocumentsAction?>(null)
    private val pendingConfirmation = MutableStateFlow<SystemConfirmationRequest?>(null)
    private val isTrashWorking = MutableStateFlow(false)
    private val trashRequestState = combine(pendingConfirmation, isTrashWorking) { confirmation, working ->
        TrashRequestState(confirmation, working)
    }
    val action: StateFlow<DocumentsAction?> = pendingAction

    private val filters = combine(
        query,
        format,
        organization,
        sort,
        selectedCategoryId,
    ) { queryText, selectedFormat, selectedOrganization, selectedSort, categoryId ->
        DocumentFilters(queryText, selectedFormat, selectedOrganization, selectedSort, categoryId)
    }

    val state: StateFlow<DocumentsUiState> = combine(
        repository.data,
        filters,
        selectionMode,
        selectedDocumentIds,
        trashRequestState,
    ) { data, filters, isSelecting, selectedIds, trashRequest ->
        val categorizedIds = data.categorizedIds + data.categoryIdsByDocument.keys
        val validCategoryId = filters.categoryId?.takeIf { id -> data.categories.any { it.id == id } }
        val filtered = data.documents.filter { document ->
            val matchesQuery = filters.query.isBlank() ||
                document.displayName.contains(filters.query.trim(), ignoreCase = true)
            val matchesFormat = filters.format == FormatFilter.ALL ||
                document.extension.uppercase().let { extension ->
                    when (filters.format) {
                        FormatFilter.ALL -> true
                        FormatFilter.PDF -> extension == "PDF"
                        FormatFilter.DOC -> extension == "DOC" || extension == "DOCX"
                        FormatFilter.XLS -> extension == "XLS" || extension == "XLSX"
                        FormatFilter.PPT -> extension == "PPT" || extension == "PPTX"
                    }
                }
            val matchesOrganization = when (filters.organization) {
                OrganizationFilter.ALL -> true
                OrganizationFilter.UNCLASSIFIED -> document.id !in categorizedIds
                OrganizationFilter.CLASSIFIED -> document.id in categorizedIds
                OrganizationFilter.RELATED -> document.id in data.relatedIds
            }
            val matchesCategory = validCategoryId == null ||
                validCategoryId in data.categoryIdsByDocument[document.id].orEmpty()
            matchesQuery && matchesFormat && matchesOrganization && matchesCategory
        }
        val ordered = when (filters.sort) {
            DocumentSort.MODIFIED -> filtered.sortedByDescending { it.modifiedAt }
            DocumentSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
            DocumentSort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
        val activeIds = data.documents.mapTo(mutableSetOf()) { it.id }
        val categoryNameById = data.categories.associate { it.id to it.name }
        val categoryNamesByDocument = data.categoryIdsByDocument.mapValues { (_, categoryIds) ->
            categoryIds.mapNotNull(categoryNameById::get)
        }
        DocumentsUiState(
            query = filters.query,
            format = filters.format,
            organization = filters.organization,
            selectedCategoryId = validCategoryId,
            sort = filters.sort,
            documents = ordered,
            totalCount = data.documents.size,
            categories = data.categories,
            categoryNamesByDocument = categoryNamesByDocument,
            selectionMode = isSelecting,
            selectedDocumentIds = selectedIds.intersect(activeIds),
            pendingConfirmation = trashRequest.confirmation,
            isTrashWorking = trashRequest.working,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DocumentsUiState(),
    )

    fun setQuery(value: String) { query.value = value }
    fun setFormat(value: FormatFilter) { format.value = value }
    fun setOrganization(value: OrganizationFilter) {
        organization.value = value
        selectedCategoryId.value = null
    }
    fun setCategoryFilter(categoryId: Long?) {
        selectedCategoryId.value = categoryId
        if (categoryId != null) organization.value = OrganizationFilter.ALL
    }
    fun setSort(value: DocumentSort) { sort.value = value }

    fun startSelection() {
        selectionMode.value = true
        selectedDocumentIds.value = emptySet()
    }

    fun cancelSelection() {
        selectionMode.value = false
        selectedDocumentIds.value = emptySet()
    }

    fun toggleSelection(documentId: Long) {
        if (!selectionMode.value || state.value.documents.none { it.id == documentId }) return
        selectedDocumentIds.value = selectedDocumentIds.value.toMutableSet().apply {
            if (!add(documentId)) remove(documentId)
        }
    }

    fun markOpened(documentId: Long) {
        viewModelScope.launch { repository.markOpened(documentId) }
    }

    fun addToCategory(documentId: Long, categoryId: Long) {
        viewModelScope.launch { repository.addToCategory(documentId, categoryId) }
    }

    fun addSelectedToCategory(categoryId: Long) {
        val activeIds = state.value.documents.mapTo(mutableSetOf()) { it.id }
        val requestedIds = selectedDocumentIds.value.intersect(activeIds)
        if (requestedIds.isEmpty()) return
        val categoryName = state.value.categories.firstOrNull { it.id == categoryId }?.name
        viewModelScope.launch {
            val added = repository.addDocumentsToCategory(requestedIds, categoryId)
            pendingAction.value = DocumentsAction(
                if (added == 0) {
                    categoryName?.let {
                        UiText.Resource(R.string.organization_already_in_category, listOf(it))
                    } ?: UiText.Resource(R.string.organization_already_in_selected_category)
                } else {
                    categoryName?.let {
                        UiText.Resource(R.string.organization_added_to_category, listOf(added, it))
                    } ?: UiText.Resource(
                        R.string.organization_added_to_selected_category,
                        listOf(added),
                    )
                },
            )
            cancelSelection()
        }
    }

    fun mergeSelectedAsVersions() {
        val feedback = feedbackController ?: return
        val activeIds = state.value.documents.mapTo(mutableSetOf()) { it.id }
        val requestedIds = selectedDocumentIds.value.intersect(activeIds)
        viewModelScope.launch {
            val result = feedback.mergeAsVersions(requestedIds)
            pendingAction.value = DocumentsAction(
                message = when (result) {
                    is FeedbackResult.SavedPendingAnalysis -> UiText.Resource(
                        R.string.organization_versions_merged,
                    )
                    is FeedbackResult.AnalysisQueueFailed -> UiText.Resource(
                        R.string.organization_merge_saved_queue_failed,
                    )
                    FeedbackResult.NotEnoughDocuments -> UiText.Resource(R.string.organization_merge_need_two)
                    FeedbackResult.TooManyDocuments -> UiText.Resource(
                        R.string.organization_merge_too_many,
                    )
                    FeedbackResult.DocumentsUnavailable,
                    FeedbackResult.GroupUnavailable,
                    -> UiText.Resource(R.string.organization_merge_unavailable)
                    FeedbackResult.IncompatibleFamily -> UiText.Resource(
                        R.string.organization_merge_family_mismatch,
                    )
                    FeedbackResult.ExactDuplicates -> UiText.Resource(
                        R.string.organization_merge_exact_duplicates,
                    )
                    FeedbackResult.BlockedByUser -> UiText.Resource(
                        R.string.organization_merge_blocked_by_feedback,
                    )
                },
                targetRelatedIds = (result as? FeedbackResult.SavedPendingAnalysis)
                    ?.documentIds.orEmpty(),
                retryAnalysisIds = (result as? FeedbackResult.AnalysisQueueFailed)
                    ?.documentIds.orEmpty(),
            )
            if (result is FeedbackResult.SavedPendingAnalysis ||
                result is FeedbackResult.AnalysisQueueFailed
            ) {
                cancelSelection()
            }
        }
    }

    fun retryAnalysis(documentIds: Set<Long>) {
        val feedback = feedbackController ?: return
        viewModelScope.launch {
            pendingAction.value = when (val result = feedback.retryAnalysis(documentIds)) {
                is FeedbackResult.SavedPendingAnalysis -> DocumentsAction(
                    message = UiText.Resource(R.string.organization_versions_merged),
                    targetRelatedIds = result.documentIds,
                )
                is FeedbackResult.AnalysisQueueFailed -> DocumentsAction(
                    message = UiText.Resource(R.string.organization_merge_saved_queue_failed),
                    retryAnalysisIds = result.documentIds,
                )
                else -> DocumentsAction(UiText.Resource(R.string.organization_merge_unavailable))
            }
        }
    }

    fun moveToTrash(documentId: Long) {
        val trash = trashController ?: return
        if (isTrashWorking.value) return
        viewModelScope.launch {
            isTrashWorking.value = true
            handleTrashResult(trash.requestTrash(setOf(documentId)))
        }
    }

    fun onSystemTrashResult(operationId: Long, approved: Boolean) {
        val trash = trashController ?: return
        if (pendingConfirmation.value?.operationId != operationId) return
        viewModelScope.launch { handleTrashResult(trash.completeConfirmation(operationId, approved)) }
    }

    private fun handleTrashResult(result: TrashOperationResult) {
        when (result) {
            is TrashOperationResult.RequiresConfirmation -> {
                pendingConfirmation.value = result.request
                isTrashWorking.value = true
            }
            is TrashOperationResult.Completed -> {
                val trashIds = result.successes.mapNotNullTo(mutableSetOf()) { it.trashId }
                val failure = result.failures.isNotEmpty()
                pendingAction.value = DocumentsAction(
                    message = when {
                        trashIds.isNotEmpty() && !failure -> UiText.Resource(R.string.organization_moved_to_trash)
                        trashIds.isNotEmpty() -> UiText.Resource(
                            R.string.organization_duplicate_trash_partial,
                            listOf(trashIds.size, result.failures.size),
                        )
                        else -> UiText.Resource(R.string.organization_trash_failed)
                    },
                    undoTrashIds = trashIds,
                )
                pendingConfirmation.value = null
                isTrashWorking.value = false
            }
            TrashOperationResult.Cancelled -> {
                pendingAction.value = DocumentsAction(UiText.Resource(R.string.organization_trash_cancelled))
                pendingConfirmation.value = null
                isTrashWorking.value = false
            }
        }
    }

    fun undo(action: DocumentsAction) {
        val trash = trashController ?: return
        if (action.undoTrashIds.isEmpty()) return
        viewModelScope.launch {
            val restored = action.undoTrashIds.count { trash.restore(it).isSuccess }
            pendingAction.value = DocumentsAction(
                UiText.Resource(R.string.organization_restored_documents, listOf(restored)),
            )
        }
    }

    fun clearAction() { pendingAction.value = null }

    fun moveToFolder(documentId: Long, targetTreeUri: String?) {
        val transfer = fileTransferController ?: return
        if (targetTreeUri == null) return
        viewModelScope.launch {
            pendingAction.value = transfer.move(documentId, targetTreeUri).fold(
                onSuccess = {
                    DocumentsAction(UiText.Resource(R.string.organization_moved_to_folder))
                },
                onFailure = {
                    DocumentsAction(UiText.Resource(R.string.organization_move_failed))
                },
            )
        }
    }

    companion object {
        fun factory(
            repository: DocumentsRepository,
            trashController: TrashController,
            fileTransferController: FileTransferController,
            feedbackController: SimilarityFeedbackController,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DocumentsViewModel(
                    repository,
                    trashController,
                    fileTransferController,
                    feedbackController,
                )
            }
        }
    }
}

private data class DocumentFilters(
    val query: String,
    val format: FormatFilter,
    val organization: OrganizationFilter,
    val sort: DocumentSort,
    val categoryId: Long?,
)

private data class TrashRequestState(
    val confirmation: SystemConfirmationRequest?,
    val working: Boolean,
)
