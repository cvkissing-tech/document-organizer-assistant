package com.wenxu.app.feature.related

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.wenxu.app.core.trash.TrashController
import com.wenxu.app.core.trash.SystemConfirmationRequest
import com.wenxu.app.core.trash.TrashOperationResult
import com.wenxu.app.core.relations.FeedbackResult
import com.wenxu.app.core.relations.SimilarityFeedbackController
import com.wenxu.app.core.database.entity.RelationAnalysisStatus

enum class TargetLifecycle {
    None,
    Pending,
    Resolved,
    Dismissed,
    NotFound,
}

private data class TargetTracking(
    val lifecycle: TargetLifecycle,
    val groupId: Long? = null,
    val awaitedGeneration: Long? = null,
)

private data class RelatedTrashRequestState(
    val confirmation: SystemConfirmationRequest?,
    val working: Boolean,
)

data class RelatedUiState(
    val nameGroups: List<NameRelatedGroup> = emptyList(),
    val exactGroups: List<ExactRelatedGroup> = emptyList(),
    val selectedExactIds: Set<Long> = emptySet(),
    val action: RelatedAction? = null,
    val pendingConfirmation: SystemConfirmationRequest? = null,
    val isTrashWorking: Boolean = false,
    val targetDocumentIds: Set<Long> = emptySet(),
    val targetGroupId: Long? = null,
    val targetLifecycle: TargetLifecycle = TargetLifecycle.None,
) {
    val nameDocumentCount: Int get() = nameGroups.sumOf { it.documents.size }
    val exactDocumentCount: Int get() = exactGroups.sumOf { it.documents.size }
    val isTargetAnalysisPending: Boolean
        get() = targetLifecycle == TargetLifecycle.Pending

    val isTargetNotFound: Boolean
        get() = targetLifecycle == TargetLifecycle.NotFound
}

data class RelatedAction(
    val message: UiText,
    val undoTrashIds: Set<Long> = emptySet(),
    val retryAnalysisIds: Set<Long> = emptySet(),
)

internal fun safeExactSelection(
    groups: List<ExactRelatedGroup>,
    requested: Set<Long>,
): Set<Long> = groups.flatMapTo(mutableSetOf()) { group ->
    val requestedInGroup = group.documents
        .map { it.id }
        .filter { it in requested }
    requestedInGroup.take((group.documents.size - 1).coerceAtLeast(0))
}

class RelatedViewModel(
    private val repository: RelatedRepository,
    private val trashController: TrashController? = null,
    private val feedbackController: SimilarityFeedbackController? = null,
    private val targetDocumentIds: Set<Long> = emptySet(),
) : ViewModel() {
    private val selectedExactIds = MutableStateFlow<Set<Long>>(emptySet())
    private val action = MutableStateFlow<RelatedAction?>(null)
    private val pendingConfirmation = MutableStateFlow<SystemConfirmationRequest?>(null)
    private val isTrashWorking = MutableStateFlow(false)
    private val trashRequestState = combine(pendingConfirmation, isTrashWorking) { confirmation, working ->
        RelatedTrashRequestState(confirmation, working)
    }
    private val targetTracking = MutableStateFlow(
        TargetTracking(
            lifecycle = if (targetDocumentIds.isEmpty()) {
                TargetLifecycle.None
            } else {
                TargetLifecycle.Pending
            },
        ),
    )

    val state: StateFlow<RelatedUiState> = combine(
        repository.data,
        selectedExactIds,
        action,
        targetTracking,
        trashRequestState,
    ) { data, selected, action, target, trashRequest ->
        RelatedUiState(
            nameGroups = data.nameGroups,
            exactGroups = data.exactGroups,
            selectedExactIds = safeExactSelection(
                groups = data.exactGroups,
                requested = selected,
            ),
            action = action,
            pendingConfirmation = trashRequest.confirmation,
            isTrashWorking = trashRequest.working,
            targetDocumentIds = targetDocumentIds,
            targetGroupId = target.groupId,
            targetLifecycle = target.lifecycle,
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RelatedUiState())

    init {
        if (targetDocumentIds.isNotEmpty()) {
            viewModelScope.launch {
                repository.data.collect(::updateTargetTracking)
            }
        }
    }

    fun confirmCurrent(groupId: Long, documentId: Long) {
        viewModelScope.launch { repository.confirmCurrent(groupId, documentId) }
    }

    fun markOpened(documentId: Long) {
        viewModelScope.launch { repository.markOpened(documentId) }
    }

    fun markNotRelated(groupId: Long, documentId: Long, expectedMemberIds: Set<Long>) {
        val feedback = feedbackController ?: return
        viewModelScope.launch {
            val result = feedback.markNotRelated(groupId, documentId, expectedMemberIds)
            action.value = when (
                result
            ) {
                is FeedbackResult.SavedPendingAnalysis -> RelatedAction(
                    UiText.Resource(R.string.organization_not_related_saved),
                )
                is FeedbackResult.AnalysisQueueFailed -> RelatedAction(
                    UiText.Resource(R.string.organization_feedback_saved_queue_failed),
                    retryAnalysisIds = result.documentIds,
                )
                else -> RelatedAction(UiText.Resource(R.string.organization_feedback_group_unavailable))
            }
            if (result is FeedbackResult.SavedPendingAnalysis ||
                result is FeedbackResult.AnalysisQueueFailed
            ) {
                dismissTarget(groupId)
            }
        }
    }

    fun ignoreGroup(groupId: Long, expectedMemberIds: Set<Long>) {
        val feedback = feedbackController ?: return
        viewModelScope.launch {
            val result = feedback.ignoreGroup(groupId, expectedMemberIds)
            action.value = when (result) {
                is FeedbackResult.SavedPendingAnalysis -> RelatedAction(
                    UiText.Resource(R.string.organization_group_ignored),
                )
                is FeedbackResult.AnalysisQueueFailed -> RelatedAction(
                    UiText.Resource(R.string.organization_feedback_saved_queue_failed),
                    retryAnalysisIds = result.documentIds,
                )
                else -> RelatedAction(UiText.Resource(R.string.organization_feedback_group_unavailable))
            }
            if (result is FeedbackResult.SavedPendingAnalysis ||
                result is FeedbackResult.AnalysisQueueFailed
            ) {
                dismissTarget(groupId)
            }
        }
    }

    fun retryAnalysis(documentIds: Set<Long>) {
        val feedback = feedbackController ?: return
        viewModelScope.launch {
            val retryingTarget = targetDocumentIds.isNotEmpty() &&
                documentIds.containsAll(targetDocumentIds)
            val previousTarget = targetTracking.value
            if (retryingTarget) {
                targetTracking.value = TargetTracking(TargetLifecycle.Pending)
            }
            val result = feedback.retryAnalysis(documentIds)
            action.value = when (result) {
                is FeedbackResult.SavedPendingAnalysis -> RelatedAction(
                    UiText.Resource(R.string.organization_analysis_retry_started),
                )
                is FeedbackResult.AnalysisQueueFailed -> RelatedAction(
                    UiText.Resource(R.string.organization_feedback_saved_queue_failed),
                    retryAnalysisIds = result.documentIds,
                )
                else -> RelatedAction(UiText.Resource(R.string.organization_feedback_group_unavailable))
            }
            if (retryingTarget && result !is FeedbackResult.SavedPendingAnalysis) {
                targetTracking.value = previousTarget
            }
        }
    }

    fun reviewLater() {
        action.value = RelatedAction(UiText.Resource(R.string.organization_review_kept_for_later))
    }

    fun toggleExact(documentId: Long) {
        val groups = state.value.exactGroups
        val validIds = groups.flatMapTo(mutableSetOf()) { group -> group.documents.map { it.id } }
        if (documentId !in validIds) return
        val currentSelection = safeExactSelection(
            groups = groups,
            requested = selectedExactIds.value,
        )
        val requestedSelection = currentSelection.toMutableSet().apply {
            if (!add(documentId)) remove(documentId)
        }
        val safeSelection = safeExactSelection(groups, requestedSelection)
        if (safeSelection != requestedSelection) {
            action.value = RelatedAction(UiText.Resource(R.string.organization_keep_one_per_group))
            return
        }
        selectedExactIds.value = safeSelection
    }

    fun moveSelectedToTrash(requestedIds: Set<Long>) {
        val trash = trashController ?: return
        if (isTrashWorking.value) return
        val ids = safeExactSelection(
            groups = state.value.exactGroups,
            requested = requestedIds
                .intersect(selectedExactIds.value),
        )
        if (ids.isEmpty()) return
        viewModelScope.launch {
            isTrashWorking.value = true
            handleTrashResult(trash.requestTrash(ids))
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
                val successfulDocumentIds = result.successes.mapTo(mutableSetOf()) { it.documentId }
                val trashIds = result.successes.mapNotNullTo(mutableSetOf()) { it.trashId }
                selectedExactIds.value = selectedExactIds.value - successfulDocumentIds
                val failedCount = result.failures.size
                action.value = RelatedAction(
                    message = if (failedCount == 0) {
                        UiText.Resource(R.string.organization_duplicate_trash_success, listOf(trashIds.size))
                    } else {
                        UiText.Resource(
                            R.string.organization_duplicate_trash_partial,
                            listOf(trashIds.size, failedCount),
                        )
                    },
                    undoTrashIds = trashIds,
                )
                pendingConfirmation.value = null
                isTrashWorking.value = false
            }
            TrashOperationResult.Cancelled -> {
                action.value = RelatedAction(UiText.Resource(R.string.organization_trash_cancelled))
                pendingConfirmation.value = null
                isTrashWorking.value = false
            }
        }
    }

    fun undo(current: RelatedAction) {
        val trash = trashController ?: return
        viewModelScope.launch {
            val restored = current.undoTrashIds.count { trash.restore(it).isSuccess }
            action.value = RelatedAction(
                UiText.Resource(R.string.organization_restored_documents, listOf(restored)),
            )
        }
    }

    fun clearAction() { action.value = null }

    private fun updateTargetTracking(data: RelatedData) {
        val current = targetTracking.value
        if (current.lifecycle == TargetLifecycle.None ||
            current.lifecycle == TargetLifecycle.Dismissed
        ) {
            return
        }
        val matchingGroupId = data.nameGroups.firstOrNull { group ->
            group.documents.mapTo(mutableSetOf()) { it.id }.containsAll(targetDocumentIds)
        }?.id
        if (matchingGroupId != null) {
            targetTracking.value = current.copy(
                lifecycle = TargetLifecycle.Resolved,
                groupId = matchingGroupId,
                awaitedGeneration = maxOf(
                    current.awaitedGeneration ?: 0L,
                    data.analysisGeneration,
                ),
            )
            return
        }
        if (current.lifecycle == TargetLifecycle.Resolved) {
            targetTracking.value = current.copy(
                lifecycle = TargetLifecycle.Dismissed,
                groupId = null,
            )
            return
        }
        if (current.lifecycle == TargetLifecycle.NotFound) return

        val awaitedGeneration = when (data.analysisStatus) {
            RelationAnalysisStatus.QUEUED,
            RelationAnalysisStatus.RUNNING,
            -> maxOf(current.awaitedGeneration ?: 0L, data.analysisGeneration)
            else -> current.awaitedGeneration
        }
        targetTracking.value = if (
            data.analysisStatus == RelationAnalysisStatus.COMPLETED &&
            data.analysisGeneration >= (awaitedGeneration ?: data.analysisGeneration)
        ) {
            TargetTracking(
                lifecycle = TargetLifecycle.NotFound,
                awaitedGeneration = data.analysisGeneration,
            )
        } else {
            current.copy(awaitedGeneration = awaitedGeneration)
        }
    }

    private fun dismissTarget(groupId: Long) {
        val current = targetTracking.value
        if (current.groupId == groupId) {
            targetTracking.value = current.copy(
                lifecycle = TargetLifecycle.Dismissed,
                groupId = null,
            )
        }
    }

    companion object {
        fun factory(
            repository: RelatedRepository,
            trashController: TrashController,
            feedbackController: SimilarityFeedbackController,
            targetDocumentIds: Set<Long> = emptySet(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RelatedViewModel(
                    repository,
                    trashController,
                    feedbackController,
                    targetDocumentIds,
                )
            }
        }
    }
}
