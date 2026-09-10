package com.wenxu.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.trash.SystemConfirmationRequest
import com.wenxu.app.core.trash.TrashOperationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<TrashItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val isWorking: Boolean = false,
    val pendingConfirmation: SystemConfirmationRequest? = null,
    val message: UiText? = null,
)

class TrashViewModel(private val repository: TrashRepository) : ViewModel() {
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val operation = MutableStateFlow(OperationState())

    val state: StateFlow<TrashUiState> = combine(
        repository.items,
        selectedIds,
        operation,
    ) { items, selected, operation ->
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        TrashUiState(
            items = items,
            selectedIds = selected.intersect(validIds),
            isWorking = operation.isWorking,
            pendingConfirmation = operation.pendingConfirmation,
            message = operation.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TrashUiState())

    fun toggle(id: Long) {
        selectedIds.value = selectedIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun selectAll() {
        selectedIds.value = if (state.value.selectedIds.size == state.value.items.size) {
            emptySet()
        } else {
            state.value.items.mapTo(mutableSetOf()) { it.id }
        }
    }

    fun restoreSelected(fallbackParentUri: String? = null) {
        val ids = selectedIds.value
        if (ids.isEmpty() || state.value.isWorking) return
        viewModelScope.launch {
            operation.value = OperationState(isWorking = true)
            handleResult(repository.requestRestore(ids, fallbackParentUri), isRestore = true)
        }
    }

    fun restoreOne(id: Long) {
        if (state.value.isWorking) return
        selectedIds.value = setOf(id)
        restoreSelected()
    }

    fun deleteSelectedForever() {
        val ids = selectedIds.value
        if (ids.isEmpty() || state.value.isWorking) return
        viewModelScope.launch {
            operation.value = OperationState(isWorking = true)
            handleResult(repository.requestDeleteForever(ids), isRestore = false)
        }
    }

    fun onSystemTrashResult(operationId: Long, approved: Boolean) {
        val request = operation.value.pendingConfirmation
        if (request?.operationId != operationId) return
        viewModelScope.launch {
            handleResult(
                repository.completeConfirmation(operationId, approved),
                isRestore = request.action == PendingTrashAction.RESTORE,
            )
        }
    }

    private fun handleResult(result: TrashOperationResult, isRestore: Boolean) {
        when (result) {
            is TrashOperationResult.RequiresConfirmation -> {
                operation.value = OperationState(
                    isWorking = true,
                    pendingConfirmation = result.request,
                )
            }
            is TrashOperationResult.Completed -> {
                val succeeded = result.successes.mapTo(mutableSetOf()) { it.targetId }
                selectedIds.value = selectedIds.value - succeeded
                val successCount = result.successes.size
                val failureCount = result.failures.size
                operation.value = OperationState(
                    message = if (isRestore) {
                        if (failureCount == 0) {
                            UiText.Plural(R.plurals.trash_restored_count, successCount)
                        } else {
                            UiText.Resource(R.string.trash_restored_with_failures, listOf(successCount, failureCount))
                        }
                    } else if (failureCount == 0) {
                        UiText.Plural(R.plurals.trash_deleted_forever_count, successCount)
                    } else {
                        UiText.Resource(R.string.trash_deleted_with_failures, listOf(successCount, failureCount))
                    },
                )
            }
            TrashOperationResult.Cancelled -> {
                operation.value = OperationState(message = UiText.Resource(R.string.trash_operation_cancelled))
            }
        }
    }

    fun clearMessage() {
        operation.value = operation.value.copy(message = null)
    }

    private data class OperationState(
        val isWorking: Boolean = false,
        val message: UiText? = null,
        val pendingConfirmation: SystemConfirmationRequest? = null,
    )

    companion object {
        fun factory(repository: TrashRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(repository) }
        }
    }
}
