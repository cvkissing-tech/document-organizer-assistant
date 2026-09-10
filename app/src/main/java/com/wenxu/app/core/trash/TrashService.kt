package com.wenxu.app.core.trash

import android.net.Uri
import android.os.Build
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashSeedFailure
import com.wenxu.app.core.database.entity.PendingTrashSeedSuccess
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashTargetKind
import com.wenxu.app.core.database.entity.PendingTrashTargetStage
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.storage.DocumentGateway
import com.wenxu.app.core.storage.DocumentDeleteNotAttemptedException
import com.wenxu.app.core.storage.DocumentOperationUnchangedException
import com.wenxu.app.core.storage.DocumentStateUnknownException
import com.wenxu.app.core.storage.TrashLocation
import java.util.concurrent.TimeUnit
import com.wenxu.app.core.relations.RelationAnalysisReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TrashResult {
    data class Success(val trashId: Long) : TrashResult
    data class Unsupported(val reason: String) : TrashResult
    data class Failed(val reason: String) : TrashResult
}

interface TrashController {
    // Safe defaults keep unmigrated controllers source-compatible during the UI transition.
    suspend fun requestTrash(documentIds: Set<Long>): TrashOperationResult {
        val ids = documentIds.sorted()
        val results = trashMany(documentIds)
        return TrashOperationResult.Completed(
            successes = ids.zip(results).mapNotNull { (id, result) ->
                (result as? TrashResult.Success)?.let { TrashSuccess(id, id, it.trashId) }
            },
            failures = ids.zip(results).filter { it.second !is TrashResult.Success }.map { (id, _) ->
                TrashFailure(id, TrashFailureReason.PROVIDER_REJECTED)
            },
        )
    }
    suspend fun requestRestore(trashIds: Set<Long>, fallbackParentUri: String? = null): TrashOperationResult {
        val ids = trashIds.sorted()
        val results = ids.map { restore(it, fallbackParentUri) }
        return TrashOperationResult.Completed(
            successes = ids.zip(results).mapNotNull { (id, result) ->
                result.getOrNull()?.let { TrashSuccess(id, it) }
            },
            failures = ids.zip(results).filter { it.second.isFailure }.map { (id, _) ->
                TrashFailure(id, TrashFailureReason.PROVIDER_REJECTED)
            },
        )
    }
    suspend fun requestDeleteForever(trashIds: Set<Long>): TrashOperationResult {
        val ids = trashIds.sorted()
        val results = ids.map { id -> deleteForever(id) }
        return TrashOperationResult.Completed(
            successes = ids.zip(results).filter { it.second.isSuccess }.map { (id, _) -> TrashSuccess(id, id) },
            failures = ids.zip(results).filter { it.second.isFailure }.map { (id, _) ->
                TrashFailure(id, TrashFailureReason.PROVIDER_REJECTED)
            },
        )
    }
    suspend fun completeConfirmation(operationId: Long, approved: Boolean): TrashOperationResult =
        TrashOperationResult.Completed(emptyList(), listOf(TrashFailure(operationId, TrashFailureReason.NOT_FOUND)))
    suspend fun reconcilePending(): List<TrashOperationResult> = emptyList()

    suspend fun trash(documentId: Long): TrashResult
    suspend fun trashMany(documentIds: Set<Long>): List<TrashResult>
    suspend fun restore(trashId: Long, fallbackParentUri: String? = null): Result<Long>
    suspend fun deleteForever(trashId: Long): Result<Unit>
}

private fun unsupportedOperation(ids: Set<Long>) = TrashOperationResult.Completed(
    emptyList(), ids.sorted().map { TrashFailure(it, TrashFailureReason.UNSUPPORTED) },
)

class TrashService(
    private val store: TrashStore,
    private val gateway: DocumentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val relationRequest: suspend (RelationAnalysisReason) -> Unit = {},
    private val systemGateway: SystemTrashGateway? = null,
    private val planner: TrashRequestPlanner = TrashRequestPlanner(Build.VERSION.SDK_INT),
) : TrashController {
    private val operationMutex = Mutex()

    override suspend fun requestTrash(documentIds: Set<Long>): TrashOperationResult = operationMutex.withLock {
        requestOperation(PendingTrashAction.TRASH, documentIds, null)
    }

    override suspend fun requestRestore(trashIds: Set<Long>, fallbackParentUri: String?): TrashOperationResult =
        operationMutex.withLock { requestOperation(PendingTrashAction.RESTORE, trashIds, fallbackParentUri) }

    override suspend fun requestDeleteForever(trashIds: Set<Long>): TrashOperationResult = operationMutex.withLock {
        requestDeleteForeverLocked(trashIds)
    }

    private suspend fun requestDeleteForeverLocked(trashIds: Set<Long>): TrashOperationResult {
        val existing = try {
            store.findUnfinishedPending().filter { operation ->
                operation.action == PendingTrashAction.DELETE_FOREVER &&
                    operation.targetIds.any(trashIds::contains)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failedIds(trashIds, TrashFailureReason.DATABASE_FAILED)
        }
        if (existing.isEmpty()) return requestOperation(PendingTrashAction.DELETE_FOREVER, trashIds, null)

        val reconciled = existing.map { operation -> processPending(operation) }
        val completed = reconciled.filterIsInstance<TrashOperationResult.Completed>()
        val recoveredSuccesses = completed.flatMap { it.successes }.filter { it.targetId in trashIds }
        val remainingClaims = try {
            store.findUnfinishedPending()
                .asSequence()
                .filter { it.action == PendingTrashAction.DELETE_FOREVER }
                .flatMap { it.targetIds.asSequence() }
                .filter(trashIds::contains)
                .toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failedIds(trashIds, TrashFailureReason.DATABASE_FAILED)
        }
        val recoveredSuccessIds = recoveredSuccesses.mapTo(mutableSetOf()) { it.targetId }
        val freshIds = trashIds - remainingClaims - recoveredSuccessIds
        val unresolvedFailures = remainingClaims.sorted().map { targetId ->
            completed.asSequence().flatMap { it.failures.asSequence() }
                .lastOrNull { it.targetId == targetId }
                ?: TrashFailure(targetId, TrashFailureReason.VERIFICATION_FAILED)
        }
        if (freshIds.isEmpty()) {
            return TrashOperationResult.Completed(
                recoveredSuccesses.sortedBy { it.targetId },
                unresolvedFailures,
            )
        }
        return when (val fresh = requestOperation(
            PendingTrashAction.DELETE_FOREVER,
            freshIds,
            null,
            seedSuccesses = recoveredSuccesses,
            seedFailures = unresolvedFailures,
        )) {
            is TrashOperationResult.Completed -> TrashOperationResult.Completed(
                successes = (recoveredSuccesses + fresh.successes).distinctBy { it.targetId }.sortedBy { it.targetId },
                failures = (unresolvedFailures + fresh.failures).distinctBy { it.targetId }.sortedBy { it.targetId },
            )
            else -> fresh
        }
    }

    private suspend fun requestOperation(
        action: PendingTrashAction,
        ids: Set<Long>,
        fallbackParentUri: String?,
        seedSuccesses: List<TrashSuccess> = emptyList(),
        seedFailures: List<TrashFailure> = emptyList(),
    ): TrashOperationResult {
        val prepared = prepareTargets(action, ids)
        val requiresImmediateJournal = action == PendingTrashAction.RESTORE ||
            action == PendingTrashAction.DELETE_FOREVER
        if (prepared.system.isEmpty() && (!requiresImmediateJournal || prepared.immediate.isEmpty())) {
            return withSeedResults(
                seedSuccesses,
                seedFailures,
                executeBatch(action, prepared.immediate, prepared.failures, fallbackParentUri),
            )
        }
        val now = clock()
        val operation = PendingTrashOperationEntity(
            action = action,
            targetKind = if (action == PendingTrashAction.TRASH) PendingTrashTargetKind.DOCUMENT else PendingTrashTargetKind.TRASH_RECORD,
            // Keep invalid selections too, so the confirmation result can account for the entire request.
            targetIds = ids.sorted().toSet(),
            eligibleTargetIds = (prepared.system + prepared.immediate).map { it.id }.sorted().toSet(),
            seedSuccesses = seedSuccesses.map { success ->
                PendingTrashSeedSuccess(success.targetId, success.documentId, success.trashId)
            },
            seedFailures = seedFailures.map { failure ->
                PendingTrashSeedFailure(failure.targetId, failure.reason)
            },
            fallbackParentUri = fallbackParentUri,
            status = if (prepared.system.isEmpty()) PendingTrashStatus.CONFIRMED else PendingTrashStatus.AWAITING_CONFIRMATION,
            createdAt = now,
            updatedAt = now,
        )
        val operationId = try {
            store.createPending(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return withSeedResults(
                seedSuccesses,
                seedFailures,
                prepared.failed(TrashFailureReason.DATABASE_FAILED),
            )
        }
        if (prepared.system.isEmpty()) return processPending(operation.copy(id = operationId), prepared)
        val request = try {
            systemGateway?.createRequest(action, prepared.system.map { it.uri })
                ?: Result.failure(UnsupportedOperationException("此操作需要系统确认"))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelPending(operation.copy(id = operationId)) }
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        request.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                withContext(NonCancellable) { cancelPending(operation.copy(id = operationId)) }
                throw error
            }
            // Mark cancelled before removal, so even a failed cleanup cannot execute a SAF member later.
            cancelPending(operation.copy(id = operationId))
            return withSeedResults(
                seedSuccesses,
                seedFailures,
                prepared.failed(providerFailure(error)),
            )
        }
        return TrashOperationResult.RequiresConfirmation(
            SystemConfirmationRequest(
                operationId,
                request.getOrThrow(),
                action,
                (ids + seedSuccesses.map { it.targetId } + seedFailures.map { it.targetId }).size,
            ),
        )
    }

    override suspend fun completeConfirmation(operationId: Long, approved: Boolean): TrashOperationResult =
        operationMutex.withLock {
            val operation = store.findPending(operationId)
                ?: return@withLock missingOperation(operationId)
            if (operation.status == PendingTrashStatus.CANCELLED) return@withLock cancelPending(operation)
            if (operation.status == PendingTrashStatus.COMPLETED) return@withLock missingOperation(operationId)
            if (!approved) return@withLock cancelPending(operation)
            try {
                store.updatePendingStatus(operationId, PendingTrashStatus.CONFIRMED, clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock withSeedResults(
                    operation,
                    failedIds(operation.targetIds, TrashFailureReason.DATABASE_FAILED),
                )
            }
            processPending(operation.copy(status = PendingTrashStatus.CONFIRMED))
        }

    override suspend fun reconcilePending(): List<TrashOperationResult> = operationMutex.withLock {
        store.findUnfinishedPending().map { operation -> processPending(operation) }
    }

    private suspend fun processPending(
        operation: PendingTrashOperationEntity,
        initiallyPrepared: PreparedBatch? = null,
    ): TrashOperationResult = withSeedResults(
        operation,
        processPendingTargets(operation, initiallyPrepared),
    )

    private suspend fun processPendingTargets(
        operation: PendingTrashOperationEntity,
        initiallyPrepared: PreparedBatch? = null,
    ): TrashOperationResult {
        if (operation.status == PendingTrashStatus.CANCELLED) return cancelPending(operation)
        val eligibleIds = operation.targetIds.intersect(operation.eligibleTargetIds)
        val targetStates = if (initiallyPrepared != null) emptyMap() else try {
            store.findTargetStates(operation.id).associateBy { it.targetId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failedIds(operation.targetIds, TrashFailureReason.DATABASE_FAILED)
        }
        val prepared = initiallyPrepared ?: prepareTargets(operation.action, eligibleIds, pending = true, targetStates).let { batch ->
            batch.copy(
                failures = batch.failures + (operation.targetIds - eligibleIds).sorted().map { id ->
                    ineligibleTargetFailure(operation.action, id)
                },
            )
        }
        val verified = if (prepared.system.isEmpty()) emptyMap() else try {
            systemGateway?.verify(operation.action, prepared.system.map { it.uri }).orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        val unresolvedPreparation = prepared.failures.any {
            it.reason == TrashFailureReason.DATABASE_FAILED || it.reason == TrashFailureReason.VERIFICATION_FAILED
        }
        if (operation.status == PendingTrashStatus.AWAITING_CONFIRMATION && prepared.system.isEmpty() && prepared.immediate.isNotEmpty() &&
            !unresolvedPreparation && targetStates.isEmpty()
        ) {
            // No surviving system evidence can authorize the still-unexecuted SAF part of this batch.
            return cancelPending(operation)
        }
        if (operation.status == PendingTrashStatus.AWAITING_CONFIRMATION && prepared.system.isNotEmpty() &&
            !unresolvedPreparation && targetStates.isEmpty() &&
            prepared.system.all { verified[it.uri] == MediaStoreVerification.NOT_MATCHED }
        ) {
            return cancelPending(operation)
        }
        val confirmed = operation.status == PendingTrashStatus.CONFIRMED ||
            prepared.system.any { verified[it.uri] == MediaStoreVerification.MATCHED } ||
            targetStates.values.any { it.stage != PendingTrashTargetStage.STARTED }
        if (confirmed && operation.status != PendingTrashStatus.CONFIRMED) {
            try {
                // Persist evidence of approval before consuming the last system target in a mixed batch.
                store.updatePendingStatus(operation.id, PendingTrashStatus.CONFIRMED, clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failedIds(operation.targetIds, TrashFailureReason.DATABASE_FAILED)
            }
        }
        val successes = mutableListOf<TrashSuccess>()
        val failures = mutableListOf<TrashFailure>()
        var changed = false
        try {
            prepared.failures.forEach { failure ->
                failures += when (failure.reason) {
                    TrashFailureReason.DATABASE_FAILED, TrashFailureReason.VERIFICATION_FAILED -> failure
                    else -> resolveFailure(operation.id, failure)
                }
            }
            prepared.system.forEach { target ->
                currentCoroutineContext().ensureActive()
                when (verified[target.uri] ?: MediaStoreVerification.UNKNOWN) {
                    MediaStoreVerification.MATCHED -> {
                        try {
                            when (val outcome = commitSystemTarget(operation, target)) {
                                is TargetOutcome.Success -> {
                                    successes += outcome.success
                                    changed = changed || outcome.changed
                                }
                                is TargetOutcome.Failure -> failures += outcome.failure
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: VerifiedTargetChanged) {
                            failures += TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED)
                        } catch (_: Exception) {
                            failures += TrashFailure(target.id, TrashFailureReason.DATABASE_FAILED)
                        }
                    }
                    MediaStoreVerification.NOT_MATCHED -> failures += resolveFailure(
                        operation.id, TrashFailure(target.id, TrashFailureReason.PROVIDER_REJECTED),
                    )
                    MediaStoreVerification.UNKNOWN -> failures += TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED)
                }
            }
            prepared.immediate.forEach { target ->
                currentCoroutineContext().ensureActive()
                if (!confirmed) {
                    failures += TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED)
                } else {
                    val outcome = withContext(NonCancellable) {
                        executeImmediate(operation.action, target, operation.fallbackParentUri, operation.id)
                    }
                    if (outcome is TargetOutcome.Success) {
                        successes += outcome.success
                        changed = changed || outcome.changed
                    } else if (outcome is TargetOutcome.Failure) {
                        failures += if (outcome.canResolve) resolveFailure(operation.id, outcome.failure)
                        else outcome.failure
                    }
                }
            }
        } finally {
            if (changed) requestRelationsAfterCommit(operation.action.relationReason())
        }
        currentCoroutineContext().ensureActive()
        return TrashOperationResult.Completed(successes.sortedBy { it.targetId }, failures.sortedBy { it.targetId })
    }

    private fun withSeedResults(
        operation: PendingTrashOperationEntity,
        result: TrashOperationResult,
    ): TrashOperationResult = withSeedResults(
        seedSuccesses = operation.seedSuccesses.map { seed ->
            TrashSuccess(seed.targetId, seed.documentId, seed.trashId)
        },
        seedFailures = operation.seedFailures.map { seed ->
            TrashFailure(seed.targetId, seed.reason)
        },
        result = result,
    )

    private fun withSeedResults(
        seedSuccesses: List<TrashSuccess>,
        seedFailures: List<TrashFailure>,
        result: TrashOperationResult,
    ): TrashOperationResult {
        if (result !is TrashOperationResult.Completed) return result
        val successes = (seedSuccesses + result.successes)
            .distinctBy { it.targetId }.sortedBy { it.targetId }
        val successIds = successes.mapTo(mutableSetOf()) { it.targetId }
        val failures = (seedFailures + result.failures).filterNot { it.targetId in successIds }
            .distinctBy { it.targetId }
            .sortedBy { it.targetId }
        return TrashOperationResult.Completed(successes, failures)
    }

    private suspend fun commitSystemTarget(
        operation: PendingTrashOperationEntity,
        target: PreparedTarget,
    ): TargetOutcome = withContext(NonCancellable) {
        store.commitPendingTarget(operation.id, target.id, clock()) {
            // Verification can suspend while another feature removes a source or its metadata.
            val currentDocument = store.findDocument(target.document.id)
                ?: return@commitPendingTarget TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.NOT_FOUND))
            val currentEntry = if (operation.action == PendingTrashAction.TRASH) null else {
                store.findEntry(target.id)
                    ?: return@commitPendingTarget TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.NOT_FOUND))
            }
            if (currentDocument.uri != target.document.uri ||
                (currentEntry != null && currentEntry.record.trashedUri != target.uri)
            ) {
                // Roll back target consumption: the URI just verified no longer identifies this metadata.
                throw VerifiedTargetChanged()
            }
            when (operation.action) {
                PendingTrashAction.TRASH -> {
                    val existing = store.findEntryByDocumentId(target.document.id)
                    if (existing != null) {
                        // This operation creates metadata and consumes its target atomically. An existing
                        // record with an unconsumed target belongs to an earlier or concurrent operation.
                        return@commitPendingTarget TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.INACTIVE))
                    }
                    if (currentDocument.indexStatus == DocumentIndexStatus.TRASHED) throw VerifiedTargetChanged()
                    val now = clock()
                    val trashId = store.recordMediaStoreTrash(currentDocument, now, now + RETENTION_MILLIS)
                    TargetOutcome.Success(TrashSuccess(target.id, target.document.id, trashId), changed = true)
                }
                PendingTrashAction.RESTORE -> {
                    val entry = checkNotNull(currentEntry)
                    metadataOutcome(target, store.restoreVerifiedMediaStoreMetadata(entry))
                }
                PendingTrashAction.DELETE_FOREVER -> {
                    metadataOutcome(target, store.deleteMetadataAndDocument(checkNotNull(currentEntry)))
                }
            }
        }
    }

    private suspend fun resolveFailure(operationId: Long, failure: TrashFailure): TrashFailure = try {
        withContext(NonCancellable) { store.commitPendingTarget(operationId, failure.targetId, clock()) {} }
        failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TrashFailure(failure.targetId, TrashFailureReason.DATABASE_FAILED)
    }

    private suspend fun cancelPending(operation: PendingTrashOperationEntity): TrashOperationResult = try {
        withContext(NonCancellable) {
            // Status and deletion share one Room transaction; delete also cascades target journals.
            store.cancelPending(operation.id, clock())
        }
        TrashOperationResult.Cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failedIds(operation.targetIds, TrashFailureReason.DATABASE_FAILED)
    }

    private suspend fun ineligibleTargetFailure(action: PendingTrashAction, id: Long): TrashFailure {
        // Eligibility is fixed at request time. Later scan/source changes cannot promote a rejected
        // selection into this operation, even when its current URI happens to pass verification.
        val reason = try {
            if (action == PendingTrashAction.TRASH) {
                val document = store.findDocument(id)
                when {
                    document == null -> TrashFailureReason.NOT_FOUND
                    store.findSource(document.sourceId) == null -> TrashFailureReason.SOURCE_REMOVED
                    else -> TrashFailureReason.INACTIVE
                }
            } else {
                val entry = store.findEntry(id)
                when {
                    entry == null -> TrashFailureReason.NOT_FOUND
                    action == PendingTrashAction.RESTORE && entry.record.storageKind == TrashStorageKind.APP_TRASH &&
                        store.findSource(entry.document.sourceId) == null -> TrashFailureReason.SOURCE_REMOVED
                    else -> TrashFailureReason.INACTIVE
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The persisted exclusion is sufficient to reject it without any filesystem change.
            TrashFailureReason.INACTIVE
        }
        return TrashFailure(id, reason)
    }

    private suspend fun prepareTargets(
        action: PendingTrashAction,
        ids: Set<Long>,
        pending: Boolean = false,
        targetStates: Map<Long, PendingTrashTargetStateEntity> = emptyMap(),
    ): PreparedBatch {
        val targets = mutableListOf<PreparedTarget>()
        val failures = mutableListOf<TrashFailure>()
        ids.sorted().forEach { id ->
            try {
                val targetState = targetStates[id]
                if (targetState?.stage == PendingTrashTargetStage.STARTED) {
                    // A provider may already have moved the file. Never retry an unverified old URI.
                    failures += TrashFailure(id, TrashFailureReason.VERIFICATION_FAILED)
                    return@forEach
                }
                if (action == PendingTrashAction.TRASH) {
                    val document = store.findDocument(id)
                    if (document == null) {
                        failures += TrashFailure(id, TrashFailureReason.NOT_FOUND)
                        return@forEach
                    }
                    if (!pending && document.indexStatus != DocumentIndexStatus.ACTIVE) {
                        failures += TrashFailure(id, TrashFailureReason.INACTIVE)
                        return@forEach
                    }
                    if (document.indexStatus == DocumentIndexStatus.TRASHED) {
                        failures += TrashFailure(
                            id,
                            if (store.findEntryByDocumentId(id) != null) TrashFailureReason.INACTIVE
                            else TrashFailureReason.VERIFICATION_FAILED,
                        )
                        return@forEach
                    }
                    val source = store.findSource(document.sourceId)
                    when {
                        source == null -> failures += TrashFailure(id, TrashFailureReason.SOURCE_REMOVED)
                        source.sourceKind != ScanSourceKind.SHARED_STORAGE && document.indexStatus != DocumentIndexStatus.ACTIVE ->
                            failures += TrashFailure(id, TrashFailureReason.INACTIVE)
                        else -> {
                            // A scan may mark a system-trashed row MISSING before the confirmation callback arrives.
                            // Only this shared-storage MISSING state may bypass the initial ACTIVE requirement.
                            targets += PreparedTarget(id, document, source.sourceKind, source)
                        }
                    }
                } else {
                    val entry = store.findEntry(id)
                    if (entry == null) failures += TrashFailure(id, TrashFailureReason.NOT_FOUND)
                    else {
                        val needsSource = entry.record.storageKind == TrashStorageKind.APP_TRASH &&
                            (action == PendingTrashAction.DELETE_FOREVER ||
                                action == PendingTrashAction.RESTORE && targetState?.stage != PendingTrashTargetStage.RESTORED)
                        val source = if (needsSource) store.findSource(entry.document.sourceId) else null
                        if (needsSource && source == null) failures += TrashFailure(id, TrashFailureReason.SOURCE_REMOVED)
                        else targets += PreparedTarget(
                            id, entry.document,
                            if (entry.record.storageKind == TrashStorageKind.MEDIA_STORE) ScanSourceKind.SHARED_STORAGE else ScanSourceKind.TREE,
                            source = source, entry = entry, targetState = targetState,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += TrashFailure(id, TrashFailureReason.DATABASE_FAILED)
            }
        }
        val plan = planner.partition(targets.map { TrashTarget(it.id, it.uri, it.sourceKind) })
        val byId = targets.associateBy { it.id }
        return PreparedBatch(
            system = plan.system.map { byId.getValue(it.id) },
            immediate = plan.immediate.map { byId.getValue(it.id) },
            failures = failures + plan.unsupported.map { TrashFailure(it.id, TrashFailureReason.UNSUPPORTED) },
        )
    }

    private suspend fun executeBatch(
        action: PendingTrashAction,
        targets: List<PreparedTarget>,
        initialFailures: List<TrashFailure>,
        fallbackParentUri: String?,
    ): TrashOperationResult.Completed {
        val successes = mutableListOf<TrashSuccess>()
        val failures = initialFailures.toMutableList()
        var changed = false
        try {
            targets.forEach { target ->
                currentCoroutineContext().ensureActive()
                when (val outcome = withContext(NonCancellable) { executeImmediate(action, target, fallbackParentUri) }) {
                    is TargetOutcome.Success -> {
                        successes += outcome.success
                        changed = changed || outcome.changed
                    }
                    is TargetOutcome.Failure -> failures += outcome.failure
                }
            }
        } finally {
            if (changed) requestRelationsAfterCommit(action.relationReason())
        }
        currentCoroutineContext().ensureActive()
        return TrashOperationResult.Completed(successes.sortedBy { it.targetId }, failures.sortedBy { it.targetId })
    }

    private suspend fun executeImmediate(
        action: PendingTrashAction,
        target: PreparedTarget,
        fallbackParentUri: String?,
        operationId: Long? = null,
    ): TargetOutcome {
        if (action == PendingTrashAction.RESTORE) {
            return executeJournaledRestore(checkNotNull(operationId), target, fallbackParentUri)
        }
        if (action == PendingTrashAction.DELETE_FOREVER) {
            return executeJournaledDelete(checkNotNull(operationId), target)
        }
        val document = target.document
        val physical = try {
            when (action) {
                PendingTrashAction.TRASH -> gateway.moveToTrash(
                    sourceTreeUri = checkNotNull(target.source).treeUri,
                    documentUri = document.uri, sourceParentUri = document.parentUri,
                    displayName = document.displayName, mimeType = document.mimeType, expectedSize = document.sizeBytes,
                ).getOrThrow()
                PendingTrashAction.RESTORE -> error("restore requires a target journal")
                PendingTrashAction.DELETE_FOREVER -> error("delete requires a target journal")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return TargetOutcome.Failure(
                TrashFailure(target.id, providerFailure(error)),
                canResolve = error is DocumentOperationUnchangedException,
            )
        }
        return try {
            commitMetadata(operationId, target.id) {
                val current = store.findDocument(document.id)
                    ?: return@commitMetadata TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.NOT_FOUND))
                if (current.uri != document.uri || current.indexStatus != DocumentIndexStatus.ACTIVE) throw VerifiedTargetChanged()
                val trashId = when (action) {
                    PendingTrashAction.TRASH -> {
                        val now = clock()
                        store.recordTrash(document, checkNotNull(physical), now, now + RETENTION_MILLIS)
                    }
                    PendingTrashAction.RESTORE -> {
                        error("restore requires a target journal")
                    }
                    PendingTrashAction.DELETE_FOREVER -> {
                        error("delete was handled above")
                    }
                }
                TargetOutcome.Success(TrashSuccess(target.id, document.id, trashId), changed = true)
            }
        } catch (error: Exception) {
            compensateMetadataFailure(action, target, physical)
            if (error is CancellationException) throw error
            TargetOutcome.Failure(TrashFailure(target.id, if (error is VerifiedTargetChanged) TrashFailureReason.VERIFICATION_FAILED else TrashFailureReason.DATABASE_FAILED))
        }
    }

    private suspend fun executeJournaledDelete(
        operationId: Long,
        target: PreparedTarget,
    ): TargetOutcome {
        val entry = checkNotNull(target.entry)
        val existingState = target.targetState
        if (existingState?.stage == PendingTrashTargetStage.STARTED) {
            return TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED))
        }
        val started = existingState ?: PendingTrashTargetStateEntity(
            operationId = operationId,
            targetId = target.id,
            stage = PendingTrashTargetStage.STARTED,
            documentUri = entry.record.trashedUri,
            parentUri = entry.record.trashedParentUri,
        ).also { state ->
            try {
                store.saveTargetState(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.DATABASE_FAILED))
            }
        }
        try {
            val deleteOutcome = gateway.deleteDocumentVerified(
                uri = started.documentUri,
                sourceTreeUri = checkNotNull(target.source).treeUri,
                parentUri = started.parentUri,
                deletePreviouslyAcknowledged = started.stage == PendingTrashTargetStage.DELETED,
            ).getOrThrow()
            if (started.stage != PendingTrashTargetStage.DELETED &&
                (deleteOutcome.providerAcknowledged || deleteOutcome.verifiedAbsent)
            ) {
                store.saveTargetState(started.copy(stage = PendingTrashTargetStage.DELETED))
            }
            if (!deleteOutcome.verifiedAbsent) throw DocumentStateUnknownException()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is DocumentDeleteNotAttemptedException) {
                return try {
                    withContext(NonCancellable) { store.clearTargetState(operationId, target.id) }
                    TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED))
                } catch (_: Exception) {
                    TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.DATABASE_FAILED))
                }
            }
            return TargetOutcome.Failure(
                TrashFailure(target.id, providerFailure(error)),
                canResolve = error is DocumentOperationUnchangedException,
            )
        }
        return try {
            commitMetadata(operationId, target.id) {
                metadataOutcome(target, store.deleteMetadataAndDocument(entry))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TargetOutcome.Failure(TrashFailure(
                target.id,
                if (error is VerifiedTargetChanged) TrashFailureReason.VERIFICATION_FAILED
                else TrashFailureReason.DATABASE_FAILED,
            ))
        }
    }

    private suspend fun executeJournaledRestore(
        operationId: Long,
        target: PreparedTarget,
        fallbackParentUri: String?,
    ): TargetOutcome {
        val entry = checkNotNull(target.entry)
        val state = target.targetState
        if (state?.stage == PendingTrashTargetStage.STARTED) {
            return TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.VERIFICATION_FAILED))
        }
        if (state?.stage == PendingTrashTargetStage.COMPENSATED) {
            val applied = try {
                withContext(NonCancellable) { store.applyCompensatedTargetState(state, entry) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.DATABASE_FAILED))
            }
            if (applied != TrashMetadataChange.CHANGED) return unresolvedMetadataOutcome(target, applied)
            val relocated = entry.copy(
                record = entry.record.copy(trashedUri = state.documentUri, trashedParentUri = state.parentUri),
                document = entry.document.copy(uri = state.documentUri, parentUri = state.parentUri),
            )
            return executeJournaledRestore(operationId, target.copy(document = relocated.document, entry = relocated, targetState = null), fallbackParentUri)
        }
        val restored = if (state?.stage == PendingTrashTargetStage.RESTORED) {
            TrashLocation(state.documentUri, state.parentUri)
        } else {
            val started = PendingTrashTargetStateEntity(
                operationId, target.id, PendingTrashTargetStage.STARTED, entry.record.trashedUri, entry.record.trashedParentUri,
            )
            try {
                withContext(NonCancellable) { store.saveTargetState(started) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.DATABASE_FAILED))
            }
            val physical = try {
                // Avoid losing a returned provider URI to prompt coroutine cancellation.
                withContext(NonCancellable) {
                    fallbackParentUri?.let {
                        gateway.persistTreePermission(Uri.parse(it)).exceptionOrNull()?.let { cause ->
                            if (cause is CancellationException) throw cause
                            throw DocumentOperationUnchangedException(cause)
                        }
                    }
                    gateway.restoreFromTrash(
                        entry.record.trashedUri, entry.record.trashedParentUri,
                        fallbackParentUri ?: entry.record.originalParentUri,
                        target.document.displayName, target.document.mimeType, target.document.sizeBytes,
                    ).getOrThrow().also { location ->
                        try {
                            store.saveTargetState(started.copy(
                                stage = PendingTrashTargetStage.RESTORED, documentUri = location.documentUri, parentUri = location.parentUri,
                            ))
                        } catch (cancelled: CancellationException) {
                            compensateUnjournaledRestore(started, target, location)
                            throw cancelled
                        } catch (error: Exception) {
                            compensateUnjournaledRestore(started, target, location)
                            throw RestoreJournalWriteFailed(error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return TargetOutcome.Failure(
                    TrashFailure(target.id, if (error is RestoreJournalWriteFailed) TrashFailureReason.DATABASE_FAILED else providerFailure(error)),
                    canResolve = error is DocumentOperationUnchangedException,
                )
            }
            physical
        }
        return try {
            commitMetadata(operationId, target.id) {
                metadataOutcome(target, store.restoreMetadata(entry, restored.documentUri, restored.parentUri))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // RESTORED is durable. Retry metadata only; compensating here would create a second move.
            TargetOutcome.Failure(TrashFailure(target.id, if (error is VerifiedTargetChanged) TrashFailureReason.VERIFICATION_FAILED else TrashFailureReason.DATABASE_FAILED))
        }
    }

    private suspend fun compensateUnjournaledRestore(
        started: PendingTrashTargetStateEntity,
        target: PreparedTarget,
        restored: TrashLocation,
    ) {
        val compensated = try {
            gateway.moveToTrash(
                checkNotNull(target.source).treeUri, restored.documentUri, restored.parentUri,
                target.document.displayName, target.document.mimeType, target.document.sizeBytes,
            ).getOrThrow()
        } catch (_: Exception) {
            // STARTED remains unresolved: no future execution may use the old URI.
            return
        }
        val state = started.copy(
            stage = PendingTrashTargetStage.COMPENSATED, documentUri = compensated.documentUri, parentUri = compensated.parentUri,
        )
        try {
            store.saveTargetState(state)
        } catch (_: Exception) {
            // If journal persistence fails, the existing two-location metadata transaction is another
            // chance to preserve C. If both fail, STARTED remains UNKNOWN; there is no safe blind retry.
            runCatching { store.applyCompensatedTargetState(state, checkNotNull(target.entry)) }
            return
        }
        // If this transaction fails, COMPENSATED(C) remains durable and reconciliation can finish it.
        runCatching { store.applyCompensatedTargetState(state, checkNotNull(target.entry)) }
    }

    private fun metadataOutcome(target: PreparedTarget, change: TrashMetadataChange): TargetOutcome = when (change) {
        TrashMetadataChange.CHANGED -> TargetOutcome.Success(TrashSuccess(target.id, target.document.id, target.id), changed = true)
        TrashMetadataChange.NOT_FOUND -> TargetOutcome.Failure(TrashFailure(target.id, TrashFailureReason.NOT_FOUND))
        TrashMetadataChange.LOCATION_CHANGED -> throw VerifiedTargetChanged()
    }

    private fun unresolvedMetadataOutcome(target: PreparedTarget, change: TrashMetadataChange) = TargetOutcome.Failure(
        TrashFailure(target.id, if (change == TrashMetadataChange.NOT_FOUND) TrashFailureReason.NOT_FOUND else TrashFailureReason.VERIFICATION_FAILED),
        canResolve = change == TrashMetadataChange.NOT_FOUND,
    )

    private class RestoreJournalWriteFailed(cause: Throwable) : Exception(cause)

    private suspend fun compensateMetadataFailure(
        action: PendingTrashAction,
        target: PreparedTarget,
        physical: TrashLocation,
    ) = withContext(NonCancellable) {
        try {
            val document = target.document
            when (action) {
                PendingTrashAction.TRASH -> gateway.restoreFromTrash(
                    trashedUri = physical.documentUri, trashedParentUri = physical.parentUri,
                    targetParentUri = document.parentUri, displayName = document.displayName,
                    mimeType = document.mimeType, expectedSize = document.sizeBytes,
                ).getOrThrow()
                PendingTrashAction.RESTORE -> error("restore uses its own journal")
                PendingTrashAction.DELETE_FOREVER -> Unit
            }
        } catch (_: Exception) {
            // The original operation remains DATABASE_FAILED, including when compensation cannot be saved.
        }
    }

    private suspend fun <T> commitMetadata(operationId: Long?, targetId: Long, commit: suspend () -> T): T =
        withContext(NonCancellable) {
            if (operationId == null) commit() else store.commitPendingTarget(operationId, targetId, clock(), commit)
        }

    private data class PreparedTarget(
        val id: Long,
        val document: DocumentEntity,
        val sourceKind: ScanSourceKind,
        val source: ScanSourceEntity? = null,
        val entry: TrashEntry? = null,
        val targetState: PendingTrashTargetStateEntity? = null,
    ) {
        val uri: String get() = entry?.record?.trashedUri ?: document.uri
    }

    private data class PreparedBatch(
        val system: List<PreparedTarget>,
        val immediate: List<PreparedTarget>,
        val failures: List<TrashFailure>,
    ) {
        fun failed(reason: TrashFailureReason) = TrashOperationResult.Completed(
            emptyList(), (failures + (system + immediate).map { TrashFailure(it.id, reason) }).sortedBy { it.targetId },
        )
    }

    private sealed interface TargetOutcome {
        data class Success(val success: TrashSuccess, val changed: Boolean) : TargetOutcome
        data class Failure(val failure: TrashFailure, val canResolve: Boolean = false) : TargetOutcome
    }

    private class VerifiedTargetChanged : Exception()

    private fun providerFailure(error: Throwable): TrashFailureReason = when (error) {
        is DocumentDeleteNotAttemptedException -> TrashFailureReason.VERIFICATION_FAILED
        is DocumentStateUnknownException -> TrashFailureReason.VERIFICATION_FAILED
        is DocumentOperationUnchangedException -> providerFailure(checkNotNull(error.cause))
        is UnsupportedOperationException -> TrashFailureReason.UNSUPPORTED
        else -> TrashFailureReason.PROVIDER_REJECTED
    }

    private fun missingOperation(operationId: Long) =
        failedIds(setOf(operationId), TrashFailureReason.NOT_FOUND)

    private fun failedIds(ids: Set<Long>, reason: TrashFailureReason) =
        TrashOperationResult.Completed(emptyList(), ids.sorted().map { TrashFailure(it, reason) })

    private fun PendingTrashAction.relationReason(): RelationAnalysisReason = when (this) {
        PendingTrashAction.TRASH -> RelationAnalysisReason.TRASH
        PendingTrashAction.RESTORE -> RelationAnalysisReason.RESTORE
        PendingTrashAction.DELETE_FOREVER -> RelationAnalysisReason.DELETE_FOREVER
    }

    override suspend fun trash(documentId: Long): TrashResult = trashMany(setOf(documentId)).single()

    override suspend fun trashMany(documentIds: Set<Long>): List<TrashResult> {
        val completed = legacyResult(requestTrash(documentIds), documentIds)
        val successes = completed.successes.associateBy { it.targetId }
        val failures = completed.failures.associateBy { it.targetId }
        return documentIds.sorted().map { id ->
            successes[id]?.let { TrashResult.Success(checkNotNull(it.trashId)) }
                ?: when (val reason = failures.getValue(id).reason) {
                    TrashFailureReason.UNSUPPORTED, TrashFailureReason.SOURCE_REMOVED -> TrashResult.Unsupported(reason.legacyMessage())
                    else -> TrashResult.Failed(reason.legacyMessage())
                }
        }
    }

    override suspend fun restore(trashId: Long, fallbackParentUri: String?): Result<Long> {
        val completed = legacyResult(requestRestore(setOf(trashId), fallbackParentUri), setOf(trashId))
        return completed.successes.singleOrNull()?.let { Result.success(it.documentId) }
            ?: Result.failure(IllegalStateException(completed.failures.single().reason.legacyMessage()))
    }

    override suspend fun deleteForever(trashId: Long): Result<Unit> {
        val completed = legacyResult(requestDeleteForever(setOf(trashId)), setOf(trashId))
        return if (completed.successes.isNotEmpty()) Result.success(Unit)
        else Result.failure(IllegalStateException(completed.failures.single().reason.legacyMessage()))
    }

    private suspend fun legacyResult(result: TrashOperationResult, ids: Set<Long>): TrashOperationResult.Completed =
        when (result) {
            is TrashOperationResult.Completed -> result
            is TrashOperationResult.RequiresConfirmation -> {
                // Legacy UI cannot launch this request. Cancel the whole batch without doing physical work.
                completeConfirmation(result.request.operationId, approved = false)
                failedIds(ids, TrashFailureReason.UNSUPPORTED)
            }
            TrashOperationResult.Cancelled -> failedIds(ids, TrashFailureReason.UNSUPPORTED)
        }

    private fun TrashFailureReason.legacyMessage(): String = when (this) {
        TrashFailureReason.NOT_FOUND -> "找不到这份文档或回收站记录"
        TrashFailureReason.INACTIVE -> "这份文档当前不能操作"
        TrashFailureReason.SOURCE_REMOVED -> "原扫描位置已被移除"
        TrashFailureReason.UNSUPPORTED -> "此位置不支持该操作或需要系统确认"
        TrashFailureReason.PROVIDER_REJECTED -> "文件提供方未完成操作"
        TrashFailureReason.VERIFICATION_FAILED -> "暂时无法核验操作结果"
        TrashFailureReason.DATABASE_FAILED -> "无法保存操作记录"
    }

    suspend fun cleanupExpired(): Int = operationMutex.withLock {
        val ids = store.findExpired(clock())
            .filter { it.record.storageKind == TrashStorageKind.APP_TRASH }
            .mapTo(mutableSetOf()) { it.record.id }
        if (ids.isEmpty()) return@withLock 0
        when (val result = requestDeleteForeverLocked(ids)) {
            is TrashOperationResult.Completed -> result.successes.size
            else -> 0
        }
    }

    private suspend fun requestRelationsAfterCommit(reason: RelationAnalysisReason) {
        withContext(NonCancellable) {
            try {
                withTimeout(RELATION_REQUEST_TIMEOUT_MILLIS) {
                    relationRequest(reason)
                }
            } catch (_: Exception) {
                // 文件操作已提交；分析排队失败或被取消不能伪装成文件操作失败。
            }
        }
    }

    private companion object {
        val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(30)
        const val RELATION_REQUEST_TIMEOUT_MILLIS = 5_000L
    }
}
