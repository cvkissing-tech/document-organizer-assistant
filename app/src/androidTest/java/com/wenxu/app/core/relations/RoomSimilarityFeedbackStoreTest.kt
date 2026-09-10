package com.wenxu.app.core.relations

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateSetEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.testDocumentEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSimilarityFeedbackStoreTest {
    private lateinit var database: WenxuDatabase
    private lateinit var store: RoomSimilarityFeedbackStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WenxuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSimilarityFeedbackStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun mergeNeverOverwritesExistingBlockFeedback() = runTest {
        insertDocuments(1L, 2L, 3L)
        database.relationAnalysisDao().upsertFeedback(
            SimilarityFeedbackEntity.normalized(
                firstDocumentId = 1L,
                secondDocumentId = 2L,
                decision = SimilarityFeedbackDecision.BLOCK,
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )

        val result = store.mergeVersions(
            MergeVersionsRequest(setOf(1L, 2L, 3L), timestamp = 20L),
        )

        assertThat(result).isEqualTo(FeedbackPersistenceResult.BlockedByUser)
        assertThat(database.relationAnalysisDao().listFeedback()).containsExactly(
            SimilarityFeedbackEntity.normalized(
                firstDocumentId = 1L,
                secondDocumentId = 2L,
                decision = SimilarityFeedbackDecision.BLOCK,
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )
    }

    @Test
    fun mergeWritesEveryNormalizedAllowPairAndDurableQueueTogether() = runTest {
        insertDocuments(1L, 2L, 3L)

        val result = store.mergeVersions(
            MergeVersionsRequest(setOf(3L, 1L, 2L), timestamp = 20L),
        )

        assertThat(result).isEqualTo(
            FeedbackPersistenceResult.Saved(setOf(1L, 2L, 3L), generation = 1L),
        )
        assertThat(database.relationAnalysisDao().listFeedback().map {
            Triple(it.documentAId, it.documentBId, it.decision)
        }).containsExactly(
            Triple(1L, 2L, SimilarityFeedbackDecision.ALLOW),
            Triple(1L, 3L, SimilarityFeedbackDecision.ALLOW),
            Triple(2L, 3L, SimilarityFeedbackDecision.ALLOW),
        )
        assertThat(database.relationAnalysisDao().getState()?.status)
            .isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(database.relationAnalysisDao().getState()?.generation).isEqualTo(1L)
    }

    @Test
    fun mergeRejectsDifferentDocumentFamiliesWithoutQueueing() = runTest {
        insertDocument(1L, "pdf")
        insertDocument(2L, "docx")

        val result = store.mergeVersions(MergeVersionsRequest(setOf(1L, 2L), 20L))

        assertThat(result).isEqualTo(FeedbackPersistenceResult.IncompatibleFamily)
        assertThat(database.relationAnalysisDao().listFeedback()).isEmpty()
        assertThat(database.relationAnalysisDao().getState()).isNull()
    }

    @Test
    fun mergeRejectsDocumentsInTheSameExactDuplicateSet() = runTest {
        insertDocuments(1L, 2L)
        val setId = database.relationDao().insertExactSet(
            ExactDuplicateSetEntity(contentHash = "same", sizeBytes = 3L, updatedAt = 10L),
        )
        database.relationDao().insertExactMembers(
            listOf(
                ExactDuplicateMemberEntity(setId, 1L),
                ExactDuplicateMemberEntity(setId, 2L),
            ),
        )

        val result = store.mergeVersions(MergeVersionsRequest(setOf(1L, 2L), 20L))

        assertThat(result).isEqualTo(FeedbackPersistenceResult.ExactDuplicates)
        assertThat(database.relationAnalysisDao().listFeedback()).isEmpty()
        assertThat(database.relationAnalysisDao().getState()).isNull()
    }

    @Test
    fun cancellationAfterFeedbackCommitStillLeavesQueuedOutbox() = runTest {
        insertDocuments(1L, 2L)
        val service = SimilarityFeedbackService(
            store = store,
            reconcileQueued = { _ -> throw CancellationException("screen closed") },
            now = { 20L },
        )

        var cancelled = false
        try {
            service.mergeAsVersions(setOf(1L, 2L))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
        assertThat(database.relationAnalysisDao().listFeedback()).hasSize(1)
        assertThat(database.relationAnalysisDao().getState()?.status)
            .isEqualTo(RelationAnalysisStatus.QUEUED)
    }

    @Test
    fun staleVisibleSnapshotCannotWriteBlockFeedback() = runTest {
        insertDocuments(1L, 2L)
        val groupId = insertGroup(1L, 2L)

        val result = store.blockMember(
            BlockMemberRequest(
                groupId = groupId,
                documentId = 1L,
                expectedMemberIds = setOf(1L, 2L, 3L),
                timestamp = 20L,
            ),
        )

        assertThat(result).isEqualTo(FeedbackPersistenceResult.GroupUnavailable)
        assertThat(database.relationAnalysisDao().listFeedback()).isEmpty()
    }

    @Test
    fun ignoredFingerprintContainsOnlyCurrentlyActiveMembers() = runTest {
        insertDocuments(1L, 2L, 3L)
        val groupId = insertGroup(1L, 2L, 3L)
        database.documentDao().updateStatus(listOf(3L), DocumentIndexStatus.TRASHED)

        val result = store.ignoreGroup(
            IgnoreGroupRequest(
                groupId = groupId,
                expectedMemberIds = setOf(1L, 2L),
                timestamp = 20L,
            ),
        )

        assertThat(result).isEqualTo(
            FeedbackPersistenceResult.Saved(setOf(1L, 2L), generation = 1L),
        )
        assertThat(
            database.relationAnalysisDao().isGroupIgnored(similarityGroupFingerprint(setOf(1L, 2L))),
        ).isTrue()
        assertThat(database.relationAnalysisDao().listIgnoredGroups().single().memberIds)
            .containsExactly(1L, 2L)
        assertThat(
            database.relationAnalysisDao().isGroupIgnored(
                similarityGroupFingerprint(setOf(1L, 2L, 3L)),
            ),
        ).isFalse()
    }

    private suspend fun insertDocuments(vararg ids: Long) {
        database.sourceDao().upsert(
            ScanSourceEntity(
                id = 1L,
                treeUri = "content://test/tree",
                displayName = "Test",
            ),
        )
        ids.forEach { id ->
            insertDocument(id, "pdf", insertSource = false)
        }
    }

    private suspend fun insertDocument(id: Long, extension: String, insertSource: Boolean = true) {
        if (insertSource) {
            database.sourceDao().upsert(
                ScanSourceEntity(
                    id = 1L,
                    treeUri = "content://test/tree",
                    displayName = "Test",
                ),
            )
        }
        database.documentDao().upsert(
            testDocumentEntity(
                id = id,
                uri = "content://test/$id.$extension",
                displayName = "report_$id.$extension",
            ).copy(
                extension = extension,
                mimeType = if (extension == "pdf") "application/pdf" else "application/octet-stream",
            ),
        )
    }

    private suspend fun insertGroup(vararg documentIds: Long): Long {
        val groupId = database.relationDao().upsertNameGroup(
            NameSimilarityGroupEntity(
                baseName = "report",
                extension = "pdf",
                updatedAt = 10L,
            ),
        )
        database.relationDao().insertNameMembers(
            documentIds.map { documentId ->
                NameSimilarityMemberEntity(
                    groupId = groupId,
                    documentId = documentId,
                    differenceSegments = emptyList(),
                )
            },
        )
        return groupId
    }
}
