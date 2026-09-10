package com.wenxu.app.core.localization

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wenxu.app.R
import com.wenxu.app.ui.theme.WenxuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTextTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun resourceTextResolvesInsideCompose() {
        rule.setContent {
            WenxuTheme {
                Text(
                    UiText.Resource(
                        R.string.home_scan_count_detail,
                        listOf(2, 12, 4),
                    ).resolve(),
                )
            }
        }

        rule.onNodeWithText("已检查 2 个文件夹 · 12 个文件 · 找到 4 份文档")
            .assertIsDisplayed()
    }

    @Test
    fun pluralTextResolvesInsideCompose() {
        rule.setContent {
            WenxuTheme {
                Text(UiText.Plural(R.plurals.documents_selected_count, 2).resolve())
            }
        }

        rule.onNodeWithText("已选择 2 份文档").assertIsDisplayed()
    }
}
