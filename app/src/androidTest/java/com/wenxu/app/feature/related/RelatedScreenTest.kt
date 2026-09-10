package com.wenxu.app.feature.related

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelatedScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun screenExplainsAndSeparatesBothRelationKinds() {
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(
                        nameGroups = listOf(
                            NameRelatedGroup(
                                id = 1,
                                baseName = "实习报告",
                                confirmedDocumentId = null,
                                documents = listOf(
                                    uiDocument(1, "实习报告最终版.docx"),
                                    uiDocument(2, "实习报告修改版.docx"),
                                ),
                            ),
                        ),
                        exactGroups = listOf(
                            ExactRelatedGroup(
                                id = 2,
                                documents = listOf(
                                    uiDocument(3, "论文.pdf"),
                                    uiDocument(4, "论文(1).pdf"),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText("确认文档").assertIsDisplayed()
        rule.onNodeWithText("名称相似").assertIsDisplayed()
        rule.onNodeWithText("完全重复").assertIsDisplayed()
        rule.onNodeWithText("应用不会自动判断最新版，也不会自动删除文件。")
            .assertDoesNotExist()
    }

    @Test
    fun exactDuplicateSectionCleansOnlyTheSelectedGroupCopies() {
        val submittedIds = AtomicReference<Set<Long>>(emptySet())
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(
                        exactGroups = listOf(
                            ExactRelatedGroup(
                                id = 2,
                                documents = listOf(
                                    uiDocument(3, "论文.pdf"),
                                    uiDocument(4, "论文(1).pdf"),
                                ),
                            ),
                        ),
                        selectedExactIds = setOf(4),
                    ),
                    onMoveSelectedToTrash = submittedIds::set,
                )
            }
        }

        rule.onNodeWithText("完全重复").performClick()
        rule.onNodeWithText("内容相同", substring = true).assertIsDisplayed()
        rule.onNodeWithText("移入回收站").performClick()

        assertThat(submittedIds.get()).containsExactly(4L)
    }

    @Test
    fun similarNamesShowFactualTimeAndSizeDifferences() {
        val latestTime = 2 * 86_400_000L
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(
                        nameGroups = listOf(
                            NameRelatedGroup(
                                id = 1,
                                baseName = "实习报告",
                                confirmedDocumentId = null,
                                documents = listOf(
                                    uiDocument(
                                        id = 1,
                                        name = "实习报告.docx",
                                        sizeBytes = 1024L * 1024L,
                                        modifiedAt = latestTime - 86_400_000L,
                                    ),
                                    uiDocument(
                                        id = 2,
                                        name = "实习报告修改版.docx",
                                        sizeBytes = 2L * 1024L * 1024L,
                                        modifiedAt = latestTime,
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText("早 1 天 · 小 1.0 MB").assertIsDisplayed()
        rule.onNodeWithText("本组最近修改 · 2.0 MB").assertIsDisplayed()
    }

    @Test
    fun duplicateCleanupDialogShowsCountSafetyAndBothActions() {
        val confirmCalls = AtomicInteger()
        val dismissCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                DuplicateCleanupDialog(
                    selectedCount = 2,
                    onConfirm = { confirmCalls.incrementAndGet() },
                    onDismiss = { dismissCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("已选择 2 份完全重复文档").assertIsDisplayed()
        rule.onNodeWithText("这些副本将移入回收站。每组至少保留一份，30 天内可以恢复。")
            .assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("移入回收站").performClick()

        assertThat(dismissCalls.get()).isEqualTo(1)
        assertThat(confirmCalls.get()).isEqualTo(1)
    }

    @Test
    fun evidenceAndCorrectionMenusStayCompactAndInvokeCallbacks() {
        val removed = AtomicReference<Pair<Long, Long>?>(null)
        val ignored = AtomicReference<Long?>(null)
        val snapshot = AtomicReference<Set<Long>>(emptySet())
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(
                        nameGroups = listOf(
                            NameRelatedGroup(
                                id = 7,
                                baseName = "实习报告",
                                confirmedDocumentId = null,
                                documents = listOf(
                                    uiDocument(1, "实习报告_最终修改版本很长的名称.docx"),
                                    uiDocument(2, "实习报告_修改版.docx"),
                                ),
                                evidence = listOf(
                                    SimilarityEvidenceUi(UiText.Resource(R.string.organization_evidence_core_name)),
                                    SimilarityEvidenceUi(UiText.Resource(R.string.organization_evidence_content)),
                                    SimilarityEvidenceUi(UiText.Resource(R.string.organization_evidence_version_marker)),
                                    SimilarityEvidenceUi(UiText.Resource(R.string.organization_evidence_same_folder)),
                                ),
                            ),
                        ),
                    ),
                    onMarkNotRelated = { groupId, documentId, expectedIds ->
                        removed.set(groupId to documentId)
                        snapshot.set(expectedIds)
                    },
                    onIgnoreGroup = { groupId, expectedIds ->
                        ignored.set(groupId)
                        snapshot.set(expectedIds)
                    },
                )
            }
        }

        rule.onNodeWithText("核心名称一致 · 内容相似 · 含版本标记", substring = true)
            .assertIsDisplayed()
        rule.onNodeWithText("来自同一文件夹").assertDoesNotExist()
        rule.onNodeWithText("查看依据").performClick()
        rule.onNodeWithText("来自同一文件夹", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("版本组更多操作").performClick()
        rule.onNodeWithText("忽略此组").performClick()
        assertThat(ignored.get()).isEqualTo(7L)
        assertThat(snapshot.get()).containsExactly(1L, 2L)

        rule.onNodeWithContentDescription("实习报告_最终修改版本很长的名称.docx的更多操作")
            .performClick()
        rule.onNodeWithText("不是同一文档").performClick()
        assertThat(removed.get()).isEqualTo(7L to 1L)
        assertThat(snapshot.get()).containsExactly(1L, 2L)
    }

    @Test
    fun longNamesKeepActionsReachableAtThreeSixtyDpAndLargeText() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                WenxuTheme {
                    Box(Modifier.width(360.dp)) {
                        RelatedScreen(
                            state = RelatedUiState(
                                nameGroups = listOf(
                                    NameRelatedGroup(
                                        id = 8,
                                        baseName = "毕业实习项目阶段总结报告",
                                        confirmedDocumentId = null,
                                        documents = listOf(
                                            uiDocument(1, "毕业实习项目阶段总结报告_最终修改版本_指导老师批注.docx"),
                                            uiDocument(2, "毕业实习项目阶段总结报告_修改版.docx"),
                                        ),
                                        evidence = listOf(
                                            SimilarityEvidenceUi(
                                                UiText.Resource(R.string.organization_evidence_core_name),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        rule.onNodeWithContentDescription("版本组更多操作").assertIsDisplayed()
        rule.onNodeWithContentDescription(
            "毕业实习项目阶段总结报告_最终修改版本_指导老师批注.docx的更多操作",
        ).assertIsDisplayed()
    }

    @Test
    fun feedbackActionsHaveEnglishResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val english = context.createConfigurationContext(configuration)

        assertThat(english.getString(R.string.organization_not_same_document))
            .isEqualTo("Not the same document")
        assertThat(english.getString(R.string.organization_ignore_group))
            .isEqualTo("Ignore this group")
        assertThat(english.getString(R.string.organization_merge_as_versions))
            .isEqualTo("Merge as versions")
    }

    @Test
    fun targetedMergeShowsWaitingInsteadOfGenericEmptyState() {
        val retryCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(targetDocumentIds = setOf(1, 2)),
                    onRetryTarget = { retryCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("正在核对这些文档").assertIsDisplayed()
        rule.onNodeWithText("没有名称相似的文档").assertDoesNotExist()
        rule.onNodeWithText("重试").performClick()
        assertThat(retryCalls.get()).isEqualTo(1)
    }

    @Test
    fun resolvedTargetScrollsToItsActualGroupHeader() {
        val groups = (1L..8L).map { groupId ->
            NameRelatedGroup(
                id = groupId,
                baseName = "版本组$groupId",
                confirmedDocumentId = null,
                documents = listOf(
                    uiDocument(groupId * 10, "报告${groupId}A.pdf"),
                    uiDocument(groupId * 10 + 1, "报告${groupId}B.pdf"),
                ),
            )
        }
        val targetState = mutableStateOf(
            RelatedUiState(
                nameGroups = groups,
                targetDocumentIds = setOf(80L, 81L),
                targetLifecycle = TargetLifecycle.Pending,
            ),
        )
        rule.setContent {
            WenxuTheme { RelatedScreen(state = targetState.value) }
        }

        rule.runOnIdle {
            targetState.value = targetState.value.copy(
                targetGroupId = 8L,
                targetLifecycle = TargetLifecycle.Resolved,
            )
        }

        rule.waitUntil(timeoutMillis = 3_000) {
            runCatching {
                rule.onNodeWithText("版本组8").assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test
    fun missingTargetShowsTerminalStateWithBackAndRetry() {
        val backCalls = AtomicInteger()
        val retryCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                RelatedScreen(
                    state = RelatedUiState(
                        targetDocumentIds = setOf(1L, 2L),
                        targetLifecycle = TargetLifecycle.NotFound,
                    ),
                    onBack = { backCalls.incrementAndGet() },
                    onRetryTarget = { retryCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("未找到对应版本组").assertIsDisplayed()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("重试").performClick()
        assertThat(backCalls.get()).isEqualTo(1)
        assertThat(retryCalls.get()).isEqualTo(1)
    }

    private fun uiDocument(
        id: Long,
        name: String,
        sizeBytes: Long = 1024,
        modifiedAt: Long = id,
    ) = DocumentRecord(
        id = id,
        uri = "content://documents/$id",
        displayName = name,
        extension = name.substringAfterLast('.', "pdf"),
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        sourceId = 1,
        parentUri = "content://documents",
    )
}
