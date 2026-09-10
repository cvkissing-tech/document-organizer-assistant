package com.wenxu.app.feature.documents

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.R
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.relations.FeedbackResult
import com.wenxu.app.core.relations.SimilarityFeedbackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun everyFilterAndSortHasAStringResource() {
        assertThat(FormatFilter.entries.map { it.labelRes }).doesNotContain(0)
        assertThat(OrganizationFilter.entries.map { it.labelRes }).doesNotContain(0)
        assertThat(DocumentSort.entries.map { it.labelRes }).doesNotContain(0)
        assertThat(OrganizationFilter.CLASSIFIED.labelRes)
            .isEqualTo(R.string.organization_filter_classified)
    }

    @Test
    fun filtersByOfficeFamilyAndOrganizationState() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeDocumentsRepository(
            DocumentsData(
                documents = listOf(
                    document(1, "课程报告.docx", "docx"),
                    document(2, "课程数据.xlsx", "xlsx"),
                    document(3, "课程总结.doc", "doc"),
                ),
                categorizedIds = setOf(1),
                relatedIds = setOf(3),
            ),
        )
        val viewModel = DocumentsViewModel(repository)
        advanceUntilIdle()

        viewModel.setQuery("课程")
        viewModel.setFormat(FormatFilter.DOC)
        viewModel.setOrganization(OrganizationFilter.UNCLASSIFIED)
        advanceUntilIdle()

        assertThat(viewModel.state.value.documents.map { it.id }).containsExactly(3L)
    }

    @Test
    fun classifiedFilterShowsEveryDocumentThatBelongsToAnyCategory() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeDocumentsRepository(
                DocumentsData(
                    documents = listOf(
                        document(1, "已分类.pdf", "pdf"),
                        document(2, "未分类.pdf", "pdf"),
                    ),
                    categorizedIds = setOf(1),
                ),
            )
            val viewModel = DocumentsViewModel(repository)
            advanceUntilIdle()

            viewModel.setOrganization(OrganizationFilter.CLASSIFIED)
            advanceUntilIdle()

            assertThat(viewModel.state.value.documents.map { it.id }).containsExactly(1L)
        }

    @Test
    fun addingCategoryDelegatesExactDocumentAndCategory() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeDocumentsRepository(DocumentsData())
        val viewModel = DocumentsViewModel(repository)

        viewModel.addToCategory(documentId = 8, categoryId = 3)
        advanceUntilIdle()

        assertThat(repository.lastAdded).isEqualTo(8L to 3L)
    }

    @Test
    fun sortByNameChangesVisibleOrder() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeDocumentsRepository(
            DocumentsData(
                documents = listOf(
                    document(1, "B.pdf", "pdf"),
                    document(2, "A.pdf", "pdf"),
                ),
            ),
        )
        val viewModel = DocumentsViewModel(repository)
        advanceUntilIdle()

        viewModel.setSort(DocumentSort.NAME)
        advanceUntilIdle()

        assertThat(viewModel.state.value.documents.map { it.displayName })
            .containsExactly("A.pdf", "B.pdf")
            .inOrder()
    }

    @Test
    fun selectingACategoryShowsOnlyDocumentsInThatCategory() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeDocumentsRepository(
            DocumentsData(
                documents = listOf(
                    document(1, "课程报告.pdf", "pdf"),
                    document(2, "实习周报.docx", "docx"),
                ),
                categoryIdsByDocument = mapOf(
                    1L to setOf(10L),
                    2L to setOf(20L),
                ),
                categories = listOf(
                    CategorySummary(10, "课程资料", 1),
                    CategorySummary(20, "实习资料", 1),
                ),
            ),
        )
        val viewModel = DocumentsViewModel(repository)
        advanceUntilIdle()

        viewModel.setCategoryFilter(10L)
        advanceUntilIdle()

        assertThat(viewModel.state.value.documents.map { it.id }).containsExactly(1L)
        assertThat(viewModel.state.value.selectedCategoryId).isEqualTo(10L)
    }

    @Test
    fun selectedDocumentsAreAddedToOneCategoryTogether() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeDocumentsRepository(
            DocumentsData(
                documents = listOf(
                    document(1, "课程报告.pdf", "pdf"),
                    document(2, "课程讲义.pdf", "pdf"),
                ),
            ),
        )
        val viewModel = DocumentsViewModel(repository)
        advanceUntilIdle()

        viewModel.startSelection()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.addSelectedToCategory(10L)
        advanceUntilIdle()

        assertThat(repository.lastBatchAdded).isEqualTo(setOf(1L, 2L) to 10L)
        assertThat(viewModel.state.value.selectionMode).isFalse()
        assertThat(viewModel.state.value.selectedDocumentIds).isEmpty()
    }

    @Test
    fun selectedDocumentsCanBeMergedAsVersionsAndOpenReview() =
        runTest(mainDispatcherRule.dispatcher) {
            val feedback = FakeDocumentsFeedbackController()
            val repository = FakeDocumentsRepository(
                DocumentsData(
                    documents = listOf(
                        document(1, "报告.docx", "docx"),
                        document(2, "报告修改版.docx", "docx"),
                    ),
                ),
            )
            val viewModel = DocumentsViewModel(repository, feedbackController = feedback)
            advanceUntilIdle()

            viewModel.startSelection()
            viewModel.toggleSelection(1)
            viewModel.toggleSelection(2)
            viewModel.mergeSelectedAsVersions()
            advanceUntilIdle()

            assertThat(feedback.mergedIds).containsExactly(1L, 2L)
            assertThat(viewModel.action.value?.targetRelatedIds).containsExactly(1L, 2L)
            assertThat(viewModel.state.value.selectionMode).isFalse()
        }

    @Test
    fun schedulerFailureKeepsTargetForRetryWithoutNavigation() =
        runTest(mainDispatcherRule.dispatcher) {
            val feedback = FakeDocumentsFeedbackController(queueFails = true)
            val repository = FakeDocumentsRepository(
                DocumentsData(
                    documents = listOf(
                        document(1, "报告.docx", "docx"),
                        document(2, "报告修改版.docx", "docx"),
                    ),
                ),
            )
            val viewModel = DocumentsViewModel(repository, feedbackController = feedback)
            advanceUntilIdle()
            viewModel.startSelection()
            viewModel.toggleSelection(1)
            viewModel.toggleSelection(2)

            viewModel.mergeSelectedAsVersions()
            advanceUntilIdle()

            assertThat(viewModel.action.value?.targetRelatedIds).isEmpty()
            assertThat(viewModel.action.value?.retryAnalysisIds).containsExactly(1L, 2L)
        }
}

private class FakeDocumentsRepository(initial: DocumentsData) : DocumentsRepository {
    private val mutableData = MutableStateFlow(initial)
    override val data: Flow<DocumentsData> = mutableData
    var lastAdded: Pair<Long, Long>? = null
    var lastBatchAdded: Pair<Set<Long>, Long>? = null

    override suspend fun markOpened(documentId: Long) = Unit

    override suspend fun addToCategory(documentId: Long, categoryId: Long) {
        lastAdded = documentId to categoryId
    }

    override suspend fun addDocumentsToCategory(documentIds: Set<Long>, categoryId: Long): Int {
        lastBatchAdded = documentIds to categoryId
        return documentIds.size
    }
}

private fun document(id: Long, name: String, extension: String) = DocumentRecord(
    id = id,
    uri = "content://documents/$id",
    displayName = name,
    extension = extension,
    sizeBytes = 100,
    modifiedAt = 1,
    sourceId = 1,
    parentUri = "content://documents",
)

private class FakeDocumentsFeedbackController(
    private val queueFails: Boolean = false,
) : SimilarityFeedbackController {
    var mergedIds: Set<Long> = emptySet()

    override suspend fun markNotRelated(
        groupId: Long,
        documentId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult =
        FeedbackResult.GroupUnavailable

    override suspend fun ignoreGroup(
        groupId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult = FeedbackResult.GroupUnavailable

    override suspend fun mergeAsVersions(documentIds: Set<Long>): FeedbackResult {
        mergedIds = documentIds
        return if (queueFails) {
            FeedbackResult.AnalysisQueueFailed(documentIds)
        } else {
            FeedbackResult.SavedPendingAnalysis(documentIds)
        }
    }

    override suspend fun retryAnalysis(documentIds: Set<Long>): FeedbackResult =
        FeedbackResult.SavedPendingAnalysis(documentIds)
}
