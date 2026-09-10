package com.wenxu.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.feature.categories.CategoryDetailScreen
import com.wenxu.app.feature.categories.CategoryDetailUiState
import com.wenxu.app.feature.documents.DocumentsScreen
import com.wenxu.app.feature.documents.DocumentsUiState
import com.wenxu.app.feature.home.HomeScanScope
import com.wenxu.app.feature.home.HomeScreen
import com.wenxu.app.feature.home.HomeUiState
import com.wenxu.app.feature.home.RelatedSummary
import com.wenxu.app.feature.home.ScanProgressAction
import com.wenxu.app.feature.home.ScanProgressUiState
import com.wenxu.app.feature.related.ExactRelatedGroup
import com.wenxu.app.feature.related.NameRelatedGroup
import com.wenxu.app.feature.related.RelatedScreen
import com.wenxu.app.feature.related.RelatedSection
import com.wenxu.app.feature.related.RelatedUiState
import com.wenxu.app.feature.trash.TrashItem
import com.wenxu.app.feature.trash.TrashScreen
import com.wenxu.app.feature.trash.TrashUiState
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.TimeUnit

@Preview(name = "首页-有待确认", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun HomeWithRelationsPreview() {
    WenxuTheme {
        HomeScreen(
            state = HomeUiState(
                hasAuthorizedSources = true,
                sourceCount = 3,
                relatedSummary = RelatedSummary(
                    nameSimilarGroups = 2,
                    exactDuplicateSets = 1,
                    previewNames = listOf("课程设计方案终稿.docx", "课程设计方案修改稿.docx"),
                ),
                unclassifiedCount = 6,
                recentDocuments = listOf(
                    previewDocument(1, "高等数学复习提纲.pdf", "pdf", modifiedAt = 1_777_228_800_000),
                    previewDocument(2, "小组展示分工表.xlsx", "xlsx", modifiedAt = 1_777_142_400_000),
                ),
                scan = ScanProgressUiState(
                    visible = true,
                    title = UiText.Plain("文档已更新"),
                    detail = UiText.Plain("刚刚完成 · 共收录 186 份文档"),
                    scope = HomeScanScope.ALL_DOCUMENTS,
                    status = ScanSessionStatus.COMPLETED,
                    phase = ScanPhase.COMPLETE,
                    discoveredDocuments = 186,
                    primaryAction = ScanProgressAction.RESTART,
                    isCompactCompletion = true,
                ),
            ),
        )
    }
}

@Preview(
    name = "首页-全部完成-大字体",
    widthDp = 360,
    heightDp = 760,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
private fun HomeEmptyLargeTextPreview() {
    WenxuTheme { HomeScreen(state = HomeUiState(hasAuthorizedSources = true)) }
}

@Preview(name = "确认文档-名称相似", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RelatedNameGroupsPreview() {
    WenxuTheme {
        RelatedScreen(
            state = RelatedUiState(
                nameGroups = listOf(
                    NameRelatedGroup(
                        id = 1,
                        baseName = "课程设计方案",
                        confirmedDocumentId = 1,
                        documents = listOf(
                            previewDocument(1, "课程设计方案终稿.docx", "docx", modifiedAt = 1_777_228_800_000),
                            previewDocument(2, "课程设计方案修改稿.docx", "docx", modifiedAt = 1_777_142_400_000),
                        ),
                    ),
                    NameRelatedGroup(
                        id = 2,
                        baseName = "英语演讲材料",
                        confirmedDocumentId = null,
                        documents = listOf(
                            previewDocument(3, "英语演讲材料.pptx", "pptx", modifiedAt = 1_776_969_600_000),
                            previewDocument(4, "英语演讲材料副本.pptx", "pptx", modifiedAt = 1_776_883_200_000),
                        ),
                    ),
                ),
            ),
        )
    }
}

@Preview(name = "确认文档-完全重复", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RelatedExactGroupsPreview() {
    val first = previewDocument(11, "实验数据汇总.xlsx", "xlsx", parent = "Download")
    val second = previewDocument(12, "实验数据汇总-副本.xlsx", "xlsx", parent = "Tencent/QQfile_recv")
    WenxuTheme {
        RelatedScreen(
            state = RelatedUiState(
                exactGroups = listOf(ExactRelatedGroup(8, listOf(first, second))),
                selectedExactIds = setOf(second.id),
            ),
            initialSection = RelatedSection.EXACT_DUPLICATE,
        )
    }
}

@Preview(name = "分类详情-空分类", widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun EmptyCategoryPreview() {
    WenxuTheme {
        CategoryDetailScreen(
            state = CategoryDetailUiState(
                title = UiText.Plain("本学期资料"),
                documents = emptyList(),
                availableDocuments = listOf(previewDocument(21, "离散数学作业.pdf", "pdf")),
            ),
        )
    }
}

@Preview(name = "首页-扫描部分异常", widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun PartialScanPreview() {
    WenxuTheme {
        HomeScreen(
            state = HomeUiState(
                hasAuthorizedSources = true,
                sourceCount = 4,
                relatedSummary = RelatedSummary(nameSimilarGroups = 1),
                unclassifiedCount = 12,
                scan = ScanProgressUiState(
                    visible = true,
                    title = UiText.Plain("正在收录文档"),
                    detail = UiText.Plain("已检查 24 个文件夹 · 1,286 个文件 · 找到 83 份文档"),
                    scope = HomeScanScope.ALL_DOCUMENTS,
                    status = ScanSessionStatus.RUNNING,
                    phase = ScanPhase.INDEXING,
                    checkedDirectories = 24,
                    checkedFiles = 1_286,
                    discoveredDocuments = 83,
                    failedFiles = 3,
                    primaryAction = ScanProgressAction.PAUSE,
                    canCancel = true,
                ),
            ),
        )
    }
}

@Preview(name = "回收站-批量选择", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun TrashSelectionPreview() {
    val now = System.currentTimeMillis()
    val items = listOf(
        previewTrashItem(31, "重复的课程表.pdf", "pdf", now, 26),
        previewTrashItem(32, "演讲材料副本.pptx", "pptx", now, 18),
        previewTrashItem(33, "旧版数据记录.xlsx", "xlsx", now, 7),
    )
    WenxuTheme {
        TrashScreen(
            state = TrashUiState(
                items = items,
                selectedIds = setOf(items[0].id, items[1].id),
            ),
        )
    }
}

@Preview(
    name = "文档-超长文件名-大字体",
    widthDp = 360,
    heightDp = 760,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
private fun LongFileNamePreview() {
    val documents = listOf(
        previewDocument(
            id = 41,
            name = "关于本学期跨专业联合课程项目展示与最终提交要求的补充说明文档.docx",
            extension = "docx",
            sizeBytes = 4_362_240,
        ),
        previewDocument(42, "概率论知识点整理.pdf", "pdf"),
    )
    WenxuTheme {
        DocumentsScreen(
            state = DocumentsUiState(
                documents = documents,
                totalCount = documents.size,
            ),
        )
    }
}

private fun previewDocument(
    id: Long,
    name: String,
    extension: String,
    sizeBytes: Long = 1_572_864,
    modifiedAt: Long = 1_777_228_800_000,
    parent: String = "Download",
): DocumentRecord = DocumentRecord(
    id = id,
    uri = "content://preview/$parent/$name",
    displayName = name,
    extension = extension,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
    sourceId = 1,
    parentUri = "content://preview/$parent",
)

private fun previewTrashItem(
    id: Long,
    name: String,
    extension: String,
    now: Long,
    remainingDays: Long,
): TrashItem = TrashItem(
    id = id,
    documentId = id,
    displayName = name,
    extension = extension,
    sizeBytes = 1_572_864,
    originalParentUri = "content://preview/Download",
    deletedAt = now - TimeUnit.DAYS.toMillis(2),
    expiresAt = now + TimeUnit.DAYS.toMillis(remainingDays),
)
