package com.wenxu.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.R
import com.wenxu.app.core.index.ScanScheduler
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.storage.ScanSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val sources: List<ScanSourceEntity> = emptyList(),
    val accessState: StorageAccessState = StorageAccessState.NeedsPermission,
    val isAdding: Boolean = false,
    val isLimitedMode: Boolean = false,
    val finishRequested: Boolean = false,
    val errorMessage: UiText? = null,
) {
    val canRequestFullAccess: Boolean
        get() = accessState == StorageAccessState.NeedsPermission && !isLimitedMode

    val showLimitedMode: Boolean
        get() = isLimitedMode

    val canReturnToFullAccess: Boolean
        get() = accessState == StorageAccessState.NeedsPermission && isLimitedMode
}

class OnboardingViewModel(
    private val repository: ScanSourceRepository,
    private val storageAccessController: StorageAccessController,
    private val scanScheduler: ScanScheduler,
    private val scanPreferences: ScanPreferences? = null,
) : ViewModel() {
    private val accessState = MutableStateFlow(storageAccessController.state())
    private val operationState = MutableStateFlow(
        OperationState(limitedModeSelected = accessState.value == StorageAccessState.Legacy),
    )
    private var fullAccessCompletionStarted = false

    init {
        viewModelScope.launch {
            val treeSources = repository.observeSources().first().treeSources()
            when {
                accessState.value == StorageAccessState.Granted -> completeWithFullAccess()
                treeSources.isNotEmpty() -> requestFinish()
            }
        }
    }

    val state: StateFlow<OnboardingUiState> = combine(
        repository.observeSources(),
        accessState,
        operationState,
    ) { sources, access, operation ->
        OnboardingUiState(
            sources = sources.treeSources(),
            accessState = access,
            isAdding = operation.isAdding,
            isLimitedMode = operation.limitedModeSelected,
            finishRequested = operation.finishRequested,
            errorMessage = operation.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = OnboardingUiState(
            accessState = accessState.value,
            isLimitedMode = accessState.value == StorageAccessState.Legacy,
        ),
    )

    fun onStorageSettingsReturned() {
        val refreshedState = storageAccessController.state()
        accessState.value = refreshedState
        if (refreshedState == StorageAccessState.Granted) {
            viewModelScope.launch {
                scanPreferences?.setScanScope(DocumentScanScope.ALL_DOCUMENTS)
                completeWithFullAccess()
            }
        } else {
            operationState.value = operationState.value.copy(
                limitedModeSelected = true,
                errorMessage = null,
            )
        }
    }

    fun onPermissionLaunchFailed() {
        operationState.value = operationState.value.copy(
            limitedModeSelected = true,
            errorMessage = UiText.Resource(R.string.onboarding_open_permission_failed),
        )
    }

    fun selectLimitedMode() {
        viewModelScope.launch {
            scanPreferences?.setScanScope(DocumentScanScope.SELECTED_FOLDERS)
        }
        operationState.value = operationState.value.copy(
            limitedModeSelected = true,
            errorMessage = null,
        )
    }

    fun selectFullAccessMode() {
        if (accessState.value != StorageAccessState.NeedsPermission) return
        viewModelScope.launch {
            scanPreferences?.setScanScope(DocumentScanScope.ALL_DOCUMENTS)
        }
        operationState.value = operationState.value.copy(
            limitedModeSelected = false,
            errorMessage = null,
        )
    }

    fun onFolderPickerResult(uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            operationState.value = operationState.value.copy(
                isAdding = true,
                errorMessage = null,
            )
            repository.add(uri).fold(
                onSuccess = {
                    scanPreferences?.setScanScope(DocumentScanScope.SELECTED_FOLDERS)
                    operationState.value = operationState.value.copy(
                        isAdding = false,
                        finishRequested = true,
                    )
                },
                onFailure = {
                    operationState.value = operationState.value.copy(
                        isAdding = false,
                        errorMessage = UiText.Resource(R.string.onboarding_save_folder_failed),
                    )
                },
            )
        }
    }

    fun clearError() {
        operationState.value = operationState.value.copy(errorMessage = null)
    }

    private suspend fun completeWithFullAccess() {
        if (fullAccessCompletionStarted) return
        fullAccessCompletionStarted = true
        scanScheduler.start()
        requestFinish()
    }

    private fun requestFinish() {
        operationState.value = operationState.value.copy(finishRequested = true)
    }

    private data class OperationState(
        val isAdding: Boolean = false,
        val limitedModeSelected: Boolean = false,
        val finishRequested: Boolean = false,
        val errorMessage: UiText? = null,
    )

    companion object {
        fun factory(
            repository: ScanSourceRepository,
            storageAccessController: StorageAccessController,
            scanScheduler: ScanScheduler,
            scanPreferences: ScanPreferences,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    repository = repository,
                    storageAccessController = storageAccessController,
                    scanScheduler = scanScheduler,
                    scanPreferences = scanPreferences,
                )
            }
        }
    }
}

private fun List<ScanSourceEntity>.treeSources(): List<ScanSourceEntity> =
    filter { source -> source.sourceKind == ScanSourceKind.TREE }
