package com.wenxu.app.core.trash

import android.content.IntentSender
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashTargetKind
import com.wenxu.app.core.database.entity.PendingTrashTargetStage
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.database.entity.TrashRecordEntity
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.relations.RelationAnalysisReason
import com.wenxu.app.core.storage.TrashLocation
import com.wenxu.app.core.storage.runDocumentMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import sun.misc.Unsafe

class PendingTrashCoordinatorTest {
    @Test
    fun mediaStoreRequestPersistsBeforeCreatingConfirmationWithoutMovingFiles() = runTest {
        val fixture = PendingFixture()
        val shared = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val tree = fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.system.beforeCreate = {
            val pending = fixture.store.pending.values.single()
            assertThat(pending.status).isEqualTo(PendingTrashStatus.AWAITING_CONFIRMATION)
            assertThat(pending.targetIds).containsExactly(shared.id, tree.id)
            assertThat(pending.eligibleTargetIds).containsExactly(shared.id, tree.id)
            assertThat(fixture.store.records).isEmpty()
        }

        val result = fixture.service.requestTrash(linkedSetOf(tree.id, shared.id)).confirmation()

        assertThat(result.action).isEqualTo(PendingTrashAction.TRASH)
        assertThat(result.targetCount).isEqualTo(2)
        assertThat(fixture.system.requests.single().second).containsExactly(shared.uri)
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun cancellationDoesNotVerifyOrChangeEitherStorageKind() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val originalDocuments = fixture.store.documents.toMap()
        val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()

        val result = fixture.service.completeConfirmation(request.operationId, approved = false)

        assertThat(result).isEqualTo(TrashOperationResult.Cancelled)
        assertThat(fixture.store.documents).isEqualTo(originalDocuments)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.store.statuses).contains(PendingTrashStatus.CANCELLED)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.system.verifications).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun cancellationAfterFirstImmediateTargetLeavesSecondPendingForReconciliation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.TREE)
        val system = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val firstApp = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val secondApp = fixture.store.trash(13, TrashStorageKind.APP_TRASH)
        val request = fixture.service.requestDeleteForever(setOf(system.id, firstApp.id, secondApp.id)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        val operationJob = Job()
        fixture.gateway.afterDelete = {
            operationJob.cancel(CancellationException("stop batch"))
        }

        val error = runCatching {
            withContext(operationJob) {
                fixture.service.completeConfirmation(request.operationId, approved = true)
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.gateway.operations).containsExactly("delete:${firstApp.trashedUri}")
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(secondApp.id)
        assertThat(fixture.store.records.keys).containsExactly(secondApp.id)

        fixture.gateway.afterDelete = {}
        val reconciled = fixture.newService().reconcilePending().single().completed()

        assertThat(reconciled.successes.map { it.documentId }).containsExactly(13L)
        assertThat(reconciled.failures).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun approvalCountsOnlyMatchedTargetsWhoseDatabaseTransactionCommitted() = runTest {
        val fixture = PendingFixture()
        (11L..14L).forEach { fixture.store.addDocument(it, ScanSourceKind.SHARED_STORAGE) }
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.match(12, MediaStoreVerification.NOT_MATCHED)
        fixture.system.match(13, MediaStoreVerification.UNKNOWN)
        fixture.system.match(14, MediaStoreVerification.MATCHED)
        fixture.store.failCommitFor += 14
        val request = fixture.service.requestTrash(setOf(14, 11, 13, 12)).confirmation()

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(
            TrashFailure(12, TrashFailureReason.PROVIDER_REJECTED),
            TrashFailure(13, TrashFailureReason.VERIFICATION_FAILED),
            TrashFailure(14, TrashFailureReason.DATABASE_FAILED),
        )
        assertThat(fixture.store.records.values.map { it.documentId }).containsExactly(11L)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(13L, 14L)
        assertThat(fixture.store.pending.values.single().eligibleTargetIds).containsExactly(13L, 14L)
        assertThat(fixture.store.pending.values.single().status).isEqualTo(PendingTrashStatus.CONFIRMED)
        assertThat(fixture.store.documents.getValue(14).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.TRASH)
    }

    @Test
    fun requestCreationFailureLeavesMixedBatchUntouchedAndCountsEveryTarget() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.system.createFailure = IllegalStateException("provider rejected")

        val result = fixture.service.requestTrash(setOf(11, 12, 99)).completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(
            TrashFailure(11, TrashFailureReason.PROVIDER_REJECTED),
            TrashFailure(12, TrashFailureReason.PROVIDER_REJECTED),
            TrashFailure(99, TrashFailureReason.NOT_FOUND),
        )
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.records).isEmpty()
    }

    @Test
    fun pendingWriteFailureNeverCreatesSystemRequestOrMovesSaf() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.failCreate = true

        val result = fixture.service.requestTrash(setOf(11, 12)).completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(
            TrashFailure(11, TrashFailureReason.DATABASE_FAILED),
            TrashFailure(12, TrashFailureReason.DATABASE_FAILED),
        )
        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
    }

    @Test
    fun mixedBatchRunsSafOnlyAfterConfirmationAndSchedulesOneAnalysis() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val tree = fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        val request = fixture.service.requestTrash(setOf(12, 11)).confirmation()
        assertThat(fixture.gateway.operations).isEmpty()

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L, 12L)
        assertThat(result.failures).isEmpty()
        assertThat(fixture.gateway.operations).containsExactly("trash:${tree.uri}")
        assertThat(fixture.store.records.values.map { it.storageKind }).containsExactly(
            TrashStorageKind.MEDIA_STORE, TrashStorageKind.APP_TRASH,
        )
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.TRASH)
        assertThat(fixture.service.completeConfirmation(request.operationId, true).completed().failures)
            .containsExactly(TrashFailure(request.operationId, TrashFailureReason.NOT_FOUND))
        assertThat(fixture.gateway.operations).hasSize(1)
    }

    @Test
    fun validationAndOldApiFailuresCountEveryIdWithoutRequestingConfirmation() = runTest {
        val fixture = PendingFixture(apiLevel = 29)
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val inactive = fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.documents[12] = inactive.copy(indexStatus = DocumentIndexStatus.MISSING)
        val removed = fixture.store.addDocument(13, ScanSourceKind.TREE)
        fixture.store.sources.remove(removed.sourceId)
        fixture.store.addDocument(14, ScanSourceKind.INBOX)

        val result = fixture.service.requestTrash(setOf(99, 14, 13, 12, 11)).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(14L)
        assertThat(result.failures).containsExactly(
            TrashFailure(99, TrashFailureReason.NOT_FOUND),
            TrashFailure(12, TrashFailureReason.INACTIVE),
            TrashFailure(13, TrashFailureReason.SOURCE_REMOVED),
            TrashFailure(11, TrashFailureReason.UNSUPPORTED),
        )
        assertThat(fixture.system.requests).isEmpty()
    }

    @Test
    fun restoreSystemTrashKeepsOriginalLocationAndPersistsFallbackForAppTrash() = runTest {
        val fixture = PendingFixture()
        val original = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val record = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)

        val request = fixture.service.requestRestore(setOf(record.id), "content://fallback/tree").confirmation()

        assertThat(request.action).isEqualTo(PendingTrashAction.RESTORE)
        assertThat(fixture.store.pending.values.single().fallbackParentUri).isEqualTo("content://fallback/tree")
        assertThat(fixture.gateway.operations).isEmpty()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes).containsExactly(TrashSuccess(record.id, original.id, record.id))
        assertThat(fixture.store.documents[11]).isEqualTo(original)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.RESTORE)
    }

    @Test
    fun permanentSystemDeleteRequiresConfirmationAndNeverUsesSafDelete() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val record = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val request = fixture.service.requestDeleteForever(setOf(record.id)).confirmation()
        assertThat(request.action).isEqualTo(PendingTrashAction.DELETE_FOREVER)
        assertThat(fixture.store.documents).hasSize(1)
        fixture.system.match(11, MediaStoreVerification.MATCHED)

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(fixture.store.documents).isEmpty()
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.DELETE_FOREVER)
    }

    @Test
    fun appTrashRestoreAndPermanentDeleteStillExecuteImmediately() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.TREE)
        fixture.store.addDocument(12, ScanSourceKind.INBOX)
        val restoreRecord = fixture.store.trash(11, TrashStorageKind.APP_TRASH)
        val deleteRecord = fixture.store.trash(12, TrashStorageKind.APP_TRASH)

        assertThat(fixture.service.requestRestore(setOf(restoreRecord.id)).completed().successes).hasSize(1)
        assertThat(fixture.service.requestDeleteForever(setOf(deleteRecord.id)).completed().successes).hasSize(1)

        assertThat(fixture.store.createdOperations).hasSize(2)
        assertThat(fixture.store.createdOperations.map { it.status })
            .containsExactly(PendingTrashStatus.CONFIRMED, PendingTrashStatus.CONFIRMED)
        assertThat(fixture.store.targetStateWrites.map { it.stage }).containsExactly(
            PendingTrashTargetStage.STARTED,
            PendingTrashTargetStage.RESTORED,
            PendingTrashTargetStage.STARTED,
            PendingTrashTargetStage.DELETED,
        ).inOrder()
        assertThat(fixture.store.documents.getValue(11).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.store.documents).doesNotContainKey(12L)
        assertThat(fixture.gateway.operations).containsExactly(
            "restore:${restoreRecord.trashedUri}", "delete:${deleteRecord.trashedUri}",
        ).inOrder()
        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.relations).containsExactly(
            RelationAnalysisReason.RESTORE, RelationAnalysisReason.DELETE_FOREVER,
        ).inOrder()
    }

    @Test
    fun appTrashPermanentDeleteCarriesAuthorizedTreeAndTrashParentContext() = runTest {
        val fixture = PendingFixture()
        val document = fixture.store.addDocument(12, ScanSourceKind.TREE)
        val record = fixture.store.trash(12, TrashStorageKind.APP_TRASH)

        val result = fixture.service.requestDeleteForever(setOf(record.id)).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(document.id)
        assertThat(fixture.gateway.lastDeleteSourceTreeUri)
            .isEqualTo(fixture.store.sources.getValue(document.sourceId).treeUri)
        assertThat(fixture.gateway.lastDeleteParentUri).isEqualTo(record.trashedParentUri)
    }

    @Test
    fun legacyRestoreAndDeleteRefuseSystemConfirmationWithoutChangingRecords() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val record = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)

        assertThat(fixture.service.restore(record.id).isFailure).isTrue()
        assertThat(fixture.service.deleteForever(record.id).isFailure).isTrue()

        assertThat(fixture.store.records).hasSize(1)
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun reconciliationOfAwaitingMatchedMixedBatchRunsEachTargetOnlyOnce() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.service.requestTrash(setOf(11, 12))
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        val restarted = fixture.newService()

        assertThat(restarted.reconcilePending().single().completed().successes).hasSize(2)
        assertThat(restarted.reconcilePending()).isEmpty()

        assertThat(fixture.store.records).hasSize(2)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).hasSize(1)
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.TRASH)
    }

    @Test
    fun reconciliationKeepsUnknownAndDoesNotMoveUnconfirmedSaf() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.service.requestTrash(setOf(11, 12))

        fixture.newService().reconcilePending()
        fixture.newService().reconcilePending()

        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(11L, 12L)
        assertThat(fixture.store.pending.values.single().status).isEqualTo(PendingTrashStatus.AWAITING_CONFIRMATION)
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun reconciliationCancelsAwaitingBatchWhenAllSystemTargetsAreNotMatched() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.service.requestTrash(setOf(11, 12))
        fixture.system.match(11, MediaStoreVerification.NOT_MATCHED)

        val result = fixture.newService().reconcilePending().single()

        assertThat(result).isEqualTo(TrashOperationResult.Cancelled)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.records).isEmpty()
    }

    @Test
    fun partialReconciliationConsumesMatchedTargetsAndRetainsOnlyUnknown() = runTest {
        val fixture = PendingFixture()
        (11L..13L).forEach { fixture.store.addDocument(it, ScanSourceKind.SHARED_STORAGE) }
        fixture.service.requestTrash(setOf(11, 12, 13))
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.match(12, MediaStoreVerification.NOT_MATCHED)

        val first = fixture.newService().reconcilePending().single().completed()

        assertThat(first.successes.map { it.documentId }).containsExactly(11L)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(13L)
        assertThat(fixture.store.pending.values.single().status).isEqualTo(PendingTrashStatus.CONFIRMED)
        fixture.system.match(13, MediaStoreVerification.MATCHED)
        val second = fixture.newService().reconcilePending().single().completed()
        assertThat(second.successes.map { it.documentId }).containsExactly(13L)
        assertThat(fixture.newService().reconcilePending()).isEmpty()
        assertThat(fixture.store.records).hasSize(2)
        assertThat(fixture.relations).hasSize(2)
    }

    @Test
    fun databaseFailureRollsBackMetadataAndPendingConsumptionForEverySystemAction() = runTest {
        PendingTrashAction.entries.forEach { action ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            val targetId = if (action == PendingTrashAction.TRASH) 11L else {
                fixture.store.trash(11, TrashStorageKind.MEDIA_STORE).id
            }
            val initialDocuments = fixture.store.documents.toMap()
            val initialRecords = fixture.store.records.toMap()
            val request = when (action) {
                PendingTrashAction.TRASH -> fixture.service.requestTrash(setOf(targetId))
                PendingTrashAction.RESTORE -> fixture.service.requestRestore(setOf(targetId))
                PendingTrashAction.DELETE_FOREVER -> fixture.service.requestDeleteForever(setOf(targetId))
            }.confirmation()
            fixture.system.match(11, MediaStoreVerification.MATCHED)
            fixture.store.failCommitFor += targetId

            val failed = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(failed.successes).isEmpty()
            assertThat(failed.failures).containsExactly(TrashFailure(targetId, TrashFailureReason.DATABASE_FAILED))
            assertThat(fixture.store.documents).isEqualTo(initialDocuments)
            assertThat(fixture.store.records).isEqualTo(initialRecords)
            assertThat(fixture.store.pending.values.single().targetIds).containsExactly(targetId)
            assertThat(fixture.store.pending.values.single().status).isEqualTo(PendingTrashStatus.CONFIRMED)
            assertThat(fixture.relations).isEmpty()
            fixture.store.failCommitFor.clear()
            assertThat(fixture.newService().reconcilePending().single().completed().successes).hasSize(1)
            assertThat(fixture.newService().reconcilePending()).isEmpty()
            assertThat(fixture.relations).hasSize(1)
        }
    }

    @Test
    fun providerVerificationExceptionKeepsPendingAndDoesNotCountSuccess() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11)).confirmation()
        fixture.system.verifyFailure = IllegalStateException("query unavailable")

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(TrashFailure(11, TrashFailureReason.VERIFICATION_FAILED))
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(11L)
        assertThat(fixture.store.records).isEmpty()
    }

    @Test
    fun verificationCancellationPropagatesWithRecoverableConfirmedOperation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11)).confirmation()
        fixture.system.verifyFailure = CancellationException("cancelled")

        val error = runCatching { fixture.service.completeConfirmation(request.operationId, true) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.store.pending.values.single().status).isEqualTo(PendingTrashStatus.CONFIRMED)
        assertThat(fixture.store.records).isEmpty()
    }

    @Test
    fun databaseReadFailureDuringConfirmationRetainsTargetForRetry() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.store.failReadFor += 12

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(TrashFailure(12, TrashFailureReason.DATABASE_FAILED))
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(12L)
        fixture.store.failReadFor.clear()
        fixture.system.match(12, MediaStoreVerification.MATCHED)
        assertThat(fixture.newService().reconcilePending().single().completed().successes).hasSize(1)
    }

    @Test
    fun legacyMixedBatchCannotMoveSafWhenAnotherMemberNeedsConfirmation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)

        val result = fixture.service.trashMany(linkedSetOf(12, 11))

        assertThat(result.filterIsInstance<TrashResult.Success>()).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun confirmationAccountsForPreflightFailuresAlongsideSuccessfulTargets() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val inactive = fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.documents[12] = inactive.copy(indexStatus = DocumentIndexStatus.MISSING)
        val removed = fixture.store.addDocument(13, ScanSourceKind.TREE)
        fixture.store.sources.remove(removed.sourceId)
        val request = fixture.service.requestTrash(setOf(11, 12, 13, 99)).confirmation()
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(11L, 12L, 13L, 99L)
        assertThat(fixture.store.pending.values.single().eligibleTargetIds).containsExactly(11L)
        fixture.system.match(11, MediaStoreVerification.MATCHED)

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(
            TrashFailure(12, TrashFailureReason.INACTIVE),
            TrashFailure(13, TrashFailureReason.SOURCE_REMOVED),
            TrashFailure(99, TrashFailureReason.NOT_FOUND),
        )
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun existingSystemTrashRecordIsInactiveAndNeverCountedAsThisOperationSuccess() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.service.requestTrash(setOf(11))
        val existing = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        fixture.system.match(11, MediaStoreVerification.MATCHED)

        val result = fixture.newService().reconcilePending().single().completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(TrashFailure(11, TrashFailureReason.INACTIVE))
        assertThat(fixture.system.verifications).isEmpty()
        assertThat(fixture.store.records.values).containsExactly(existing)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.newService().reconcilePending()).isEmpty()
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun batchCountsOnlyNewTrashAndReportsPreexistingSystemTrashAsInactive() = runTest {
        val fixture = PendingFixture()
        val active = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.SHARED_STORAGE)
        val oldRecord = fixture.store.trash(12, TrashStorageKind.MEDIA_STORE)
        val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()
        assertThat(fixture.system.requests.single().second).containsExactly(active.uri)
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.match(12, MediaStoreVerification.MATCHED)

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(TrashFailure(12, TrashFailureReason.INACTIVE))
        assertThat(fixture.system.verifications.single().second).containsExactly(active.uri)
        assertThat(fixture.store.records.getValue(oldRecord.id)).isEqualTo(oldRecord)
        assertThat(fixture.store.records).hasSize(2)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.TRASH)
    }

    @Test
    fun trashedStatusWithoutRecordKeepsPendingInsteadOfGuessingSuccess() = runTest {
        listOf(false, true).forEach { duringVerification ->
            val fixture = PendingFixture()
            val document = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            val request = fixture.service.requestTrash(setOf(11)).confirmation()
            val markInconsistent = { fixture.store.documents[11] = document.copy(indexStatus = DocumentIndexStatus.TRASHED) }
            if (duringVerification) fixture.system.afterVerify = markInconsistent else markInconsistent()
            fixture.system.match(11, MediaStoreVerification.MATCHED)

            val result = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes).isEmpty()
            assertThat(result.failures).containsExactly(TrashFailure(11, TrashFailureReason.VERIFICATION_FAILED))
            assertThat(fixture.store.records).isEmpty()
            assertThat(fixture.store.pending.values.single().targetIds).containsExactly(11L)
            assertThat(fixture.relations).isEmpty()
        }
    }

    @Test
    fun recordCreatedByAnotherOperationDuringVerificationCannotCountAsThisSuccess() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.afterVerify = { fixture.store.trash(11, TrashStorageKind.MEDIA_STORE) }

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(TrashFailure(11, TrashFailureReason.INACTIVE))
        assertThat(fixture.store.records).hasSize(1)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun mixedRestoreAndDeleteWaitForConfirmationThenRunAppTargetOnce() = runTest {
        listOf(PendingTrashAction.RESTORE, PendingTrashAction.DELETE_FOREVER).forEach { action ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            fixture.store.addDocument(12, ScanSourceKind.TREE)
            val system = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
            val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
            val ids = setOf(system.id, app.id)
            val request = if (action == PendingTrashAction.RESTORE) fixture.service.requestRestore(ids).confirmation()
            else fixture.service.requestDeleteForever(ids).confirmation()
            assertThat(fixture.gateway.operations).isEmpty()
            fixture.system.match(11, MediaStoreVerification.MATCHED)

            val result = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes.map { it.documentId }).containsExactly(11L, 12L)
            assertThat(result.failures).isEmpty()
            assertThat(fixture.gateway.operations).hasSize(1)
            assertThat(fixture.relations).hasSize(1)
            assertThat(fixture.store.pending).isEmpty()
            assertThat(fixture.newService().reconcilePending()).isEmpty()
        }
    }

    @Test
    fun cleanupOnlyDeletesExpiredAppRecordsAndSchedulesOnceAcrossTwoTargets() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.INBOX)
        val system = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.store.trash(13, TrashStorageKind.APP_TRASH)
        fixture.store.records.replaceAll { _, record -> record.copy(expiresAt = 500) }

        assertThat(fixture.service.cleanupExpired()).isEqualTo(2)

        assertThat(fixture.store.records.keys).containsExactly(system.id)
        assertThat(fixture.gateway.operations).hasSize(2)
        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.DELETE_FOREVER)
    }

    @Test
    fun trashDirectoryFailureIsConsumedAfterOneSafAttempt() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.gateway.moveToTrashResult = runDocumentMutation {
            throw UnsupportedOperationException("cannot create trash directory")
        }
        val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(TrashFailure(12, TrashFailureReason.UNSUPPORTED))
        assertThat(fixture.store.documents.getValue(12).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.newService().reconcilePending()).isEmpty()
        assertThat(fixture.gateway.operations).hasSize(1)
        assertThat(fixture.relations).hasSize(1)
    }

    @Test
    fun vanishedMetadataBetweenVerificationAndCommitCannotBecomeSuccess() = runTest {
        PendingTrashAction.entries.forEach { action ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            val targetId = if (action == PendingTrashAction.TRASH) 11L
            else fixture.store.trash(11, TrashStorageKind.MEDIA_STORE).id
            val request = when (action) {
                PendingTrashAction.TRASH -> fixture.service.requestTrash(setOf(targetId))
                PendingTrashAction.RESTORE -> fixture.service.requestRestore(setOf(targetId))
                PendingTrashAction.DELETE_FOREVER -> fixture.service.requestDeleteForever(setOf(targetId))
            }.confirmation()
            fixture.system.match(11, MediaStoreVerification.MATCHED)
            fixture.system.afterVerify = {
                if (action == PendingTrashAction.TRASH) fixture.store.documents.remove(11)
                else fixture.store.records.remove(targetId)
            }

            val result = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes).isEmpty()
            assertThat(result.failures).containsExactly(TrashFailure(targetId, TrashFailureReason.NOT_FOUND))
            assertThat(fixture.store.pending).isEmpty()
            assertThat(fixture.relations).isEmpty()
        }
    }

    @Test
    fun verifiedMediaStoreRestoreAcceptsSameUriAlreadyMarkedActiveByScan() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val record = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val request = fixture.service.requestRestore(setOf(record.id)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.afterVerify = {
            fixture.store.documents[11] = fixture.store.documents.getValue(11).copy(
                uri = record.trashedUri,
                indexStatus = DocumentIndexStatus.ACTIVE,
            )
        }

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).isEmpty()
        assertThat(fixture.store.documents.getValue(11).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.RESTORE)
        assertThat(fixture.newService().reconcilePending()).isEmpty()
        assertThat(fixture.relations).containsExactly(RelationAnalysisReason.RESTORE)
    }

    @Test
    fun changedDocumentUriAfterVerificationIsNotMarkedTrashed() = runTest {
        val fixture = PendingFixture()
        val document = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.afterVerify = {
            fixture.store.documents[11] = document.copy(uri = "content://media/external/file/99")
        }

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes).isEmpty()
        assertThat(result.failures).containsExactly(TrashFailure(11, TrashFailureReason.VERIFICATION_FAILED))
        assertThat(fixture.store.documents.getValue(11).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(11L)
        assertThat(fixture.relations).isEmpty()
    }

    @Test
    fun sharedDocumentMarkedMissingAfterRequestIsStillVerifiedAndCommitted() = runTest {
        listOf(false, true).forEach { restart ->
            val fixture = PendingFixture()
            val document = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            val request = fixture.service.requestTrash(setOf(11)).confirmation()
            fixture.store.documents[11] = document.copy(indexStatus = DocumentIndexStatus.MISSING)
            fixture.system.match(11, MediaStoreVerification.MATCHED)

            val result = if (restart) fixture.newService().reconcilePending().single().completed()
            else fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes.map { it.documentId }).containsExactly(11L)
            assertThat(result.failures).isEmpty()
            assertThat(fixture.system.verifications.single().second).containsExactly(document.uri)
            assertThat(fixture.store.records.values.single().storageKind).isEqualTo(TrashStorageKind.MEDIA_STORE)
            assertThat(fixture.store.documents.getValue(11).indexStatus).isEqualTo(DocumentIndexStatus.TRASHED)
            assertThat(fixture.store.pending).isEmpty()
        }
    }

    @Test
    fun targetAlreadyMissingAtRequestNeverBecomesEligibleLater() = runTest {
        listOf(ScanSourceKind.SHARED_STORAGE, ScanSourceKind.TREE).forEach { sourceKind ->
            listOf(DocumentIndexStatus.MISSING, DocumentIndexStatus.ACTIVE).forEach { laterStatus ->
                val fixture = PendingFixture()
                val active = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
                val initiallyMissing = fixture.store.addDocument(12, sourceKind)
                fixture.store.documents[12] = initiallyMissing.copy(indexStatus = DocumentIndexStatus.MISSING)
                val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()
                assertThat(fixture.system.requests.single().second).containsExactly(active.uri)
                assertThat(fixture.store.pending.values.single().eligibleTargetIds).containsExactly(active.id)
                fixture.store.documents[12] = initiallyMissing.copy(indexStatus = laterStatus)
                fixture.system.match(11, MediaStoreVerification.MATCHED)
                fixture.system.match(12, MediaStoreVerification.MATCHED)

                val result = fixture.service.completeConfirmation(request.operationId, true).completed()

                assertThat(result.successes.map { it.documentId }).containsExactly(11L)
                assertThat(result.failures).containsExactly(TrashFailure(12, TrashFailureReason.INACTIVE))
                assertThat(fixture.system.verifications.single().second).containsExactly(active.uri)
                assertThat(fixture.gateway.operations).isEmpty()
                assertThat(fixture.store.records.values.map { it.documentId }).containsExactly(11L)
                assertThat(fixture.store.pending).isEmpty()
            }
        }
    }

    @Test
    fun restoreAndDeleteCannotExecuteTrashIdsThatOnlyBecameValidAfterRequest() = runTest {
        listOf(PendingTrashAction.RESTORE, PendingTrashAction.DELETE_FOREVER).forEach { action ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            fixture.store.addDocument(12, ScanSourceKind.TREE)
            val shared = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
            val lateRecord = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
            fixture.store.records.remove(lateRecord.id)
            val ids = setOf(shared.id, lateRecord.id)
            val request = when (action) {
                PendingTrashAction.RESTORE -> fixture.service.requestRestore(ids)
                else -> fixture.service.requestDeleteForever(ids)
            }.confirmation()
            assertThat(fixture.store.pending.values.single().eligibleTargetIds).containsExactly(shared.id)
            fixture.store.records[lateRecord.id] = lateRecord
            fixture.system.match(11, MediaStoreVerification.MATCHED)

            val result = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes.map { it.documentId }).containsExactly(11L)
            assertThat(result.failures).containsExactly(TrashFailure(lateRecord.id, TrashFailureReason.INACTIVE))
            assertThat(fixture.store.records.values).containsExactly(lateRecord)
            assertThat(fixture.gateway.operations).isEmpty()
            assertThat(fixture.store.pending).isEmpty()
        }
    }

    @Test
    fun reconciliationDoesNotPromoteInitiallyIneligibleTargets() = runTest {
        val fixture = PendingFixture()
        val eligible = fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val excludedShared = fixture.store.addDocument(12, ScanSourceKind.SHARED_STORAGE)
        val excludedTree = fixture.store.addDocument(13, ScanSourceKind.TREE)
        listOf(excludedShared, excludedTree).forEach { document ->
            fixture.store.documents[document.id] = document.copy(indexStatus = DocumentIndexStatus.MISSING)
        }
        fixture.service.requestTrash(setOf(11, 12, 13))
        listOf(excludedShared, excludedTree).forEach { fixture.store.documents[it.id] = it }
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.system.match(12, MediaStoreVerification.MATCHED)

        val result = fixture.newService().reconcilePending().single().completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(eligible.id)
        assertThat(result.failures).containsExactly(
            TrashFailure(excludedShared.id, TrashFailureReason.INACTIVE),
            TrashFailure(excludedTree.id, TrashFailureReason.INACTIVE),
        )
        assertThat(fixture.system.verifications.single().second).containsExactly(eligible.uri)
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.newService().reconcilePending()).isEmpty()
    }

    @Test
    fun pendingWithoutSavedEligibilityCannotActOnAnyTarget() = runTest {
        listOf(PendingTrashStatus.AWAITING_CONFIRMATION, PendingTrashStatus.CONFIRMED).forEach { status ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            fixture.store.addDocument(12, ScanSourceKind.TREE)
            fixture.store.createPending(
                PendingTrashOperationEntity(
                    action = PendingTrashAction.TRASH,
                    targetKind = PendingTrashTargetKind.DOCUMENT,
                    targetIds = setOf(11, 12),
                    status = status,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            fixture.system.match(11, MediaStoreVerification.MATCHED)

            val result = fixture.newService().reconcilePending().single().completed()

            assertThat(result.successes).isEmpty()
            assertThat(result.failures).containsExactly(
                TrashFailure(11, TrashFailureReason.INACTIVE),
                TrashFailure(12, TrashFailureReason.INACTIVE),
            )
            assertThat(fixture.system.verifications).isEmpty()
            assertThat(fixture.gateway.operations).isEmpty()
            assertThat(fixture.store.records).isEmpty()
            assertThat(fixture.store.pending).isEmpty()
        }
    }

    @Test
    fun awaitingNotMatchedCannotCancelTargetsWhosePreparationFailed() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.SHARED_STORAGE)
        fixture.service.requestTrash(setOf(11, 12))
        fixture.system.match(11, MediaStoreVerification.NOT_MATCHED)
        fixture.store.failReadFor += 12

        val result = fixture.newService().reconcilePending().single().completed()

        assertThat(result.failures).contains(TrashFailure(12, TrashFailureReason.DATABASE_FAILED))
        assertThat(fixture.store.pending.values.single().targetIds).contains(12L)
        assertThat(fixture.store.statuses).doesNotContain(PendingTrashStatus.CANCELLED)
        assertThat(fixture.gateway.operations).isEmpty()
    }

    @Test
    fun restoreCommitFailureReconcilesPersistedPhysicalResultWithoutMovingAgain() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val shared = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val restored = TrashLocation("content://test/restored/12.pdf", "content://test/restored")
        fixture.gateway.put(app.trashedUri, byteArrayOf(1, 2, 3))
        fixture.gateway.requireTrackedFiles = true
        fixture.gateway.restoreResult = Result.success(restored)
        val request = fixture.service.requestRestore(setOf(shared.id, app.id)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.store.failCommitFor += app.id

        val first = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(first.successes.map { it.documentId }).containsExactly(11L)
        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.DATABASE_FAILED))
        assertThat(fixture.gateway.operations).containsExactly("restore:${app.trashedUri}")
        assertThat(fixture.gateway.openInput(restored.documentUri).use { it.readBytes().toList() }).containsExactly(1.toByte(), 2.toByte(), 3.toByte())
        assertThat(fixture.store.records.getValue(app.id).trashedUri).isEqualTo(app.trashedUri)
        assertThat(fixture.store.documents.getValue(12).uri).isEqualTo(app.trashedUri)
        assertThat(fixture.store.documents.getValue(12).indexStatus).isEqualTo(DocumentIndexStatus.TRASHED)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(app.id)
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.RESTORED)
        fixture.store.failCommitFor.clear()

        val second = fixture.newService().reconcilePending().single().completed()

        assertThat(second.successes.map { it.documentId }).containsExactly(12L)
        assertThat(second.failures).isEmpty()
        assertThat(fixture.gateway.operations).containsExactly("restore:${app.trashedUri}")
        assertThat(fixture.store.documents.getValue(12).uri).isEqualTo(restored.documentUri)
        assertThat(fixture.store.documents.getValue(12).indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.store.targetStates).isEmpty()
    }

    @Test
    fun deleteCommitFailureReconcilesPersistedProviderAcknowledgementWithoutDeletingAgain() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.put(app.trashedUri, byteArrayOf(1, 2, 3))
        fixture.gateway.requireTrackedFiles = true
        fixture.store.failCommitFor += app.id

        val first = fixture.service.requestDeleteForever(setOf(app.id)).completed()

        assertThat(first.successes).isEmpty()
        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.DATABASE_FAILED))
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.store.records).containsKey(app.id)
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.DELETED)
        assertThat(fixture.gateway.deleteAcknowledgementInputs).containsExactly(false)
        fixture.store.failCommitFor.clear()

        val second = fixture.newService().reconcilePending().single().completed()

        assertThat(second.successes.map { it.documentId }).containsExactly(12L)
        assertThat(second.failures).isEmpty()
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.gateway.deleteAcknowledgementInputs).containsExactly(false, true).inOrder()
        assertThat(fixture.store.documents).doesNotContainKey(12L)
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.store.targetStates).isEmpty()
    }

    @Test
    fun deleteAcknowledgementSurvivesPostDeleteVerificationFailureAndRetriesOnlyVerification() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.deleteVerifiedAbsent = false

        val first = fixture.service.requestDeleteForever(setOf(app.id)).completed()

        assertThat(first.successes).isEmpty()
        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.VERIFICATION_FAILED))
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.DELETED)
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        fixture.gateway.deleteVerifiedAbsent = true

        val second = fixture.newService().reconcilePending().single().completed()

        assertThat(second.successes.map { it.documentId }).containsExactly(12L)
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.gateway.deleteAcknowledgementInputs).containsExactly(false, true).inOrder()
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun repeatedDeleteRequestReusesStartedOperationWithoutDeletingOldUriAgain() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.deleteResult = Result.failure(IllegalStateException("delete result unknown"))

        fixture.service.requestDeleteForever(setOf(app.id)).completed()
        val repeated = fixture.service.requestDeleteForever(setOf(app.id)).completed()

        assertThat(repeated.failures).containsExactly(
            TrashFailure(app.id, TrashFailureReason.VERIFICATION_FAILED),
        )
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.store.pending).hasSize(1)
        assertThat(fixture.store.createdOperations).hasSize(1)
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.STARTED)
    }

    @Test
    fun repeatedCleanupReusesStartedDeleteOperationWithoutDeletingAgain() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.store.records[app.id] = app.copy(expiresAt = 0)
        fixture.gateway.deleteResult = Result.failure(IllegalStateException("delete result unknown"))

        assertThat(fixture.service.cleanupExpired()).isEqualTo(0)
        assertThat(fixture.service.cleanupExpired()).isEqualTo(0)

        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.store.pending).hasSize(1)
        assertThat(fixture.store.createdOperations).hasSize(1)
    }

    @Test
    fun reconciledDeleteAcknowledgementNeverDeletesAReusedOldUri() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.store.failCommitFor += app.id

        fixture.service.requestDeleteForever(setOf(app.id)).completed()
        fixture.store.documents[13] = fixture.store.documents.getValue(13).copy(uri = app.trashedUri)
        fixture.store.failCommitFor.clear()
        fixture.gateway.deleteVerifiedAbsent = false

        val reconciled = fixture.newService().reconcilePending().single().completed()

        assertThat(reconciled.failures).containsExactly(
            TrashFailure(app.id, TrashFailureReason.VERIFICATION_FAILED),
        )
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.store.documents).containsKey(13L)
        assertThat(fixture.store.documents.getValue(13).uri).isEqualTo(app.trashedUri)
        assertThat(fixture.store.records).containsKey(app.id)
        assertThat(fixture.store.pending).hasSize(1)
    }

    @Test
    fun preflightPermissionFailureClearsStartedStateAndSecondRequestReusesOperation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.deletePreflightFailure = SecurityException("permission temporarily unavailable")

        val first = fixture.service.requestDeleteForever(setOf(app.id)).completed()

        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.VERIFICATION_FAILED))
        assertThat(fixture.gateway.operations).isEmpty()
        assertThat(fixture.store.targetStates).isEmpty()
        assertThat(fixture.store.pending).hasSize(1)
        fixture.gateway.deletePreflightFailure = null

        val second = fixture.service.requestDeleteForever(setOf(app.id)).completed()

        assertThat(second.successes.map { it.documentId }).containsExactly(12L)
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.store.createdOperations).hasSize(1)
        assertThat(fixture.store.pending).isEmpty()
    }

    @Test
    fun mixedRecoveredSuccessAndNewSystemTargetAreAggregatedAfterConfirmation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.SHARED_STORAGE)
        val recovered = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val system = fixture.store.trash(13, TrashStorageKind.MEDIA_STORE)
        fixture.store.failCommitFor += recovered.id
        fixture.service.requestDeleteForever(setOf(recovered.id))
        fixture.store.failCommitFor.clear()

        val request = fixture.service.requestDeleteForever(setOf(recovered.id, system.id)).confirmation()
        fixture.system.match(13, MediaStoreVerification.MATCHED)
        val completed = fixture.service.completeConfirmation(request.operationId, approved = true).completed()

        assertThat(request.targetCount).isEqualTo(2)
        assertThat(completed.successes.map { it.targetId }).containsExactly(recovered.id, system.id)
        assertThat(completed.successes.map { it.documentId }).containsExactly(12L, 13L)
        assertThat(completed.failures).isEmpty()
    }

    @Test
    fun mixedUnresolvedFailureAndNewSystemTargetAreAggregatedAfterConfirmation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.SHARED_STORAGE)
        val unresolved = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val system = fixture.store.trash(13, TrashStorageKind.MEDIA_STORE)
        fixture.gateway.deleteResult = Result.failure(IllegalStateException("delete outcome unknown"))
        fixture.service.requestDeleteForever(setOf(unresolved.id))
        fixture.gateway.deleteResult = Result.success(Unit)

        val request = fixture.service.requestDeleteForever(setOf(unresolved.id, system.id)).confirmation()
        fixture.system.match(13, MediaStoreVerification.MATCHED)
        val completed = fixture.service.completeConfirmation(request.operationId, approved = true).completed()

        assertThat(request.targetCount).isEqualTo(2)
        assertThat(completed.successes.map { it.targetId }).containsExactly(system.id)
        assertThat(completed.failures).containsExactly(
            TrashFailure(unresolved.id, TrashFailureReason.VERIFICATION_FAILED),
        )
        assertThat(fixture.gateway.operations).containsExactly("delete:${unresolved.trashedUri}")
    }

    @Test
    fun recoveredSuccessSeedSurvivesServiceRestartBeforeSystemConfirmation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.store.addDocument(13, ScanSourceKind.SHARED_STORAGE)
        val recovered = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val system = fixture.store.trash(13, TrashStorageKind.MEDIA_STORE)
        fixture.store.failCommitFor += recovered.id
        fixture.service.requestDeleteForever(setOf(recovered.id))
        fixture.store.failCommitFor.clear()

        val request = fixture.service.requestDeleteForever(setOf(recovered.id, system.id)).confirmation()
        fixture.system.match(13, MediaStoreVerification.MATCHED)
        val completed = fixture.newService().completeConfirmation(request.operationId, approved = true).completed()

        assertThat(completed.successes.map { it.targetId }).containsExactly(recovered.id, system.id)
        assertThat(completed.failures).isEmpty()
    }

    @Test
    fun startedRestoreJournalStaysUnknownAndNeverBlindlyMovesOldUriAgain() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.restoreResult = Result.failure(IllegalStateException("provider disconnected during restore"))

        val first = fixture.service.requestRestore(setOf(app.id)).completed()

        assertThat(first.successes).isEmpty()
        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.PROVIDER_REJECTED))
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.STARTED)
        assertThat(fixture.gateway.operations).containsExactly("restore:${app.trashedUri}")

        repeat(2) {
            val retry = fixture.newService().reconcilePending().single().completed()
            assertThat(retry.successes).isEmpty()
            assertThat(retry.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.VERIFICATION_FAILED))
        }

        assertThat(fixture.gateway.operations).containsExactly("restore:${app.trashedUri}")
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(app.id)
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.STARTED)
    }

    @Test
    fun cancellationWhileWritingRestoredJournalPropagatesAfterSafetyCompensation() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.store.restoredJournalFailure = CancellationException("database cancelled")
        fixture.gateway.moveToTrashResult = Result.success(
            TrashLocation("content://test/trash/compensated-12.pdf", "content://test/trash"),
        )

        val error = runCatching { fixture.service.requestRestore(setOf(app.id)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.gateway.operations).hasSize(2)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(app.id)
    }

    @Test
    fun awaitingWithOnlyUnexecutedSafTargetsCancelsAndDoesNotLoop() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.service.requestTrash(setOf(11, 12))
        fixture.store.documents.remove(11)

        assertThat(fixture.newService().reconcilePending()).containsExactly(TrashOperationResult.Cancelled)
        assertThat(fixture.newService().reconcilePending()).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
    }

    @Test
    fun cancelledResidueIsRemovedByReconciliationWithoutExecutingAnything() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        val request = fixture.service.requestTrash(setOf(11)).confirmation()
        fixture.store.updatePendingStatus(request.operationId, PendingTrashStatus.CANCELLED, 2L)

        fixture.newService().reconcilePending()

        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.system.verifications).isEmpty()
        assertThat(fixture.gateway.operations).isEmpty()
    }

    @Test
    fun safMetadataDisappearanceDuringPhysicalRestoreOrDeleteIsNeverCounted() = runTest {
        listOf(PendingTrashAction.RESTORE, PendingTrashAction.DELETE_FOREVER).forEach { action ->
            val fixture = PendingFixture()
            fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
            fixture.store.addDocument(12, ScanSourceKind.TREE)
            val shared = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
            val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
            val disappear = {
                fixture.store.records.remove(app.id)
                fixture.store.documents.remove(12)
                Unit
            }
            fixture.gateway.afterRestore = disappear
            fixture.gateway.afterDelete = disappear
            val request = when (action) {
                PendingTrashAction.RESTORE -> fixture.service.requestRestore(setOf(shared.id, app.id))
                else -> fixture.service.requestDeleteForever(setOf(shared.id, app.id))
            }.confirmation()
            fixture.system.match(11, MediaStoreVerification.NOT_MATCHED)

            val result = fixture.service.completeConfirmation(request.operationId, true).completed()

            assertThat(result.successes).isEmpty()
            assertThat(result.failures).contains(TrashFailure(app.id, TrashFailureReason.NOT_FOUND))
            assertThat(fixture.relations).isEmpty()
            assertThat(fixture.store.pending).isEmpty()
        }
    }

    @Test
    fun failedCompensationLocationWriteRetainsPendingAndNeverClaimsRestoreSuccess() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val shared = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        val request = fixture.service.requestRestore(setOf(shared.id, app.id)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.store.failLocationWrite = true
        fixture.store.restoredJournalFailure = IllegalStateException("restored journal write failed")
        fixture.gateway.moveToTrashResult = Result.success(TrashLocation("content://test/trash/new-12.pdf", "content://test/trash"))

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.DATABASE_FAILED))
        assertThat(fixture.gateway.operations).hasSize(2)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(app.id)
        assertThat(fixture.store.records.getValue(app.id).trashedUri).isEqualTo(app.trashedUri)
        assertThat(fixture.store.targetStates.values.single().stage).isEqualTo(PendingTrashTargetStage.COMPENSATED)

        fixture.store.failLocationWrite = false
        fixture.store.restoredJournalFailure = null

        val reconciled = fixture.newService().reconcilePending().single().completed()

        assertThat(reconciled.successes.map { it.documentId }).containsExactly(12L)
        assertThat(reconciled.failures).isEmpty()
        assertThat(fixture.gateway.operations.last()).isEqualTo("restore:content://test/trash/new-12.pdf")
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.store.targetStates).isEmpty()
    }

    @Test
    fun deleteAfterDatabaseFailureRetriesTheAlreadyAbsentFileThenCommitsMetadata() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        val shared = fixture.store.trash(11, TrashStorageKind.MEDIA_STORE)
        val app = fixture.store.trash(12, TrashStorageKind.APP_TRASH)
        fixture.gateway.put(app.trashedUri, byteArrayOf(1, 2, 3))
        val request = fixture.service.requestDeleteForever(setOf(shared.id, app.id)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)
        fixture.store.failCommitFor += app.id

        val first = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(first.failures).containsExactly(TrashFailure(app.id, TrashFailureReason.DATABASE_FAILED))
        assertThat(runCatching { fixture.gateway.openInput(app.trashedUri) }.isFailure).isTrue()
        assertThat(fixture.store.records).containsKey(app.id)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(app.id)
        fixture.store.failCommitFor.clear()

        val second = fixture.newService().reconcilePending().single().completed()

        assertThat(second.successes.map { it.documentId }).containsExactly(12L)
        assertThat(second.failures).isEmpty()
        assertThat(fixture.store.records).isEmpty()
        assertThat(fixture.store.documents).isEmpty()
        assertThat(fixture.store.pending).isEmpty()
        assertThat(fixture.gateway.operations).containsExactly("delete:${app.trashedUri}")
        assertThat(fixture.gateway.deleteAcknowledgementInputs).containsExactly(false, true).inOrder()
    }

    @Test
    fun uncertainProviderFailureRetainsPendingInsteadOfDiscardingRecoveryContext() = runTest {
        val fixture = PendingFixture()
        fixture.store.addDocument(11, ScanSourceKind.SHARED_STORAGE)
        fixture.store.addDocument(12, ScanSourceKind.TREE)
        fixture.gateway.moveToTrashResult = Result.failure(IllegalStateException("provider disconnected during move"))
        val request = fixture.service.requestTrash(setOf(11, 12)).confirmation()
        fixture.system.match(11, MediaStoreVerification.MATCHED)

        val result = fixture.service.completeConfirmation(request.operationId, true).completed()

        assertThat(result.successes.map { it.documentId }).containsExactly(11L)
        assertThat(result.failures.map { it.targetId }).containsExactly(12L)
        assertThat(fixture.store.pending.values.single().targetIds).containsExactly(12L)
    }
}

private fun TrashOperationResult.confirmation(): SystemConfirmationRequest =
    (this as TrashOperationResult.RequiresConfirmation).request

private fun TrashOperationResult.completed(): TrashOperationResult.Completed =
    this as TrashOperationResult.Completed

private class PendingFixture(private val apiLevel: Int = 30) {
    val store = PendingMemoryStore()
    val gateway = FakeDocumentGateway()
    val system = FakeSystemTrashGateway()
    val relations = mutableListOf<RelationAnalysisReason>()
    val service = newService()

    fun newService() = TrashService(
        store = store,
        gateway = gateway,
        systemGateway = system,
        planner = TrashRequestPlanner(apiLevel),
        clock = { 1_000L },
        relationRequest = relations::add,
    )
}

private class FakeSystemTrashGateway : SystemTrashGateway {
    var beforeCreate: () -> Unit = {}
    var createFailure: Exception? = null
    var verifyFailure: Exception? = null
    var afterVerify: () -> Unit = {}
    val requests = mutableListOf<Pair<PendingTrashAction, List<String>>>()
    val verifications = mutableListOf<Pair<PendingTrashAction, List<String>>>()
    private val verification = mutableMapOf<String, MediaStoreVerification>()

    override fun createRequest(action: PendingTrashAction, uris: List<String>): Result<IntentSender> {
        beforeCreate()
        requests += action to uris
        createFailure?.let { return Result.failure(it) }
        // The controller only transports this Android handle; a JVM test cannot create a PendingIntent.
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return Result.success((field.get(null) as Unsafe).allocateInstance(IntentSender::class.java) as IntentSender)
    }

    override suspend fun verify(action: PendingTrashAction, uris: List<String>): Map<String, MediaStoreVerification> {
        verifications += action to uris
        verifyFailure?.let { throw it }
        return uris.associateWith { verification[it] ?: MediaStoreVerification.UNKNOWN }.also { afterVerify() }
    }

    fun match(id: Long, result: MediaStoreVerification) {
        verification["content://media/external/file/$id"] = result
    }
}

private class PendingMemoryStore : TrashStore {
    val documents = linkedMapOf<Long, DocumentEntity>()
    val sources = linkedMapOf<Long, ScanSourceEntity>()
    val records = linkedMapOf<Long, TrashRecordEntity>()
    val pending = linkedMapOf<Long, PendingTrashOperationEntity>()
    val targetStates = linkedMapOf<Pair<Long, Long>, PendingTrashTargetStateEntity>()
    val createdOperations = mutableListOf<PendingTrashOperationEntity>()
    val targetStateWrites = mutableListOf<PendingTrashTargetStateEntity>()
    val statuses = mutableListOf<PendingTrashStatus>()
    val failCommitFor = mutableSetOf<Long>()
    val failReadFor = mutableSetOf<Long>()
    var failCreate = false
    var failLocationWrite = false
    var restoredJournalFailure: Throwable? = null
    private var nextRecordId = 101L
    private var nextPendingId = 501L

    fun addDocument(id: Long, kind: ScanSourceKind): DocumentEntity {
        val sourceId = id + 1_000
        sources[sourceId] = ScanSourceEntity(
            id = sourceId, treeUri = "content://test/tree/$sourceId", displayName = "Source $id", sourceKind = kind,
        )
        return DocumentEntity(
            id = id,
            uri = if (kind == ScanSourceKind.SHARED_STORAGE) "content://media/external/file/$id" else "content://test/$id.pdf",
            displayName = "$id.pdf", normalizedName = "$id", mimeType = "application/pdf", extension = "pdf",
            sizeBytes = 3, modifiedAt = 1, sourceId = sourceId, parentUri = "content://test/parent/$id", lastSeenScanId = "scan",
        ).also { documents[id] = it }
    }

    fun trash(id: Long, kind: TrashStorageKind): TrashRecordEntity {
        val document = documents.getValue(id)
        return insertTrash(
            document,
            if (kind == TrashStorageKind.MEDIA_STORE) TrashLocation(document.uri, document.parentUri)
            else TrashLocation("content://test/trash/${document.displayName}", "content://test/trash"),
            kind, 1_000, 2_000,
        ).let { records.getValue(it) }
    }

    override fun observeEntries(): Flow<List<TrashEntry>> = MutableStateFlow(records.values.map { TrashEntry(it, documents.getValue(it.documentId)) })
    override suspend fun findDocument(documentId: Long): DocumentEntity? {
        check(documentId !in failReadFor) { "database read failed" }
        return documents[documentId]
    }
    override suspend fun findSource(sourceId: Long): ScanSourceEntity? = sources[sourceId]
    override suspend fun findEntry(trashId: Long): TrashEntry? = records[trashId]?.let { record -> documents[record.documentId]?.let { TrashEntry(record, it) } }
    override suspend fun findEntryByDocumentId(documentId: Long): TrashEntry? = records.values.firstOrNull { it.documentId == documentId }?.let { findEntry(it.id) }
    override suspend fun recordTrash(document: DocumentEntity, location: TrashLocation, deletedAt: Long, expiresAt: Long): Long =
        insertTrash(document, location, TrashStorageKind.APP_TRASH, deletedAt, expiresAt)

    override suspend fun recordMediaStoreTrash(document: DocumentEntity, deletedAt: Long, expiresAt: Long): Long =
        records.values.firstOrNull { it.documentId == document.id }?.id
            ?: insertTrash(document, TrashLocation(document.uri, document.parentUri), TrashStorageKind.MEDIA_STORE, deletedAt, expiresAt)

    private fun insertTrash(document: DocumentEntity, location: TrashLocation, kind: TrashStorageKind, deletedAt: Long, expiresAt: Long): Long {
        check(records.values.none { it.documentId == document.id })
        val id = nextRecordId++
        records[id] = TrashRecordEntity(
            id = id, documentId = document.id, originalUri = document.uri, trashedUri = location.documentUri,
            trashedParentUri = location.parentUri, originalParentUri = document.parentUri,
            deletedAt = deletedAt, expiresAt = expiresAt, storageKind = kind,
        )
        documents[document.id] = document.copy(uri = location.documentUri, parentUri = location.parentUri, indexStatus = DocumentIndexStatus.TRASHED)
        return id
    }

    override suspend fun restoreMetadata(
        expected: TrashEntry,
        restoredUri: String,
        restoredParentUri: String,
    ): TrashMetadataChange {
        val current = findEntry(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        val record = records.remove(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        documents[record.documentId]?.let {
            documents[it.id] = it.copy(uri = restoredUri, parentUri = restoredParentUri, indexStatus = DocumentIndexStatus.ACTIVE)
        }
        return TrashMetadataChange.CHANGED
    }

    override suspend fun restoreVerifiedMediaStoreMetadata(expected: TrashEntry): TrashMetadataChange {
        val current = findEntry(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        if (!sameVerifiedMediaStoreRestoreTarget(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        val record = records.remove(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        documents[record.documentId] = current.document.copy(
            uri = record.originalUri,
            parentUri = record.originalParentUri,
            indexStatus = DocumentIndexStatus.ACTIVE,
        )
        return TrashMetadataChange.CHANGED
    }

    override suspend fun updateAppTrashLocation(trashId: Long, location: TrashLocation) {
        check(!failLocationWrite) { "compensation location write failed" }
        val record = records.getValue(trashId)
        check(record.storageKind == TrashStorageKind.APP_TRASH)
        records[trashId] = record.copy(trashedUri = location.documentUri, trashedParentUri = location.parentUri)
        documents[record.documentId] = documents.getValue(record.documentId).copy(
            uri = location.documentUri, parentUri = location.parentUri, indexStatus = DocumentIndexStatus.TRASHED,
        )
    }

    override suspend fun deleteMetadataAndDocument(expected: TrashEntry): TrashMetadataChange {
        val current = findEntry(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        val record = records.remove(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        documents.remove(record.documentId)
        return TrashMetadataChange.CHANGED
    }

    override suspend fun findExpired(now: Long): List<TrashEntry> = records.values.filter { it.expiresAt <= now }.mapNotNull { findEntry(it.id) }
    override suspend fun createPending(operation: PendingTrashOperationEntity): Long {
        check(!failCreate) { "database failed" }
        createdOperations += operation
        return nextPendingId++.also { pending[it] = operation.copy(id = it) }
    }
    override suspend fun findPending(id: Long): PendingTrashOperationEntity? = pending[id]
    override suspend fun findUnfinishedPending(): List<PendingTrashOperationEntity> = pending.values.filter {
        it.status == PendingTrashStatus.AWAITING_CONFIRMATION || it.status == PendingTrashStatus.CONFIRMED ||
            it.status == PendingTrashStatus.CANCELLED
    }
    override suspend fun updatePending(operation: PendingTrashOperationEntity) { pending[operation.id] = operation }
    override suspend fun updatePendingStatus(id: Long, status: PendingTrashStatus, updatedAt: Long) {
        statuses += status
        pending[id]?.let { pending[id] = it.copy(status = status, updatedAt = updatedAt) }
    }
    override suspend fun removePending(id: Long) {
        pending.remove(id)
        targetStates.keys.removeAll { it.first == id }
    }
    override suspend fun cancelPending(id: Long, updatedAt: Long) {
        statuses += PendingTrashStatus.CANCELLED
        removePending(id)
    }
    override suspend fun findTargetStates(operationId: Long): List<PendingTrashTargetStateEntity> =
        targetStates.values.filter { it.operationId == operationId }.sortedBy { it.targetId }
    override suspend fun saveTargetState(state: PendingTrashTargetStateEntity) {
        if (state.stage == PendingTrashTargetStage.RESTORED) restoredJournalFailure?.let { throw it }
        targetStateWrites += state
        targetStates[state.operationId to state.targetId] = state
    }
    override suspend fun clearTargetState(operationId: Long, targetId: Long) {
        targetStates.remove(operationId to targetId)
    }
    override suspend fun applyCompensatedTargetState(
        state: PendingTrashTargetStateEntity,
        expected: TrashEntry,
    ): TrashMetadataChange {
        check(!failLocationWrite) { "compensation location write failed" }
        val current = findEntry(expected.record.id) ?: return TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return TrashMetadataChange.LOCATION_CHANGED
        updateAppTrashLocation(state.targetId, TrashLocation(state.documentUri, state.parentUri))
        targetStates.remove(state.operationId to state.targetId)
        return TrashMetadataChange.CHANGED
    }
    override suspend fun <T> commitPendingTarget(operationId: Long, targetId: Long, updatedAt: Long, commit: suspend () -> T): T {
        val beforeDocuments = documents.toMap()
        val beforeRecords = records.toMap()
        val beforePending = pending.toMap()
        val beforeTargetStates = targetStates.toMap()
        try {
            val operation = checkNotNull(pending[operationId])
            check(targetId in operation.targetIds)
            val result = commit()
            targetStates.remove(operationId to targetId)
            val remaining = operation.targetIds - targetId
            if (remaining.isEmpty()) pending.remove(operationId)
            else pending[operationId] = operation.copy(
                targetIds = remaining,
                eligibleTargetIds = operation.eligibleTargetIds - targetId,
                updatedAt = updatedAt,
            )
            check(targetId !in failCommitFor) { "transaction failed" }
            return result
        } catch (error: Throwable) {
            documents.clear(); documents.putAll(beforeDocuments)
            records.clear(); records.putAll(beforeRecords)
            pending.clear(); pending.putAll(beforePending)
            targetStates.clear(); targetStates.putAll(beforeTargetStates)
            throw error
        }
    }
}
