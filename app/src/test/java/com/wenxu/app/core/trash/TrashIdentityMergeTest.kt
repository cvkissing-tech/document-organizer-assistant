package com.wenxu.app.core.trash

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.relations.NameGroupDraft
import com.wenxu.app.core.relations.NameGroupMemberDraft
import com.wenxu.app.core.relations.filterIgnoredNameGroups
import com.wenxu.app.core.relations.similarityGroupFingerprint
import org.junit.Test

class TrashIdentityMergeTest {
    @Test
    fun feedbackPairsAreNormalizedWithBlockWinningConflictsAndSelfPairsRemoved() {
        val canonicalId = 2L
        val collisionId = 9L
        val feedback = listOf(
            SimilarityFeedbackEntity.normalized(canonicalId, 5L, SimilarityFeedbackDecision.ALLOW, 1L, 10L),
            SimilarityFeedbackEntity.normalized(collisionId, 5L, SimilarityFeedbackDecision.BLOCK, 2L, 8L),
            SimilarityFeedbackEntity.normalized(collisionId, 7L, SimilarityFeedbackDecision.ALLOW, 3L, 4L),
            SimilarityFeedbackEntity.normalized(canonicalId, collisionId, SimilarityFeedbackDecision.BLOCK, 5L, 6L),
        )

        val merged = mergeIdentityFeedback(feedback, canonicalId, collisionId)

        assertThat(merged).containsExactly(
            SimilarityFeedbackEntity.normalized(canonicalId, 5L, SimilarityFeedbackDecision.BLOCK, 1L, 10L),
            SimilarityFeedbackEntity.normalized(canonicalId, 7L, SimilarityFeedbackDecision.ALLOW, 3L, 4L),
        )
    }

    @Test
    fun ignoredMemberIdentityIsRewrittenAndStillFiltersTheReanalysisDraft() {
        val oldMembers = setOf(5L, 9L)
        val ignored = IgnoredSimilarityGroupEntity(
            groupFingerprint = similarityGroupFingerprint(oldMembers),
            ignoredAt = 12L,
            memberIds = oldMembers,
        )

        val migrated = remapIgnoredSimilarityGroups(
            ignoredGroups = listOf(ignored),
            currentMemberSets = emptyList(),
            canonicalDocumentId = 2L,
            collisionDocumentId = 9L,
        )
        val reanalysisDraft = NameGroupDraft(
            baseName = "报告",
            extension = "docx",
            members = listOf(2L, 5L).map { NameGroupMemberDraft(it, emptyList()) },
        )

        assertThat(migrated).containsExactly(
            IgnoredSimilarityGroupEntity(
                similarityGroupFingerprint(setOf(2L, 5L)),
                ignoredAt = 12L,
                memberIds = setOf(2L, 5L),
            ),
        )
        assertThat(filterIgnoredNameGroups(listOf(reanalysisDraft), migrated.map { it.groupFingerprint }.toSet()))
            .isEmpty()
    }
}
