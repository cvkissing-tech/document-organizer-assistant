package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.FingerprintExtractStatus
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.trash.mergeIdentityFeedback
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RelationAnalysisCoordinatorTest {
    @Test
    fun exactDuplicatesAreCollapsedBeforeVersionScoring() = runTest {
        val events = mutableListOf<String>()
        val documents = listOf(
            document(1, "实习报告_草稿.docx", modifiedAt = 3_000),
            document(2, "实习报告_副本.docx", modifiedAt = 5_000),
            document(3, "实习报告_最终版.docx", modifiedAt = 4_000),
        )
        val store = FakeRelationAnalysisStore(documents, events)
        val coordinator = coordinator(
            store = store,
            events = events,
            exactGroups = listOf(ExactDuplicateGroup("hash", 1_001, listOf(1, 2))),
        )

        val report = coordinator.run(7, "run-7")

        assertThat(events).containsExactly(
            "EXACT_DUPLICATES",
            "NAME_CANDIDATES",
            "CONTENT_VERIFICATION",
            "SAVING",
        ).inOrder()
        assertThat(report.versionMemberIds).containsNoDuplicates()
        assertThat(report.versionMemberIds).doesNotContain(2L)
        assertThat(report.exactSetCount).isEqualTo(1)
    }

    @Test
    fun oneUnreadableCandidateDoesNotFailTheAnalysis() = runTest {
        val documents = listOf(
            document(1, "实习报告_草稿.docx", modifiedAt = 1_000),
            document(3, "实习报告_最终版.docx", modifiedAt = 2_000),
        )
        val store = FakeRelationAnalysisStore(documents)
        val coordinator = coordinator(store, failFingerprintFor = setOf(3))

        val report = coordinator.run(8, "run-8")

        assertThat(report.failedExtractions).isEqualTo(1)
        assertThat(report.completed).isTrue()
        assertThat(store.saved).isTrue()
    }

    @Test
    fun staleGenerationNeverReplacesSavedRelations() = runTest {
        val store = FakeRelationAnalysisStore(
            documents = listOf(
                document(1, "实习报告_草稿.docx"),
                document(2, "实习报告_最终版.docx"),
            ),
            acceptSave = false,
        )

        val report = coordinator(store).run(3, "run-3")

        assertThat(report.completed).isFalse()
        assertThat(store.saved).isFalse()
    }

    @Test
    fun candidateEvidenceIsSavedButOnlyHighEdgesBecomeGroups() = runTest {
        val documents = listOf(
            document(1, "实习报告.docx", modifiedAt = 1_000),
            document(2, "实习报告.docx", modifiedAt = 2_000),
        )
        val store = FakeRelationAnalysisStore(documents)
        val minHash = testMinHash()
        val coordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis { emptyList() },
            store = store,
            fingerprintProvider = RelationFingerprintProvider {
                PreparedFingerprint(null, null, failed = false)
            },
            minHash = minHash,
        )

        coordinator.run(9, "run-9")

        assertThat(store.savedCandidates).hasSize(1)
        assertThat(store.savedCandidates.single().confidenceBand).isEqualTo(ConfidenceBand.CANDIDATE)
        assertThat(store.savedCandidates.single().evidenceCodes)
            .containsAtLeast(EvidenceCode.CORE_EXACT, EvidenceCode.SAME_FOLDER)
        assertThat(store.savedGroups).isEmpty()
    }

    @Test
    fun blockedFeedbackWinsButAllowedSameFamilyCanCreateAHighEdge() = runTest {
        val documents = listOf(
            document(1, "完全不同的名字_甲.docx"),
            document(2, "另一份材料_乙.docx"),
            document(3, "第三份材料_丙.docx"),
        )
        val feedback = listOf(
            feedback(1, 2, SimilarityFeedbackDecision.ALLOW),
            feedback(1, 2, SimilarityFeedbackDecision.BLOCK),
            feedback(2, 3, SimilarityFeedbackDecision.ALLOW),
        )
        val store = FakeRelationAnalysisStore(documents, feedback = feedback)

        coordinator(store).run(4, "run-4")

        assertThat(store.savedCandidates.map { it.documentAId to it.documentBId })
            .contains(2L to 3L)
        assertThat(store.savedCandidates.map { it.documentAId to it.documentBId })
            .doesNotContain(1L to 2L)
        assertThat(store.savedGroups.flatMap { it.members }.map { it.documentId })
            .containsExactly(2L, 3L)
    }

    @Test
    fun migratedRestoreCollisionFeedbackStillControlsReanalysis() = runTest {
        val canonicalId = 1L
        val collisionId = 9L
        val allowId = 2L
        val blockId = 3L
        val migratedFeedback = mergeIdentityFeedback(
            feedback = listOf(
                feedback(collisionId, allowId, SimilarityFeedbackDecision.ALLOW),
                feedback(collisionId, blockId, SimilarityFeedbackDecision.ALLOW),
                feedback(canonicalId, blockId, SimilarityFeedbackDecision.BLOCK),
                feedback(canonicalId, collisionId, SimilarityFeedbackDecision.ALLOW),
            ),
            canonicalDocumentId = canonicalId,
            collisionDocumentId = collisionId,
        )
        val store = FakeRelationAnalysisStore(
            documents = listOf(
                document(canonicalId, "完全不同的名字_甲.docx"),
                document(allowId, "另一份材料_乙.docx"),
                document(blockId, "第三份材料_丙.docx"),
            ),
            feedback = migratedFeedback,
        )

        coordinator(store).run(46, "run-46")

        assertThat(store.savedCandidates.map { it.documentAId to it.documentBId })
            .contains(canonicalId to allowId)
        assertThat(store.savedCandidates.map { it.documentAId to it.documentBId })
            .doesNotContain(canonicalId to blockId)
    }

    @Test
    fun allowedFeedbackCannotCrossDocumentFamiliesOrChangeDuplicateFacts() = runTest {
        val documents = listOf(
            document(1, "报告.docx"),
            document(2, "报告.pdf"),
            document(3, "报告_副本.docx"),
        )
        val store = FakeRelationAnalysisStore(
            documents = documents,
            feedback = listOf(
                feedback(1, 2, SimilarityFeedbackDecision.ALLOW),
                feedback(1, 3, SimilarityFeedbackDecision.ALLOW),
            ),
        )

        val report = coordinator(
            store = store,
            exactGroups = listOf(ExactDuplicateGroup("hash", 1_001, listOf(1, 3))),
        ).run(5, "run-5")

        assertThat(report.exactSetCount).isEqualTo(1)
        assertThat(store.savedCandidates).isEmpty()
        assertThat(store.savedGroups).isEmpty()
    }

    @Test
    fun generationSupersededAfterExactAnalysisWritesNoRelationTables() = runTest {
        val store = FakeRelationAnalysisStore(
            documents = listOf(
                document(1, "报告_草稿.docx"),
                document(2, "报告_最终版.docx"),
            ),
        )
        val coordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis {
                store.supersedeWith(11)
                listOf(ExactDuplicateGroup("hash", 1_001, listOf(1, 2)))
            },
            store = store,
            fingerprintProvider = RelationFingerprintProvider {
                error("过期代次不应读取指纹")
            },
            minHash = testMinHash(),
        )

        val report = coordinator.run(10, "run-10")

        assertThat(report.completed).isFalse()
        assertThat(store.saved).isFalse()
        assertThat(store.savedExactGroups).isEmpty()
        assertThat(store.savedFingerprints).isEmpty()
        assertThat(store.savedCandidates).isEmpty()
        assertThat(store.savedGroups).isEmpty()
    }

    @Test
    fun coordinatorCancellationLeavesStateForWorkerRunnerToResolve() = runTest {
        val currentStore = FakeRelationAnalysisStore(emptyList())
        val currentCoordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis { throw CancellationException("stop") },
            store = currentStore,
            fingerprintProvider = RelationFingerprintProvider { error("unused") },
            minHash = testMinHash(),
        )

        val currentFailure = runCatching { currentCoordinator.run(20, "run-20") }.exceptionOrNull()
        assertThat(currentFailure).isInstanceOf(CancellationException::class.java)
        assertThat(currentStore.runningToken).isEqualTo("run-20")
        assertThat(currentStore.queuedGeneration).isNull()

        val newerStore = FakeRelationAnalysisStore(emptyList())
        val newerCoordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis {
                newerStore.supersedeWith(22)
                throw CancellationException("superseded")
            },
            store = newerStore,
            fingerprintProvider = RelationFingerprintProvider { error("unused") },
            minHash = testMinHash(),
        )
        val newerFailure = runCatching { newerCoordinator.run(21, "run-21") }.exceptionOrNull()
        assertThat(newerFailure).isInstanceOf(CancellationException::class.java)
        assertThat(newerStore.queuedGeneration).isNull()
        assertThat(newerStore.latestGeneration).isEqualTo(22)
    }

    @Test
    fun newerRunTokenFencesEveryLateWriteFromOlderWorker() = runTest {
        val store = FakeRelationAnalysisStore(emptyList())
        assertThat(store.begin(40, "token-A")).isTrue()
        assertThat(store.begin(40, "token-B")).isTrue()
        assertThat(
            store.replace(
                generation = 40,
                runToken = "token-B",
                exactGroups = emptyList(),
                groups = emptyList(),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 0,
            ),
        ).isTrue()

        assertThat(store.phase(40, "token-A", RelationAnalysisPhase.SAVING, 9, 9, 9)).isFalse()
        assertThat(store.fail(40, "token-A", 9)).isFalse()
        assertThat(store.cancel(40, "token-A")).isFalse()
        assertThat(
            store.replace(
                generation = 40,
                runToken = "token-A",
                exactGroups = listOf(ExactDuplicateGroup("stale", 1, emptyList())),
                groups = emptyList(),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 9,
            ),
        ).isFalse()

        assertThat(store.completedToken).isEqualTo("token-B")
        assertThat(store.savedExactGroups).isEmpty()
    }

    @Test
    fun olderRunTokenCannotChangeNewerWorkersFailedState() = runTest {
        val store = FakeRelationAnalysisStore(emptyList())
        assertThat(store.begin(41, "token-A")).isTrue()
        assertThat(store.begin(41, "token-B")).isTrue()
        assertThat(store.fail(41, "token-B", 2)).isTrue()

        assertThat(store.phase(41, "token-A", RelationAnalysisPhase.SAVING, 9, 9, 9)).isFalse()
        assertThat(store.fail(41, "token-A", 9)).isFalse()
        assertThat(store.cancel(41, "token-A")).isFalse()
        assertThat(
            store.replace(
                generation = 41,
                runToken = "token-A",
                exactGroups = listOf(ExactDuplicateGroup("stale", 1, emptyList())),
                groups = emptyList(),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 9,
            ),
        ).isFalse()
        assertThat(store.failedToken).isEqualTo("token-B")
        assertThat(store.queuedGeneration).isNull()
        assertThat(store.savedExactGroups).isEmpty()
    }

    @Test
    fun beginAllowsRunningTakeoverButRejectsCompletedAndFailedState() = runTest {
        val completedStore = FakeRelationAnalysisStore(emptyList())
        assertThat(completedStore.begin(42, "token-A")).isTrue()
        assertThat(completedStore.begin(42, "token-B")).isTrue()
        assertThat(
            completedStore.replace(
                generation = 42,
                runToken = "token-B",
                exactGroups = emptyList(),
                groups = emptyList(),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 0,
            ),
        ).isTrue()
        assertThat(completedStore.begin(42, "token-C")).isFalse()
        assertThat(completedStore.completedToken).isEqualTo("token-B")

        val failedStore = FakeRelationAnalysisStore(emptyList())
        assertThat(failedStore.begin(43, "token-A")).isTrue()
        assertThat(failedStore.fail(43, "token-A", 1)).isTrue()
        assertThat(failedStore.begin(43, "token-B")).isFalse()
        assertThat(failedStore.failedToken).isEqualTo("token-A")
    }

    @Test
    fun coordinatorRethrowsStaleFailureWithoutChangingNewerFailedOwner() = runTest {
        val store = FakeRelationAnalysisStore(emptyList())
        val staleCoordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis {
                assertThat(store.begin(44, "token-B")).isTrue()
                assertThat(store.fail(44, "token-B", 2)).isTrue()
                throw IOException("token-A finished late")
            },
            store = store,
            fingerprintProvider = RelationFingerprintProvider { error("unused") },
            minHash = testMinHash(),
        )

        val error = runCatching { staleCoordinator.run(44, "token-A") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(store.failedToken).isEqualTo("token-B")
        assertThat(store.queuedGeneration).isNull()
    }

    @Test
    fun coordinatorLeavesOwnedRunRunningWhenAnalysisThrowsForRunnerToResolve() = runTest {
        val store = FakeRelationAnalysisStore(emptyList())
        val coordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis { throw IOException("temporary") },
            store = store,
            fingerprintProvider = RelationFingerprintProvider { error("unused") },
            minHash = testMinHash(),
        )

        val error = runCatching { coordinator.run(45, "token-A") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(store.runningToken).isEqualTo("token-A")
        assertThat(store.failedToken).isNull()
        assertThat(store.queuedGeneration).isNull()
    }

    @Test
    fun cancellationCheckpointsFinishedFingerprintAndNextRunDoesNotExtractItAgain() = runTest {
        val documents = listOf(
            document(1, "实习报告_草稿.docx", modifiedAt = 1_000),
            document(2, "实习报告_最终版.docx", modifiedAt = 2_000),
        )
        val store = FakeRelationAnalysisStore(documents)
        val fingerprints = InterruptibleCheckpointProvider(testMinHash())
        val coordinator = RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis { emptyList() },
            store = store,
            fingerprintProvider = fingerprints,
            minHash = testMinHash(),
        )

        val cancelled = runCatching { coordinator.run(30, "run-30-a") }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(fingerprints.checkpointedIds).containsExactly(1L)

        val resumed = coordinator.run(30, "run-30-b")

        assertThat(resumed.completed).isTrue()
        assertThat(fingerprints.extractionsById[1]).isEqualTo(1)
        assertThat(fingerprints.extractionsById[2]).isEqualTo(2)
    }

    private fun coordinator(
        store: FakeRelationAnalysisStore,
        events: MutableList<String> = mutableListOf(),
        exactGroups: List<ExactDuplicateGroup> = emptyList(),
        failFingerprintFor: Set<Long> = emptySet(),
    ): RelationAnalysisCoordinator {
        val minHash = testMinHash()
        val fingerprints = FakeFingerprintProvider(minHash, failFingerprintFor)
        return RelationAnalysisCoordinator(
            exactDuplicateAnalysis = ExactDuplicateAnalysis {
                exactGroups
            },
            store = store,
            fingerprintProvider = fingerprints,
            minHash = minHash,
            phaseObserver = { phase ->
                if (events !== store.events) events += phase.name
            },
        )
    }

    private fun feedback(
        first: Long,
        second: Long,
        decision: SimilarityFeedbackDecision,
    ) = SimilarityFeedbackEntity.normalized(first, second, decision, 1, 1)

    private fun document(
        id: Long,
        name: String,
        modifiedAt: Long = 1_000,
    ) = DocumentEntity(
        id = id,
        uri = "content://document/$id",
        displayName = name,
        normalizedName = name.lowercase(),
        mimeType = "application/octet-stream",
        extension = name.substringAfterLast('.'),
        sizeBytes = 1_000 + id,
        modifiedAt = modifiedAt,
        sourceId = 1,
        parentUri = "content://tree/documents/folder",
        indexStatus = DocumentIndexStatus.ACTIVE,
        lastSeenScanId = "scan",
    )
}

private class InterruptibleCheckpointProvider(
    private val minHash: MinHashFingerprint,
) : RelationFingerprintProvider {
    private val cached = linkedMapOf<Long, DocumentFingerprint>()
    private var interruptSecond = true
    val checkpointedIds = mutableListOf<Long>()
    val extractionsById = mutableMapOf<Long, Int>()

    override suspend fun prepare(document: DocumentEntity): PreparedFingerprint {
        cached[document.id]?.let { fingerprint ->
            return PreparedFingerprint(fingerprint, null, failed = false)
        }
        extractionsById[document.id] = (extractionsById[document.id] ?: 0) + 1
        if (document.id == 2L && interruptSecond) {
            interruptSecond = false
            throw CancellationException("system stopped")
        }
        val fingerprint = DocumentFingerprint(
            documentId = document.id,
            algorithmVersion = minHash.algorithmVersion,
            signature = minHash.create("共同正文 ${document.displayName}"),
            normalizedTextLength = 20,
            structure = StructureSignature.EMPTY,
        )
        return PreparedFingerprint(
            fingerprint = fingerprint,
            entity = DocumentFingerprintStore.toEntity(
                fingerprint = fingerprint,
                status = FingerprintExtractStatus.SUCCESS,
                basisSize = document.sizeBytes,
                basisModifiedAt = document.modifiedAt,
                updatedAt = 1,
            ),
            failed = false,
        )
    }

    override suspend fun checkpoint(prepared: PreparedFingerprint) {
        val fingerprint = prepared.fingerprint ?: return
        cached[fingerprint.documentId] = fingerprint
        checkpointedIds += fingerprint.documentId
    }
}

private class FakeFingerprintProvider(
    private val minHash: MinHashFingerprint,
    private val failFor: Set<Long>,
) : RelationFingerprintProvider {
    override suspend fun prepare(document: DocumentEntity): PreparedFingerprint {
        if (document.id in failFor) return PreparedFingerprint(null, null, failed = true)
        val fingerprint = DocumentFingerprint(
            documentId = document.id,
            algorithmVersion = minHash.algorithmVersion,
            signature = minHash.create("共同正文 ${document.displayName.substringBefore('_')}") ,
            normalizedTextLength = 20,
            structure = StructureSignature(1, 0, 0, emptySet()),
        )
        return PreparedFingerprint(fingerprint, null, failed = false)
    }
}

private class FakeRelationAnalysisStore(
    private val documents: List<DocumentEntity>,
    val events: MutableList<String> = mutableListOf(),
    private val feedback: List<SimilarityFeedbackEntity> = emptyList(),
    private val acceptSave: Boolean = true,
) : RelationAnalysisStore {
    var saved = false
    var savedExactGroups: List<ExactDuplicateGroup> = emptyList()
    var savedFingerprints: List<DocumentFingerprintEntity> = emptyList()
    var savedGroups: List<NameGroupDraft> = emptyList()
    var savedCandidates: List<SimilarityCandidateEntity> = emptyList()
    var latestGeneration: Long? = null
        private set
    var queuedGeneration: Long? = null
        private set
    var completedToken: String? = null
        private set
    var failedToken: String? = null
        private set
    val runningToken: String?
        get() = activeToken.takeIf { status == RelationAnalysisStatus.RUNNING }
    private var activeToken: String? = null
    private var status: RelationAnalysisStatus? = null

    override suspend fun activeDocumentEntities(): List<DocumentEntity> = documents

    override suspend fun begin(generation: Long, runToken: String): Boolean {
        val latest = latestGeneration
        if (latest != null && latest > generation) return false
        if (latest == generation && status in TERMINAL_STATUSES) return false
        latestGeneration = generation
        status = RelationAnalysisStatus.RUNNING
        activeToken = runToken
        queuedGeneration = null
        return true
    }

    override suspend fun phase(
        generation: Long,
        runToken: String,
        phase: RelationAnalysisPhase,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
    ): Boolean {
        if (!isCurrentRun(generation, runToken)) return false
        events += phase.name
        return true
    }

    override suspend fun feedback(): List<SimilarityFeedbackEntity> = feedback

    override suspend fun replace(
        generation: Long,
        runToken: String,
        exactGroups: List<ExactDuplicateGroup>,
        groups: List<NameGroupDraft>,
        candidates: List<SimilarityCandidateEntity>,
        fingerprints: List<DocumentFingerprintEntity>,
        failedCount: Int,
    ): Boolean {
        if (!acceptSave || !isCurrentRun(generation, runToken)) return false
        saved = true
        savedExactGroups = exactGroups
        savedFingerprints = fingerprints
        savedGroups = groups
        savedCandidates = candidates
        status = RelationAnalysisStatus.COMPLETED
        completedToken = runToken
        activeToken = null
        return true
    }

    suspend fun fail(
        generation: Long,
        runToken: String,
        failedCount: Int,
    ): Boolean {
        if (!isCurrentRun(generation, runToken)) return false
        status = RelationAnalysisStatus.FAILED
        failedToken = runToken
        activeToken = null
        return true
    }

    suspend fun cancel(generation: Long, runToken: String): Boolean {
        if (!isCurrentRun(generation, runToken)) return false
        status = RelationAnalysisStatus.QUEUED
        activeToken = null
        queuedGeneration = generation
        return true
    }

    fun supersedeWith(generation: Long) {
        latestGeneration = generation
        status = RelationAnalysisStatus.QUEUED
        activeToken = null
    }

    private fun isCurrentRun(generation: Long, runToken: String): Boolean =
        latestGeneration == generation &&
            status == RelationAnalysisStatus.RUNNING &&
            activeToken == runToken

    private companion object {
        val TERMINAL_STATUSES = setOf(
            RelationAnalysisStatus.COMPLETED,
            RelationAnalysisStatus.FAILED,
        )
    }
}
