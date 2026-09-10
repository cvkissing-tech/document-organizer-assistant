package com.wenxu.app.feature.guide

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.settings.GuidePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstUseGuideViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun newUserSeesGuide() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = FirstUseGuideViewModel(FakeGuidePreferences(false))

        advanceUntilIdle()

        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.shouldShowGuide).isTrue()
    }

    @Test
    fun returningUserSkipsGuide() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = FirstUseGuideViewModel(FakeGuidePreferences(true))

        advanceUntilIdle()

        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.shouldShowGuide).isFalse()
    }

    @Test
    fun completingGuidePersistsChoiceAndRequestsExit() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeGuidePreferences(false)
        val viewModel = FirstUseGuideViewModel(preferences)
        advanceUntilIdle()

        viewModel.completeGuide()
        advanceUntilIdle()

        assertThat(preferences.hasCompletedFirstUseGuideValue).isTrue()
        assertThat(viewModel.state.value.shouldShowGuide).isFalse()
    }
}

private class FakeGuidePreferences(initial: Boolean) : GuidePreferences {
    private val completed = MutableStateFlow(initial)
    override val hasCompletedFirstUseGuide: Flow<Boolean> = completed
    val hasCompletedFirstUseGuideValue: Boolean get() = completed.value

    override suspend fun setFirstUseGuideCompleted(completed: Boolean) {
        this.completed.value = completed
    }
}
