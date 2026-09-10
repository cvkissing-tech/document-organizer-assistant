package com.wenxu.app.core.importing

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.settings.InboxPreferences
import com.wenxu.app.core.storage.ScanSourceRepository
import com.wenxu.app.feature.home.HomeData
import com.wenxu.app.feature.home.HomeRepository
import com.wenxu.app.core.relations.RelationAnalysisReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InboxImportServiceTest {
    @Test
    fun successfulMultipleImportCopiesDocumentsAndRescansOnce() = runTest {
        val gateway = FakeDocumentGateway()
        val relationRequests = mutableListOf<RelationAnalysisReason>()
        val home = ImportHomeRepository(
            onSuccessfulRescan = { relationRequests += RelationAnalysisReason.SCAN_COMPLETED },
        )
        val preferences = ImportInboxPreferences()
        val service = InboxImportService(
            gateway,
            ImportSourceRepository(),
            preferences,
            home,
        )

        val report = service.import(
            listOf(document("a.pdf"), document("b.docx")),
            "content://tree/wenxu-inbox",
        )

        assertThat(report.imported).isEqualTo(2)
        assertThat(home.rescanCount).isEqualTo(1)
        assertThat(preferences.current.value).isEqualTo("content://tree/wenxu-inbox")
        assertThat(gateway.operations.filter { it.startsWith("copy:") }).hasSize(2)
        assertThat(relationRequests).containsExactly(RelationAnalysisReason.SCAN_COMPLETED)
    }

    @Test
    fun failedCopyDoesNotClaimImportOrTriggerRescan() = runTest {
        val gateway = FakeDocumentGateway().apply {
            copyToFolderResult = Result.failure(IllegalStateException("复制内容校验失败"))
        }
        val home = ImportHomeRepository()
        val service = InboxImportService(
            gateway,
            ImportSourceRepository(),
            ImportInboxPreferences(),
            home,
        )

        val report = service.import(listOf(document("a.pdf")), "content://tree/wenxu-inbox")

        assertThat(report.imported).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(home.rescanCount).isEqualTo(0)
    }

    @Test
    fun failedRescanDoesNotRequestRelationAnalysis() = runTest {
        val requests = mutableListOf<RelationAnalysisReason>()
        val home = ImportHomeRepository(
            rescanResult = Result.failure(IllegalStateException("scan failed")),
            onSuccessfulRescan = { requests += RelationAnalysisReason.SCAN_COMPLETED },
        )
        val service = InboxImportService(
            FakeDocumentGateway(),
            ImportSourceRepository(),
            ImportInboxPreferences(),
            home,
        )

        service.import(listOf(document("a.pdf")), "content://tree/wenxu-inbox")

        assertThat(requests).isEmpty()
    }

    private fun document(name: String) = IncomingDocument(
        uri = "content://shared/$name",
        displayName = name,
        mimeType = "application/octet-stream",
        sizeBytes = 8,
    )
}

private class ImportInboxPreferences : InboxPreferences {
    val current = MutableStateFlow<String?>(null)
    override val inboxTreeUri: Flow<String?> = current
    override suspend fun setInboxTreeUri(uri: String) { current.value = uri }
}

private class ImportSourceRepository : ScanSourceRepository {
    override fun observeSources(): Flow<List<ScanSourceEntity>> = MutableStateFlow(emptyList())
    override suspend fun add(uri: String): Result<Long> = Result.success(1)
    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(sourceId: Long): Result<Unit> = Result.success(Unit)
}

private class ImportHomeRepository(
    private val rescanResult: Result<Unit> = Result.success(Unit),
    private val onSuccessfulRescan: () -> Unit = {},
) : HomeRepository {
    override val data: Flow<HomeData> = MutableStateFlow(HomeData())
    var rescanCount = 0
    override suspend fun rescan(): Result<Unit> {
        rescanCount++
        if (rescanResult.isSuccess) onSuccessfulRescan()
        return rescanResult
    }
    override suspend fun markOpened(documentId: Long) = Unit
}
