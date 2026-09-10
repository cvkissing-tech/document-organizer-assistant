package com.wenxu.app.feature.categories

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun categoryDetailKeepsAddDocumentsVisible() {
        val addCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                CategoryDetailScreen(
                    state = CategoryDetailUiState(
                        title = UiText.Plain("课程资料"),
                        documents = emptyList(),
                    ),
                    onAddDocuments = { addCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("添加文档").assertIsDisplayed().performClick()
        rule.onNodeWithText("这个分类还没有文档").assertIsDisplayed()
        assertThat(addCalls.get()).isEqualTo(1)
    }

    @Test
    fun unclassifiedDetailDoesNotOfferAddingDocuments() {
        rule.setContent {
            WenxuTheme {
                CategoryDetailScreen(
                    state = CategoryDetailUiState(isUnclassified = true),
                )
            }
        }

        rule.onNodeWithText("待归类文档").assertIsDisplayed()
        rule.onNodeWithText("没有待归类文档").assertIsDisplayed()
        rule.onNodeWithContentDescription("添加文档").assertDoesNotExist()
        rule.onNodeWithText("添加文档").assertDoesNotExist()
    }

    @Test
    fun unclassifiedDetailExposesMultiSelectAndBatchClassification() {
        val enterCalls = AtomicInteger()
        val batchCalls = AtomicInteger()
        val document = document(1, "课程讲义.pdf")
        rule.setContent {
            WenxuTheme {
                CategoryDetailScreen(
                    state = CategoryDetailUiState(
                        isUnclassified = true,
                        documents = listOf(document),
                    ),
                    onEnterSelection = { enterCalls.incrementAndGet() },
                )
            }
        }
        rule.onNodeWithText("多选").assertIsDisplayed().performClick()
        assertThat(enterCalls.get()).isEqualTo(1)

        rule.setContent {
            WenxuTheme {
                CategoryDetailScreen(
                    state = CategoryDetailUiState(
                        isUnclassified = true,
                        documents = listOf(document),
                        selectionMode = true,
                        selectedDocumentIds = setOf(document.id),
                    ),
                    onBatchClassify = { batchCalls.incrementAndGet() },
                )
            }
        }
        rule.onNodeWithText("已选择 1 份文档").assertIsDisplayed()
        rule.onNodeWithText("归入分类").assertIsDisplayed().performClick()
        assertThat(batchCalls.get()).isEqualTo(1)
    }
}

private fun document(id: Long, name: String) = DocumentRecord(
    id = id,
    uri = "content://documents/$id",
    displayName = name,
    extension = name.substringAfterLast('.'),
    sizeBytes = 1024,
    modifiedAt = 1,
    sourceId = 1,
    parentUri = "content://documents",
)
