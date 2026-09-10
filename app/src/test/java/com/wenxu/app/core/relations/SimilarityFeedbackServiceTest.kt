package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SimilarityFeedbackServiceTest {
    @Test
    fun removingMemberUsesExpectedVisibleSnapshot() = runTest {
        val store = RecordingFeedbackStore(
            blockResult = FeedbackPersistenceResult.Saved(setOf(1L, 2L, 3L), generation = 4L),
        )
        val service = SimilarityFeedbackService(store, { _ -> true }, now = { 100L })

        val result = service.markNotRelated(10, 3, expectedMemberIds = setOf(1, 2, 3))

        assertThat(store.blockRequest).isEqualTo(
            BlockMemberRequest(10, 3, setOf(1, 2, 3), 100),
        )
        assertThat(result).isEqualTo(FeedbackResult.SavedPendingAnalysis(setOf(1, 2, 3)))
    }

    @Test
    fun staleGroupSnapshotDoesNotScheduleAnalysis() = runTest {
        val store = RecordingFeedbackStore(blockResult = FeedbackPersistenceResult.GroupUnavailable)
        var scheduled = false
        val service = SimilarityFeedbackService(store, { _ -> scheduled = true; true })

        val result = service.markNotRelated(10, 3, expectedMemberIds = setOf(1, 2, 3))

        assertThat(result).isEqualTo(FeedbackResult.GroupUnavailable)
        assertThat(scheduled).isFalse()
    }

    @Test
    fun blockedPairRejectsWholeManualMerge() = runTest {
        val store = RecordingFeedbackStore(mergeResult = FeedbackPersistenceResult.BlockedByUser)
        val service = SimilarityFeedbackService(store, { _ -> true })

        val result = service.mergeAsVersions(setOf(1, 2, 3))

        assertThat(result).isEqualTo(FeedbackResult.BlockedByUser)
        assertThat(store.mergeRequest?.documentIds).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun schedulerFailureReportsSavedFeedbackAndCanRetry() = runTest {
        val store = RecordingFeedbackStore(
            ignoreResult = FeedbackPersistenceResult.Saved(setOf(2L, 5L, 8L), generation = 5L),
        )
        var attempts = 0
        val service = SimilarityFeedbackService(
            store = store,
            reconcileQueued = { _ ->
                attempts += 1
                if (attempts == 1) error("enqueue failed")
                true
            },
        )

        val failed = service.ignoreGroup(10, expectedMemberIds = setOf(8, 2, 5))
        val retried = service.retryAnalysis(setOf(2, 5, 8))

        assertThat(failed).isEqualTo(FeedbackResult.AnalysisQueueFailed(setOf(2, 5, 8)))
        assertThat(retried).isEqualTo(FeedbackResult.SavedPendingAnalysis(setOf(2, 5, 8)))
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun cancellationAfterCommitLeavesDurableQueuedGeneration() = runTest {
        val store = RecordingFeedbackStore(
            mergeResult = FeedbackPersistenceResult.Saved(setOf(1L, 2L), generation = 9L),
        )
        val service = SimilarityFeedbackService(
            store = store,
            reconcileQueued = { _ -> throw CancellationException("screen closed") },
        )

        var cancelled = false
        try {
            service.mergeAsVersions(setOf(1L, 2L))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
        assertThat(store.mergeRequest?.documentIds).containsExactly(1L, 2L)
        assertThat(store.lastSavedGeneration).isEqualTo(9L)
    }

    @Test
    fun manualMergeHasBoundedSelection() = runTest {
        val store = RecordingFeedbackStore()
        val service = SimilarityFeedbackService(store, { _ -> true })

        val result = service.mergeAsVersions((1L..21L).toSet())

        assertThat(result).isEqualTo(FeedbackResult.TooManyDocuments)
        assertThat(store.mergeRequest).isNull()
    }

    @Test
    fun manualMergeClearsCurrentAndHistoricalAliasFingerprints() {
        val aliases = listOf(
            DocumentIdentityAliasEntity(oldDocumentId = 9, canonicalDocumentId = 4),
            DocumentIdentityAliasEntity(oldDocumentId = 4, canonicalDocumentId = 2),
        )

        val fingerprints = ignoredFingerprintsForManualMerge(setOf(2, 5), aliases)

        assertThat(fingerprints).containsExactly(
            similarityGroupFingerprint(setOf(2, 5)),
            similarityGroupFingerprint(setOf(4, 5)),
            similarityGroupFingerprint(setOf(9, 5)),
        )
    }

    @Test
    fun ignoredFingerprintDeletionChunksMoreThanSqliteVariableLimit() {
        val fingerprints = (1..2_005).map { "fingerprint-$it" }

        val chunks = chunkIgnoredFingerprints(fingerprints)

        assertThat(chunks.map { it.size }).containsExactly(900, 900, 205).inOrder()
        assertThat(chunks.flatten()).containsExactlyElementsIn(fingerprints).inOrder()
    }

    @Test
    fun retryAfterCompletedAnalysisCreatesOneNewDurableGeneration() = runTest {
        val store = RecordingFeedbackStore()
        val requestedGenerations = mutableListOf<Long?>()
        val service = SimilarityFeedbackService(
            store = store,
            reconcileQueued = { generation ->
                requestedGenerations += generation
                generation != null
            },
            now = { 300L },
        )

        val result = service.retryAnalysis(setOf(1L, 2L))

        assertThat(result).isEqualTo(FeedbackResult.SavedPendingAnalysis(setOf(1L, 2L)))
        assertThat(store.retryTimestamp).isEqualTo(300L)
        assertThat(requestedGenerations).containsExactly(null, 100L).inOrder()
    }
}

private class RecordingFeedbackStore(
    private val blockResult: FeedbackPersistenceResult = FeedbackPersistenceResult.GroupUnavailable,
    private val ignoreResult: FeedbackPersistenceResult = FeedbackPersistenceResult.GroupUnavailable,
    private val mergeResult: FeedbackPersistenceResult = FeedbackPersistenceResult.NotEnoughDocuments,
) : SimilarityFeedbackStore {
    var blockRequest: BlockMemberRequest? = null
    var ignoreRequest: IgnoreGroupRequest? = null
    var mergeRequest: MergeVersionsRequest? = null
    var retryTimestamp: Long? = null
    val lastSavedGeneration: Long?
        get() = listOf(blockResult, ignoreResult, mergeResult)
            .filterIsInstance<FeedbackPersistenceResult.Saved>()
            .lastOrNull()
            ?.generation

    override suspend fun blockMember(request: BlockMemberRequest): FeedbackPersistenceResult {
        blockRequest = request
        return blockResult
    }

    override suspend fun ignoreGroup(request: IgnoreGroupRequest): FeedbackPersistenceResult {
        ignoreRequest = request
        return ignoreResult
    }

    override suspend fun mergeVersions(request: MergeVersionsRequest): FeedbackPersistenceResult {
        mergeRequest = request
        return mergeResult
    }

    override suspend fun queueRetry(timestamp: Long): Long {
        retryTimestamp = timestamp
        return 100L
    }
}
