package com.wenxu.app.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun runningProgressIsCompactReadableAndControllable() {
        val pauseCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        sourceCount = 1,
                        unclassifiedCount = 6,
                        scan = ScanProgressUiState(
                            visible = true,
                            title = UiText.Plain("正在查找文档"),
                            detail = UiText.Plain("已检查 24 个文件夹 · 1,286 个文件 · 找到 83 份文档"),
                            scope = HomeScanScope.ALL_DOCUMENTS,
                            status = ScanSessionStatus.RUNNING,
                            phase = ScanPhase.DISCOVERING,
                            primaryAction = ScanProgressAction.PAUSE,
                            canCancel = true,
                        ),
                    ),
                    onPauseScan = { pauseCalls.incrementAndGet() },
                    onCancelScan = { cancelCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("6份待归类").assertIsDisplayed()
        rule.onNodeWithText("正在查找文档").assertIsDisplayed()
        rule.onNodeWithText("已检查 24 个文件夹 · 1,286 个文件 · 找到 83 份文档")
            .assertIsDisplayed()
        rule.onNodeWithText("暂停").performClick()
        rule.onNodeWithContentDescription("停止扫描").performClick()

        assertThat(pauseCalls.get()).isEqualTo(1)
        assertThat(cancelCalls.get()).isEqualTo(1)
    }

    @Test
    fun failedScanOffersAWorkingRetryAction() {
        val retryCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        scan = ScanProgressUiState(
                            visible = true,
                            title = UiText.Plain("扫描未完成"),
                            detail = UiText.Plain("部分位置暂时无法读取"),
                            status = ScanSessionStatus.FAILED,
                            primaryAction = ScanProgressAction.RETRY,
                        ),
                    ),
                    onRetryScan = { retryCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertThat(retryCalls.get()).isEqualTo(1)
    }

    @Test
    fun completedScanShowsAResultReceiptAndRoutesToNextTasks() {
        val relatedCalls = AtomicInteger()
        val unclassifiedCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        relatedSummary = RelatedSummary(
                            nameSimilarGroups = 2,
                            exactDuplicateSets = 1,
                        ),
                        unclassifiedCount = 6,
                        scan = ScanProgressUiState(
                            visible = true,
                            title = UiText.Plain("文档已更新"),
                            detail = UiText.Plain("找到 83 份文档"),
                            status = ScanSessionStatus.COMPLETED,
                            discoveredDocuments = 83,
                            primaryAction = ScanProgressAction.RESTART,
                            isCompactCompletion = true,
                        ),
                    ),
                    onOpenRelated = { relatedCalls.incrementAndGet() },
                    onOpenUnclassified = { unclassifiedCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("整理完成").assertIsDisplayed()
        rule.onNodeWithText("已找到 83 份文档").assertIsDisplayed()
        rule.onNodeWithContentDescription("待确认，3").performClick()
        rule.onNodeWithContentDescription("待归类，6").performClick()

        assertThat(relatedCalls.get()).isEqualTo(1)
        assertThat(unclassifiedCalls.get()).isEqualTo(1)
    }

    @Test
    fun confirmDocumentsIsThePrimaryTaskAndExplainsBothKinds() {
        val relatedCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        relatedSummary = RelatedSummary(
                            nameSimilarGroups = 2,
                            exactDuplicateSets = 1,
                            previewNames = listOf(
                                "实习报告最终版.docx",
                                "实习报告修改版.docx",
                            ),
                        ),
                        unclassifiedCount = 6,
                    ),
                    onOpenRelated = { relatedCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("确认文档").assertIsDisplayed()
        rule.onNodeWithText("把容易混淆的文档放在一起，帮你确认版本或清理重复副本。")
            .assertDoesNotExist()
        rule.onNodeWithText("名称相似 2组").assertIsDisplayed()
        rule.onNodeWithText("完全重复 1组").assertIsDisplayed()
        rule.onNodeWithText("6份待归类").assertIsDisplayed()
        rule.onNodeWithText("开始确认").performClick()

        assertThat(relatedCalls.get()).isEqualTo(1)
    }

    @Test
    fun emptyHomeNeverInventsDocuments() {
        rule.setContent {
            WenxuTheme {
                HomeScreen(state = HomeUiState(hasAuthorizedSources = true))
            }
        }

        rule.onNodeWithText("暂无待确认").assertIsDisplayed()
        rule.onNodeWithText("文档都已归类").assertIsDisplayed()
        rule.onNodeWithText("实习报告").assertDoesNotExist()
    }

    @Test
    fun coreHomeActionsRemainVisibleAtSmallWidthAndLargeText() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                Box(Modifier.width(360.dp).height(760.dp)) {
                    HomeScreen(
                        state = HomeUiState(
                            hasAuthorizedSources = true,
                            relatedSummary = RelatedSummary(
                                nameSimilarGroups = 1,
                                exactDuplicateSets = 1,
                                previewNames = listOf("课程报告终稿.docx", "课程报告修改稿.docx"),
                            ),
                            unclassifiedCount = 3,
                            relationAnalysis = RelationAnalysisUiState(
                                visible = true,
                                phase = UiText.Plain("正在核对文档内容"),
                                checked = 12,
                                candidates = 3,
                            ),
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText("确认文档").assertIsDisplayed()
        rule.onNodeWithText("开始确认").assertIsDisplayed()
        rule.onNodeWithText("去整理").assertIsDisplayed()
        rule.onNodeWithText("正在核对文档内容").assertIsDisplayed()
    }

    @Test
    fun indexedDocumentAndBackgroundRelationStatusAreVisibleTogether() {
        val document = DocumentRecord(
            id = 1,
            uri = "content://documents/report",
            displayName = "课程报告.docx",
            extension = "docx",
            sizeBytes = 1_024,
            modifiedAt = 123L,
            sourceId = 1,
            parentUri = "content://documents",
        )
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        recentDocuments = listOf(document),
                        scan = ScanProgressUiState(
                            visible = true,
                            title = UiText.Plain("文档已找到"),
                            detail = UiText.Plain("已找到 1 份文档"),
                            status = ScanSessionStatus.RUNNING,
                        ),
                        relationAnalysis = RelationAnalysisUiState(
                            visible = true,
                            phase = UiText.Plain("正在核对重复文件"),
                            checked = 1,
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText("课程报告.docx").assertIsDisplayed()
        rule.onNodeWithText("正在核对重复文件").assertIsDisplayed()
    }

    @Test
    fun completedScanWaitsToCallOrganizationCompleteUntilRelationAnalysisFinishes() {
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        scan = ScanProgressUiState(
                            visible = true,
                            status = ScanSessionStatus.COMPLETED,
                            discoveredDocuments = 12,
                            isCompactCompletion = true,
                        ),
                        relationAnalysis = RelationAnalysisUiState(
                            visible = true,
                            phase = UiText.Plain("正在查找相似版本"),
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText("文档已找到").assertIsDisplayed()
        rule.onNodeWithText("正在查找相似版本").assertIsDisplayed()
        rule.onNodeWithText("整理完成").assertDoesNotExist()
    }

    @Test
    fun relationFailureIsNotPresentedAsScanFailure() {
        rule.setContent {
            WenxuTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasAuthorizedSources = true,
                        scan = ScanProgressUiState(
                            visible = true,
                            title = UiText.Plain("文档已更新"),
                            detail = UiText.Plain(""),
                        ),
                        relationAnalysis = RelationAnalysisUiState(
                            visible = true,
                            phase = UiText.Plain("版本核对稍后重试"),
                            failures = 1,
                            isFailure = true,
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText("版本核对稍后重试").assertIsDisplayed()
        rule.onNodeWithText("扫描失败").assertDoesNotExist()
    }

    @Test
    fun relationStatusHasEnglishResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val resources = context.createConfigurationContext(configuration).resources

        assertThat(resources.getString(com.wenxu.app.R.string.home_relation_exact_duplicates))
            .isEqualTo("Checking duplicate files")
        assertThat(resources.getString(com.wenxu.app.R.string.home_relation_retry_later))
            .isEqualTo("Version check will retry later")
    }
}
