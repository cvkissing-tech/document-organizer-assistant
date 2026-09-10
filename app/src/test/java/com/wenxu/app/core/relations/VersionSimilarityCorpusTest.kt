package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.testDocument
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong
import org.junit.Test

class VersionSimilarityCorpusTest {
    private val normalizer = NameNormalizer()
    private val nameScorer = NameSimilarityScorer()
    private val versionScorer = VersionSimilarityScorer()

    @Test
    fun corpusSchemaCoverageFrozenSplitAndHashAreValid() {
        val loaded = loadCorpus()
        val corpus = loaded.rows

        assertThat(canonicalSha256(loaded.bytes)).isEqualTo(EXPECTED_CANONICAL_SHA256)
        assertThat(corpus).hasSize(EXPECTED_CORPUS_SIZE)
        assertThat(corpus.map { row -> row.id }.toSet()).hasSize(corpus.size)
        assertThat(corpus.map { row -> row.id }).containsExactlyElementsIn(1..EXPECTED_CORPUS_SIZE).inOrder()
        assertThat(corpus.map { row -> row.label }.toSet()).containsExactlyElementsIn(CorpusLabel.entries)
        assertThat(corpus.map { row -> row.split }.toSet()).containsExactlyElementsIn(CorpusSplit.entries)

        val regressionIds = corpus
            .filter { row -> row.split == CorpusSplit.ENGINEERING_REGRESSION }
            .map { row -> row.id }
        assertThat(regressionIds).containsExactlyElementsIn(3..EXPECTED_CORPUS_SIZE step 3).inOrder()
        assertThat(corpus.count { row -> row.split == CorpusSplit.ENGINEERING_REGRESSION }).isEqualTo(200)
        assertThat(corpus.count { row -> row.split == CorpusSplit.ENGINEERING_CALIBRATION }).isEqualTo(400)

        assertThat(corpus.count { row -> row.label == CorpusLabel.VERSION }).isAtLeast(150)
        assertThat(corpus.count { row -> row.label == CorpusLabel.EXACT_DUPLICATE }).isAtLeast(50)
        assertThat(corpus.count { row -> row.reason.startsWith("negative:") }).isAtLeast(200)
        assertThat(corpus.count { row -> row.reason.startsWith(STRONG_CONFLICT_PREFIX) }).isAtLeast(100)
        assertThat(corpus.any { row -> row.nameA.any(::isHanCharacter) }).isTrue()
        assertThat(corpus.any { row -> row.nameA.any { character -> character in 'a'..'z' } }).isTrue()
        assertThat(corpus.flatMap { row -> listOf(row.extensionA, row.extensionB) }.toSet())
            .containsAtLeast("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
        assertThat(corpus.any { row -> row.modifiedGapHours == null }).isTrue()
        assertThat(corpus.any { row -> row.sizeRatio == null }).isTrue()
        assertThat(corpus.any { row -> row.contentSimilarity == null }).isTrue()
        val scenarioNamespaces = corpus.map { row -> row.nameA.substringBefore('-') }
        assertThat(scenarioNamespaces.toSet()).hasSize(corpus.size)
        corpus.forEach { row ->
            assertThat(row.nameB).startsWith("${row.nameA.substringBefore('-')}-")
        }
    }

    @Test
    fun csvParserSupportsQuotedCommaAndEscapedQuote() {
        val fields = parseCsvRecord(
            "7,ENGINEERING_CALIBRATION,VERSION,\"Report, draft.docx\"," +
                "\"Report \"\"final\"\".docx\",docx,docx,SAME,24,1.02,0.90,quoted",
        )

        assertThat(fields).hasSize(EXPECTED_COLUMN_COUNT)
        assertThat(fields[3]).isEqualTo("Report, draft.docx")
        assertThat(fields[4]).isEqualTo("Report \"final\".docx")
    }

    @Test
    fun calibrationMetricsRemainObservableWithoutDefiningAReleaseClaim() {
        val metrics = evaluate(
            loadCorpus().rows.filter { row -> row.split == CorpusSplit.ENGINEERING_CALIBRATION },
        )

        println("VERSION_SIMILARITY_ENGINEERING_CALIBRATION ${metrics.report()}")
        assertNonEmptyMetricDenominators(metrics)
        assertThat(metrics.exactDuplicateCount).isGreaterThan(0)
    }

    @Test
    fun syntheticRegressionMeetsEngineeringGuardrailsOnly() {
        val metrics = evaluate(
            loadCorpus().rows.filter { row -> row.split == CorpusSplit.ENGINEERING_REGRESSION },
        )

        println("VERSION_SIMILARITY_ENGINEERING_REGRESSION ${metrics.report()}")
        assertNonEmptyMetricDenominators(metrics)
        assertThat(metrics.exactDuplicateCount).isGreaterThan(0)
        assertThat(metrics.highConfidenceCount).isAtLeast(MINIMUM_HIGH_CONFIDENCE_COUNT)
        assertThat(metrics.highConfidencePrecision).isAtLeast(0.95)
        assertThat(metrics.recall).isAtLeast(0.70)
        assertThat(metrics.strongConflictFalsePositives).isEqualTo(0)
    }

    @Test
    fun everyGeneratedCandidateContributesToThePrecisionDenominator() {
        val run = runCorpus(
            loadCorpus().rows.filter { row -> row.split == CorpusSplit.ENGINEERING_REGRESSION },
        )

        assertThat(run.candidates).hasSize(run.generatedCandidateCount)
        assertThat(run.candidates.count { candidate -> candidate.isSameCorpusRow })
            .isLessThan(run.generatedCandidateCount)
        assertThat(run.candidates.filter { candidate -> candidate.band == ConfidenceBand.HIGH })
            .containsExactlyElementsIn(run.predictedHigh)
    }

    @Test
    fun declaredStrongConflictsAreProducedByProductionNameRulesAndNeverBecomeHigh() {
        val rows = loadCorpus().rows
        val run = runCorpus(rows)
        val strong = rows.filter { row -> row.expectedConflict != null }.map { row ->
            val pair = VersionDocumentPair.of(firstId(row), secondId(row))
            val first = checkNotNull(run.documentsById[pair.first])
            val second = checkNotNull(run.documentsById[pair.second])
            val nameDecision = nameScorer.compare(first.features, second.features, run.weights)
            DeclaredEvaluation(
                row = row,
                actualConflicts = nameDecision.conflicts,
                finalBand = run.candidatesByPair[pair]?.band ?: ConfidenceBand.REJECTED,
            )
        }

        assertThat(strong).isNotEmpty()
        strong.forEach { item ->
            assertThat(item.actualConflicts).contains(item.row.expectedConflict)
            assertThat(item.finalBand).isNotEqualTo(ConfidenceBand.HIGH)
        }
        assertThat(strong.mapNotNull { item -> item.row.expectedConflict }.toSet())
            .containsAtLeast(ConflictCode.YEAR, ConflictCode.CHAPTER, ConflictCode.ASSIGNMENT)
        val assignmentNumbers = strong.filter { item ->
            item.row.expectedConflict == ConflictCode.ASSIGNMENT && "作业" in item.row.nameA
        }
        val courseNumbers = strong.filter { item -> "course-number" in item.row.reason }
        assertThat(assignmentNumbers).isNotEmpty()
        assertThat(courseNumbers).isNotEmpty()
        assertThat(assignmentNumbers.all { item -> ConflictCode.ASSIGNMENT in item.actualConflicts }).isTrue()
        assertThat(courseNumbers.all { item -> ConflictCode.ASSIGNMENT in item.actualConflicts }).isTrue()
    }

    @Test
    fun exactDuplicatesAreFoldedBeforeWeightsAndCandidateGeneration() {
        val rows = loadCorpus().rows.filter { row -> row.split == CorpusSplit.ENGINEERING_REGRESSION }
        val exact = rows.filter { row -> row.label == CorpusLabel.EXACT_DUPLICATE }
        val run = runCorpus(rows)

        assertThat(exact).hasSize(20)
        exact.forEach { row ->
            assertThat(row.nameA).isEqualTo(row.nameB)
            assertThat(row.sizeRatio).isEqualTo(1.0)
            assertThat(row.contentSimilarity).isEqualTo(1.0)
            assertThat(run.documentsById).containsKey(firstId(row))
            assertThat(run.documentsById).doesNotContainKey(secondId(row))
        }
        assertThat(run.logicalDocumentCount).isEqualTo(rows.size * 2 - exact.size)
        assertThat(run.exactDuplicateCount).isEqualTo(exact.size)
    }

    private fun assertNonEmptyMetricDenominators(metrics: CorpusMetrics) {
        assertThat(metrics.rowCount).isGreaterThan(0)
        assertThat(metrics.evaluatedCandidateCount).isGreaterThan(0)
        assertThat(metrics.highConfidenceCount).isGreaterThan(0)
        assertThat(metrics.actualVersionCount).isGreaterThan(0)
        assertThat(metrics.strongConflictCount).isGreaterThan(0)
    }

    private fun evaluate(rows: List<CorpusRow>): CorpusMetrics {
        val run = runCorpus(rows)
        val trueHigh = run.predictedHigh.count { candidate -> candidate.pair in run.versionPairs }
        val actualVersions = run.versionPairs.size
        val strongConflicts = run.candidates.filter { candidate ->
            candidate.actualConflicts.any(BLOCKING_CONFLICTS::contains)
        }
        require(rows.isNotEmpty()) { "Metric rows must not be empty" }
        require(run.candidates.isNotEmpty()) { "Evaluated-candidate denominator must not be zero" }
        require(run.predictedHigh.isNotEmpty()) { "High-confidence denominator must not be zero" }
        require(actualVersions > 0) { "Recall denominator must not be zero" }
        require(strongConflicts.isNotEmpty()) { "Strong-conflict set must not be empty" }
        return CorpusMetrics(
            rowCount = rows.size,
            logicalDocumentCount = run.logicalDocumentCount,
            generatedCandidateCount = run.generatedCandidateCount,
            evaluatedCandidateCount = run.candidates.size,
            versionPairsReachedCandidateStage = run.versionPairs.count(run.candidatesByPair::containsKey),
            highConfidenceCount = run.predictedHigh.size,
            trueHighCount = trueHigh,
            actualVersionCount = actualVersions,
            exactDuplicateCount = run.exactDuplicateCount,
            highConfidencePrecision = trueHigh.toDouble() / run.predictedHigh.size.toDouble(),
            recall = trueHigh.toDouble() / actualVersions.toDouble(),
            strongConflictCount = strongConflicts.size,
            strongConflictFalsePositives = strongConflicts.count { candidate ->
                candidate.band == ConfidenceBand.HIGH
            },
        )
    }

    private fun runCorpus(rows: List<CorpusRow>): CorpusRun {
        val documents = rows.flatMap { row ->
            buildList {
                add(CorpusDocument(row, features = features(row, CorpusSide.FIRST)))
                if (row.label != CorpusLabel.EXACT_DUPLICATE) {
                    add(CorpusDocument(row, features = features(row, CorpusSide.SECOND)))
                }
            }
        }
        val features = documents.map { item -> item.features }
        val documentsById = documents.associateBy { item -> item.features.document.id }
        val weights = NameCorpusWeights.from(features.map { item -> item.name })
        val versionPairs = rows.filter { row -> row.label == CorpusLabel.VERSION }
            .mapTo(hashSetOf()) { row -> VersionDocumentPair.of(firstId(row), secondId(row)) }
        val generated = boundedNameCandidatePairs(features, weights)
        val candidates = generated.map { generatedPair ->
            val first = documents[generatedPair.first]
            val second = documents[generatedPair.second]
            val pair = VersionDocumentPair.of(first.features.document.id, second.features.document.id)
            val nameDecision = nameScorer.compare(first.features, second.features, weights)
            val isSameRow = first.row.id == second.row.id
            val contentSimilarity = if (isSameRow) first.row.contentSimilarity else CROSS_ROW_CONTENT_SIMILARITY
            val decision = if (
                shouldEvaluateVersionCandidate(
                    first.features,
                    second.features,
                    nameDecision,
                    userAllowed = false,
                )
            ) {
                versionScorer.decide(
                    buildVersionCandidateSignals(
                        first = first.features,
                        second = second.features,
                        nameDecision = nameDecision,
                        contentSimilarity = contentSimilarity,
                        userAllowed = false,
                    ),
                )
            } else {
                VersionSimilarityDecision(
                    band = ConfidenceBand.REJECTED,
                    score = 0,
                    evidence = emptySet(),
                    conflicts = nameDecision.conflicts,
                )
            }
            CandidateEvaluation(
                pair = pair,
                isSameCorpusRow = isSameRow,
                actualConflicts = nameDecision.conflicts + decision.conflicts,
                band = decision.band,
            )
        }
        return CorpusRun(
            logicalDocumentCount = documents.size,
            exactDuplicateCount = rows.count { row -> row.label == CorpusLabel.EXACT_DUPLICATE },
            generatedCandidateCount = generated.size,
            documentsById = documentsById,
            weights = weights,
            versionPairs = versionPairs,
            candidates = candidates,
        )
    }

    private fun features(row: CorpusRow, side: CorpusSide): NameDocumentFeatures {
        val isFirst = side == CorpusSide.FIRST
        val name = if (isFirst) row.nameA else row.nameB
        val id = if (isFirst) firstId(row) else secondId(row)
        val extension = if (isFirst) row.extensionA else row.extensionB
        val document = testDocument(name, id).copy(
            uri = "content://corpus/${row.split}/${row.id}/${side.name.lowercase(Locale.ROOT)}",
            extension = extension,
            parentUri = if (isFirst || row.parentRelation == ParentRelation.SAME) {
                "content://corpus/folder/${row.id}"
            } else {
                "content://corpus/folder/${row.id}/other"
            },
            modifiedAt = if (isFirst) {
                row.modifiedGapHours?.let { BASE_MODIFIED_AT } ?: 0L
            } else {
                row.modifiedGapHours?.let { gap -> BASE_MODIFIED_AT + gap * HOUR_MILLIS } ?: 0L
            },
            sizeBytes = if (isFirst) {
                row.sizeRatio?.let { BASE_SIZE_BYTES } ?: 0L
            } else {
                row.sizeRatio?.let { ratio -> (BASE_SIZE_BYTES * ratio).roundToLong() } ?: 0L
            },
        )
        return NameDocumentFeatures(document, normalizer.analyze(name))
    }

    private fun loadCorpus(): LoadedCorpus {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(CORPUS_RESOURCE)) {
            "Missing corpus resource: $CORPUS_RESOURCE"
        }.use { stream -> stream.readBytes() }
        val lines = bytes.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        assertThat(lines).isNotEmpty()
        assertThat(parseCsvRecord(lines.first())).containsExactlyElementsIn(EXPECTED_HEADER)
        return LoadedCorpus(bytes, lines.drop(1).mapIndexed { index, line -> parseRow(index + 2, line) })
    }

    private fun parseRow(lineNumber: Int, line: String): CorpusRow {
        val cells = parseCsvRecord(line)
        check(cells.size == EXPECTED_COLUMN_COUNT) {
            "CSV row $lineNumber has ${cells.size} columns; expected $EXPECTED_COLUMN_COUNT"
        }
        fun required(index: Int): String = cells[index].trim().also { value ->
            check(value.isNotEmpty()) { "CSV row $lineNumber column $index is blank" }
        }
        fun optionalDouble(index: Int): Double? = cells[index].trim().takeIf(String::isNotEmpty)?.toDouble()
        fun optionalLong(index: Int): Long? = cells[index].trim().takeIf(String::isNotEmpty)?.toLong()
        val reason = required(11)

        return CorpusRow(
            id = required(0).toInt(),
            split = CorpusSplit.valueOf(required(1)),
            label = CorpusLabel.valueOf(required(2)),
            nameA = required(3),
            nameB = required(4),
            extensionA = required(5).lowercase(Locale.ROOT),
            extensionB = required(6).lowercase(Locale.ROOT),
            parentRelation = ParentRelation.valueOf(required(7)),
            modifiedGapHours = optionalLong(8),
            sizeRatio = optionalDouble(9),
            contentSimilarity = optionalDouble(10),
            reason = reason,
            expectedConflict = parseExpectedConflict(reason),
        ).also { row ->
            check(row.modifiedGapHours == null || row.modifiedGapHours >= 0L)
            check(row.sizeRatio == null || row.sizeRatio.isFinite() && row.sizeRatio > 0.0)
            check(row.contentSimilarity == null || row.contentSimilarity in 0.0..1.0)
        }
    }

    private fun parseExpectedConflict(reason: String): ConflictCode? {
        if (!reason.startsWith(STRONG_CONFLICT_PREFIX)) return null
        return ConflictCode.valueOf(reason.substringAfter(STRONG_CONFLICT_PREFIX).substringBefore(':'))
    }

    private fun canonicalSha256(bytes: ByteArray): String {
        val canonical = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
            .toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun firstId(row: CorpusRow): Long = row.id * 2L - 1L
    private fun secondId(row: CorpusRow): Long = row.id * 2L

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private data class LoadedCorpus(val bytes: ByteArray, val rows: List<CorpusRow>)
    private data class CorpusDocument(val row: CorpusRow, val features: NameDocumentFeatures)
    private data class CandidateEvaluation(
        val pair: VersionDocumentPair,
        val isSameCorpusRow: Boolean,
        val actualConflicts: Set<ConflictCode>,
        val band: ConfidenceBand,
    )
    private data class CorpusRun(
        val logicalDocumentCount: Int,
        val exactDuplicateCount: Int,
        val generatedCandidateCount: Int,
        val documentsById: Map<Long, CorpusDocument>,
        val weights: NameCorpusWeights,
        val versionPairs: Set<VersionDocumentPair>,
        val candidates: List<CandidateEvaluation>,
    ) {
        val candidatesByPair = candidates.associateBy { candidate -> candidate.pair }
        val predictedHigh = candidates.filter { candidate -> candidate.band == ConfidenceBand.HIGH }
    }
    private data class DeclaredEvaluation(
        val row: CorpusRow,
        val actualConflicts: Set<ConflictCode>,
        val finalBand: ConfidenceBand,
    )
    private data class CorpusMetrics(
        val rowCount: Int,
        val logicalDocumentCount: Int,
        val generatedCandidateCount: Int,
        val evaluatedCandidateCount: Int,
        val versionPairsReachedCandidateStage: Int,
        val highConfidenceCount: Int,
        val trueHighCount: Int,
        val actualVersionCount: Int,
        val exactDuplicateCount: Int,
        val highConfidencePrecision: Double,
        val recall: Double,
        val strongConflictCount: Int,
        val strongConflictFalsePositives: Int,
    ) {
        fun report(): String = String.format(
            Locale.ROOT,
            "rows=%d logicalDocuments=%d generatedCandidates=%d evaluatedCandidates=%d " +
                "versionPairsAtCandidateStage=%d highConfidence=%d trueHigh=%d " +
                "actualVersions=%d exactDuplicates=%d precision=%.4f recall=%.4f " +
                "strongConflicts=%d strongConflictFalsePositives=%d",
            rowCount, logicalDocumentCount, generatedCandidateCount, evaluatedCandidateCount,
            versionPairsReachedCandidateStage, highConfidenceCount, trueHighCount,
            actualVersionCount, exactDuplicateCount, highConfidencePrecision, recall,
            strongConflictCount, strongConflictFalsePositives,
        )
    }
    private data class CorpusRow(
        val id: Int,
        val split: CorpusSplit,
        val label: CorpusLabel,
        val nameA: String,
        val nameB: String,
        val extensionA: String,
        val extensionB: String,
        val parentRelation: ParentRelation,
        val modifiedGapHours: Long?,
        val sizeRatio: Double?,
        val contentSimilarity: Double?,
        val reason: String,
        val expectedConflict: ConflictCode?,
    )

    private enum class CorpusSide { FIRST, SECOND }
    private enum class CorpusSplit { ENGINEERING_CALIBRATION, ENGINEERING_REGRESSION }
    private enum class CorpusLabel { VERSION, NOT_VERSION, EXACT_DUPLICATE }
    private enum class ParentRelation { SAME, DIFFERENT }

    private companion object {
        const val CORPUS_RESOURCE = "version-similarity/pairs-zh-en.csv"
        val EXPECTED_HEADER = listOf(
            "id", "split", "label", "nameA", "nameB", "extensionA", "extensionB",
            "parentRelation", "modifiedGapHours", "sizeRatio", "contentSimilarity", "reason",
        )
        const val EXPECTED_COLUMN_COUNT = 12
        const val EXPECTED_CORPUS_SIZE = 600
        const val EXPECTED_CANONICAL_SHA256 =
            "0FAF33CCED1C9E8818BC6EFE084F9B46779688DC19FF716CB06F121D7BC8AAE6"
        const val MINIMUM_HIGH_CONFIDENCE_COUNT = 20
        const val BASE_MODIFIED_AT = 1_800_000_000_000L
        const val BASE_SIZE_BYTES = 1_000_000L
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        const val CROSS_ROW_CONTENT_SIMILARITY = 0.0
        const val STRONG_CONFLICT_PREFIX = "strong-conflict:"
        val BLOCKING_CONFLICTS = setOf(
            ConflictCode.FAMILY, ConflictCode.YEAR, ConflictCode.CHAPTER,
            ConflictCode.ASSIGNMENT, ConflictCode.PERSON, ConflictCode.CONTENT_DIVERGED,
            ConflictCode.USER_BLOCKED,
        )
    }
}

internal fun parseCsvRecord(line: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        when {
            character == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }
            character == '"' -> inQuotes = !inQuotes
            character == ',' && !inQuotes -> {
                values += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
        index += 1
    }
    require(!inQuotes) { "Unclosed quoted CSV field" }
    values += current.toString()
    return values
}
