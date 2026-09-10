package com.wenxu.app.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.index.ScanScheduler
import com.wenxu.app.core.index.ScanSessionSnapshot
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.storage.ScanSourceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun grantedAccessStartsBackgroundScanAndFinishesImmediately() =
        runTest(mainDispatcherRule.dispatcher) {
            val scheduler = FakeScanScheduler()
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(sdkInt = 30, granted = true),
                scanScheduler = scheduler,
            )

            advanceUntilIdle()

            assertThat(scheduler.startCount).isEqualTo(1)
            assertThat(viewModel.state.value.accessState).isEqualTo(StorageAccessState.Granted)
            assertThat(viewModel.state.value.finishRequested).isTrue()
        }

    @Test
    fun permissionReturnRechecksAccessThenStartsScanAndFinishes() =
        runTest(mainDispatcherRule.dispatcher) {
            var granted = false
            val scheduler = FakeScanScheduler()
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(30) { granted },
                scanScheduler = scheduler,
            )
            advanceUntilIdle()

            granted = true
            viewModel.onStorageSettingsReturned()
            advanceUntilIdle()

            assertThat(scheduler.startCount).isEqualTo(1)
            assertThat(viewModel.state.value.finishRequested).isTrue()
        }

    @Test
    fun deniedPermissionKeepsOnboardingAndOffersLimitedMode() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )
            advanceUntilIdle()

            viewModel.onStorageSettingsReturned()
            advanceUntilIdle()

            assertThat(viewModel.state.value.finishRequested).isFalse()
            assertThat(viewModel.state.value.showLimitedMode).isTrue()
            assertThat(viewModel.state.value.canRequestFullAccess).isFalse()
            assertThat(viewModel.state.value.canReturnToFullAccess).isTrue()
        }

    @Test
    fun initialAndroid11ScreenRecommendsFullAccessWithoutShowingFolderPicker() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )

            advanceUntilIdle()

            assertThat(viewModel.state.value.canRequestFullAccess).isTrue()
            assertThat(viewModel.state.value.showLimitedMode).isFalse()
            assertThat(viewModel.state.value.canReturnToFullAccess).isFalse()
        }

    @Test
    fun userCanEnterLimitedModeAndReturnToFullAccessChoice() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )
            advanceUntilIdle()

            viewModel.selectLimitedMode()
            advanceUntilIdle()
            assertThat(viewModel.state.value.showLimitedMode).isTrue()
            assertThat(viewModel.state.value.canReturnToFullAccess).isTrue()

            viewModel.selectFullAccessMode()
            advanceUntilIdle()
            assertThat(viewModel.state.value.showLimitedMode).isFalse()
            assertThat(viewModel.state.value.canRequestFullAccess).isTrue()
        }

    @Test
    fun android10UsesFolderModeWithoutRequestingFullAccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val scheduler = FakeScanScheduler()
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(),
                storageAccessController = accessController(29, granted = false),
                scanScheduler = scheduler,
            )

            advanceUntilIdle()

            assertThat(viewModel.state.value.accessState).isEqualTo(StorageAccessState.Legacy)
            assertThat(viewModel.state.value.showLimitedMode).isTrue()
            assertThat(viewModel.state.value.finishRequested).isFalse()
            assertThat(scheduler.startCount).isEqualTo(0)
        }

    @Test
    fun existingTreeSourceSkipsOnboardingWhenFullAccessIsMissing() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(
                    initialSources = listOf(source(id = 7, name = "Documents")),
                ),
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )

            advanceUntilIdle()

            assertThat(viewModel.state.value.finishRequested).isTrue()
        }

    @Test
    fun sharedStorageSourceIsNotMistakenForFolderAuthorization() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = OnboardingViewModel(
                repository = FakeSourceRepository(
                    initialSources = listOf(
                        source(id = 8, name = "手机文档").copy(
                            sourceKind = ScanSourceKind.SHARED_STORAGE,
                        ),
                    ),
                ),
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )

            advanceUntilIdle()

            assertThat(viewModel.state.value.sources).isEmpty()
            assertThat(viewModel.state.value.finishRequested).isFalse()
        }

    @Test
    fun selectedFolderIsSavedAndOnboardingFinishesWithoutWaitingForScan() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeSourceRepository()
            val viewModel = OnboardingViewModel(
                repository = repository,
                storageAccessController = accessController(30, granted = false),
                scanScheduler = FakeScanScheduler(),
            )
            advanceUntilIdle()

            viewModel.onFolderPickerResult("content://provider/tree/Download")
            advanceUntilIdle()

            assertThat(repository.sources.value.single().displayName).isEqualTo("Download")
            assertThat(viewModel.state.value.finishRequested).isTrue()
            assertThat(viewModel.state.value.isAdding).isFalse()
        }

    @Test
    fun cancelledPickerKeepsOnboardingVisible() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = OnboardingViewModel(
            repository = FakeSourceRepository(),
            storageAccessController = accessController(30, granted = false),
            scanScheduler = FakeScanScheduler(),
        )

        viewModel.onFolderPickerResult(null)
        advanceUntilIdle()

        assertThat(viewModel.state.value.finishRequested).isFalse()
    }
}

private fun accessController(
    sdkInt: Int,
    granted: Boolean,
): StorageAccessController = accessController(sdkInt) { granted }

private fun accessController(
    sdkInt: Int,
    granted: () -> Boolean,
) = StorageAccessController(
    sdkInt = sdkInt,
    isExternalStorageManager = granted,
)

private class FakeScanScheduler : ScanScheduler {
    var startCount = 0

    override suspend fun start(): Boolean {
        startCount++
        return true
    }

    override suspend fun pause(): Boolean = true
    override suspend fun resume(): Boolean = true
    override suspend fun cancel(): Boolean = true
    override fun observeSession(): Flow<ScanSessionSnapshot?> = flowOf(
        ScanSessionSnapshot(
            sessionId = "session",
            scanId = "scan",
            status = ScanSessionStatus.QUEUED,
            phase = ScanPhase.DISCOVERING,
            checkedDirectories = 0,
            checkedFiles = 0,
            discoveredDocuments = 0,
            failedFiles = 0,
            updatedAt = 0,
            errorMessage = null,
        ),
    )
}

private class FakeSourceRepository(
    private val addResult: Result<Unit> = Result.success(Unit),
    initialSources: List<ScanSourceEntity> = emptyList(),
) : ScanSourceRepository {
    val sources = MutableStateFlow(initialSources)

    override fun observeSources(): Flow<List<ScanSourceEntity>> = sources

    override suspend fun add(uri: String): Result<Long> = addResult.map {
        val source = ScanSourceEntity(
            id = (sources.value.maxOfOrNull { it.id } ?: 0L) + 1L,
            treeUri = uri,
            displayName = uri.substringAfterLast('/'),
        )
        sources.value = sources.value + source
        source.id
    }

    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = addResult

    override suspend fun remove(sourceId: Long): Result<Unit> {
        sources.value = sources.value.filterNot { it.id == sourceId }
        return Result.success(Unit)
    }
}

private fun source(id: Long, name: String) = ScanSourceEntity(
    id = id,
    treeUri = "content://provider/tree/$name",
    displayName = name,
)
