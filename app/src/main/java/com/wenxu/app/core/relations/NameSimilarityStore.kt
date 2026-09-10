package com.wenxu.app.core.relations

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.database.entity.SimilarityReviewState
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.NameSegment

internal const val RELATION_RUN_TOKEN_PREFIX = "run:"

internal fun relationRunMarker(runToken: String): String = "$RELATION_RUN_TOKEN_PREFIX$runToken"

val RelationAnalysisStateEntity.userVisibleErrorMessage: String?
    get() = errorMessage?.takeUnless { message ->
        status == RelationAnalysisStatus.RUNNING && message.startsWith(RELATION_RUN_TOKEN_PREFIX)
    }

data class NameGroupDraft(
    val baseName: String,
    val extension: String,
    val members: List<NameGroupMemberDraft>,
    val analysis: NameGroupAnalysisDraft? = null,
)

data class NameGroupMemberDraft(
    val documentId: Long,
    val differenceSegments: List<NameSegment>,
    val score: Int? = null,
    val evidenceCodes: Set<EvidenceCode>? = null,
)

data class NameGroupAnalysisDraft(
    val referenceDocumentId: Long,
    val confidenceBand: ConfidenceBand,
    val analysisVersion: Int,
    val reviewState: SimilarityReviewState? = null,
)

internal data class ExistingNameGroupMatch(
    val entity: NameSimilarityGroupEntity,
    val confirmedDocumentId: Long?,
)

internal fun matchExistingNameGroup(
    draft: NameGroupDraft,
    existingGroups: List<NameSimilarityGroupEntity>,
    existingMemberIdsByGroup: Map<Long, Set<Long>> = emptyMap(),
    usedExistingIds: Set<Long> = emptySet(),
): ExistingNameGroupMatch? {
    val draftFamily = DocumentFamily.fromExtension(draft.extension) ?: return null
    val memberIds = draft.members.mapTo(hashSetOf()) { member -> member.documentId }
    val candidates = existingGroups
        .filter { group ->
            val existingMemberIds = existingMemberIdsByGroup[group.id].orEmpty()
            group.id !in usedExistingIds &&
                DocumentFamily.fromExtension(group.extension) == draftFamily &&
                hasStableMemberContinuity(existingMemberIds, memberIds)
        }
        .sortedWith(
            compareByDescending<NameSimilarityGroupEntity> { group ->
                existingMemberIdsByGroup[group.id].orEmpty() == memberIds
            }
                .thenByDescending { group ->
                    group.baseName == draft.baseName
                }
                .thenByDescending { group ->
                    existingMemberIdsByGroup[group.id].orEmpty().count(memberIds::contains)
                }
                .thenByDescending { group ->
                    group.extension.equals(draft.extension, ignoreCase = true)
                }
                .thenByDescending { group -> group.confirmedDocumentId in memberIds }
                .thenBy { group -> group.id },
        )
    if (candidates.isEmpty()) return null

    val selected = candidates.first()
    val confirmedDocumentId = buildList {
        add(selected)
        addAll(candidates.filterNot { group -> group.id == selected.id })
    }.firstNotNullOfOrNull { group -> group.confirmedDocumentId?.takeIf(memberIds::contains) }
    return ExistingNameGroupMatch(selected, confirmedDocumentId)
}

private fun hasStableMemberContinuity(
    existingMemberIds: Set<Long>,
    draftMemberIds: Set<Long>,
): Boolean {
    val overlap = existingMemberIds.count(draftMemberIds::contains)
    if (overlap < MIN_STABLE_MEMBER_OVERLAP) return false
    val unionSize = existingMemberIds.size + draftMemberIds.size - overlap
    val jaccard = overlap.toDouble() / unionSize
    val existingCoverage = overlap.toDouble() / existingMemberIds.size
    val draftCoverage = overlap.toDouble() / draftMemberIds.size
    return jaccard >= MIN_STABLE_MEMBER_RATIO ||
        (existingCoverage >= MIN_STABLE_MEMBER_RATIO && draftCoverage >= MIN_STABLE_MEMBER_RATIO)
}

private const val MIN_STABLE_MEMBER_OVERLAP = 2
private const val MIN_STABLE_MEMBER_RATIO = 0.5

internal fun mergeNameGroupEntity(
    draft: NameGroupDraft,
    match: ExistingNameGroupMatch?,
    updatedAt: Long,
): NameSimilarityGroupEntity {
    val existing = match?.entity
    val memberIds = draft.members.mapTo(hashSetOf()) { member -> member.documentId }
    val analysis = draft.analysis
    return NameSimilarityGroupEntity(
        id = existing?.id ?: 0,
        baseName = draft.baseName,
        extension = existing?.extension ?: draft.extension,
        confirmedDocumentId = match?.confirmedDocumentId?.takeIf(memberIds::contains),
        referenceDocumentId = analysis?.referenceDocumentId
            ?: existing?.referenceDocumentId?.takeIf(memberIds::contains),
        confidenceBand = analysis?.confidenceBand
            ?: existing?.confidenceBand
            ?: ConfidenceBand.HIGH,
        analysisVersion = analysis?.analysisVersion
            ?: existing?.analysisVersion
            ?: 1,
        reviewState = analysis?.reviewState
            ?: existing?.reviewState
            ?: SimilarityReviewState.PENDING,
        updatedAt = updatedAt,
    )
}

internal fun mergeNameMemberEntity(
    groupId: Long,
    draft: NameGroupMemberDraft,
    previous: NameSimilarityMemberEntity?,
): NameSimilarityMemberEntity = NameSimilarityMemberEntity(
    groupId = groupId,
    documentId = draft.documentId,
    differenceSegments = draft.differenceSegments,
    score = draft.score ?: previous?.score ?: 0,
    evidenceCodes = draft.evidenceCodes ?: previous?.evidenceCodes.orEmpty(),
)

interface NameSimilarityStore {
    suspend fun activeDocuments(): List<DocumentRecord>
    suspend fun replaceGroups(groups: List<NameGroupDraft>)
    suspend fun isMember(groupId: Long, documentId: Long): Boolean
    suspend fun setConfirmedCurrent(groupId: Long, documentId: Long)
}

class RoomNameSimilarityStore(
    private val database: WenxuDatabase,
) : NameSimilarityStore, RelationAnalysisStore {
    private val documentDao = database.documentDao()
    private val relationDao = database.relationDao()
    private val analysisDao = database.relationAnalysisDao()

    override suspend fun activeDocuments(): List<DocumentRecord> =
        documentDao.listActive().map { document ->
            DocumentRecord(
                id = document.id,
                uri = document.uri,
                displayName = document.displayName,
                extension = document.extension,
                sizeBytes = document.sizeBytes,
                modifiedAt = document.modifiedAt,
                sourceId = document.sourceId,
                parentUri = document.parentUri,
            )
        }

    override suspend fun replaceGroups(groups: List<NameGroupDraft>) {
        database.withTransaction {
            replaceGroupsInTransaction(groups)
        }
    }

    override suspend fun activeDocumentEntities(): List<DocumentEntity> = documentDao.listActive()

    override suspend fun begin(generation: Long, runToken: String): Boolean =
        analysisDao.claimRun(
            generation = generation,
            runMarker = relationRunMarker(runToken),
            updatedAt = System.currentTimeMillis(),
        ) == 1

    override suspend fun phase(
        generation: Long,
        runToken: String,
        phase: RelationAnalysisPhase,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
    ): Boolean = analysisDao.updatePhaseIfCurrentRun(
        generation = generation,
        runMarker = relationRunMarker(runToken),
        phase = phase,
        processedCount = processedCount,
        candidateCount = candidateCount,
        failedCount = failedCount,
        updatedAt = System.currentTimeMillis(),
    ) == 1

    override suspend fun feedback(): List<SimilarityFeedbackEntity> = analysisDao.listFeedback()

    override suspend fun replace(
        generation: Long,
        runToken: String,
        exactGroups: List<ExactDuplicateGroup>,
        groups: List<NameGroupDraft>,
        candidates: List<SimilarityCandidateEntity>,
        fingerprints: List<DocumentFingerprintEntity>,
        failedCount: Int,
    ): Boolean = database.withTransaction {
        val runMarker = relationRunMarker(runToken)
        if (!analysisDao.isCurrentRun(generation, runMarker)) return@withTransaction false
        replaceExactGroupsInTransaction(relationDao, exactGroups, System.currentTimeMillis())
        if (fingerprints.isNotEmpty()) analysisDao.upsertFingerprints(fingerprints)
        analysisDao.clearCandidates()
        if (candidates.isNotEmpty()) analysisDao.upsertCandidates(candidates)
        replaceGroupsInTransaction(groups)
        analysisDao.completeIfCurrentRun(
            generation = generation,
            runMarker = runMarker,
            processedCount = candidates.size,
            candidateCount = candidates.size,
            failedCount = failedCount,
            updatedAt = System.currentTimeMillis(),
        ) == 1
    }

    private suspend fun replaceGroupsInTransaction(groups: List<NameGroupDraft>) {
        val visibleGroups = filterIgnoredNameGroups(
            groups = groups,
            ignoredFingerprints = analysisDao.listIgnoredGroupFingerprints().toSet(),
            identityAliases = database.documentIdentityAliasDao().listAll(),
        )
        val existingGroups = relationDao.listNameGroups()
        val existingMembers = relationDao.listNameMembers()
        val existingMemberIdsByGroup = existingMembers.groupBy { member -> member.groupId }
            .mapValues { (_, members) ->
                members.mapTo(hashSetOf()) { member -> member.documentId }
            }
        val existingMembersByGroup = existingMembers.groupBy { member -> member.groupId }
            .mapValues { (_, members) -> members.associateBy { member -> member.documentId } }
        val retainedIds = mutableSetOf<Long>()

        visibleGroups.forEach { draft ->
            val match = matchExistingNameGroup(
                draft = draft,
                existingGroups = existingGroups,
                existingMemberIdsByGroup = existingMemberIdsByGroup,
                usedExistingIds = retainedIds,
            )
            val existing = match?.entity
            val groupEntity = mergeNameGroupEntity(draft, match, System.currentTimeMillis())
            val insertedId = relationDao.upsertNameGroup(groupEntity)
            val groupId = existing?.id ?: insertedId
            retainedIds += groupId
            val previousMembers = existing?.let { previous ->
                existingMembersByGroup[previous.id]
            }.orEmpty()
            relationDao.clearNameMembers(groupId)
            relationDao.insertNameMembers(
                draft.members.map { member ->
                    val previous = previousMembers[member.documentId]
                    mergeNameMemberEntity(groupId, member, previous)
                },
            )
        }

        val obsoleteIds = existingGroups.map { it.id }.filterNot(retainedIds::contains)
        if (obsoleteIds.isNotEmpty()) relationDao.deleteNameGroups(obsoleteIds)
    }

    override suspend fun isMember(groupId: Long, documentId: Long): Boolean =
        relationDao.isNameGroupMember(groupId, documentId)

    override suspend fun setConfirmedCurrent(groupId: Long, documentId: Long) {
        relationDao.confirmCurrent(groupId, documentId)
    }
}

internal fun filterIgnoredNameGroups(
    groups: List<NameGroupDraft>,
    ignoredFingerprints: Set<String>,
    identityAliases: List<DocumentIdentityAliasEntity> = emptyList(),
): List<NameGroupDraft> = groups.filterNot { group ->
    ignoredFingerprintCandidates(
        group.members.mapTo(mutableSetOf()) { member -> member.documentId },
        identityAliases,
    ).any(ignoredFingerprints::contains)
}

private const val MAX_ALIAS_FINGERPRINT_CANDIDATES = 4_096
private const val MAX_ALIAS_CHAIN_DEPTH = 64

/**
 * Produces only exact historical identity combinations and caps work deterministically. Cyclic or
 * overlong aliases are ignored, so damaged alias data fails open instead of hiding a new group.
 * If more than 4,096 combinations exist, later combinations are intentionally not treated as
 * compatible legacy identities; this bounded, conservative edge prevents exponential work.
 */
internal fun ignoredFingerprintCandidates(
    currentDocumentIds: Set<Long>,
    identityAliases: List<DocumentIdentityAliasEntity>,
    limit: Int = MAX_ALIAS_FINGERPRINT_CANDIDATES,
): Set<String> {
    if (currentDocumentIds.isEmpty() || limit <= 0) return emptySet()
    val nextByOld = identityAliases.associate { it.oldDocumentId to it.canonicalDocumentId }
    fun resolvedCanonical(oldId: Long): Long? {
        var current = oldId
        val visited = mutableSetOf<Long>()
        repeat(MAX_ALIAS_CHAIN_DEPTH) {
            if (!visited.add(current)) return null
            val next = nextByOld[current] ?: return current
            current = next
        }
        return null
    }
    val historicalByCanonical = mutableMapOf<Long, MutableSet<Long>>()
    identityAliases.asSequence().sortedBy { it.oldDocumentId }.forEach { alias ->
        val canonical = resolvedCanonical(alias.oldDocumentId) ?: return@forEach
        if (canonical in currentDocumentIds) {
            historicalByCanonical.getOrPut(canonical, ::sortedSetOf).add(alias.oldDocumentId)
        }
    }
    val choices = currentDocumentIds.sorted().map { current ->
        buildList {
            add(current)
            addAll(historicalByCanonical[current].orEmpty())
        }.distinct()
    }
    val fingerprints = linkedSetOf<String>()
    fun visit(index: Int, chosen: MutableSet<Long>) {
        if (fingerprints.size >= limit) return
        if (index == choices.size) {
            if (chosen.size == currentDocumentIds.size) {
                fingerprints += similarityGroupFingerprint(chosen)
            }
            return
        }
        choices[index].forEach { id ->
            if (fingerprints.size >= limit) return
            if (chosen.add(id)) {
                visit(index + 1, chosen)
                chosen.remove(id)
            }
        }
    }
    visit(0, linkedSetOf())
    return fingerprints
}
