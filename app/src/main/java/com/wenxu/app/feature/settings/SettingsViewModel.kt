package com.wenxu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.settings.LanguagePreferences
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.storage.ScanSourceRepository
import com.wenxu.app.feature.trash.TrashRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val sourceCount: Int = 0,
    val scanScopeMode: DocumentScanScope = DocumentScanScope.SELECTED_FOLDERS,
    val scanOnLaunch: Boolean = true,
    val trashCount: Int = 0,
    val appLanguage: AppLanguage = AppLanguage.systemDefault(),
)

class SettingsViewModel(
    sourceRepository: ScanSourceRepository,
    private val preferences: ScanPreferences,
    languagePreferences: LanguagePreferences,
    trashRepository: TrashRepository,
    private val storageAccessController: StorageAccessController,
) : ViewModel() {
    private val accessState = MutableStateFlow(storageAccessController.state())

    private val settingsState = combine(
        sourceRepository.observeSources(),
        preferences.scanOnLaunch,
        preferences.scanScope,
        trashRepository.items,
        accessState,
    ) { sources, scanOnLaunch, configuredScope, trash, access ->
        SettingsUiState(
            sourceCount = sources.count { source -> source.sourceKind == ScanSourceKind.TREE },
            scanScopeMode = if (
                access == StorageAccessState.Granted &&
                configuredScope == DocumentScanScope.ALL_DOCUMENTS
            ) {
                DocumentScanScope.ALL_DOCUMENTS
            } else {
                DocumentScanScope.SELECTED_FOLDERS
            },
            scanOnLaunch = scanOnLaunch,
            trashCount = trash.size,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        settingsState,
        languagePreferences.selectedLanguage,
    ) { settings, selectedLanguage ->
        settings.copy(appLanguage = selectedLanguage ?: AppLanguage.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setScanOnLaunch(enabled: Boolean) {
        viewModelScope.launch { preferences.setScanOnLaunch(enabled) }
    }

    fun refreshAccessState() {
        accessState.value = storageAccessController.state()
    }

    companion object {
        fun factory(
            sourceRepository: ScanSourceRepository,
            preferences: ScanPreferences,
            languagePreferences: LanguagePreferences,
            trashRepository: TrashRepository,
            storageAccessController: StorageAccessController,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    sourceRepository = sourceRepository,
                    preferences = preferences,
                    languagePreferences = languagePreferences,
                    trashRepository = trashRepository,
                    storageAccessController = storageAccessController,
                )
            }
        }
    }
}
