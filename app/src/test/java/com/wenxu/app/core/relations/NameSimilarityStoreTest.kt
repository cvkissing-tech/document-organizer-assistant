package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.SimilarityReviewState
import com.wenxu.app.core.model.NameSegment
import org.junit.Test

class NameSimilarityStoreTest {
    @Test
    fun ignoredStableMemberSetIsNotSavedAgain() {
        val ignored = draft(memberIds = listOf(3, 1, 2))
        val visible = draft(memberIds = listOf(4, 5))

        val retained = filterIgnoredNameGroups(
            groups = listOf(ignored, visible),
            ignoredFingerprints = setOf(similarityGroupFingerprint(setOf(1, 2, 3))),
        )

        assertThat(retained).containsExactly(visible)
    }

    @Test
    fun legacyHashOnlyIgnoreMatchesOldIdentityAliasAfterCollision() {
        val legacyFingerprint = similarityGroupFingerprint(setOf(9, 5))
        val visible = draft(memberIds = listOf(2, 6))

        val retained = filterIgnoredNameGroups(
            groups = listOf(draft(memberIds = listOf(2, 5)), visible),
            ignoredFingerprints = setOf(legacyFingerprint),
            identityAliases = listOf(DocumentIdentityAliasEntity(oldDocumentId = 9, canonicalDocumentId = 2)),
        )

        assertThat(retained).containsExactly(visible)
    }

    @Test
    fun identityAliasChainsAreResolvedButCyclesFailOpen() {
        val groups = listOf(draft(memberIds = listOf(2, 5)))
        val aliases = listOf(
            DocumentIdentityAliasEntity(9, 4),
            DocumentIdentityAliasEntity(4, 2),
            DocumentIdentityAliasEntity(20, 21),
            DocumentIdentityAliasEntity(21, 20),
        )

        assertThat(filterIgnoredNameGroups(
            groups,
            setOf(similarityGroupFingerprint(setOf(9, 5))),
            aliases,
        )).isEmpty()
        assertThat(filterIgnoredNameGroups(
            groups,
            setOf(similarityGroupFingerprint(setOf(20, 5))),
            aliases,
        )).containsExactlyElementsIn(groups)
    }

    @Test
    fun identityAliasFingerprintExpansionIsStrictlyBounded() {
        val aliases = buildList {
            repeat(100) { offset ->
                add(DocumentIdentityAliasEntity(1_000L + offset, 1))
                add(DocumentIdentityAliasEntity(2_000L + offset, 2))
                add(DocumentIdentityAliasEntity(3_000L + offset, 3))
            }
        }

        val candidates = ignoredFingerprintCandidates(setOf(1, 2, 3), aliases, limit = 257)

        assertThat(candidates).hasSize(257)
        assertThat(candidates).contains(similarityGroupFingerprint(setOf(1, 2, 3)))
        // Compatibility beyond the deterministic cap is conservative: late legacy combinations
        // are left unmatched rather than spending exponential time or hiding a new group.
        assertThat(candidates).doesNotContain(similarityGroupFingerprint(setOf(1_099, 2_099, 3_099)))
    }

    @Test
    fun canonicalFamilyMatchPreservesConfirmedMemberFromLegacyExtensionGroup() {
        val draft = NameGroupDraft(
            baseName = "报告",
            extension = "docx",
            members = listOf(
                NameGroupMemberDraft(documentId = 1, differenceSegments = emptyList()),
                NameGroupMemberDraft(documentId = 2, differenceSegments = emptyList()),
            ),
        )
        val canonical = group(id = 10, extension = "docx", confirmedDocumentId = null)
        val legacy = group(id = 11, extension = "doc", confirmedDocumentId = 1)

        val match = matchExistingNameGroup(
            draft,
            listOf(canonical, legacy),
            existingMemberIdsByGroup = mapOf(10L to setOf(1L, 2L), 11L to setOf(1L, 2L)),
        )

        assertThat(match?.entity?.id).isEqualTo(10)
        assertThat(match?.confirmedDocumentId).isEqualTo(1)
    }

    @Test
    fun draftsChooseUnusedExistingGroupsByMemberOverlap() {
        val firstDraft = draft(memberIds = listOf(1, 2))
        val secondDraft = draft(memberIds = listOf(3, 4))
        val firstExisting = group(id = 10, extension = "docx", confirmedDocumentId = null)
        val secondExisting = group(id = 11, extension = "doc", confirmedDocumentId = null)
        val existing = listOf(firstExisting, secondExisting)
        val membersByGroup = mapOf(
            10L to setOf(3L, 4L),
            11L to setOf(1L, 2L),
        )

        val firstMatch = matchExistingNameGroup(
            draft = firstDraft,
            existingGroups = existing,
            existingMemberIdsByGroup = membersByGroup,
            usedExistingIds = emptySet(),
        )
        val secondMatch = matchExistingNameGroup(
            draft = secondDraft,
            existingGroups = existing,
            existingMemberIdsByGroup = membersByGroup,
            usedExistingIds = setOf(checkNotNull(firstMatch).entity.id),
        )

        assertThat(firstMatch.entity.id).isEqualTo(11)
        assertThat(secondMatch?.entity?.id).isEqualTo(10)
    }

    @Test
    fun stableMemberSetPreservesConfirmationWhenRepresentativeBaseNameChanges() {
        val draft = draft(memberIds = listOf(1, 2)).copy(baseName = "毕业实习报告")
        val existing = group(id = 10, extension = "docx", confirmedDocumentId = 1)

        val match = matchExistingNameGroup(
            draft = draft,
            existingGroups = listOf(existing),
            existingMemberIdsByGroup = mapOf(10L to setOf(1L, 2L)),
        )

        assertThat(match?.entity?.id).isEqualTo(10)
        assertThat(match?.confirmedDocumentId).isEqualTo(1)
    }

    @Test
    fun sameNameWithoutMemberOverlapDoesNotReuseOldGroupMetadata() {
        val draft = draft(memberIds = listOf(3, 4))
        val existing = group(id = 10, extension = "docx", confirmedDocumentId = 1).copy(
            referenceDocumentId = 2,
            reviewState = SimilarityReviewState.REVIEWED,
        )

        val match = matchExistingNameGroup(
            draft = draft,
            existingGroups = listOf(existing),
            existingMemberIdsByGroup = mapOf(10L to setOf(1L, 2L)),
        )

        assertThat(match).isNull()
        val merged = mergeNameGroupEntity(draft, match = null, updatedAt = 20)
        assertThat(merged.id).isEqualTo(0)
        assertThat(merged.confirmedDocumentId).isNull()
        assertThat(merged.referenceDocumentId).isNull()
        assertThat(merged.reviewState).isEqualTo(SimilarityReviewState.PENDING)
    }

    @Test
    fun oneMemberOverlapFromTwoMemberGroupDoesNotReuseOldMetadata() {
        val existing = group(id = 10, extension = "docx", confirmedDocumentId = 1).copy(
            referenceDocumentId = 2,
            reviewState = SimilarityReviewState.REVIEWED,
        )
        val existingGroups = listOf(existing)
        val existingMembers = mapOf(10L to setOf(1L, 2L))

        val firstSplit = matchExistingNameGroup(
            draft = draft(memberIds = listOf(1, 3)),
            existingGroups = existingGroups,
            existingMemberIdsByGroup = existingMembers,
        )
        val secondSplit = matchExistingNameGroup(
            draft = draft(memberIds = listOf(2, 4)),
            existingGroups = existingGroups,
            existingMemberIdsByGroup = existingMembers,
        )

        assertThat(firstSplit).isNull()
        assertThat(secondSplit).isNull()
        val merged = mergeNameGroupEntity(
            draft = draft(memberIds = listOf(1, 3)),
            match = firstSplit,
            updatedAt = 20,
        )
        assertThat(merged.id).isEqualTo(0)
        assertThat(merged.confirmedDocumentId).isNull()
        assertThat(merged.referenceDocumentId).isNull()
        assertThat(merged.reviewState).isEqualTo(SimilarityReviewState.PENDING)
    }

    @Test
    fun legacyDraftDoesNotClearPersistedAnalysisMetadataOrEvidence() {
        val existing = group(id = 10, extension = "docx", confirmedDocumentId = 2).copy(
            referenceDocumentId = 1,
            confidenceBand = ConfidenceBand.CANDIDATE,
            analysisVersion = 9,
            reviewState = SimilarityReviewState.REVIEWED,
        )
        val draft = draft(memberIds = listOf(1, 2))
        val match = ExistingNameGroupMatch(existing, confirmedDocumentId = 2)
        val previousMember = NameSimilarityMemberEntity(
            groupId = 10,
            documentId = 1,
            differenceSegments = emptyList(),
            score = 88,
            evidenceCodes = setOf(EvidenceCode.CONTENT_SIMILAR),
        )

        val mergedGroup = mergeNameGroupEntity(draft, match, updatedAt = 20)
        val mergedMember = mergeNameMemberEntity(10, draft.members.first(), previousMember)

        assertThat(mergedGroup.confirmedDocumentId).isEqualTo(2)
        assertThat(mergedGroup.referenceDocumentId).isEqualTo(1)
        assertThat(mergedGroup.confidenceBand).isEqualTo(ConfidenceBand.CANDIDATE)
        assertThat(mergedGroup.analysisVersion).isEqualTo(9)
        assertThat(mergedGroup.reviewState).isEqualTo(SimilarityReviewState.REVIEWED)
        assertThat(mergedMember.score).isEqualTo(88)
        assertThat(mergedMember.evidenceCodes).containsExactly(EvidenceCode.CONTENT_SIMILAR)
    }

    @Test
    fun coordinatorDraftPersistsExplicitReferenceScoreAndEvidence() {
        val draft = NameGroupDraft(
            baseName = "报告",
            extension = "docx",
            members = listOf(
                NameGroupMemberDraft(
                    documentId = 1,
                    differenceSegments = listOf(NameSegment("草稿", true)),
                    score = 93,
                    evidenceCodes = setOf(EvidenceCode.CORE_EXACT, EvidenceCode.CONTENT_SIMILAR),
                ),
                NameGroupMemberDraft(documentId = 2, differenceSegments = emptyList(), score = 100),
            ),
            analysis = NameGroupAnalysisDraft(
                referenceDocumentId = 2,
                confidenceBand = ConfidenceBand.HIGH,
                analysisVersion = 3,
            ),
        )

        val group = mergeNameGroupEntity(draft, match = null, updatedAt = 5)
        val member = mergeNameMemberEntity(12, draft.members.first(), previous = null)

        assertThat(group.referenceDocumentId).isEqualTo(2)
        assertThat(group.analysisVersion).isEqualTo(3)
        assertThat(member.score).isEqualTo(93)
        assertThat(member.evidenceCodes)
            .containsExactly(EvidenceCode.CORE_EXACT, EvidenceCode.CONTENT_SIMILAR)
    }

    private fun draft(memberIds: List<Long>) = NameGroupDraft(
        baseName = "报告",
        extension = "docx",
        members = memberIds.map { id ->
            NameGroupMemberDraft(documentId = id, differenceSegments = emptyList())
        },
    )

    private fun group(
        id: Long,
        extension: String,
        confirmedDocumentId: Long?,
    ) = NameSimilarityGroupEntity(
        id = id,
        baseName = "报告",
        extension = extension,
        confirmedDocumentId = confirmedDocumentId,
        updatedAt = id,
    )
}
