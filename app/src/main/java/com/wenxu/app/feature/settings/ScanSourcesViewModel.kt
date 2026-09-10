package com.wenxu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.storage.ScanSourceRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanSourcesUiState(
    val sources: List<ScanSourceEntity> = emptyList(),
    val hasFullAccessPermission: Boolean = false,
    val selectedScope: DocumentScanScope = DocumentScanScope.SELECTED_FOLDERS,
) {
    val isFullAccessMode: Boolean
        get() = hasFullAccessPermission && selectedScope == DocumentScanScope.ALL_DOCUMENTS

    val canAddFolder: Boolean
        get() = !isFullAccessMode
}

class ScanSourcesViewModel(
    private val repository: ScanSourceRepository,
    private val storageAccessController: StorageAccessController,
    private val preferences: ScanPreferences,
    private val scanSelectedFolders: suspend () -> Result<Unit> = { Result.success(Unit) },
) : ViewModel() {
    private val mutableMessage = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = mutableMessage
    private val accessState = MutableStateFlow(storageAccessController.state())

    val state: StateFlow<ScanSourcesUiState> = combine(
        repository.observeSources(),
        accessState,
        preferences.scanScope,
    ) { sources, access, configuredScope ->
        val hasFullAccess = access == StorageAccessState.Granted
        ScanSourcesUiState(
            sources = sources.filter { source -> source.sourceKind == ScanSourceKind.TREE },
            hasFullAccessPermission = hasFullAccess,
            selectedScope = if (hasFullAccess) {
                configuredScope
            } else {
                DocumentScanScope.SELECTED_FOLDERS
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ScanSourcesUiState(
            hasFullAccessPermission = accessState.value == StorageAccessState.Granted,
        ),
    )

    fun setScope(scope: DocumentScanScope) {
        if (scope == DocumentScanScope.ALL_DOCUMENTS &&
            accessState.value != StorageAccessState.Granted
        ) {
            mutableMessage.value = UiText.Resource(R.string.scan_sources_need_full_access)
            return
        }
        viewModelScope.launch {
            preferences.setScanScope(scope)
            mutableMessage.value = if (scope == DocumentScanScope.ALL_DOCUMENTS) {
                UiText.Resource(R.string.scan_sources_switched_all)
            } else {
                UiText.Resource(R.string.scan_sources_switched_folders)
            }
        }
    }

    fun remove(sourceId: Long) {
        viewModelScope.launch {
            mutableMessage.value = repository.remove(sourceId).fold(
                onSuccess = { UiText.Resource(R.string.scan_sources_removed) },
                onFailure = { UiText.Resource(R.string.scan_sources_remove_failed) },
            )
        }
    }

    fun add(uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            repository.add(uri).fold(
                onSuccess = {
                    mutableMessage.value = scanSelectedFolders().fold(
                        onSuccess = { UiText.Resource(R.string.scan_sources_added_updating) },
                        onFailure = { UiText.Resource(R.string.scan_sources_added_scan_later) },
                    )
                },
                onFailure = { mutableMessage.value = UiText.Resource(R.string.scan_sources_add_failed) },
            )
        }
    }

    fun rescan() {
        viewModelScope.launch {
            mutableMessage.value = scanSelectedFolders().fold(
                onSuccess = { UiText.Resource(R.string.scan_sources_rescanning) },
                onFailure = { UiText.Resource(R.string.scan_sources_rescan_failed) },
            )
        }
    }

    fun reauthorize(sourceId: Long, uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            repository.reauthorize(sourceId, uri).fold(
                onSuccess = {
                    mutableMessage.value = scanSelectedFolders().fold(
                        onSuccess = { UiText.Resource(R.string.scan_sources_reauthorized_updating) },
                        onFailure = { UiText.Resource(R.string.scan_sources_reauthorized_scan_later) },
                    )
                },
                onFailure = { mutableMessage.value = UiText.Resource(R.string.scan_sources_reauthorize_failed) },
            )
        }
    }

    fun clearMessage() { mutableMessage.value = null }

    fun onStorageSettingsReturned() {
        val refreshedState = storageAccessController.state()
        accessState.value = refreshedState
        if (refreshedState != StorageAccessState.Granted) {
            mutableMessage.value = UiText.Resource(R.string.scan_sources_permission_denied)
            return
        }
        viewModelScope.launch {
            preferences.setScanScope(DocumentScanScope.ALL_DOCUMENTS)
            mutableMessage.value = scanSelectedFolders().fold(
                onSuccess = { UiText.Resource(R.string.scan_sources_all_updating) },
                onFailure = { UiText.Resource(R.string.scan_sources_all_scan_later) },
            )
        }
    }

    fun onPermissionLaunchFailed() {
        mutableMessage.value = UiText.Resource(R.string.scan_sources_open_system_failed)
    }

    fun refreshAccessState() {
        accessState.value = storageAccessController.state()
    }

    companion object {
        fun factory(
            repository: ScanSourceRepository,
            storageAccessController: StorageAccessController,
            preferences: ScanPreferences,
            scanSelectedFolders: suspend () -> Result<Unit>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScanSourcesViewModel(
                    repository,
                    storageAccessController,
                    preferences,
                    scanSelectedFolders,
                )
            }
        }
    }
}
