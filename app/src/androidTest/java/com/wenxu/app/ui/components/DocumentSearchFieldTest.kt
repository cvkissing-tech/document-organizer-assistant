package com.wenxu.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wenxu.app.ui.theme.WenxuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentSearchFieldTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun placeholderInputAndClearRemainAccessibleInCompactHeight() {
        var value by mutableStateOf("")

        rule.setContent {
            WenxuTheme {
                DocumentSearchField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "搜索文件名",
                )
            }
        }

        rule.onNodeWithText("搜索文件名").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索文档").performTextInput("报告")
        rule.onNodeWithText("报告").assertIsDisplayed()
        rule.onNodeWithContentDescription("清除搜索").performClick()
        rule.onNodeWithText("搜索文件名").assertIsDisplayed()
    }
}
