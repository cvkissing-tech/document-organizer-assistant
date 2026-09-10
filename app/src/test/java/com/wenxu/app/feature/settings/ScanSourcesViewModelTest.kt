package com.wenxu.app.feature.settings

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.storage.ScanSourceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanSourcesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun fullAccessModeHidesSharedStorageFromRemovableSources() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeScanSourceRepository(
                initial = listOf(
                    source(1, ScanSourceKind.SHARED_STORAGE),
                    source(2, ScanSourceKind.TREE),
                ),
            )
            val viewModel = ScanSourcesViewModel(
                repository = repository,
                storageAccessController = accessController(granted = true),
                preferences = FakeSourceScanPreferences(),
            )

            advanceUntilIdle()

            assertThat(viewModel.state.value.isFullAccessMode).isTrue()
            assertThat(viewModel.state.value.sources.map { it.id }).containsExactly(2L)
            assertThat(viewModel.state.value.canAddFolder).isFalse()
        }

    @Test
    fun fullPermissionCanSwitchToSelectedFoldersAndAddLocations() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeSourceScanPreferences()
            val viewModel = ScanSourcesViewModel(
                repository = FakeScanSourceRepository(),
                storageAccessController = accessController(granted = true),
                preferences = preferences,
            )

            viewModel.setScope(DocumentScanScope.SELECTED_FOLDERS)
            advanceUntilIdle()

            assertThat(viewModel.state.value.isFullAccessMode).isFalse()
            assertThat(viewModel.state.value.canAddFolder).isTrue()
            assertThat(preferences.scanScopeValue.value)
                .isEqualTo(DocumentScanScope.SELECTED_FOLDERS)
        }

    @Test
    fun limitedModeAllowsAddingFolderWithoutWaitingForScan() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeScanSourceRepository()
            var scanRequests = 0
            val viewModel = ScanSourcesViewModel(
                repository = repository,
                storageAccessController = accessController(granted = false),
                preferences = FakeSourceScanPreferences(),
                scanSelectedFolders = {
                    scanRequests++
                    Result.success(Unit)
                },
            )

            viewModel.add("content://tree/download")
            advanceUntilIdle()

            assertThat(viewModel.state.value.canAddFolder).isTrue()
            assertThat(scanRequests).isEqualTo(1)
            assertThat(viewModel.message.value)
                .isEqualTo(UiText.Resource(R.string.scan_sources_added_updating))
        }

    @Test
    fun returningFromSystemWithPermissionSwitchesToAllDocumentsAndRescans() =
        runTest(mainDispatcherRule.dispatcher) {
            var granted = false
            var scanRequests = 0
            val preferences = FakeSourceScanPreferences(DocumentScanScope.SELECTED_FOLDERS)
            val viewModel = ScanSourcesViewModel(
                repository = FakeScanSourceRepository(),
                storageAccessController = accessController { granted },
                preferences = preferences,
                scanSelectedFolders = {
                    scanRequests++
                    Result.success(Unit)
                },
            )

            granted = true
            viewModel.onStorageSettingsReturned()
            advanceUntilIdle()

            assertThat(preferences.scanScopeValue.value)
                .isEqualTo(DocumentScanScope.ALL_DOCUMENTS)
            assertThat(viewModel.state.value.isFullAccessMode).isTrue()
            assertThat(scanRequests).isEqualTo(1)
            assertThat(viewModel.message.value)
                .isEqualTo(UiText.Resource(R.string.scan_sources_all_updating))
        }

    @Test
    fun returningWithoutPermissionKeepsSelectedFoldersMode() =
        runTest(mainDispatcherRule.dispatcher) {
            var scanRequests = 0
            val preferences = FakeSourceScanPreferences(DocumentScanScope.SELECTED_FOLDERS)
            val viewModel = ScanSourcesViewModel(
                repository = FakeScanSourceRepository(),
                storageAccessController = accessController(granted = false),
                preferences = preferences,
                scanSelectedFolders = {
                    scanRequests++
                    Result.success(Unit)
                },
            )

            viewModel.onStorageSettingsReturned()
            advanceUntilIdle()

            assertThat(preferences.scanScopeValue.value)
                .isEqualTo(DocumentScanScope.SELECTED_FOLDERS)
            assertThat(viewModel.state.value.isFullAccessMode).isFalse()
            assertThat(scanRequests).isEqualTo(0)
            assertThat(viewModel.message.value)
                .isEqualTo(UiText.Resource(R.string.scan_sources_permission_denied))
        }

    @Test
    fun failedSourceRemovalUsesLocalizedMessage() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeScanSourceRepository(
            removeResult = Result.failure(IllegalStateException("请先处理回收站文档")),
        )
        val viewModel = ScanSourcesViewModel(
            repository = repository,
            storageAccessController = accessController(granted = false),
            preferences = FakeSourceScanPreferences(),
        )

        viewModel.remove(3)
        advanceUntilIdle()

        assertThat(viewModel.message.value)
            .isEqualTo(UiText.Resource(R.string.scan_sources_remove_failed))
    }
}

private class FakeSourceScanPreferences(
    initialScope: DocumentScanScope = DocumentScanScope.ALL_DOCUMENTS,
) : ScanPreferences {
    override val scanOnLaunch: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setScanOnLaunch(enabled: Boolean) = Unit
    val scanScopeValue = MutableStateFlow(initialScope)
    override val scanScope: Flow<DocumentScanScope> = scanScopeValue
    override suspend fun setScanScope(scope: DocumentScanScope) { scanScopeValue.value = scope }
}

private fun accessController(granted: Boolean) = StorageAccessController(
    sdkInt = 30,
    isExternalStorageManager = { granted },
)

private fun accessController(granted: () -> Boolean) = StorageAccessController(
    sdkInt = 30,
    isExternalStorageManager = granted,
)

private class FakeScanSourceRepository(
    private val removeResult: Result<Unit> = Result.success(Unit),
    initial: List<ScanSourceEntity> = emptyList(),
) : ScanSourceRepository {
    private val sources = MutableStateFlow(initial)

    override fun observeSources(): Flow<List<ScanSourceEntity>> = sources
    override suspend fun add(uri: String): Result<Long> {
        val id = (sources.value.maxOfOrNull { it.id } ?: 0L) + 1
        sources.value = sources.value + source(id, ScanSourceKind.TREE)
        return Result.success(id)
    }
    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(sourceId: Long): Result<Unit> = removeResult
}

private fun source(id: Long, kind: ScanSourceKind) = ScanSourceEntity(
    id = id,
    treeUri = "content://tree/$id",
    displayName = "位置$id",
    sourceKind = kind,
)
