package com.wenxu.app.feature.settings

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.core.settings.LanguagePreferences
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.storage.ScanSourceRepository
import com.wenxu.app.feature.trash.TrashItem
import com.wenxu.app.feature.trash.TrashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun fullAccessShowsAllDocumentsModeAndCountsOnlyLegacyTreeSources() =
        runTest(mainDispatcherRule.dispatcher) {
            val sources = listOf(
                source(1, ScanSourceKind.SHARED_STORAGE),
                source(2, ScanSourceKind.TREE),
            )
            val viewModel = createViewModel(sources, sdkInt = 30, fullAccess = true)
            advanceUntilIdle()

            assertThat(viewModel.state.value.scanScopeMode).isEqualTo(DocumentScanScope.ALL_DOCUMENTS)
            assertThat(viewModel.state.value.sourceCount).isEqualTo(1)
        }

    @Test
    fun missingFullAccessShowsSelectedFolderMode() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = createViewModel(
            sources = listOf(source(1, ScanSourceKind.TREE)),
            sdkInt = 30,
            fullAccess = false,
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.scanScopeMode).isEqualTo(DocumentScanScope.SELECTED_FOLDERS)
        assertThat(viewModel.state.value.sourceCount).isEqualTo(1)
    }

    @Test
    fun legacyAndroidShowsSelectedFolderMode() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = createViewModel(emptyList(), sdkInt = 29, fullAccess = false)
        advanceUntilIdle()

        assertThat(viewModel.state.value.scanScopeMode).isEqualTo(DocumentScanScope.SELECTED_FOLDERS)
    }

    @Test
    fun fullPermissionStillRespectsUserSelectedFolderScope() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeSettingsScanPreferences(
                initial = true,
                initialScope = DocumentScanScope.SELECTED_FOLDERS,
            )
            val viewModel = SettingsViewModel(
                sourceRepository = SettingsSourceRepository(listOf(source(2, ScanSourceKind.TREE))),
                preferences = preferences,
                languagePreferences = FakeSettingsLanguagePreferences(),
                trashRepository = SettingsTrashRepository(emptyList()),
                storageAccessController = StorageAccessController(
                    sdkInt = 30,
                    isExternalStorageManager = { true },
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.scanScopeMode)
                .isEqualTo(DocumentScanScope.SELECTED_FOLDERS)
        }

    @Test
    fun keepsTrashCountAndScanPreference() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeSettingsScanPreferences(true)
        val viewModel = SettingsViewModel(
            sourceRepository = SettingsSourceRepository(emptyList()),
            preferences = preferences,
            languagePreferences = FakeSettingsLanguagePreferences(),
            trashRepository = SettingsTrashRepository(List(2) { trashItem(it + 1L) }),
            storageAccessController = StorageAccessController(
                sdkInt = 30,
                isExternalStorageManager = { true },
            ),
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.trashCount).isEqualTo(2)
        viewModel.setScanOnLaunch(false)
        advanceUntilIdle()
        assertThat(viewModel.state.value.scanOnLaunch).isFalse()
    }

    private fun createViewModel(
        sources: List<ScanSourceEntity>,
        sdkInt: Int,
        fullAccess: Boolean,
    ) = SettingsViewModel(
        sourceRepository = SettingsSourceRepository(sources),
        preferences = FakeSettingsScanPreferences(true),
        languagePreferences = FakeSettingsLanguagePreferences(),
        trashRepository = SettingsTrashRepository(emptyList()),
        storageAccessController = StorageAccessController(
            sdkInt = sdkInt,
            isExternalStorageManager = { fullAccess },
        ),
    )
}

private class FakeSettingsLanguagePreferences(
    initial: AppLanguage? = AppLanguage.SIMPLIFIED_CHINESE,
) : LanguagePreferences {
    private val language = MutableStateFlow(initial)
    override val selectedLanguage: Flow<AppLanguage?> = language
    override suspend fun setSelectedLanguage(language: AppLanguage) {
        this.language.value = language
    }
}

private class FakeSettingsScanPreferences(
    initial: Boolean,
    initialScope: DocumentScanScope = DocumentScanScope.ALL_DOCUMENTS,
) : ScanPreferences {
    private val value = MutableStateFlow(initial)
    override val scanOnLaunch: Flow<Boolean> = value
    override suspend fun setScanOnLaunch(enabled: Boolean) { value.value = enabled }
    private val scope = MutableStateFlow(initialScope)
    override val scanScope: Flow<DocumentScanScope> = scope
    override suspend fun setScanScope(scope: DocumentScanScope) { this.scope.value = scope }
}

private class SettingsSourceRepository(initial: List<ScanSourceEntity>) : ScanSourceRepository {
    private val sources = MutableStateFlow(initial)
    override fun observeSources(): Flow<List<ScanSourceEntity>> = sources
    override suspend fun add(uri: String): Result<Long> = Result.success(1)
    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(sourceId: Long): Result<Unit> = Result.success(Unit)
}

private class SettingsTrashRepository(initial: List<TrashItem>) : TrashRepository {
    override val items: Flow<List<TrashItem>> = MutableStateFlow(initial)
    override suspend fun restore(ids: Set<Long>, fallbackParentUri: String?): List<Result<Long>> = emptyList()
    override suspend fun deleteForever(ids: Set<Long>): List<Result<Unit>> = emptyList()
}

private fun source(id: Long, kind: ScanSourceKind) = ScanSourceEntity(
    id = id,
    treeUri = "content://tree/$id",
    displayName = "位置$id",
    sourceKind = kind,
)

private fun trashItem(id: Long) = TrashItem(
    id = id,
    documentId = id,
    displayName = "文档$id.pdf",
    extension = "pdf",
    sizeBytes = 10,
    originalParentUri = "content://tree/source",
    deletedAt = 1,
    expiresAt = 2,
)
