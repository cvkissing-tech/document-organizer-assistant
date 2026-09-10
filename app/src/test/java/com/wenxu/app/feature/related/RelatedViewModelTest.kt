package com.wenxu.app.feature.related

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.R
import com.wenxu.app.MainDispatcherRule
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.relations.FeedbackResult
import com.wenxu.app.core.relations.SimilarityFeedbackController
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.relations.EvidenceCode
import com.wenxu.app.core.trash.TrashController
import com.wenxu.app.core.trash.TrashResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun keepsNameSimilarAndExactGroupsSeparate() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRelatedRepository(
            RelatedData(
                nameGroups = listOf(NameRelatedGroup(1, "实习报告", null, listOf(document(1), document(2)))),
                exactGroups = listOf(ExactRelatedGroup(2, listOf(document(3), document(4)))),
            ),
        )
        val viewModel = RelatedViewModel(repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.nameGroups).hasSize(1)
        assertThat(viewModel.state.value.exactGroups).hasSize(1)
        assertThat(viewModel.state.value.nameDocumentCount).isEqualTo(2)
        assertThat(viewModel.state.value.exactDocumentCount).isEqualTo(2)
    }

    @Test
    fun repositoryKeepsDetailedEvidenceForOnDemandExpansion() {
        val evidence = buildSimilarityEvidence(
            members = listOf(
                NameSimilarityMemberEntity(
                    groupId = 1,
                    documentId = 1,
                    differenceSegments = emptyList(),
                    evidenceCodes = setOf(
                        EvidenceCode.CORE_EXACT,
                        EvidenceCode.CONTENT_SIMILAR,
                        EvidenceCode.VERSION_MARKER,
                        EvidenceCode.SAME_FOLDER,
                        EvidenceCode.CLOSE_TIME,
                    ),
                ),
            ),
            documents = listOf(document(1), document(2)),
        )

        assertThat(evidence).hasSize(5)
        assertThat(evidence.map { it.text }).containsAtLeast(
            UiText.Resource(R.string.organization_evidence_core_name),
            UiText.Resource(R.string.organization_evidence_content),
            UiText.Resource(R.string.organization_evidence_version_marker),
        )
    }

    @Test
    fun confirmCurrentDelegatesGroupAndDocument() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRelatedRepository(RelatedData())
        val viewModel = RelatedViewModel(repository)

        viewModel.confirmCurrent(groupId = 7, documentId = 9)
        advanceUntilIdle()

        assertThat(repository.confirmed).isEqualTo(7L to 9L)
    }

    @Test
    fun openingRelatedDocumentUpdatesRecentHistory() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRelatedRepository(RelatedData())
        val viewModel = RelatedViewModel(repository)

        viewModel.markOpened(12)
        advanceUntilIdle()

        assertThat(repository.openedId).isEqualTo(12)
    }

    @Test
    fun exactGroupAlwaysKeepsOneDocument() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRelatedRepository(
            RelatedData(
                exactGroups = listOf(
                    ExactRelatedGroup(1, listOf(document(10), document(11))),
                ),
            ),
        )
        val viewModel = RelatedViewModel(repository)
        advanceUntilIdle()

        viewModel.toggleExact(10L)
        viewModel.toggleExact(11L)
        advanceUntilIdle()

        assertThat(viewModel.state.value.selectedExactIds).containsExactly(10L)
        assertThat(viewModel.state.value.action?.message)
            .isEqualTo(UiText.Resource(R.string.organization_keep_one_per_group))
    }

    @Test
    fun safetyCheckKeepsOneDocumentInEveryRequestedGroup() {
        val groups = listOf(
            ExactRelatedGroup(1, listOf(document(1), document(2))),
            ExactRelatedGroup(2, listOf(document(3), document(4), document(5))),
        )

        val safeIds = safeExactSelection(
            groups = groups,
            requested = setOf(1, 2, 3, 4, 5),
        )

        assertThat(safeIds.intersect(setOf(1, 2))).hasSize(1)
        assertThat(safeIds.intersect(setOf(3, 4, 5))).hasSize(2)
    }

    @Test
    fun trashActionOnlyProcessesRequestedDuplicateGroup() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRelatedRepository(
            RelatedData(
                exactGroups = listOf(
                    ExactRelatedGroup(1, listOf(document(1), document(2))),
                    ExactRelatedGroup(2, listOf(document(3), document(4))),
                ),
            ),
        )
        val trash = FakeRelatedTrashController()
        val viewModel = RelatedViewModel(repository, trash)
        advanceUntilIdle()
        viewModel.toggleExact(1)
        viewModel.toggleExact(3)

        viewModel.moveSelectedToTrash(setOf(1))
        advanceUntilIdle()

        assertThat(trash.trashedDocumentIds).containsExactly(1L)
        assertThat(viewModel.state.value.selectedExactIds).containsExactly(3L)
    }

    @Test
    fun notRelatedAndIgnoreDelegateToFeedbackService() = runTest(mainDispatcherRule.dispatcher) {
        val feedback = FakeFeedbackController()
        val viewModel = RelatedViewModel(FakeRelatedRepository(RelatedData()), feedbackController = feedback)

        viewModel.markNotRelated(groupId = 7, documentId = 9, expectedMemberIds = setOf(8, 9))
        advanceUntilIdle()
        assertThat(feedback.notRelated).isEqualTo(7L to 9L)
        assertThat(viewModel.state.value.action?.message)
            .isEqualTo(UiText.Resource(R.string.organization_not_related_saved))

        assertThat(feedback.expectedMemberIds).containsExactly(8L, 9L)

        viewModel.ignoreGroup(7, expectedMemberIds = setOf(8, 9))
        advanceUntilIdle()
        assertThat(feedback.ignoredGroupId).isEqualTo(7L)
        assertThat(viewModel.state.value.action?.message)
            .isEqualTo(UiText.Resource(R.string.organization_group_ignored))
    }

    @Test
    fun targetedDocumentsShowWaitingUntilMatchingGroupAppears() =
        runTest(mainDispatcherRule.dispatcher) {
            val data = MutableStateFlow(RelatedData())
            val viewModel = RelatedViewModel(
                repository = FakeRelatedRepository(data),
                targetDocumentIds = setOf(4, 5),
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.isTargetAnalysisPending).isTrue()

            data.value = RelatedData(
                nameGroups = listOf(
                    NameRelatedGroup(9, "报告", null, listOf(document(4), document(5))),
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.isTargetAnalysisPending).isFalse()
            assertThat(viewModel.state.value.targetGroupId).isEqualTo(9L)
            assertThat(viewModel.state.value.nameGroups.first().id).isEqualTo(9L)
            assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.Resolved)
        }

    @Test
    fun emptyTargetNeverResolvesOrExpandsAGroup() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = RelatedViewModel(
            repository = FakeRelatedRepository(
                RelatedData(
                    nameGroups = listOf(
                        NameRelatedGroup(9, "报告", null, listOf(document(4), document(5))),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.targetGroupId).isNull()
        assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.None)
        assertThat(viewModel.state.value.isTargetAnalysisPending).isFalse()
    }

    @Test
    fun resolvedTargetDoesNotReturnToWaitingWhenGroupDisappears() =
        runTest(mainDispatcherRule.dispatcher) {
            val data = MutableStateFlow(
                RelatedData(
                    nameGroups = listOf(
                        NameRelatedGroup(9, "报告", null, listOf(document(4), document(5))),
                    ),
                    analysisStatus = RelationAnalysisStatus.COMPLETED,
                    analysisGeneration = 7L,
                ),
            )
            val viewModel = RelatedViewModel(
                repository = FakeRelatedRepository(data),
                targetDocumentIds = setOf(4, 5),
            )
            advanceUntilIdle()
            assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.Resolved)

            data.value = data.value.copy(nameGroups = emptyList())
            advanceUntilIdle()

            assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.Dismissed)
            assertThat(viewModel.state.value.isTargetAnalysisPending).isFalse()
        }

    @Test
    fun completedAnalysisWithoutTargetEndsInNotFound() = runTest(mainDispatcherRule.dispatcher) {
        val data = MutableStateFlow(
            RelatedData(
                analysisStatus = RelationAnalysisStatus.QUEUED,
                analysisGeneration = 12L,
            ),
        )
        val viewModel = RelatedViewModel(
            repository = FakeRelatedRepository(data),
            targetDocumentIds = setOf(4, 5),
        )
        advanceUntilIdle()

        data.value = data.value.copy(analysisStatus = RelationAnalysisStatus.COMPLETED)
        advanceUntilIdle()

        assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.NotFound)
        assertThat(viewModel.state.value.isTargetAnalysisPending).isFalse()
    }

    @Test
    fun correctingResolvedTargetDismissesItsTargetLifecycle() =
        runTest(mainDispatcherRule.dispatcher) {
            val data = RelatedData(
                nameGroups = listOf(
                    NameRelatedGroup(9, "报告", null, listOf(document(4), document(5))),
                ),
            )
            val viewModel = RelatedViewModel(
                repository = FakeRelatedRepository(data),
                feedbackController = FakeFeedbackController(),
                targetDocumentIds = setOf(4, 5),
            )
            advanceUntilIdle()

            viewModel.ignoreGroup(9L, setOf(4L, 5L))
            advanceUntilIdle()

            assertThat(viewModel.state.value.targetLifecycle).isEqualTo(TargetLifecycle.Dismissed)
            assertThat(viewModel.state.value.targetGroupId).isNull()
            assertThat(viewModel.state.value.isTargetAnalysisPending).isFalse()
        }
}

private class FakeRelatedRepository(initial: RelatedData) : RelatedRepository {
    constructor(data: MutableStateFlow<RelatedData>) : this(data.value) {
        mutableData = data
    }

    private var mutableData = MutableStateFlow(initial)
    override val data: Flow<RelatedData> get() = mutableData
    var confirmed: Pair<Long, Long>? = null
    var openedId: Long? = null

    override suspend fun confirmCurrent(groupId: Long, documentId: Long) {
        confirmed = groupId to documentId
    }

    override suspend fun markOpened(documentId: Long) {
        openedId = documentId
    }
}

private fun document(id: Long) = DocumentRecord(
    id = id,
    uri = "content://documents/$id",
    displayName = "文档$id.pdf",
    extension = "pdf",
    sizeBytes = 1024,
    modifiedAt = id,
    sourceId = 1,
    parentUri = "content://documents",
)

private class FakeRelatedTrashController : TrashController {
    val trashedDocumentIds = mutableListOf<Long>()

    override suspend fun trash(documentId: Long): TrashResult {
        trashedDocumentIds += documentId
        return TrashResult.Success(trashId = documentId + 100)
    }

    override suspend fun trashMany(documentIds: Set<Long>): List<TrashResult> =
        documentIds.map { trash(it) }

    override suspend fun restore(trashId: Long, fallbackParentUri: String?): Result<Long> =
        Result.success(trashId)

    override suspend fun deleteForever(trashId: Long): Result<Unit> = Result.success(Unit)
}

private class FakeFeedbackController : SimilarityFeedbackController {
    var notRelated: Pair<Long, Long>? = null
    var ignoredGroupId: Long? = null
    var expectedMemberIds: Set<Long> = emptySet()

    override suspend fun markNotRelated(
        groupId: Long,
        documentId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult {
        notRelated = groupId to documentId
        this.expectedMemberIds = expectedMemberIds
        return FeedbackResult.SavedPendingAnalysis(setOf(documentId))
    }

    override suspend fun ignoreGroup(
        groupId: Long,
        expectedMemberIds: Set<Long>,
    ): FeedbackResult {
        ignoredGroupId = groupId
        this.expectedMemberIds = expectedMemberIds
        return FeedbackResult.SavedPendingAnalysis(expectedMemberIds)
    }

    override suspend fun mergeAsVersions(documentIds: Set<Long>): FeedbackResult =
        FeedbackResult.SavedPendingAnalysis(documentIds)

    override suspend fun retryAnalysis(documentIds: Set<Long>): FeedbackResult =
        FeedbackResult.SavedPendingAnalysis(documentIds)
}
