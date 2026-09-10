package com.wenxu.app.core.storage

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileTransferServiceTest {
    @Test
    fun successfulMoveUpdatesIndexAfterPhysicalMove() = runTest {
        val store = FakeTransferStore()
        val gateway = FakeDocumentGateway()
        val service = FileTransferService(store, FakeTransferSourceRepository(), gateway) { "move-scan" }

        val result = service.move(1, "content://target/tree")

        assertThat(result.isSuccess).isTrue()
        assertThat(gateway.operations.first()).startsWith("move:")
        assertThat(store.updatedUri).isEqualTo("content://target/tree/报告.pdf")
        assertThat(store.updatedSourceId).isEqualTo(9)
    }

    @Test
    fun providerFailureLeavesIndexUntouched() = runTest {
        val store = FakeTransferStore()
        val gateway = FakeDocumentGateway().apply {
            moveToFolderResult = Result.failure(IllegalStateException("copy failed"))
        }
        val service = FileTransferService(store, FakeTransferSourceRepository(), gateway)

        val result = service.move(1, "content://target/tree")

        assertThat(result.isFailure).isTrue()
        assertThat(store.updatedUri).isNull()
    }
}

private class FakeTransferStore : FileTransferStore {
    private val document = DocumentEntity(
        id = 1,
        uri = "content://source/报告.pdf",
        displayName = "报告.pdf",
        normalizedName = "报告",
        mimeType = "application/pdf",
        extension = "pdf",
        sizeBytes = 3,
        modifiedAt = 1,
        sourceId = 2,
        parentUri = "content://source/parent",
        lastSeenScanId = "scan",
    )
    private val source = ScanSourceEntity(2, "content://source/tree", "Download")
    var updatedUri: String? = null
    var updatedSourceId: Long? = null

    override suspend fun findDocument(documentId: Long): DocumentEntity? = document.takeIf { it.id == documentId }
    override suspend fun findSource(sourceId: Long): ScanSourceEntity? = source.takeIf { it.id == sourceId }
    override suspend fun updateMovedDocument(
        documentId: Long,
        uri: String,
        parentUri: String,
        sourceId: Long,
        scanId: String,
    ) {
        updatedUri = uri
        updatedSourceId = sourceId
    }
}

private class FakeTransferSourceRepository : ScanSourceRepository {
    override fun observeSources(): Flow<List<ScanSourceEntity>> = MutableStateFlow(emptyList())
    override suspend fun add(uri: String): Result<Long> = Result.success(9)
    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(sourceId: Long): Result<Unit> = Result.success(Unit)
}
