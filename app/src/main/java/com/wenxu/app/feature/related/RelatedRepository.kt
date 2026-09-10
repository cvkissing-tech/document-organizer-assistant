package com.wenxu.app.feature.related

import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.toRecord
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.relations.EvidenceCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class NameRelatedGroup(
    val id: Long,
    val baseName: String,
    val confirmedDocumentId: Long?,
    val documents: List<DocumentRecord>,
    val evidence: List<SimilarityEvidenceUi> = emptyList(),
)

data class SimilarityEvidenceUi(val text: UiText)

data class ExactRelatedGroup(
    val id: Long,
    val documents: List<DocumentRecord>,
)

data class RelatedData(
    val nameGroups: List<NameRelatedGroup> = emptyList(),
    val exactGroups: List<ExactRelatedGroup> = emptyList(),
    val analysisStatus: RelationAnalysisStatus? = null,
    val analysisGeneration: Long = 0L,
)

interface RelatedRepository {
    val data: Flow<RelatedData>
    suspend fun confirmCurrent(groupId: Long, documentId: Long)
    suspend fun markOpened(documentId: Long)
}

class RoomRelatedRepository(database: WenxuDatabase) : RelatedRepository {
    private val documentDao = database.documentDao()
    private val relationDao = database.relationDao()
    private val analysisDao = database.relationAnalysisDao()

    private val relationData: Flow<RelatedData> = combine(
        documentDao.observeActive(),
        relationDao.observeNameGroups(),
        relationDao.observeNameMembers(),
        relationDao.observeExactSets(),
        relationDao.observeExactMembers(),
    ) { documents, nameGroups, nameMembers, exactSets, exactMembers ->
        val documentsById = documents.associateBy { it.id }
        RelatedData(
            nameGroups = nameGroups.mapNotNull { group ->
                val memberEntities = nameMembers
                    .filter { it.groupId == group.id }
                val members = memberEntities
                    .mapNotNull { member -> documentsById[member.documentId]?.toRecord() }
                if (members.size < 2) null else NameRelatedGroup(
                    id = group.id,
                    baseName = group.baseName,
                    confirmedDocumentId = group.confirmedDocumentId,
                    documents = members.sortedByDescending { it.modifiedAt },
                    evidence = buildSimilarityEvidence(memberEntities, members),
                )
            },
            exactGroups = exactSets.mapNotNull { set ->
                val members = exactMembers
                    .filter { it.setId == set.id }
                    .mapNotNull { member -> documentsById[member.documentId]?.toRecord() }
                if (members.size < 2) null else ExactRelatedGroup(set.id, members)
            },
        )
    }

    override val data: Flow<RelatedData> = combine(
        relationData,
        analysisDao.observeState(),
    ) { data, analysisState ->
        data.copy(
            analysisStatus = analysisState?.status,
            analysisGeneration = analysisState?.generation ?: 0L,
        )
    }

    override suspend fun confirmCurrent(groupId: Long, documentId: Long) {
        if (!relationDao.isNameGroupMember(groupId, documentId)) return
        relationDao.confirmCurrent(groupId, documentId)
    }

    override suspend fun markOpened(documentId: Long) {
        documentDao.updateLastOpened(documentId, System.currentTimeMillis())
    }
}

internal fun buildSimilarityEvidence(
    members: List<NameSimilarityMemberEntity>,
    documents: List<DocumentRecord>,
): List<SimilarityEvidenceUi> {
    val codes = members.flatMapTo(linkedSetOf()) { member -> member.evidenceCodes }
    val evidence = mutableListOf<SimilarityEvidenceUi>()
    fun add(text: UiText) {
        evidence += SimilarityEvidenceUi(text)
    }

    when {
        EvidenceCode.CORE_EXACT in codes -> add(UiText.Resource(R.string.organization_evidence_core_name))
        EvidenceCode.CORE_FUZZY in codes -> add(UiText.Resource(R.string.organization_evidence_core_name))
    }
    if (EvidenceCode.CONTENT_SIMILAR in codes) {
        add(UiText.Resource(R.string.organization_evidence_content))
    }
    if (EvidenceCode.USER_ALLOWED in codes) {
        add(UiText.Resource(R.string.organization_evidence_user_merged))
    }
    if (EvidenceCode.VERSION_MARKER in codes) {
        add(UiText.Resource(R.string.organization_evidence_version_marker))
    }
    if (EvidenceCode.SAME_FOLDER in codes) {
        add(UiText.Resource(R.string.organization_evidence_same_folder))
    }
    if (EvidenceCode.CLOSE_TIME in codes) {
        val validTimes = documents.map { it.modifiedAt }.filter { it > 0L }
        val gapDays = if (validTimes.size > 1) {
            ((validTimes.max() - validTimes.min()) / MILLIS_PER_DAY).toInt()
        } else {
            0
        }
        add(
            if (gapDays > 0) {
                UiText.Plural(R.plurals.organization_evidence_days_apart, gapDays)
            } else {
                UiText.Resource(R.string.organization_evidence_close_time)
            },
        )
    }
    if (EvidenceCode.CLOSE_SIZE in codes) {
        add(UiText.Resource(R.string.organization_evidence_close_size))
    }
    return evidence
}

private const val MILLIS_PER_DAY = 86_400_000L
