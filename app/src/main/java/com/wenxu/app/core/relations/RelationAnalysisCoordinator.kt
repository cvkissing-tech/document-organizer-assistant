package com.wenxu.app.core.relations

import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.model.DocumentRecord
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

fun interface ExactDuplicateAnalysis {
    suspend fun analyze(): List<ExactDuplicateGroup>
}

interface RelationAnalysisStore {
    suspend fun activeDocumentEntities(): List<DocumentEntity>
    suspend fun begin(generation: Long, runToken: String): Boolean
    suspend fun phase(
        generation: Long,
        runToken: String,
        phase: RelationAnalysisPhase,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
    ): Boolean
    suspend fun feedback(): List<SimilarityFeedbackEntity>
    suspend fun replace(
        generation: Long,
        runToken: String,
        exactGroups: List<ExactDuplicateGroup>,
        groups: List<NameGroupDraft>,
        candidates: List<SimilarityCandidateEntity>,
        fingerprints: List<DocumentFingerprintEntity>,
        failedCount: Int,
    ): Boolean
}

data class RelationAnalysisReport(
    val completed: Boolean,
    val exactSetCount: Int,
    val highConfidenceGroupCount: Int,
    val candidatePairCount: Int,
    val failedExtractions: Int,
    val versionMemberIds: List<Long> = emptyList(),
)

class RelationAnalysisCoordinator(
    private val exactDuplicateAnalysis: ExactDuplicateAnalysis,
    private val store: RelationAnalysisStore,
    private val fingerprintProvider: RelationFingerprintProvider,
    private val minHash: MinHashFingerprint,
    private val normalizer: NameNormalizer = NameNormalizer(),
    private val nameScorer: NameSimilarityScorer = NameSimilarityScorer(),
    private val versionScorer: VersionSimilarityScorer = VersionSimilarityScorer(),
    private val clusterBuilder: StrictClusterBuilder = StrictClusterBuilder(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val phaseObserver: (RelationAnalysisPhase) -> Unit = {},
) {
    suspend fun run(
        generation: Long,
        runToken: String,
    ): RelationAnalysisReport {
        if (!store.begin(generation, runToken)) return emptyReport()
        var failedExtractions = 0
        var exactSetCount = 0
        var candidatePairCount = 0
        return run {
            if (!advance(generation, runToken, RelationAnalysisPhase.EXACT_DUPLICATES)) {
                return emptyReport()
            }
            val exactGroups = exactDuplicateAnalysis.analyze()
            exactSetCount = exactGroups.size
            val documents = store.activeDocumentEntities()
            val documentsById = documents.associateBy { document -> document.id }
            val representativeByDocument = duplicateRepresentatives(
                documentsById = documentsById,
                exactGroups = exactGroups,
            )
            val logicalDocuments = documents.filter { document ->
                representativeByDocument[document.id]?.let { representative ->
                    representative == document.id
                } ?: true
            }

            if (!advance(generation, runToken, RelationAnalysisPhase.NAME_CANDIDATES)) {
                return report(false, exactSetCount, 0, 0, failedExtractions, emptyList())
            }
            val items = logicalDocuments.map { document ->
                NameDocumentFeatures(document.toRecord(), normalizer.analyze(document.displayName))
            }
            val itemsById = items.associateBy { item -> item.document.id }
            val weights = NameCorpusWeights.from(items.map { item -> item.name })
            val feedback = normalizedFeedback(store.feedback(), representativeByDocument, documentsById)
            val candidatePairs = boundedNameCandidatePairs(items, weights).mapTo(linkedSetOf()) { pair ->
                VersionDocumentPair.of(
                    items[pair.first].document.id,
                    items[pair.second].document.id,
                )
            }.apply {
                feedback.filterValues { decision -> decision == SimilarityFeedbackDecision.ALLOW }
                    .keys
                    .forEach(::add)
            }
            candidatePairCount = candidatePairs.size

            val contexts = candidatePairs.mapNotNull { pair ->
                val first = itemsById[pair.first] ?: return@mapNotNull null
                val second = itemsById[pair.second] ?: return@mapNotNull null
                if (feedback[pair] == SimilarityFeedbackDecision.BLOCK) return@mapNotNull null
                val userAllowed = feedback[pair] == SimilarityFeedbackDecision.ALLOW
                val nameDecision = nameScorer.compare(first, second, weights)
                CandidateContext(
                    pair = pair,
                    first = first,
                    second = second,
                    nameDecision = nameDecision,
                    userAllowed = userAllowed,
                ).takeIf {
                    shouldEvaluateVersionCandidate(first, second, nameDecision, userAllowed)
                }
            }

            if (!advance(
                    generation,
                    runToken,
                    RelationAnalysisPhase.CONTENT_VERIFICATION,
                    candidateCount = contexts.size,
                )
            ) {
                return report(false, exactSetCount, 0, candidatePairCount, failedExtractions, emptyList())
            }
            val preparedById = linkedMapOf<Long, PreparedFingerprint>()
            val fingerprintDocuments = contexts.asSequence()
                .filterNot { context -> context.userAllowed }
                .flatMap { context -> sequenceOf(context.first.document.id, context.second.document.id) }
                .distinct()
                .mapNotNull(documentsById::get)
                .toList()
            fingerprintDocuments.forEach { document ->
                val prepared = try {
                    fingerprintProvider.prepare(document)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PreparedFingerprint(null, null, failed = true)
                }
                if (prepared.failed) failedExtractions += 1
                fingerprintProvider.checkpoint(prepared)
                preparedById[document.id] = prepared
            }

            val evaluated = contexts.map { context ->
                val contentSimilarity = if (context.userAllowed) {
                    null
                } else {
                    val first = preparedById[context.first.document.id]?.fingerprint
                    val second = preparedById[context.second.document.id]?.fingerprint
                    if (first != null && second != null &&
                        first.algorithmVersion == second.algorithmVersion
                    ) {
                        minHash.similarity(first.signature, second.signature)
                    } else {
                        null
                    }
                }
                val decision = if (context.userAllowed) {
                    VersionSimilarityDecision(
                        band = ConfidenceBand.HIGH,
                        score = 100,
                        evidence = setOf(EvidenceCode.USER_ALLOWED),
                        conflicts = emptySet(),
                    )
                } else {
                    versionScorer.decide(
                        buildVersionCandidateSignals(
                            first = context.first,
                            second = context.second,
                            nameDecision = context.nameDecision,
                            contentSimilarity = contentSimilarity,
                            userAllowed = context.userAllowed,
                        ),
                    )
                }
                EvaluatedCandidate(context, contentSimilarity, decision)
            }
            val retained = evaluated.filter { candidate ->
                candidate.decision.band != ConfidenceBand.REJECTED
            }
            val highEdges = retained.filter { candidate ->
                candidate.decision.band == ConfidenceBand.HIGH
            }.associate { candidate -> candidate.context.pair to candidate.decision.score }
            val groups = createGroupDrafts(
                clusters = clusterBuilder.build(highEdges),
                itemsById = itemsById,
                decisionsByPair = retained.associateBy { candidate -> candidate.context.pair },
            )
            val candidates = retained.map { candidate -> candidate.toEntity(nowMillis()) }
            val fingerprints = preparedById.values.mapNotNull { prepared -> prepared.entity }

            if (!advance(
                    generation,
                    runToken,
                    RelationAnalysisPhase.SAVING,
                    processedCount = contexts.size,
                    candidateCount = contexts.size,
                    failedCount = failedExtractions,
                )
            ) {
                return report(false, exactSetCount, groups.size, candidatePairCount, failedExtractions, emptyList())
            }
            val saved = store.replace(
                generation = generation,
                runToken = runToken,
                exactGroups = exactGroups,
                groups = groups,
                candidates = candidates,
                fingerprints = fingerprints,
                failedCount = failedExtractions,
            )
            report(
                completed = saved,
                exactSetCount = exactSetCount,
                highConfidenceGroupCount = groups.size,
                candidatePairCount = candidatePairCount,
                failedExtractions = failedExtractions,
                versionMemberIds = if (saved) {
                    groups.flatMap { group -> group.members.map { member -> member.documentId } }
                } else {
                    emptyList()
                },
            )
        }
    }

    private suspend fun advance(
        generation: Long,
        runToken: String,
        phase: RelationAnalysisPhase,
        processedCount: Int = 0,
        candidateCount: Int = 0,
        failedCount: Int = 0,
    ): Boolean {
        val current = store.phase(
            generation = generation,
            runToken = runToken,
            phase = phase,
            processedCount = processedCount,
            candidateCount = candidateCount,
            failedCount = failedCount,
        )
        if (current) phaseObserver(phase)
        return current
    }

    private fun duplicateRepresentatives(
        documentsById: Map<Long, DocumentEntity>,
        exactGroups: List<ExactDuplicateGroup>,
    ): Map<Long, Long> = buildMap {
        exactGroups.forEach { group ->
            val members = group.memberIds.mapNotNull(documentsById::get)
            val representative = members.sortedWith(
                compareBy<DocumentEntity> { document ->
                    normalizer.analyze(document.displayName).markers.count { marker ->
                        marker.kind == NameMarkerKind.COPY
                    }
                }
                    .thenByDescending { document ->
                        normalizer.analyze(document.displayName).coreKey.length
                    }
                    .thenByDescending { document -> document.modifiedAt }
                    .thenBy { document -> document.id },
            ).firstOrNull() ?: return@forEach
            members.forEach { document -> put(document.id, representative.id) }
        }
    }

    private fun normalizedFeedback(
        feedback: List<SimilarityFeedbackEntity>,
        representativeByDocument: Map<Long, Long>,
        documentsById: Map<Long, DocumentEntity>,
    ): Map<VersionDocumentPair, SimilarityFeedbackDecision> {
        val result = linkedMapOf<VersionDocumentPair, SimilarityFeedbackDecision>()
        feedback.forEach { entity ->
            val first = representativeByDocument[entity.documentAId] ?: entity.documentAId
            val second = representativeByDocument[entity.documentBId] ?: entity.documentBId
            if (first == second || first !in documentsById || second !in documentsById) return@forEach
            val pair = VersionDocumentPair.of(first, second)
            val previous = result[pair]
            result[pair] = if (
                previous == SimilarityFeedbackDecision.BLOCK ||
                entity.decision == SimilarityFeedbackDecision.BLOCK
            ) {
                SimilarityFeedbackDecision.BLOCK
            } else {
                SimilarityFeedbackDecision.ALLOW
            }
        }
        return result
    }

    private fun createGroupDrafts(
        clusters: List<Set<Long>>,
        itemsById: Map<Long, NameDocumentFeatures>,
        decisionsByPair: Map<VersionDocumentPair, EvaluatedCandidate>,
    ): List<NameGroupDraft> = clusters.filter { cluster -> cluster.size > 1 }.map { cluster ->
        val members = cluster.mapNotNull(itemsById::get)
        val reference = members.sortedWith(
            compareByDescending<NameDocumentFeatures> { item -> item.document.modifiedAt }
                .thenBy { item -> item.document.id },
        ).first()
        val baseName = members.groupingBy { item -> item.name.coreName }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }
                    .thenBy { entry -> entry.key.length }
                    .thenBy { entry -> entry.key },
            ).first().key
        NameGroupDraft(
            baseName = baseName,
            extension = canonicalExtension(reference.document.extension),
            analysis = NameGroupAnalysisDraft(
                referenceDocumentId = reference.document.id,
                confidenceBand = ConfidenceBand.HIGH,
                analysisVersion = ANALYSIS_VERSION,
            ),
            members = members.sortedWith(
                compareByDescending<NameDocumentFeatures> { item -> item.document.modifiedAt }
                    .thenBy { item -> item.document.id },
            ).map { member ->
                val relative = if (member.document.id == reference.document.id) {
                    null
                } else {
                    decisionsByPair[VersionDocumentPair.of(reference.document.id, member.document.id)]
                }
                NameGroupMemberDraft(
                    documentId = member.document.id,
                    differenceSegments = member.name.differenceSegments,
                    score = relative?.decision?.score ?: 100,
                    evidenceCodes = relative?.decision?.evidence.orEmpty(),
                )
            },
        )
    }.sortedWith(compareBy<NameGroupDraft> { draft -> draft.baseName }.thenBy { draft -> draft.extension })

    private fun EvaluatedCandidate.toEntity(updatedAt: Long): SimilarityCandidateEntity =
        SimilarityCandidateEntity.normalized(
            firstDocumentId = context.pair.first,
            secondDocumentId = context.pair.second,
            nameScore = context.nameDecision.score,
            contentScore = contentSimilarity,
            metadataScore = (decision.score - (context.nameDecision.score * 60).roundToInt())
                .coerceAtLeast(0),
            confidenceBand = decision.band,
            evidenceCodes = decision.evidence,
            analysisVersion = ANALYSIS_VERSION,
            updatedAt = updatedAt,
        )

    private fun DocumentEntity.toRecord() = DocumentRecord(
        id = id,
        uri = uri,
        displayName = displayName,
        extension = extension,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        sourceId = sourceId,
        parentUri = parentUri,
    )

    private fun canonicalExtension(extension: String): String = when (DocumentFamily.fromExtension(extension)) {
        DocumentFamily.PDF -> "pdf"
        DocumentFamily.WORD -> "docx"
        DocumentFamily.SHEET -> "xlsx"
        DocumentFamily.SLIDES -> "pptx"
        null -> extension.lowercase()
    }

    private fun emptyReport() = report(false, 0, 0, 0, 0, emptyList())

    private fun report(
        completed: Boolean,
        exactSetCount: Int,
        highConfidenceGroupCount: Int,
        candidatePairCount: Int,
        failedExtractions: Int,
        versionMemberIds: List<Long>,
    ) = RelationAnalysisReport(
        completed = completed,
        exactSetCount = exactSetCount,
        highConfidenceGroupCount = highConfidenceGroupCount,
        candidatePairCount = candidatePairCount,
        failedExtractions = failedExtractions,
        versionMemberIds = versionMemberIds,
    )

    private data class CandidateContext(
        val pair: VersionDocumentPair,
        val first: NameDocumentFeatures,
        val second: NameDocumentFeatures,
        val nameDecision: NameSimilarityDecision,
        val userAllowed: Boolean,
    )

    private data class EvaluatedCandidate(
        val context: CandidateContext,
        val contentSimilarity: Double?,
        val decision: VersionSimilarityDecision,
    )

    private companion object {
        const val ANALYSIS_VERSION = 1
    }
}
