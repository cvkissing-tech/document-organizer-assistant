package com.wenxu.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithLanguageSelectionBeforeAuthorization() {
        rule.onNodeWithText("选择语言 / Choose language").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("English").assertIsDisplayed()
    }
}
