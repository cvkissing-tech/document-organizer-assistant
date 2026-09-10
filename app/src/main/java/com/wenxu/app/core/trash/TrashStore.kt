package com.wenxu.app.core.trash

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashTargetStage
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.database.entity.TrashCategorySnapshotEntity
import com.wenxu.app.core.database.entity.TrashRecordEntity
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.relations.similarityGroupFingerprint
import com.wenxu.app.core.storage.TrashLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TrashEntry(
    val record: TrashRecordEntity,
    val document: DocumentEntity,
)

enum class TrashMetadataChange { CHANGED, NOT_FOUND, LOCATION_CHANGED }

interface TrashStore {
    fun observeEntries(): Flow<List<TrashEntry>>
    suspend fun findDocument(documentId: Long): DocumentEntity?
    suspend fun findSource(sourceId: Long): ScanSourceEntity?
    suspend fun findEntry(trashId: Long): TrashEntry?
    suspend fun findEntryByDocumentId(documentId: Long): TrashEntry?
    suspend fun createPending(operation: PendingTrashOperationEntity): Long
    suspend fun findPending(id: Long): PendingTrashOperationEntity?
    suspend fun findUnfinishedPending(): List<PendingTrashOperationEntity>
    suspend fun updatePending(operation: PendingTrashOperationEntity)
    suspend fun updatePendingStatus(id: Long, status: PendingTrashStatus, updatedAt: Long)
    suspend fun removePending(id: Long)
    suspend fun cancelPending(id: Long, updatedAt: Long)
    suspend fun findTargetStates(operationId: Long): List<PendingTrashTargetStateEntity>
    suspend fun saveTargetState(state: PendingTrashTargetStateEntity)
    suspend fun clearTargetState(operationId: Long, targetId: Long)
    /** Persist a known compensated location and clear its journal, without consuming the target. */
    suspend fun applyCompensatedTargetState(state: PendingTrashTargetStateEntity, expected: TrashEntry): TrashMetadataChange
    /** Only metadata writes belong in [commit]; they and target consumption share one transaction. */
    suspend fun <T> commitPendingTarget(
        operationId: Long,
        targetId: Long,
        updatedAt: Long,
        commit: suspend () -> T,
    ): T
    suspend fun recordMediaStoreTrash(document: DocumentEntity, deletedAt: Long, expiresAt: Long): Long
    suspend fun recordTrash(
        document: DocumentEntity,
        location: TrashLocation,
        deletedAt: Long,
        expiresAt: Long,
    ): Long
    suspend fun restoreMetadata(expected: TrashEntry, restoredUri: String, restoredParentUri: String): TrashMetadataChange
    suspend fun restoreVerifiedMediaStoreMetadata(expected: TrashEntry): TrashMetadataChange
    suspend fun updateAppTrashLocation(trashId: Long, location: TrashLocation)
    suspend fun deleteMetadataAndDocument(expected: TrashEntry): TrashMetadataChange
    suspend fun findExpired(now: Long): List<TrashEntry>
}

class RoomTrashStore(private val database: WenxuDatabase) : TrashStore {
    private val documentDao = database.documentDao()
    private val sourceDao = database.sourceDao()
    private val categoryDao = database.categoryDao()
    private val relationDao = database.relationDao()
    private val relationAnalysisDao = database.relationAnalysisDao()
    private val trashDao = database.trashDao()
    private val pendingDao = database.pendingTrashOperationDao()
    private val targetStateDao = database.pendingTrashTargetStateDao()
    private val identityAliasDao = database.documentIdentityAliasDao()

    override fun observeEntries(): Flow<List<TrashEntry>> = combine(
        trashDao.observeAll(),
        documentDao.observeAllIndexed(),
    ) { records, documents ->
        val byId = documents.associateBy { it.id }
        records.mapNotNull { record ->
            byId[record.documentId]?.let { TrashEntry(record, it) }
        }
    }

    override suspend fun findDocument(documentId: Long): DocumentEntity? = documentDao.findById(documentId)

    override suspend fun findSource(sourceId: Long): ScanSourceEntity? = sourceDao.findById(sourceId)

    override suspend fun findEntry(trashId: Long): TrashEntry? {
        val record = trashDao.findById(trashId) ?: return null
        val document = documentDao.findById(record.documentId) ?: return null
        return TrashEntry(record, document)
    }

    override suspend fun findEntryByDocumentId(documentId: Long): TrashEntry? =
        trashDao.findByDocumentId(documentId)?.let { findEntry(it.id) }

    override suspend fun createPending(operation: PendingTrashOperationEntity): Long = pendingDao.insert(operation)

    override suspend fun findPending(id: Long): PendingTrashOperationEntity? = pendingDao.findById(id)

    override suspend fun findUnfinishedPending(): List<PendingTrashOperationEntity> = pendingDao.findUnfinished()

    override suspend fun updatePending(operation: PendingTrashOperationEntity) {
        check(pendingDao.update(operation) == 1) { "待处理操作不存在" }
    }

    override suspend fun updatePendingStatus(id: Long, status: PendingTrashStatus, updatedAt: Long) {
        check(pendingDao.updateStatus(id, status, updatedAt) == 1) { "待处理操作不存在" }
    }

    override suspend fun removePending(id: Long) {
        pendingDao.deleteById(id)
    }

    override suspend fun cancelPending(id: Long, updatedAt: Long) = database.withTransaction {
        check(pendingDao.updateStatus(id, PendingTrashStatus.CANCELLED, updatedAt) == 1) {
            "待处理操作不存在"
        }
        pendingDao.deleteById(id)
        Unit
    }

    override suspend fun findTargetStates(operationId: Long): List<PendingTrashTargetStateEntity> =
        targetStateDao.findForOperation(operationId)

    override suspend fun saveTargetState(state: PendingTrashTargetStateEntity) = database.withTransaction {
        val operation = checkNotNull(pendingDao.findById(state.operationId))
        check(operation.status == PendingTrashStatus.CONFIRMED)
        check(state.targetId in operation.targetIds && state.targetId in operation.eligibleTargetIds)
        targetStateDao.upsert(state)
    }

    override suspend fun clearTargetState(operationId: Long, targetId: Long) {
        targetStateDao.deleteTarget(operationId, targetId)
    }

    override suspend fun applyCompensatedTargetState(
        state: PendingTrashTargetStateEntity,
        expected: TrashEntry,
    ): TrashMetadataChange = database.withTransaction {
        check(state.stage == PendingTrashTargetStage.COMPENSATED)
        val operation = checkNotNull(pendingDao.findById(state.operationId))
        check(operation.status == PendingTrashStatus.CONFIRMED)
        check(state.targetId in operation.targetIds && state.targetId in operation.eligibleTargetIds)
        check(state.targetId == expected.record.id)
        val current = findEntry(expected.record.id) ?: return@withTransaction TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return@withTransaction TrashMetadataChange.LOCATION_CHANGED
        updateAppTrashLocation(state.targetId, TrashLocation(state.documentUri, state.parentUri))
        targetStateDao.deleteTarget(state.operationId, state.targetId)
        TrashMetadataChange.CHANGED
    }

    override suspend fun <T> commitPendingTarget(
        operationId: Long,
        targetId: Long,
        updatedAt: Long,
        commit: suspend () -> T,
    ): T = database.withTransaction {
        val operation = checkNotNull(pendingDao.findById(operationId)) { "待处理操作不存在" }
        check(targetId in operation.targetIds) { "目标已完成" }
        val result = commit()
        targetStateDao.deleteTarget(operationId, targetId)
        val remaining = operation.targetIds - targetId
        if (remaining.isEmpty()) {
            pendingDao.updateStatus(operationId, PendingTrashStatus.COMPLETED, updatedAt)
            pendingDao.deleteById(operationId)
        } else {
            updatePending(
                operation.copy(
                    targetIds = remaining,
                    eligibleTargetIds = operation.eligibleTargetIds - targetId,
                    updatedAt = updatedAt,
                ),
            )
        }
        result
    }

    override suspend fun recordMediaStoreTrash(
        document: DocumentEntity,
        deletedAt: Long,
        expiresAt: Long,
    ): Long = database.withTransaction {
        trashDao.findByDocumentId(document.id)?.let { return@withTransaction it.id }
        insertTrash(document, TrashLocation(document.uri, document.parentUri), deletedAt, expiresAt, TrashStorageKind.MEDIA_STORE)
    }

    override suspend fun recordTrash(
        document: DocumentEntity,
        location: TrashLocation,
        deletedAt: Long,
        expiresAt: Long,
    ): Long = database.withTransaction {
        check(trashDao.findByDocumentId(document.id) == null) { "文档已经在回收站中" }
        insertTrash(document, location, deletedAt, expiresAt, TrashStorageKind.APP_TRASH)
    }

    private suspend fun insertTrash(
        document: DocumentEntity,
        location: TrashLocation,
        deletedAt: Long,
        expiresAt: Long,
        storageKind: TrashStorageKind,
    ): Long {
        val categories = categoryDao.listForDocument(document.id)
        val trashId = trashDao.insert(
            TrashRecordEntity(
                documentId = document.id,
                originalUri = document.uri,
                trashedUri = location.documentUri,
                trashedParentUri = location.parentUri,
                originalParentUri = document.parentUri,
                deletedAt = deletedAt,
                expiresAt = expiresAt,
                storageKind = storageKind,
            ),
        )
        if (categories.isNotEmpty()) {
            trashDao.insertCategorySnapshots(
                categories.map { category ->
                    TrashCategorySnapshotEntity(
                        trashRecordId = trashId,
                        categoryId = category.id,
                        categoryName = category.name,
                    )
                },
            )
        }
        documentDao.updateLocationAndStatus(
            documentId = document.id,
            uri = location.documentUri,
            parentUri = location.parentUri,
            status = DocumentIndexStatus.TRASHED,
        )
        return trashId
    }

    override suspend fun restoreMetadata(
        expected: TrashEntry,
        restoredUri: String,
        restoredParentUri: String,
    ): TrashMetadataChange = database.withTransaction {
        val current = findEntry(expected.record.id) ?: return@withTransaction TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return@withTransaction TrashMetadataChange.LOCATION_CHANGED
        val record = current.record
        removeRestoreUriCollision(record.documentId, restoredUri)?.let { return@withTransaction it }
        documentDao.updateLocationAndStatus(
            documentId = record.documentId,
            uri = restoredUri,
            parentUri = restoredParentUri,
            status = DocumentIndexStatus.ACTIVE,
        )
        trashDao.delete(record)
        TrashMetadataChange.CHANGED
    }

    override suspend fun restoreVerifiedMediaStoreMetadata(expected: TrashEntry): TrashMetadataChange =
        database.withTransaction {
            val current = findEntry(expected.record.id) ?: return@withTransaction TrashMetadataChange.NOT_FOUND
            if (!sameVerifiedMediaStoreRestoreTarget(current, expected)) {
                return@withTransaction TrashMetadataChange.LOCATION_CHANGED
            }
            removeRestoreUriCollision(current.record.documentId, current.record.originalUri)?.let {
                return@withTransaction it
            }
            documentDao.updateLocationAndStatus(
                documentId = current.record.documentId,
                uri = current.record.originalUri,
                parentUri = current.record.originalParentUri,
                status = DocumentIndexStatus.ACTIVE,
            )
            trashDao.delete(current.record)
            TrashMetadataChange.CHANGED
        }

    private suspend fun removeRestoreUriCollision(
        canonicalDocumentId: Long,
        restoredUri: String,
    ): TrashMetadataChange? {
        val collision = documentDao.findByUri(restoredUri) ?: return null
        if (collision.id == canonicalDocumentId) return null
        if (trashDao.findByDocumentId(collision.id) != null) return TrashMetadataChange.LOCATION_CHANGED
        val remappedFeedback = mergeIdentityFeedback(
            relationAnalysisDao.listFeedbackTouching(listOf(canonicalDocumentId, collision.id)),
            canonicalDocumentId,
            collision.id,
        )
        val remappedIgnoredGroups = remapIgnoredSimilarityGroups(
            ignoredGroups = relationAnalysisDao.listIgnoredGroups(),
            currentMemberSets = relationDao.listNameMembers().groupBy { it.groupId }
                .values.map { members -> members.mapTo(mutableSetOf()) { it.documentId } },
            canonicalDocumentId = canonicalDocumentId,
            collisionDocumentId = collision.id,
        )
        categoryDao.mergeDocumentCategories(canonicalDocumentId, collision.id)
        relationDao.mergeConfirmedDocumentReferences(canonicalDocumentId, collision.id)
        relationDao.mergeReferenceDocumentReferences(canonicalDocumentId, collision.id)
        relationDao.mergeNameMembers(canonicalDocumentId, collision.id)
        relationDao.mergeExactMembers(canonicalDocumentId, collision.id)
        relationAnalysisDao.upsertFeedback(remappedFeedback)
        remappedIgnoredGroups.forEach { relationAnalysisDao.upsertIgnoredGroup(it) }
        // The surviving row is authoritative. Remove a stale reverse alias first so this merge
        // cannot introduce a two-node cycle, then retain the retired scan identity for v5 hashes.
        identityAliasDao.deleteByOldId(canonicalDocumentId)
        identityAliasDao.upsert(DocumentIdentityAliasEntity(collision.id, canonicalDocumentId))
        documentDao.deleteById(collision.id)
        return null
    }

    override suspend fun deleteMetadataAndDocument(expected: TrashEntry): TrashMetadataChange = database.withTransaction {
        val current = findEntry(expected.record.id) ?: return@withTransaction TrashMetadataChange.NOT_FOUND
        if (!sameTrashLocation(current, expected)) return@withTransaction TrashMetadataChange.LOCATION_CHANGED
        documentDao.deleteById(current.document.id)
        TrashMetadataChange.CHANGED
    }

    override suspend fun updateAppTrashLocation(trashId: Long, location: TrashLocation) = database.withTransaction {
        val record = checkNotNull(trashDao.findById(trashId)) { "回收站记录不存在" }
        check(record.storageKind == TrashStorageKind.APP_TRASH) { "仅应用回收站支持移动补偿" }
        checkNotNull(documentDao.findById(record.documentId)) { "文档记录不存在" }
        check(trashDao.updateAppTrashLocation(trashId, location.documentUri, location.parentUri) == 1)
        documentDao.updateLocationAndStatus(record.documentId, location.documentUri, location.parentUri, DocumentIndexStatus.TRASHED)
    }

    override suspend fun findExpired(now: Long): List<TrashEntry> = trashDao.findExpired(now).mapNotNull { record ->
        documentDao.findById(record.documentId)?.let { TrashEntry(record, it) }
    }
}

internal fun mergeIdentityFeedback(
    feedback: List<SimilarityFeedbackEntity>,
    canonicalDocumentId: Long,
    collisionDocumentId: Long,
): List<SimilarityFeedbackEntity> = feedback.mapNotNull { entity ->
    val first = if (entity.documentAId == collisionDocumentId) canonicalDocumentId else entity.documentAId
    val second = if (entity.documentBId == collisionDocumentId) canonicalDocumentId else entity.documentBId
    if (first == second) null else SimilarityFeedbackEntity.normalized(
        firstDocumentId = first,
        secondDocumentId = second,
        decision = entity.decision,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )
}.groupBy { it.documentAId to it.documentBId }
    .map { (_, entries) ->
        val decision = if (entries.any { it.decision == SimilarityFeedbackDecision.BLOCK }) {
            SimilarityFeedbackDecision.BLOCK
        } else {
            SimilarityFeedbackDecision.ALLOW
        }
        entries.first().copy(
            decision = decision,
            createdAt = entries.minOf { it.createdAt },
            updatedAt = entries.maxOf { it.updatedAt },
        )
    }
    .sortedWith(compareBy(SimilarityFeedbackEntity::documentAId, SimilarityFeedbackEntity::documentBId))

internal fun remapIgnoredSimilarityGroups(
    ignoredGroups: List<IgnoredSimilarityGroupEntity>,
    currentMemberSets: Collection<Set<Long>>,
    canonicalDocumentId: Long,
    collisionDocumentId: Long,
): List<IgnoredSimilarityGroupEntity> {
    return ignoredGroups.flatMap { ignored ->
        val knownMemberSets = if (ignored.memberIds.isNotEmpty()) {
            listOf(ignored.memberIds)
        } else {
            currentMemberSets.filter { similarityGroupFingerprint(it) == ignored.groupFingerprint }
        }
        knownMemberSets.mapNotNull { memberIds ->
            if (collisionDocumentId !in memberIds) return@mapNotNull null
            val remappedIds = memberIds.mapTo(mutableSetOf()) { id ->
                if (id == collisionDocumentId) canonicalDocumentId else id
            }
            if (remappedIds.size < 2) return@mapNotNull null
            ignored.copy(
                groupFingerprint = similarityGroupFingerprint(remappedIds),
                memberIds = remappedIds,
            )
        }
    }.groupBy { it.groupFingerprint }
        .map { (_, entries) -> entries.minBy { it.ignoredAt } }
}

internal fun sameTrashLocation(current: TrashEntry, expected: TrashEntry): Boolean =
    current.record.documentId == expected.record.documentId && current.record.storageKind == expected.record.storageKind &&
        current.record.trashedUri == expected.record.trashedUri && current.record.trashedParentUri == expected.record.trashedParentUri &&
        current.document.uri == expected.document.uri && current.document.parentUri == expected.document.parentUri &&
        current.document.indexStatus == DocumentIndexStatus.TRASHED

internal fun sameVerifiedMediaStoreRestoreTarget(current: TrashEntry, expected: TrashEntry): Boolean =
    current.record.documentId == expected.record.documentId &&
        current.record.storageKind == TrashStorageKind.MEDIA_STORE && expected.record.storageKind == TrashStorageKind.MEDIA_STORE &&
        current.record.trashedUri == expected.record.trashedUri && current.record.trashedParentUri == expected.record.trashedParentUri &&
        current.document.uri == current.record.trashedUri &&
        (current.document.indexStatus == DocumentIndexStatus.ACTIVE ||
            current.document.indexStatus == DocumentIndexStatus.TRASHED)
