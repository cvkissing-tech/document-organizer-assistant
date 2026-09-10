package com.wenxu.app.feature.language

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.settings.LanguagePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun systemLanguageIsOnlyPreselectedUntilUserConfirms() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeLanguagePreferences()
            val viewModel = LanguageViewModel(
                preferences = preferences,
                recommendedLanguage = { AppLanguage.ENGLISH },
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.selectedLanguage).isEqualTo(AppLanguage.ENGLISH)
            assertThat(viewModel.state.value.savedLanguage).isNull()
            assertThat(preferences.savedValues).isEmpty()
        }

    @Test
    fun confirmPersistsManualSelectionAndReportsIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeLanguagePreferences()
            val viewModel = LanguageViewModel(
                preferences = preferences,
                recommendedLanguage = { AppLanguage.ENGLISH },
            )
            advanceUntilIdle()

            var confirmed: AppLanguage? = null
            viewModel.selectLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            viewModel.confirmSelection { confirmed = it }
            advanceUntilIdle()

            assertThat(preferences.savedValues).containsExactly(AppLanguage.SIMPLIFIED_CHINESE)
            assertThat(confirmed).isEqualTo(AppLanguage.SIMPLIFIED_CHINESE)
            assertThat(viewModel.state.value.isSaving).isFalse()
        }
}

private class FakeLanguagePreferences : LanguagePreferences {
    private val language = MutableStateFlow<AppLanguage?>(null)
    val savedValues = mutableListOf<AppLanguage>()

    override val selectedLanguage: Flow<AppLanguage?> = language

    override suspend fun setSelectedLanguage(language: AppLanguage) {
        savedValues += language
        this.language.value = language
    }
}
