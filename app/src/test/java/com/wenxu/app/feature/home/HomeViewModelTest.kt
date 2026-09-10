package com.wenxu.app.feature.home

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.index.ScanScheduler
import com.wenxu.app.core.index.ScanSessionSnapshot
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.permission.StorageAccessController
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
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun homeSeparatesSimilarAndExactCounts() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(
            HomeData(hasAuthorizedSources = true, similarGroups = 2, exactSets = 1),
        )
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.relatedSummary)
            .isEqualTo(RelatedSummary(nameSimilarGroups = 2, exactDuplicateSets = 1))
    }

    @Test
    fun noRelationsExposeNoPreviewNames() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = HomeViewModel(FakeHomeRepository(HomeData()))
        advanceUntilIdle()
        assertThat(viewModel.state.value.relatedSummary.totalGroups).isEqualTo(0)
        assertThat(viewModel.state.value.relatedSummary.previewNames).isEmpty()
    }

    @Test
    fun relationPreviewUsesRepositoryNames() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = HomeViewModel(
            FakeHomeRepository(
                HomeData(
                    similarGroups = 1,
                    relationPreviewNames = listOf("课程报告修改版.docx"),
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.relatedSummary.previewNames)
            .containsExactly("课程报告修改版.docx")
    }

    @Test
    fun relationAnalysisDoesNotHideIndexedDocuments() = runTest(mainDispatcherRule.dispatcher) {
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
        val repository = FakeHomeRepository(
            HomeData(
                hasAuthorizedSources = true,
                recentDocuments = listOf(document),
                relationAnalysis = RelationAnalysisData(
                    status = RelationAnalysisStatus.RUNNING,
                    phase = RelationAnalysisPhase.CONTENT_VERIFICATION,
                    checked = 9,
                    candidates = 3,
                    failures = 1,
                ),
            ),
        )
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.recentDocuments).containsExactly(document)
        assertThat(viewModel.state.value.relationAnalysis.visible).isTrue()
        assertThat(viewModel.state.value.relationAnalysis.phase)
            .isEqualTo(UiText.Resource(R.string.home_relation_content_verification))
        assertThat(viewModel.state.value.relationAnalysis.checked).isEqualTo(9)
        assertThat(viewModel.state.value.relationAnalysis.candidates).isEqualTo(3)
        assertThat(viewModel.state.value.relationAnalysis.failures).isEqualTo(1)
    }

    @Test
    fun relationAnalysisMapsStagesFailureAndHiddenStates() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeHomeRepository(HomeData(hasAuthorizedSources = true))
            val viewModel = HomeViewModel(repository)

            val expectedStages = listOf(
                RelationAnalysisPhase.EXACT_DUPLICATES to R.string.home_relation_exact_duplicates,
                RelationAnalysisPhase.NAME_CANDIDATES to R.string.home_relation_name_candidates,
                RelationAnalysisPhase.CONTENT_VERIFICATION to
                    R.string.home_relation_content_verification,
                RelationAnalysisPhase.SAVING to R.string.home_relation_saving,
            )
            expectedStages.forEach { (phase, stringId) ->
                repository.emit(
                    HomeData(
                        hasAuthorizedSources = true,
                        relationAnalysis = RelationAnalysisData(
                            status = RelationAnalysisStatus.RUNNING,
                            phase = phase,
                        ),
                    ),
                )
                advanceUntilIdle()
                assertThat(viewModel.state.value.relationAnalysis.visible).isTrue()
                assertThat(viewModel.state.value.relationAnalysis.phase)
                    .isEqualTo(UiText.Resource(stringId))
            }

            repository.emit(
                HomeData(
                    hasAuthorizedSources = true,
                    relationAnalysis = RelationAnalysisData(
                        status = RelationAnalysisStatus.QUEUED,
                        phase = RelationAnalysisPhase.EXACT_DUPLICATES,
                    ),
                ),
            )
            advanceUntilIdle()
            assertThat(viewModel.state.value.relationAnalysis.visible).isTrue()
            assertThat(viewModel.state.value.relationAnalysis.phase)
                .isEqualTo(UiText.Resource(R.string.home_relation_exact_duplicates))

            repository.emit(
                HomeData(
                    hasAuthorizedSources = true,
                    relationAnalysis = RelationAnalysisData(
                        status = RelationAnalysisStatus.FAILED,
                        phase = RelationAnalysisPhase.CONTENT_VERIFICATION,
                        failures = 2,
                    ),
                ),
            )
            advanceUntilIdle()
            assertThat(viewModel.state.value.relationAnalysis.visible).isTrue()
            assertThat(viewModel.state.value.relationAnalysis.phase)
                .isEqualTo(UiText.Resource(R.string.home_relation_retry_later))
            assertThat(viewModel.state.value.relationAnalysis.isFailure).isTrue()

            listOf(null, RelationAnalysisStatus.COMPLETED).forEach { status ->
                repository.emit(
                    HomeData(
                        hasAuthorizedSources = true,
                        relationAnalysis = status?.let {
                            RelationAnalysisData(
                                status = it,
                                phase = RelationAnalysisPhase.SAVING,
                            )
                        },
                    ),
                )
                advanceUntilIdle()
                assertThat(viewModel.state.value.relationAnalysis.visible).isFalse()
            }
        }

    @Test
    fun completedScanDoesNotShowOrganizationCompleteBeforeNewRelationRunIsQueued() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeHomeRepository(
                HomeData(
                    hasAuthorizedSources = true,
                    relationAnalysis = RelationAnalysisData(
                        status = RelationAnalysisStatus.COMPLETED,
                        phase = RelationAnalysisPhase.SAVING,
                        updatedAt = 1_000L,
                    ),
                ),
            )
            val scheduler = FakeScanScheduler()
            val viewModel = HomeViewModel(
                repository = repository,
                scanPreferences = FakeHomeScanPreferences(false),
                scanScheduler = scheduler,
                storageAccessController = grantedAccess(),
            )
            scheduler.session.value = snapshot(
                status = ScanSessionStatus.COMPLETED,
                phase = ScanPhase.COMPLETE,
                updatedAt = 2_000L,
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.relationAnalysis.visible).isTrue()
            assertThat(viewModel.state.value.relationAnalysis.phase)
                .isEqualTo(UiText.Resource(R.string.home_relation_exact_duplicates))

            repository.emit(
                HomeData(
                    hasAuthorizedSources = true,
                    relationAnalysis = RelationAnalysisData(
                        status = RelationAnalysisStatus.COMPLETED,
                        phase = RelationAnalysisPhase.SAVING,
                        updatedAt = 3_000L,
                    ),
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.relationAnalysis.visible).isFalse()
        }

    @Test
    fun grantedLaunchStartsOnlyProgressiveScheduler() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val scheduler = FakeScanScheduler()
        HomeViewModel(
            repository = repository,
            sourceRepository = FakeHomeScanSourceRepository(listOf(treeSource(null))),
            scanPreferences = FakeHomeScanPreferences(true),
            scanScheduler = scheduler,
            storageAccessController = grantedAccess(),
        )
        advanceUntilIdle()

        assertThat(scheduler.startCount).isEqualTo(1)
        assertThat(repository.rescanCount).isEqualTo(0)
    }

    @Test
    fun grantedPermissionWithSelectedFolderScopeUsesLegacyScanner() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeHomeRepository(HomeData(hasAuthorizedSources = true))
            val scheduler = FakeScanScheduler()
            HomeViewModel(
                repository = repository,
                sourceRepository = FakeHomeScanSourceRepository(listOf(treeSource(null))),
                scanPreferences = FakeHomeScanPreferences(
                    enabled = true,
                    scope = DocumentScanScope.SELECTED_FOLDERS,
                ),
                scanScheduler = scheduler,
                storageAccessController = grantedAccess(),
            )
            advanceUntilIdle()

            assertThat(repository.appliedScopes).contains(DocumentScanScope.SELECTED_FOLDERS)
            assertThat(repository.rescanCount).isEqualTo(1)
            assertThat(scheduler.startCount).isEqualTo(0)
        }

    @Test
    fun restrictedLaunchStartsOnlyLegacyFolderScan() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val scheduler = FakeScanScheduler()
        HomeViewModel(
            repository = repository,
            sourceRepository = FakeHomeScanSourceRepository(listOf(treeSource(null))),
            scanPreferences = FakeHomeScanPreferences(true),
            scanScheduler = scheduler,
            storageAccessController = restrictedAccess(),
        )
        advanceUntilIdle()

        assertThat(repository.rescanCount).isEqualTo(1)
        assertThat(scheduler.startCount).isEqualTo(0)
    }

    @Test
    fun disabledLaunchScanPreferenceDoesNotStartEitherScanner() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeHomeRepository(HomeData())
            val scheduler = FakeScanScheduler()
            HomeViewModel(
                repository = repository,
                sourceRepository = FakeHomeScanSourceRepository(listOf(treeSource(null))),
                scanPreferences = FakeHomeScanPreferences(false),
                scanScheduler = scheduler,
                storageAccessController = grantedAccess(),
            )
            advanceUntilIdle()

            assertThat(repository.rescanCount).isEqualTo(0)
            assertThat(scheduler.startCount).isEqualTo(0)
        }

    @Test
    fun grantedManualScanUsesOnlyProgressiveScheduler() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val scheduler = FakeScanScheduler()
        val viewModel = HomeViewModel(
            repository = repository,
            scanPreferences = FakeHomeScanPreferences(false),
            scanScheduler = scheduler,
            storageAccessController = grantedAccess(),
        )
        advanceUntilIdle()

        viewModel.rescan()
        advanceUntilIdle()

        assertThat(scheduler.startCount).isEqualTo(1)
        assertThat(repository.rescanCount).isEqualTo(0)
    }

    @Test
    fun restrictedManualScanUsesOnlyLegacyRepository() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val scheduler = FakeScanScheduler()
        val viewModel = HomeViewModel(
            repository = repository,
            scanPreferences = FakeHomeScanPreferences(false),
            scanScheduler = scheduler,
            storageAccessController = restrictedAccess(),
        )
        advanceUntilIdle()

        viewModel.rescan()
        advanceUntilIdle()

        assertThat(repository.rescanCount).isEqualTo(1)
        assertThat(scheduler.startCount).isEqualTo(0)
    }

    @Test
    fun folderSelectionIsIgnoredWhenFullAccessIsGranted() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val sources = FakeHomeScanSourceRepository(emptyList())
        val viewModel = HomeViewModel(
            repository = repository,
            sourceRepository = sources,
            scanPreferences = FakeHomeScanPreferences(false),
            scanScheduler = FakeScanScheduler(),
            storageAccessController = grantedAccess(),
        )
        advanceUntilIdle()

        viewModel.onFolderSelected("content://tree/download")
        advanceUntilIdle()

        assertThat(sources.addCount).isEqualTo(0)
        assertThat(repository.rescanCount).isEqualTo(0)
    }

    @Test
    fun folderSelectionAddsAndScansOnlyInRestrictedMode() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeHomeRepository(HomeData())
        val sources = FakeHomeScanSourceRepository(emptyList())
        val viewModel = HomeViewModel(
            repository = repository,
            sourceRepository = sources,
            scanPreferences = FakeHomeScanPreferences(false),
            scanScheduler = FakeScanScheduler(),
            storageAccessController = restrictedAccess(),
        )
        advanceUntilIdle()

        viewModel.onFolderSelected("content://tree/download")
        advanceUntilIdle()

        assertThat(sources.addCount).isEqualTo(1)
        assertThat(repository.rescanCount).isEqualTo(1)
    }

    @Test
    fun folderSelectionWorksWithFullPermissionWhenSelectedFolderScopeIsActive() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeHomeRepository(HomeData())
            val sources = FakeHomeScanSourceRepository(emptyList())
            val viewModel = HomeViewModel(
                repository = repository,
                sourceRepository = sources,
                scanPreferences = FakeHomeScanPreferences(
                    enabled = false,
                    scope = DocumentScanScope.SELECTED_FOLDERS,
                ),
                scanScheduler = FakeScanScheduler(),
                storageAccessController = grantedAccess(),
            )
            advanceUntilIdle()

            viewModel.onFolderSelected("content://tree/download")
            advanceUntilIdle()

            assertThat(sources.addCount).isEqualTo(1)
            assertThat(repository.rescanCount).isEqualTo(1)
        }

    @Test
    fun progressiveSessionMapsConcreteCountsAndPhase() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeScanScheduler()
        val viewModel = fullAccessViewModel(scheduler)
        scheduler.session.value = snapshot(
            status = ScanSessionStatus.RUNNING,
            phase = ScanPhase.DISCOVERING,
            checkedDirectories = 24,
            checkedFiles = 1_286,
            discoveredDocuments = 83,
            failedFiles = 2,
        )
        advanceUntilIdle()

        val scan = viewModel.state.value.scan
        assertThat(scan.title).isEqualTo(UiText.Resource(R.string.home_scan_discovering))
        assertThat(scan.detail).isEqualTo(
            UiText.Resource(R.string.home_scan_count_detail, listOf(24, 1_286, 83)),
        )
        assertThat(scan.checkedDirectories).isEqualTo(24)
        assertThat(scan.checkedFiles).isEqualTo(1_286)
        assertThat(scan.discoveredDocuments).isEqualTo(83)
        assertThat(scan.failedFiles).isEqualTo(2)
        assertThat(scan.primaryAction).isEqualTo(ScanProgressAction.PAUSE)
        assertThat(viewModel.state.value.sourceCount).isEqualTo(1)
    }

    @Test
    fun pauseResumeCancelDelegateToProgressiveScheduler() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeScanScheduler()
        val viewModel = fullAccessViewModel(scheduler)
        advanceUntilIdle()

        viewModel.pauseScan()
        viewModel.resumeScan()
        viewModel.cancelScan()
        advanceUntilIdle()

        assertThat(scheduler.pauseCount).isEqualTo(1)
        assertThat(scheduler.resumeCount).isEqualTo(1)
        assertThat(scheduler.cancelCount).isEqualTo(1)
    }

    @Test
    fun pausedAndCancelledSessionsExplainWhatUserCanDoNext() =
        runTest(mainDispatcherRule.dispatcher) {
            val scheduler = FakeScanScheduler()
            val viewModel = fullAccessViewModel(scheduler)

            scheduler.session.value = snapshot(status = ScanSessionStatus.PAUSED)
            advanceUntilIdle()
            assertThat(viewModel.state.value.scan.title)
                .isEqualTo(UiText.Resource(R.string.home_scan_paused))
            assertThat(viewModel.state.value.scan.primaryAction).isEqualTo(ScanProgressAction.RESUME)
            assertThat(viewModel.state.value.scan.canCancel).isTrue()

            scheduler.session.value = snapshot(
                status = ScanSessionStatus.CANCELLED,
                discoveredDocuments = 12,
            )
            advanceUntilIdle()
            assertThat(viewModel.state.value.scan.title)
                .isEqualTo(UiText.Resource(R.string.home_scan_stopped))
            assertThat(viewModel.state.value.scan.detail).isEqualTo(
                UiText.Plural(R.plurals.home_scan_cancelled_kept, 12),
            )
            assertThat(viewModel.state.value.scan.primaryAction).isEqualTo(ScanProgressAction.RESTART)
            assertThat(viewModel.state.value.scan.canCancel).isFalse()
        }

    @Test
    fun failedSessionOffersRetryAndUsesLocalizedFailureMessage() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeScanScheduler()
        val viewModel = fullAccessViewModel(scheduler)
        scheduler.session.value = snapshot(
            status = ScanSessionStatus.FAILED,
            errorMessage = "系统中断了扫描",
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.scan.title)
            .isEqualTo(UiText.Resource(R.string.home_scan_failed))
        assertThat(viewModel.state.value.scan.errorMessage)
            .isEqualTo(UiText.Resource(R.string.home_scan_error_detail))
        assertThat(viewModel.state.value.scan.primaryAction).isEqualTo(ScanProgressAction.RETRY)

        viewModel.retryScan()
        advanceUntilIdle()
        assertThat(scheduler.startCount).isEqualTo(1)
    }

    @Test
    fun completedSessionStaysCompactAndOffersRescan() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeScanScheduler()
        val viewModel = fullAccessViewModel(scheduler)
        scheduler.session.value = snapshot(
            status = ScanSessionStatus.COMPLETED,
            phase = ScanPhase.COMPLETE,
            checkedDirectories = 18,
            checkedFiles = 920,
            discoveredDocuments = 61,
            updatedAt = 1_777_777L,
        )
        advanceUntilIdle()

        val scan = viewModel.state.value.scan
        assertThat(scan.title).isEqualTo(UiText.Resource(R.string.home_scan_updated))
        val detail = scan.detail as UiText.Plural
        assertThat(detail.id).isEqualTo(R.plurals.home_scan_completed_detail)
        assertThat(detail.quantity).isEqualTo(61)
        assertThat(detail.args.last()).isEqualTo(61)
        assertThat(scan.updatedAt).isEqualTo(1_777_777L)
        assertThat(scan.primaryAction).isEqualTo(ScanProgressAction.RESTART)
        assertThat(scan.isCompactCompletion).isTrue()
    }

    private fun fullAccessViewModel(scheduler: FakeScanScheduler) = HomeViewModel(
        repository = FakeHomeRepository(HomeData()),
        scanPreferences = FakeHomeScanPreferences(false),
        scanScheduler = scheduler,
        storageAccessController = grantedAccess(),
    )

    private fun snapshot(
        status: ScanSessionStatus,
        phase: ScanPhase = ScanPhase.DISCOVERING,
        checkedDirectories: Int = 3,
        checkedFiles: Int = 120,
        discoveredDocuments: Int = 12,
        failedFiles: Int = 0,
        updatedAt: Long = 123L,
        errorMessage: String? = null,
    ) = ScanSessionSnapshot(
        sessionId = "session-1",
        scanId = "scan-1",
        status = status,
        phase = phase,
        checkedDirectories = checkedDirectories,
        checkedFiles = checkedFiles,
        discoveredDocuments = discoveredDocuments,
        failedFiles = failedFiles,
        updatedAt = updatedAt,
        errorMessage = errorMessage,
    )
}

private class FakeHomeRepository(initialData: HomeData) : HomeRepository {
    private val mutableData = MutableStateFlow(initialData)
    override val data: Flow<HomeData> = mutableData
    var rescanCount = 0
        private set
    val appliedScopes = mutableListOf<DocumentScanScope>()

    fun emit(data: HomeData) {
        mutableData.value = data
    }

    override suspend fun rescan(): Result<Unit> { rescanCount++; return Result.success(Unit) }
    override suspend fun applyScanScope(scope: DocumentScanScope) { appliedScopes += scope }
    override suspend fun markOpened(documentId: Long) = Unit
}

private class FakeScanScheduler : ScanScheduler {
    val session = MutableStateFlow<ScanSessionSnapshot?>(null)
    var startCount = 0
    var pauseCount = 0
    var resumeCount = 0
    var cancelCount = 0

    override suspend fun start(): Boolean { startCount++; return true }
    override suspend fun pause(): Boolean { pauseCount++; return true }
    override suspend fun resume(): Boolean { resumeCount++; return true }
    override suspend fun cancel(): Boolean { cancelCount++; return true }
    override fun observeSession(): Flow<ScanSessionSnapshot?> = session
}

private class FakeHomeScanSourceRepository(
    initialSources: List<ScanSourceEntity>,
) : ScanSourceRepository {
    private val sources = MutableStateFlow(initialSources)
    var addCount = 0

    override fun observeSources(): Flow<List<ScanSourceEntity>> = sources
    override suspend fun add(uri: String): Result<Long> { addCount++; return Result.success(1) }
    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(sourceId: Long): Result<Unit> = Result.success(Unit)
}

private class FakeHomeScanPreferences(
    enabled: Boolean,
    scope: DocumentScanScope = DocumentScanScope.ALL_DOCUMENTS,
) : ScanPreferences {
    private val value = MutableStateFlow(enabled)
    override val scanOnLaunch: Flow<Boolean> = value
    override suspend fun setScanOnLaunch(enabled: Boolean) { value.value = enabled }
    private val mutableScope = MutableStateFlow(scope)
    override val scanScope: Flow<DocumentScanScope> = mutableScope
    override suspend fun setScanScope(scope: DocumentScanScope) { mutableScope.value = scope }
}

private fun treeSource(lastScanAt: Long?) = ScanSourceEntity(
    id = 1,
    treeUri = "content://tree/download",
    displayName = "Download",
    lastScanAt = lastScanAt,
)

private fun grantedAccess() = StorageAccessController(
    sdkInt = 30,
    isExternalStorageManager = { true },
)

private fun restrictedAccess() = StorageAccessController(
    sdkInt = 30,
    isExternalStorageManager = { false },
)
