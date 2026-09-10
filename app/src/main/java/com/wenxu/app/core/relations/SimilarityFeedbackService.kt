package com.wenxu.app.core.relations

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

const val MAX_MANUAL_MERGE_DOCUMENTS = 20

sealed interface FeedbackResult {
    data class SavedPendingAnalysis(val documentIds: Set<Long>) : FeedbackResult
    data class AnalysisQueueFailed(val documentIds: Set<Long>) : FeedbackResult
    data object GroupUnavailable : FeedbackResult
    data object NotEnoughDocuments : FeedbackResult
    data object TooManyDocuments : FeedbackResult
    data object DocumentsUnavailable : FeedbackResult
    data object IncompatibleFamily : FeedbackResult
    data object ExactDuplicates : FeedbackResult
    data object BlockedByUser : FeedbackResult
}

internal sealed interface FeedbackPersistenceResult {
    data class Saved(
        val documentIds: Set<Long>,
        val generation: Long,
    ) : FeedbackPersistenceResult
    data object GroupUnavailable : FeedbackPersistenceResult
    data object NotEnoughDocuments : FeedbackPersistenceResult
    data object DocumentsUnavailable : FeedbackPersistenceResult
    data object IncompatibleFamily : FeedbackPersistenceResult
    data object ExactDuplicates : FeedbackPersistenceResult
    data object BlockedByUser : FeedbackPersistenceResult
}

internal data class BlockMemberRequest(
    val groupId: Long,
    val documentId: Long,
    val expectedMemberIds: Set<Long>,
    val timestamp: Long,
)

internal data class IgnoreGroupRequest(
    val groupId: Long,
    val expectedMemberIds: Set<Long>,
    val timestamp: Long,
)

internal data class MergeVersionsRequest(
    val documentIds: Set<Long>,
    val timestamp: Long,
)

internal interface SimilarityFeedbackStore {
    suspend fun blockMember(request: BlockMemberRequest): FeedbackPersistenceResult
    suspend fun ignoreGroup(request: IgnoreGroupRequest): FeedbackPersistenceResult
    suspend fun mergeVersions(request: MergeVersionsRequest): FeedbackPersistenceResult
    suspend fun queueRetry(timestamp: Long): Long
}

interface SimilarityFeedbackController {
    suspend fun markNotRelated(
        groupId: Long,
        documentId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult

    suspend fun ignoreGroup(groupId: Long, expectedMemberIds: Set<Long>): FeedbackResult
    suspend fun mergeAsVersions(documentIds: Set<Long>): FeedbackResult
    suspend fun retryAnalysis(documentIds: Set<Long>): FeedbackResult
}

class SimilarityFeedbackService internal constructor(
    private val store: SimilarityFeedbackStore,
    private val reconcileQueued: suspend (Long?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : SimilarityFeedbackController {
    constructor(
        database: WenxuDatabase,
        reconcileQueued: suspend (Long?) -> Boolean,
    ) : this(RoomSimilarityFeedbackStore(database), reconcileQueued)

    override suspend fun markNotRelated(
        groupId: Long,
        documentId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult = saveThenSchedule(
        store.blockMember(
            BlockMemberRequest(groupId, documentId, expectedMemberIds, now()),
        ),
    )

    override suspend fun ignoreGroup(
        groupId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult = saveThenSchedule(
        store.ignoreGroup(IgnoreGroupRequest(groupId, expectedMemberIds, now())),
    )

    override suspend fun mergeAsVersions(documentIds: Set<Long>): FeedbackResult {
        if (documentIds.size < 2) return FeedbackResult.NotEnoughDocuments
        if (documentIds.size > MAX_MANUAL_MERGE_DOCUMENTS) return FeedbackResult.TooManyDocuments
        return saveThenSchedule(store.mergeVersions(MergeVersionsRequest(documentIds, now())))
    }

    override suspend fun retryAnalysis(documentIds: Set<Long>): FeedbackResult {
        return try {
            if (reconcileQueued(null)) {
                FeedbackResult.SavedPendingAnalysis(documentIds)
            } else {
                scheduleSaved(documentIds, store.queueRetry(now()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FeedbackResult.AnalysisQueueFailed(documentIds)
        }
    }

    private suspend fun saveThenSchedule(result: FeedbackPersistenceResult): FeedbackResult =
        when (result) {
            is FeedbackPersistenceResult.Saved -> scheduleSaved(
                result.documentIds,
                result.generation,
            )
            FeedbackPersistenceResult.GroupUnavailable -> FeedbackResult.GroupUnavailable
            FeedbackPersistenceResult.NotEnoughDocuments -> FeedbackResult.NotEnoughDocuments
            FeedbackPersistenceResult.DocumentsUnavailable -> FeedbackResult.DocumentsUnavailable
            FeedbackPersistenceResult.IncompatibleFamily -> FeedbackResult.IncompatibleFamily
            FeedbackPersistenceResult.ExactDuplicates -> FeedbackResult.ExactDuplicates
            FeedbackPersistenceResult.BlockedByUser -> FeedbackResult.BlockedByUser
        }

    private suspend fun scheduleSaved(
        documentIds: Set<Long>,
        generation: Long?,
    ): FeedbackResult = try {
        if (reconcileQueued(generation)) {
            FeedbackResult.SavedPendingAnalysis(documentIds)
        } else {
            FeedbackResult.AnalysisQueueFailed(documentIds)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        FeedbackResult.AnalysisQueueFailed(documentIds)
    }

}

internal class RoomSimilarityFeedbackStore(
    private val database: WenxuDatabase,
) : SimilarityFeedbackStore {
    private val relationDao = database.relationDao()
    private val analysisDao = database.relationAnalysisDao()
    private val documentDao = database.documentDao()
    private val identityAliasDao = database.documentIdentityAliasDao()

    override suspend fun blockMember(request: BlockMemberRequest): FeedbackPersistenceResult =
        database.withTransaction {
            val activeMemberIds = relationDao.listActiveNameMemberIds(request.groupId).toSet()
            if (activeMemberIds.size < 2 ||
                activeMemberIds != request.expectedMemberIds ||
                request.documentId !in activeMemberIds
            ) {
                return@withTransaction FeedbackPersistenceResult.GroupUnavailable
            }
            val existing = analysisDao.listFeedbackWithin(activeMemberIds.toList())
                .associateBy { it.documentAId to it.documentBId }
            val entries = activeMemberIds.asSequence()
                .filterNot { it == request.documentId }
                .map { otherId ->
                    val first = minOf(request.documentId, otherId)
                    val second = maxOf(request.documentId, otherId)
                    SimilarityFeedbackEntity.normalized(
                        firstDocumentId = first,
                        secondDocumentId = second,
                        decision = SimilarityFeedbackDecision.BLOCK,
                        createdAt = existing[first to second]?.createdAt ?: request.timestamp,
                        updatedAt = request.timestamp,
                    )
                }
                .toList()
            analysisDao.upsertFeedback(entries)
            FeedbackPersistenceResult.Saved(
                activeMemberIds,
                queueRelationAnalysisState(analysisDao, request.timestamp),
            )
        }

    override suspend fun ignoreGroup(request: IgnoreGroupRequest): FeedbackPersistenceResult =
        database.withTransaction {
            val activeMemberIds = relationDao.listActiveNameMemberIds(request.groupId).toSet()
            if (activeMemberIds.size < 2 || activeMemberIds != request.expectedMemberIds) {
                return@withTransaction FeedbackPersistenceResult.GroupUnavailable
            }
            analysisDao.upsertIgnoredGroup(
                IgnoredSimilarityGroupEntity(
                    groupFingerprint = similarityGroupFingerprint(activeMemberIds),
                    ignoredAt = request.timestamp,
                    memberIds = activeMemberIds,
                ),
            )
            FeedbackPersistenceResult.Saved(
                activeMemberIds,
                queueRelationAnalysisState(analysisDao, request.timestamp),
            )
        }

    override suspend fun mergeVersions(request: MergeVersionsRequest): FeedbackPersistenceResult =
        database.withTransaction {
            val documentIds = request.documentIds
            if (documentIds.size < 2) return@withTransaction FeedbackPersistenceResult.NotEnoughDocuments
            val documents = documentDao.findActiveByIds(documentIds.toList())
            if (documents.size != documentIds.size) {
                return@withTransaction FeedbackPersistenceResult.DocumentsUnavailable
            }
            val families = documents.map { DocumentFamily.fromExtension(it.extension) }.toSet()
            if (null in families || families.size != 1) {
                return@withTransaction FeedbackPersistenceResult.IncompatibleFamily
            }
            val exactSetCounts = relationDao.listExactMembersForDocuments(documentIds.toList())
                .groupingBy { member -> member.setId }
                .eachCount()
            if (exactSetCounts.values.any { count -> count > 1 }) {
                return@withTransaction FeedbackPersistenceResult.ExactDuplicates
            }
            val existingFeedback = analysisDao.listFeedbackWithin(documentIds.toList())
            if (existingFeedback.any { it.decision == SimilarityFeedbackDecision.BLOCK }) {
                return@withTransaction FeedbackPersistenceResult.BlockedByUser
            }
            val existingByPair = existingFeedback.associateBy { it.documentAId to it.documentBId }
            val sortedIds = documentIds.sorted()
            val entries = buildList {
                sortedIds.forEachIndexed { index, firstId ->
                    for (secondIndex in index + 1 until sortedIds.size) {
                        val secondId = sortedIds[secondIndex]
                        add(
                            SimilarityFeedbackEntity.normalized(
                                firstDocumentId = firstId,
                                secondDocumentId = secondId,
                                decision = SimilarityFeedbackDecision.ALLOW,
                                createdAt = existingByPair[firstId to secondId]?.createdAt
                                    ?: request.timestamp,
                                updatedAt = request.timestamp,
                            ),
                        )
                    }
                }
            }
            chunkIgnoredFingerprints(
                ignoredFingerprintsForManualMerge(documentIds, identityAliasDao.listAll()).toList(),
            ).forEach { chunk -> analysisDao.deleteIgnoredGroups(chunk) }
            analysisDao.upsertFeedback(entries)
            FeedbackPersistenceResult.Saved(
                sortedIds.toSet(),
                queueRelationAnalysisState(analysisDao, request.timestamp),
            )
        }

    override suspend fun queueRetry(timestamp: Long): Long = database.withTransaction {
        queueRelationAnalysisState(analysisDao, timestamp)
    }
}

internal fun ignoredFingerprintsForManualMerge(
    documentIds: Set<Long>,
    identityAliases: List<DocumentIdentityAliasEntity>,
): Set<String> = ignoredFingerprintCandidates(documentIds, identityAliases)

private const val MAX_SQLITE_IN_ARGUMENTS = 900

internal fun chunkIgnoredFingerprints(
    fingerprints: List<String>,
): List<List<String>> = fingerprints.chunked(MAX_SQLITE_IN_ARGUMENTS)

internal fun similarityGroupFingerprint(documentIds: Set<Long>): String {
    val normalized = documentIds.sorted().joinToString(":")
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
