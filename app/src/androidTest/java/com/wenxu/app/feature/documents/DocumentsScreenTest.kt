package com.wenxu.app.feature.documents

import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.R
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun searchDoesNotStealFocusAndClearButtonOnlyAppearsWithText() {
        rule.setContent {
            WenxuTheme {
                DocumentsScreen(state = DocumentsUiState())
            }
        }

        rule.onNodeWithText("搜索文件名").assertIsNotFocused()
        rule.onNodeWithContentDescription("清除搜索").assertDoesNotExist()
    }

    @Test
    fun documentLibraryExposesMultiSelectAndBatchClassification() {
        val enterCalls = AtomicInteger()
        val batchCalls = AtomicInteger()
        val document = document(1, "课程报告.pdf")

        rule.setContent {
            WenxuTheme {
                DocumentsScreen(
                    state = DocumentsUiState(documents = listOf(document), totalCount = 1),
                    onEnterSelection = { enterCalls.incrementAndGet() },
                )
            }
        }
        rule.onNodeWithText("多选").assertIsDisplayed().performClick()
        assertThat(enterCalls.get()).isEqualTo(1)

        rule.setContent {
            WenxuTheme {
                DocumentsScreen(
                    state = DocumentsUiState(
                        documents = listOf(document),
                        totalCount = 1,
                        selectionMode = true,
                        selectedDocumentIds = setOf(document.id),
                    ),
                    onBatchClassify = { batchCalls.incrementAndGet() },
                )
            }
        }
        rule.onNodeWithText("已选择 1 份文档").assertIsDisplayed()
        rule.onNodeWithText("添加到分类").assertIsDisplayed().performClick()
        assertThat(batchCalls.get()).isEqualTo(1)
    }

    @Test
    fun batchMoreExposesMergeAsVersions() {
        val mergeCalls = AtomicInteger()
        val document = document(1, "课程报告最终修改版本很长的名称.docx")

        rule.setContent {
            WenxuTheme {
                DocumentsScreen(
                    state = DocumentsUiState(
                        documents = listOf(document),
                        totalCount = 1,
                        selectionMode = true,
                        selectedDocumentIds = setOf(document.id),
                    ),
                    onBatchMerge = { mergeCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("更多").performClick()
        rule.onNodeWithText("合并为版本组").assertIsDisplayed().performClick()
        assertThat(mergeCalls.get()).isEqualTo(1)
    }

    @Test
    fun filterUsesPlainLanguageForClassifiedAndPossibleDuplicates() {
        rule.setContent {
            WenxuTheme {
                DocumentsScreen(state = DocumentsUiState(), onShowFilters = {})
            }
        }

        assertThat(OrganizationFilter.CLASSIFIED.labelRes)
            .isEqualTo(R.string.organization_filter_classified)
        assertThat(OrganizationFilter.RELATED.labelRes)
            .isEqualTo(R.string.organization_filter_needs_review)
    }

    @Test
    fun documentLibraryShowsOrganizationFiltersAndDirectFormatPicker() {
        var selectedFormat: FormatFilter? = null

        rule.setContent {
            WenxuTheme {
                DocumentsScreen(
                    state = DocumentsUiState(totalCount = 8),
                    onFormatChange = { selectedFormat = it },
                )
            }
        }

        rule.onNodeWithText("未分类").assertIsDisplayed()
        rule.onNodeWithText("已分类").assertIsDisplayed()
        rule.onNodeWithText("需确认").assertIsDisplayed()
        rule.onNodeWithText("类型：全部").assertIsDisplayed().performClick()
        rule.onNodeWithText("选择文件类型").assertIsDisplayed()
        rule.onNodeWithText("PDF").assertIsDisplayed().performClick()

        assertThat(selectedFormat).isEqualTo(FormatFilter.PDF)
    }
}

private fun document(id: Long, name: String) = DocumentRecord(
    id = id,
    uri = "content://documents/$id",
    displayName = name,
    extension = name.substringAfterLast('.', "pdf"),
    sizeBytes = 1024,
    modifiedAt = 1,
    sourceId = 1,
    parentUri = "content://documents",
)
