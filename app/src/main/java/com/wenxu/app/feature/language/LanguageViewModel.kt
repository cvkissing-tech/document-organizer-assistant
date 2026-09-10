package com.wenxu.app.feature.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.settings.LanguagePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LanguageUiState(
    val isLoading: Boolean = true,
    val savedLanguage: AppLanguage? = null,
    val selectedLanguage: AppLanguage = AppLanguage.systemDefault(),
    val isSaving: Boolean = false,
)

class LanguageViewModel(
    private val preferences: LanguagePreferences,
    private val recommendedLanguage: () -> AppLanguage = AppLanguage::systemDefault,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LanguageUiState(selectedLanguage = recommendedLanguage()),
    )
    val state: StateFlow<LanguageUiState> = mutableState.asStateFlow()

    private var hasManualSelection = false

    init {
        viewModelScope.launch {
            preferences.selectedLanguage.collect { savedLanguage ->
                mutableState.update { current ->
                    current.copy(
                        isLoading = false,
                        savedLanguage = savedLanguage,
                        selectedLanguage = when {
                            hasManualSelection -> current.selectedLanguage
                            savedLanguage != null -> savedLanguage
                            else -> recommendedLanguage()
                        },
                    )
                }
            }
        }
    }

    fun selectLanguage(language: AppLanguage) {
        hasManualSelection = true
        mutableState.update { current -> current.copy(selectedLanguage = language) }
    }

    fun confirmSelection(onSaved: (AppLanguage) -> Unit) {
        if (mutableState.value.isSaving) return
        val language = mutableState.value.selectedLanguage
        mutableState.update { current -> current.copy(isSaving = true) }
        viewModelScope.launch {
            preferences.setSelectedLanguage(language)
            mutableState.update { current ->
                current.copy(savedLanguage = language, isSaving = false)
            }
            onSaved(language)
        }
    }

    companion object {
        fun factory(preferences: LanguagePreferences): ViewModelProvider.Factory = viewModelFactory {
            initializer { LanguageViewModel(preferences) }
        }
    }
}
