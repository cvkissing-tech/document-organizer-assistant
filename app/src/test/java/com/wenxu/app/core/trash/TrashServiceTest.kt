package com.wenxu.app.core.trash

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.database.entity.TrashRecordEntity
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.storage.TrashLocation
import com.wenxu.app.core.relations.RelationAnalysisReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Test

class TrashServiceTest {
    @Test
    fun legacySharedTrashNeverUsesSafOrChangesMetadata() = runTest {
        val store = FakeTrashStore(sourceKind = ScanSourceKind.SHARED_STORAGE)
        val gateway = FakeDocumentGateway()

        val result = TrashService(store, gateway).trash(store.document.id)

        assertThat(result).isInstanceOf(TrashResult.Unsupported::class.java)
        assertThat(gateway.operations).isEmpty()
        assertThat(store.recorded).isFalse()
    }

    @Test
    fun cleanupExpiredLeavesSystemTrashForExplicitUserAction() = runTest {
        val store = FakeTrashStore(returnExpired = true).apply {
            installTrashEntry(storageKind = TrashStorageKind.MEDIA_STORE)
        }
        val gateway = FakeDocumentGateway()
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(store, gateway, relationRequest = requests::add)

        assertThat(service.cleanupExpired()).isEqualTo(0)
        assertThat(gateway.operations).isEmpty()
        assertThat(store.metadataDeleted).isFalse()
        assertThat(requests).isEmpty()
    }

    @Test
    fun unsupportedProviderNeverChangesMetadata() = runTest {
        val store = FakeTrashStore()
        val gateway = FakeDocumentGateway().apply {
            moveToTrashResult = Result.failure(UnsupportedOperationException("不支持安全删除"))
        }
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(store, gateway, relationRequest = requests::add)

        val result = service.trash(store.document.id)

        assertThat(result).isInstanceOf(TrashResult.Unsupported::class.java)
        assertThat(store.recorded).isFalse()
        assertThat(requests).isEmpty()
    }

    @Test
    fun successfulTrashRecordsVerifiedPhysicalLocation() = runTest {
        val store = FakeTrashStore()
        val gateway = FakeDocumentGateway()
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(
            store,
            gateway,
            clock = { 1_000L },
            relationRequest = requests::add,
        )

        val result = service.trash(store.document.id)

        assertThat(result).isEqualTo(TrashResult.Success(7))
        assertThat(store.recorded).isTrue()
        assertThat(store.lastLocation?.documentUri).contains("/trash/")
        assertThat(requests).containsExactly(RelationAnalysisReason.TRASH)
    }

    @Test
    fun metadataFailureAttemptsToPutPhysicalFileBack() = runTest {
        val store = FakeTrashStore(recordFailure = IllegalStateException("db failed"))
        val gateway = FakeDocumentGateway()
        val service = TrashService(store, gateway)

        val result = service.trash(store.document.id)

        assertThat(result).isInstanceOf(TrashResult.Failed::class.java)
        assertThat(gateway.operations).contains("restore:content://test/trash/报告.pdf")
    }

    @Test
    fun permanentDeleteKeepsMetadataWhenProviderFails() = runTest {
        val store = FakeTrashStore().apply { installTrashEntry() }
        val gateway = FakeDocumentGateway().apply {
            deleteResult = Result.failure(IllegalStateException("provider failed"))
        }
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(store, gateway, relationRequest = requests::add)

        val result = service.deleteForever(7)

        assertThat(result.isFailure).isTrue()
        assertThat(store.metadataDeleted).isFalse()
        assertThat(requests).isEmpty()
    }

    @Test
    fun restoreAndPermanentDeleteRequestAnalysisOnlyAfterSuccess() = runTest {
        val store = FakeTrashStore().apply { installTrashEntry() }
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(
            store,
            FakeDocumentGateway(),
            relationRequest = requests::add,
        )

        assertThat(service.restore(7).isSuccess).isTrue()
        store.installTrashEntry()
        assertThat(service.deleteForever(7).isSuccess).isTrue()

        assertThat(requests).containsExactly(
            RelationAnalysisReason.RESTORE,
            RelationAnalysisReason.DELETE_FOREVER,
        ).inOrder()
    }

    @Test
    fun trashManyRequestsOneAnalysisAfterAnySuccessfulMember() = runTest {
        val store = FakeTrashStore(includeSecondDocument = true)
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(
            store,
            FakeDocumentGateway(),
            relationRequest = requests::add,
        )

        val results = service.trashMany(setOf(11, 12))

        assertThat(results.filterIsInstance<TrashResult.Success>()).hasSize(2)
        assertThat(requests).containsExactly(RelationAnalysisReason.TRASH)
    }

    @Test
    fun cleanupExpiredRequestsOneAnalysisForTheWholeBatch() = runTest {
        val store = FakeTrashStore(returnExpired = true).apply { installTrashEntry() }
        val requests = mutableListOf<RelationAnalysisReason>()
        val service = TrashService(
            store,
            FakeDocumentGateway(),
            relationRequest = requests::add,
        )

        assertThat(service.cleanupExpired()).isEqualTo(1)
        assertThat(requests).containsExactly(RelationAnalysisReason.DELETE_FOREVER)
    }

    @Test
    fun committedRestoreIsNotReportedFailedWhenRelationSchedulingIsCancelled() = runTest {
        val store = FakeTrashStore().apply { installTrashEntry() }
        val service = TrashService(
            store,
            FakeDocumentGateway(),
            relationRequest = { throw CancellationException("scheduler stopped") },
        )

        val result = service.restore(7)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun operationCancellationIsNeverWrappedAsOrdinaryFailure() = runTest {
        val store = FakeTrashStore(recordFailure = CancellationException("operation cancelled"))
        val service = TrashService(store, FakeDocumentGateway())

        val error = runCatching { service.trash(store.document.id) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }
}

private class FakeTrashStore(
    private val recordFailure: Throwable? = null,
    private val includeSecondDocument: Boolean = false,
    private val returnExpired: Boolean = false,
    sourceKind: ScanSourceKind = ScanSourceKind.TREE,
) : TrashStore {
    val document = DocumentEntity(
        id = 11,
        uri = "content://test/报告.pdf",
        displayName = "报告.pdf",
        normalizedName = "报告",
        mimeType = "application/pdf",
        extension = "pdf",
        sizeBytes = 3,
        modifiedAt = 1,
        sourceId = 2,
        parentUri = "content://test/parent",
        indexStatus = DocumentIndexStatus.ACTIVE,
        lastSeenScanId = "scan",
    )
    private val source = ScanSourceEntity(
        id = 2,
        treeUri = "content://test/tree",
        displayName = "Download",
        sourceKind = sourceKind,
    )
    private var entry: TrashEntry? = null
    private var pending: PendingTrashOperationEntity? = null
    private var targetState: PendingTrashTargetStateEntity? = null
    var recorded = false
    var metadataDeleted = false
    var lastLocation: TrashLocation? = null

    override fun observeEntries(): Flow<List<TrashEntry>> = MutableStateFlow(entry?.let(::listOf) ?: emptyList())
    override suspend fun findDocument(documentId: Long): DocumentEntity? = when (documentId) {
        document.id -> document
        12L -> document.copy(
            id = 12,
            uri = "content://test/第二份.pdf",
            displayName = "第二份.pdf",
        ).takeIf { includeSecondDocument }
        else -> null
    }
    override suspend fun findSource(sourceId: Long): ScanSourceEntity? = source.takeIf { it.id == sourceId }
    override suspend fun findEntry(trashId: Long): TrashEntry? = entry?.takeIf { it.record.id == trashId }
    override suspend fun findEntryByDocumentId(documentId: Long): TrashEntry? = entry?.takeIf { it.record.documentId == documentId }
    override suspend fun createPending(operation: PendingTrashOperationEntity): Long = 1L.also {
        pending = operation.copy(id = it)
    }
    override suspend fun findPending(id: Long): PendingTrashOperationEntity? = pending?.takeIf { it.id == id }
    override suspend fun findUnfinishedPending(): List<PendingTrashOperationEntity> = pending?.let(::listOf).orEmpty()
    override suspend fun updatePending(operation: PendingTrashOperationEntity) { pending = operation }
    override suspend fun updatePendingStatus(id: Long, status: PendingTrashStatus, updatedAt: Long) {
        pending = pending?.takeIf { it.id == id }?.copy(status = status, updatedAt = updatedAt)
    }
    override suspend fun removePending(id: Long) {
        if (pending?.id == id) pending = null
        if (targetState?.operationId == id) targetState = null
    }
    override suspend fun cancelPending(id: Long, updatedAt: Long) = removePending(id)
    override suspend fun findTargetStates(operationId: Long): List<PendingTrashTargetStateEntity> =
        targetState?.takeIf { it.operationId == operationId }?.let(::listOf).orEmpty()
    override suspend fun saveTargetState(state: PendingTrashTargetStateEntity) { targetState = state }
    override suspend fun clearTargetState(operationId: Long, targetId: Long) {
        if (targetState?.operationId == operationId && targetState?.targetId == targetId) targetState = null
    }
    override suspend fun applyCompensatedTargetState(
        state: PendingTrashTargetStateEntity,
        expected: TrashEntry,
    ): TrashMetadataChange {
        val current = entry ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        updateAppTrashLocation(state.targetId, TrashLocation(state.documentUri, state.parentUri))
        targetState = null
        return TrashMetadataChange.CHANGED
    }
    override suspend fun <T> commitPendingTarget(operationId: Long, targetId: Long, updatedAt: Long, commit: suspend () -> T): T {
        val result = commit()
        targetState = null
        pending = null
        return result
    }
    override suspend fun recordMediaStoreTrash(document: DocumentEntity, deletedAt: Long, expiresAt: Long): Long =
        error("SAF fixture cannot record system trash")

    override suspend fun recordTrash(
        document: DocumentEntity,
        location: TrashLocation,
        deletedAt: Long,
        expiresAt: Long,
    ): Long {
        recordFailure?.let { throw it }
        recorded = true
        lastLocation = location
        installTrashEntry(location, deletedAt, expiresAt)
        return 7
    }

    override suspend fun restoreMetadata(
        expected: TrashEntry,
        restoredUri: String,
        restoredParentUri: String,
    ): TrashMetadataChange {
        val current = entry ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        entry = null
        return TrashMetadataChange.CHANGED
    }

    override suspend fun restoreVerifiedMediaStoreMetadata(expected: TrashEntry): TrashMetadataChange =
        restoreMetadata(expected, expected.record.originalUri, expected.record.originalParentUri)

    override suspend fun updateAppTrashLocation(trashId: Long, location: TrashLocation) {
        entry = entry?.let {
            TrashEntry(
                it.record.copy(trashedUri = location.documentUri, trashedParentUri = location.parentUri),
                it.document.copy(uri = location.documentUri, parentUri = location.parentUri, indexStatus = DocumentIndexStatus.TRASHED),
            )
        }
    }

    override suspend fun deleteMetadataAndDocument(expected: TrashEntry): TrashMetadataChange {
        val current = entry ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        metadataDeleted = true
        entry = null
        return TrashMetadataChange.CHANGED
    }

    override suspend fun findExpired(now: Long): List<TrashEntry> =
        if (returnExpired) entry?.let(::listOf).orEmpty() else emptyList()

    fun installTrashEntry(
        location: TrashLocation = TrashLocation("content://test/trash/报告.pdf", "content://test/trash"),
        deletedAt: Long = 1_000,
        expiresAt: Long = 2_000,
        storageKind: TrashStorageKind = TrashStorageKind.APP_TRASH,
    ) {
        entry = TrashEntry(
            record = TrashRecordEntity(
                id = 7,
                documentId = document.id,
                originalUri = document.uri,
                trashedUri = location.documentUri,
                trashedParentUri = location.parentUri,
                originalParentUri = document.parentUri,
                deletedAt = deletedAt,
                expiresAt = expiresAt,
                storageKind = storageKind,
            ),
            document = document.copy(
                uri = location.documentUri,
                parentUri = location.parentUri,
                indexStatus = DocumentIndexStatus.TRASHED,
            ),
        )
    }
}
