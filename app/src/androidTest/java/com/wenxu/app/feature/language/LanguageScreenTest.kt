package com.wenxu.app.feature.language

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsBothLanguagesAndUsesSelectedLanguageForPrimaryAction() {
        val confirmCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                LanguageScreen(
                    state = LanguageUiState(
                        isLoading = false,
                        selectedLanguage = AppLanguage.ENGLISH,
                    ),
                    onConfirm = { confirmCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("English").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsDisplayed().performClick()
        assertThat(confirmCalls.get()).isEqualTo(1)
    }
}
