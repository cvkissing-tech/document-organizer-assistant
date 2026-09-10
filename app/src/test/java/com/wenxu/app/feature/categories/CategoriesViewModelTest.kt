package com.wenxu.app.feature.categories

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.R
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun categoryListShowsCountsFromRepository() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeCategoriesRepository(
            CategoriesData(
                categories = listOf(CategorySummary(4, "课程资料", 2)),
                unclassifiedCount = 3,
            ),
        )
        val viewModel = CategoriesViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.categories.single().documentCount).isEqualTo(2)
        assertThat(viewModel.state.value.unclassifiedCount).isEqualTo(3)
    }

    @Test
    fun unclassifiedDetailUsesClearTitle() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeCategoriesRepository(CategoriesData())
        val viewModel = CategoryDetailViewModel(repository, categoryId = null)
        advanceUntilIdle()

        assertThat(viewModel.state.value.title)
            .isEqualTo(UiText.Resource(R.string.organization_unclassified_documents))
        assertThat(viewModel.state.value.isUnclassified).isTrue()
    }

    @Test
    fun removingDocumentOnlyUnlinksSelectedCategory() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeCategoriesRepository(CategoriesData())
        val viewModel = CategoryDetailViewModel(repository, categoryId = 4)

        viewModel.removeDocument(documentId = 9)
        advanceUntilIdle()

        assertThat(repository.removed).isEqualTo(9L to 4L)
    }

    @Test
    fun unclassifiedDocumentCanBeAssignedDirectlyToChosenCategory() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeCategoriesRepository(
                CategoriesData(categories = listOf(CategorySummary(4, "课程资料", 0))),
            )
            val viewModel = CategoryDetailViewModel(repository, categoryId = null)

            viewModel.addDocumentToCategory(documentId = 9, targetCategoryId = 4)
            advanceUntilIdle()

            assertThat(repository.added).containsExactly(9L to 4L)
        }

    @Test
    fun unclassifiedDocumentsCanBeSelectedAndClassifiedTogether() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeCategoriesRepository(
                initial = CategoriesData(categories = listOf(CategorySummary(4, "课程资料", 0))),
                initialDocuments = listOf(
                    document(8, "讲义.pdf"),
                    document(9, "作业.docx"),
                ),
            )
            val viewModel = CategoryDetailViewModel(repository, categoryId = null)
            advanceUntilIdle()

            viewModel.startSelection()
            viewModel.toggleSelection(8)
            viewModel.toggleSelection(9)
            viewModel.addSelectedToCategory(4)
            advanceUntilIdle()

            assertThat(repository.batchAdded).isEqualTo(setOf(8L, 9L) to 4L)
            assertThat(viewModel.state.value.selectionMode).isFalse()
            assertThat(viewModel.state.value.selectedDocumentIds).isEmpty()
        }

    @Test
    fun categoryDetailCanAddSeveralExistingDocuments() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeCategoriesRepository(CategoriesData())
        val viewModel = CategoryDetailViewModel(repository, categoryId = 4)

        viewModel.addDocuments(setOf(8, 9))
        advanceUntilIdle()

        assertThat(repository.added).containsExactly(8L to 4L, 9L to 4L)
    }

    @Test
    fun creatingCategoryFromTriageImmediatelyAddsDocument() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeCategoriesRepository(CategoriesData(), createdId = 7)
            val viewModel = CategoryDetailViewModel(repository, categoryId = null)

            viewModel.createCategoryAndAdd("考试资料", documentId = 9)
            advanceUntilIdle()

            assertThat(repository.createdNames).containsExactly("考试资料")
            assertThat(repository.added).containsExactly(9L to 7L)
        }
}

private class FakeCategoriesRepository(
    initial: CategoriesData,
    private val createdId: Long = 1,
    initialDocuments: List<DocumentRecord> = emptyList(),
) : CategoriesRepository {
    private val mutableData = MutableStateFlow(initial)
    override val data: Flow<CategoriesData> = mutableData
    private val documents = MutableStateFlow(initialDocuments)
    override fun observeDocuments(categoryId: Long?): Flow<List<DocumentRecord>> = documents
    override fun observeAllDocuments(): Flow<List<DocumentRecord>> = MutableStateFlow(emptyList())
    val createdNames = mutableListOf<String>()
    override suspend fun create(name: String): Result<Long> {
        createdNames += name
        return Result.success(createdId)
    }
    override suspend fun rename(categoryId: Long, name: String): Result<Unit> = Result.success(Unit)
    override suspend fun delete(categoryId: Long): Result<Unit> = Result.success(Unit)
    override suspend fun markOpened(documentId: Long) = Unit
    val added = mutableListOf<Pair<Long, Long>>()
    override suspend fun addToCategory(documentId: Long, categoryId: Long) {
        added += documentId to categoryId
    }
    var batchAdded: Pair<Set<Long>, Long>? = null
    override suspend fun addDocumentsToCategory(documentIds: Set<Long>, categoryId: Long): Int {
        batchAdded = documentIds to categoryId
        return documentIds.size
    }
    var removed: Pair<Long, Long>? = null
    override suspend fun removeFromCategory(documentId: Long, categoryId: Long) {
        removed = documentId to categoryId
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
