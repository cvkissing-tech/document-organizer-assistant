package com.wenxu.app.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.core.settings.GuidePreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FirstUseGuideUiState(
    val isLoading: Boolean = true,
    val shouldShowGuide: Boolean = false,
)

class FirstUseGuideViewModel(
    private val preferences: GuidePreferences,
) : ViewModel() {
    val state: StateFlow<FirstUseGuideUiState> = preferences.hasCompletedFirstUseGuide
        .map { completed ->
            FirstUseGuideUiState(
                isLoading = false,
                shouldShowGuide = !completed,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FirstUseGuideUiState(),
        )

    fun completeGuide() {
        viewModelScope.launch {
            preferences.setFirstUseGuideCompleted(true)
        }
    }

    companion object {
        fun factory(preferences: GuidePreferences): ViewModelProvider.Factory = viewModelFactory {
            initializer { FirstUseGuideViewModel(preferences) }
        }
    }
}
