package com.wenxu.app.feature.trash

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun nothingIsSelectedAutomatically() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeTrashRepository(listOf(item(1), item(2)))
        val viewModel = TrashViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.selectedIds).isEmpty()
    }

    @Test
    fun restoresOnlyUserSelectedItems() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeTrashRepository(listOf(item(1), item(2)))
        val viewModel = TrashViewModel(repository)
        advanceUntilIdle()

        viewModel.toggle(2)
        viewModel.restoreSelected()
        advanceUntilIdle()

        assertThat(repository.restoredIds).containsExactly(2L)
        assertThat(viewModel.state.value.selectedIds).isEmpty()
    }
}

private class FakeTrashRepository(initial: List<TrashItem>) : TrashRepository {
    private val mutableItems = MutableStateFlow(initial)
    override val items: Flow<List<TrashItem>> = mutableItems
    var restoredIds: Set<Long> = emptySet()

    override suspend fun restore(ids: Set<Long>, fallbackParentUri: String?): List<Result<Long>> {
        restoredIds = ids
        mutableItems.value = mutableItems.value.filterNot { it.id in ids }
        return ids.map { Result.success(it) }
    }

    override suspend fun deleteForever(ids: Set<Long>): List<Result<Unit>> {
        mutableItems.value = mutableItems.value.filterNot { it.id in ids }
        return ids.map { Result.success(Unit) }
    }
}

private fun item(id: Long) = TrashItem(
    id = id,
    documentId = id + 10,
    displayName = "文档$id.pdf",
    extension = "pdf",
    sizeBytes = 100,
    originalParentUri = "content://test/Download",
    deletedAt = 1,
    expiresAt = Long.MAX_VALUE,
)
