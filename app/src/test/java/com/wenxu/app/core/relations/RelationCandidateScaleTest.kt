package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.testDocument
import java.util.Random
import org.junit.Test

class RelationCandidateScaleTest {
    private val normalizer = NameNormalizer()

    @Test
    fun frequentReportCorpusStaysWithinDeterministicGlobalAndPerDocumentBounds() {
        val items = frequentReportItems(DOCUMENT_COUNT)
        val firstRun = candidateDocumentIds(items)
        val secondRun = candidateDocumentIds(items)
        val degrees = degrees(firstRun)

        val fullPairCount = DOCUMENT_COUNT.toLong() * (DOCUMENT_COUNT - 1L) / 2L
        println(
            "RELATION_CANDIDATE_SCALE documents=$DOCUMENT_COUNT candidates=${firstRun.size} " +
                "maxPerDocument=${degrees.values.max()} fullPairs=$fullPairCount",
        )
        assertThat(fullPairCount).isEqualTo(12_497_500L)
        assertThat(firstRun).isNotEmpty()
        assertThat(firstRun).isEqualTo(secondRun)
        assertThat(firstRun.size).isAtMost(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(degrees.values.max()).isAtMost(MAX_NAME_CANDIDATES_PER_DOCUMENT)
        assertThat(firstRun.size.toLong()).isLessThan(fullPairCount / 100L)
    }

    @Test
    fun adversarialHotBucketIsCappedFairAndStableAfterInputReordering() {
        val items = (1..HOT_BUCKET_DOCUMENTS).map { id ->
            item("统一报告_${VERSION_MARKERS[id % VERSION_MARKERS.size]}.docx", id.toLong())
        }
        val shuffled = items.shuffled(Random(20260905L))
        val originalPairs = candidateDocumentIds(items)
        val shuffledPairs = candidateDocumentIds(shuffled)
        val involvedIds = originalPairs.flatMapTo(hashSetOf()) { pair -> listOf(pair.first, pair.second) }

        println(
            "RELATION_HOT_BUCKET documents=$HOT_BUCKET_DOCUMENTS candidates=${originalPairs.size} " +
                "involvedDocuments=${involvedIds.size}",
        )

        assertThat(originalPairs.size).isAtMost(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(originalPairs).isEqualTo(shuffledPairs)
        assertThat(involvedIds).hasSize(HOT_BUCKET_DOCUMENTS)
        assertThat(degrees(originalPairs).values.max()).isAtMost(MAX_NAME_CANDIDATES_PER_DOCUMENT)
    }

    @Test
    fun oneHundredExactCoreDocumentsKeepEveryLegalPair() {
        val items = (1..SMALL_EXACT_DOCUMENTS).map { id ->
            item("百人项目_${VERSION_MARKERS[id % VERSION_MARKERS.size]}.docx", id.toLong())
        }
        val pairs = candidateDocumentIds(items)
        val expectedPairCount = SMALL_EXACT_DOCUMENTS * (SMALL_EXACT_DOCUMENTS - 1) / 2

        println("RELATION_SMALL_EXACT documents=$SMALL_EXACT_DOCUMENTS candidates=${pairs.size}")

        assertThat(pairs).hasSize(expectedPairCount)
        assertThat(degrees(pairs).values).containsExactlyElementsIn(
            List(SMALL_EXACT_DOCUMENTS) { SMALL_EXACT_DOCUMENTS - 1 },
        )
        assertThat(pairs).contains(VersionDocumentPair.of(1L, SMALL_EXACT_DOCUMENTS.toLong()))
    }

    @Test
    fun largeExactBucketPrioritizesStrongMetadataNeighbours() {
        val knownFirst = 50_001L
        val knownSecond = 50_002L
        val items = (1..LARGE_METADATA_BUCKET_DOCUMENTS).map { offset ->
            item("元数据项目_${VERSION_MARKERS[offset % VERSION_MARKERS.size]}.docx", offset.toLong())
                .let { item ->
                    item.copy(
                        document = item.document.copy(
                            parentUri = "content://folder/$offset",
                            modifiedAt = 1_000_000L + offset * 10_000L,
                            sizeBytes = 1_000_000L + offset * 10_000L,
                        ),
                    )
                }
        } + listOf(
            item("元数据项目_草稿.docx", knownFirst).let { item ->
                item.copy(document = item.document.copy(
                    parentUri = "content://folder/known",
                    modifiedAt = 9_000_000L,
                    sizeBytes = 3_000_000L,
                ))
            },
            item("元数据项目_最终版.docx", knownSecond).let { item ->
                item.copy(document = item.document.copy(
                    parentUri = "content://folder/known",
                    modifiedAt = 9_000_100L,
                    sizeBytes = 3_000_100L,
                ))
            },
        )

        assertThat(candidateDocumentIds(items))
            .contains(VersionDocumentPair.of(knownFirst, knownSecond))
    }

    @Test
    fun parentMetadataCandidateBudgetIsSharedAcrossManyFolders() {
        val items = (0 until MANY_PARENT_GROUPS).flatMap { parent ->
            (0 until DOCUMENTS_PER_PARENT_GROUP).map { offset ->
                val id = parent * DOCUMENTS_PER_PARENT_GROUP + offset + 1L
                item("多目录压力项目_${VERSION_MARKERS[offset % VERSION_MARKERS.size]}.docx", id)
                    .let { item ->
                        item.copy(
                            document = item.document.copy(
                                parentUri = "content://folder/many/$parent",
                                modifiedAt = 1_000_000L + offset,
                                sizeBytes = 1_000_000L + offset,
                            ),
                        )
                    }
            }
        }
        val diagnostics = exactMetadataCandidateDiagnostics(
            items = items,
            weights = NameCorpusWeights.from(items.map { item -> item.name }),
        ).single()

        println(
            "RELATION_METADATA_BUDGET documents=${items.size} parents=$MANY_PARENT_GROUPS " +
                "parentCandidates=${diagnostics.parentCandidates}",
        )

        assertThat(diagnostics.parentCandidates)
            .isAtMost(MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL)
        assertThat(diagnostics.modifiedCandidates)
            .isAtMost(MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL)
        assertThat(diagnostics.sizeCandidates)
            .isAtMost(MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL)
        assertThat(diagnostics.stableIdentityCandidates)
            .isAtMost(MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL)
        val pairs = candidateDocumentIds(items)
        assertThat(pairs).hasSize(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(pairs.flatMapTo(hashSetOf()) { pair -> listOf(pair.first, pair.second) })
            .hasSize(items.size)
        assertThat(degrees(pairs).values.max()).isAtMost(MAX_NAME_CANDIDATES_PER_DOCUMENT)
    }

    @Test
    fun exactCoverageFinishesBeforeSaturatedWeakSourcesUseRemainingBudget() {
        val exact = (0 until SATURATED_EXACT_BUCKETS).flatMap { bucket ->
            (1..HOT_BUCKET_DOCUMENTS).map { offset ->
                val id = bucket * HOT_BUCKET_DOCUMENTS + offset + 1L
                item("核心组${bucket}报告_${VERSION_MARKERS[offset % VERSION_MARKERS.size]}.docx", id)
            }
        }
        val protected = (1900..1999).map { year ->
            item("${year}年度弱来源报告_草稿.docx", 100_000L + year)
        }
        val token = (1..COMMON_TOKEN_DOCUMENTS).map { offset ->
            item("弱词元来源${offset}.docx", 200_000L + offset)
                .withTokens(setOf("weak-common", "weak-$offset"))
        }
        val allItems = exact + protected + token
        val pairs = candidateDocumentIds(allItems)
        val exactIds = exact.mapTo(hashSetOf()) { item -> item.document.id }
        val involvedExactIds = pairs.flatMapTo(hashSetOf()) { pair ->
            listOf(pair.first, pair.second).filter(exactIds::contains)
        }
        val weakIds = (protected + token).mapTo(hashSetOf()) { item -> item.document.id }
        val weakCandidateCount = pairs.count { pair -> pair.first in weakIds || pair.second in weakIds }

        println(
            "RELATION_EXACT_BEFORE_WEAK exactDocuments=${exactIds.size} " +
                "candidates=${pairs.size} weakCandidates=$weakCandidateCount",
        )

        assertThat(exactIds).hasSize(SATURATED_EXACT_BUCKETS * HOT_BUCKET_DOCUMENTS)
        assertThat(involvedExactIds).containsExactlyElementsIn(exactIds)
        assertThat(pairs.any { pair -> pair.first in weakIds || pair.second in weakIds }).isTrue()
        assertThat(pairs).hasSize(MAX_NAME_CANDIDATE_PAIRS)
    }

    @Test
    fun exactCoverageBeyondGlobalBudgetIsSharedAcrossBuckets() {
        val bucketById = mutableMapOf<Long, Int>()
        val exact = (0 until OVERFLOW_EXACT_BUCKETS).flatMap { bucket ->
            (1..HOT_BUCKET_DOCUMENTS).map { offset ->
                val id = bucket * HOT_BUCKET_DOCUMENTS + offset + 1L
                bucketById[id] = bucket
                item("溢出核心组${bucket}报告_${VERSION_MARKERS[offset % VERSION_MARKERS.size]}.docx", id)
            }
        }
        val pairs = candidateDocumentIds(exact)
        val involvedByBucket = IntArray(OVERFLOW_EXACT_BUCKETS)
        pairs.flatMap { pair -> listOf(pair.first, pair.second) }.toSet().forEach { id ->
            involvedByBucket[checkNotNull(bucketById[id])] += 1
        }

        println(
            "RELATION_MULTI_EXACT buckets=$OVERFLOW_EXACT_BUCKETS candidates=${pairs.size} " +
                "minInvolved=${involvedByBucket.min()} maxInvolved=${involvedByBucket.max()}",
        )

        assertThat(pairs).hasSize(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(involvedByBucket.min()).isGreaterThan(0)
        assertThat(involvedByBucket.max() - involvedByBucket.min()).isAtMost(2)
    }

    @Test
    fun candidateRelationsUseStableFileIdentityRatherThanDatabaseId() {
        val items = (1..HOT_BUCKET_DOCUMENTS).map { id ->
            item("统一报告_${VERSION_MARKERS[id % VERSION_MARKERS.size]}.docx", id.toLong())
        }
        val remapped = items.shuffled(Random(9032026L)).mapIndexed { index, item ->
            item.copy(document = item.document.copy(id = 900_000L + index))
        }

        assertThat(candidateDocumentUris(items)).isEqualTo(candidateDocumentUris(remapped))
    }

    @Test
    fun exactCoreAboveTwentyThousandDocumentsHitsTheExplicitGlobalBoundary() {
        val items = extremeExactItems()
        val pairs = candidateDocumentIds(items)
        val involved = pairs.flatMapTo(hashSetOf()) { pair -> listOf(pair.first, pair.second) }

        assertThat(pairs).hasSize(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(involved).hasSize(MAX_NAME_CANDIDATE_PAIRS * 2)
        assertThat(involved.size).isLessThan(ABOVE_GLOBAL_COVERAGE_DOCUMENTS)
        assertThat(candidateDocumentIds(items.reversed())).isEqualTo(pairs)
    }

    @Test
    fun oversizedCommonTokenBucketIsSampledInsteadOfDropped() {
        val items = (0 until COMMON_TOKEN_DOCUMENTS).map { offset ->
            val suffix = (0x4E00 + offset).toChar()
            item("共同高频报告$suffix.docx", offset + 1L)
        }

        val pairs = candidateDocumentIds(items)
        val reorderedPairs = candidateDocumentIds(items.shuffled(Random(42L)))

        println(
            "RELATION_COMMON_TOKEN_BUCKET documents=$COMMON_TOKEN_DOCUMENTS candidates=${pairs.size}",
        )

        assertThat(COMMON_TOKEN_DOCUMENTS).isGreaterThan(MAX_NAME_CANDIDATE_DOCUMENTS_PER_BUCKET)
        assertThat(pairs).isNotEmpty()
        assertThat(pairs).isEqualTo(reorderedPairs)
        assertThat(pairs.size).isAtMost(MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET * MAX_TOKENS_PER_DOCUMENT)
    }

    @Test
    fun singleBucketIteratorHasAHardPairLimit() {
        val pairs = stableBucketPairIterator(
            StableCandidateBucket(
                source = CandidateSourceKind.TOKEN,
                key = "adversarial",
                indices = (0 until COMMON_TOKEN_DOCUMENTS).toList(),
            ),
        ).asSequence().toList()

        assertThat(pairs).hasSize(MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET)
        assertThat(pairs.toSet()).hasSize(pairs.size)
    }

    @Test
    fun documentAppearingInManyBucketsHitsPerDocumentHardLimit() {
        val sharedTokens = (1..HUB_TOKEN_COUNT).map { number -> "shared-token-$number" }
        val hub = item("中心文档.docx", HUB_DOCUMENT_ID).withTokens(sharedTokens.toSet())
        val satellites = sharedTokens.flatMapIndexed { tokenIndex, token ->
            (1..SATELLITES_PER_TOKEN).map { offset ->
                val id = 200_000L + tokenIndex * SATELLITES_PER_TOKEN + offset
                item("分组${tokenIndex}文档${offset}.docx", id)
                    .withTokens(setOf(token, "unique-$id"))
            }
        }
        val pairs = candidateDocumentIds(listOf(hub) + satellites)
        val hubDegree = degrees(pairs).getValue(HUB_DOCUMENT_ID)

        assertThat(HUB_TOKEN_COUNT * SATELLITES_PER_TOKEN)
            .isGreaterThan(MAX_NAME_CANDIDATES_PER_DOCUMENT)
        assertThat(hubDegree).isEqualTo(MAX_NAME_CANDIDATES_PER_DOCUMENT)
        assertThat(degrees(pairs).values.max()).isAtMost(MAX_NAME_CANDIDATES_PER_DOCUMENT)
    }

    @Test
    fun obviousVersionPairSurvivesLargeNoisyCorpusAndInputReordering() {
        val noise = frequentReportItems(DOCUMENT_COUNT)
        val obvious = listOf(
            item("zz精准项目_草稿.docx", 100_001L),
            item("zz精准项目_最终版.docx", 100_002L),
        )
        val expected = VersionDocumentPair.of(100_001L, 100_002L)
        val items = noise + obvious

        assertThat(candidateDocumentIds(items)).contains(expected)
        assertThat(candidateDocumentIds(items.reversed())).contains(expected)
    }

    private fun frequentReportItems(count: Int): List<NameDocumentFeatures> =
        (0 until count).map { index ->
            val group = index / DOCUMENTS_PER_BUCKET
            val course = COURSE_NAMES[group % COURSE_NAMES.size]
            val marker = VERSION_MARKERS[index % VERSION_MARKERS.size]
            item("${course}专题${group}报告_$marker.docx", index + 1L)
        }

    private fun extremeExactItems(): List<NameDocumentFeatures> {
        val sharedName = "极端同名报告_草稿.docx"
        val sharedFeatures = normalizer.analyze(sharedName)
        return (1..ABOVE_GLOBAL_COVERAGE_DOCUMENTS).map { id ->
            val document = testDocument(sharedName, id.toLong()).copy(
                uri = "content://test/extreme/$id",
            )
            NameDocumentFeatures(document, sharedFeatures)
        }
    }

    private fun item(name: String, id: Long): NameDocumentFeatures {
        val document = testDocument(name, id).copy(uri = "content://test/document/$id/$name")
        return NameDocumentFeatures(document, normalizer.analyze(name))
    }

    private fun NameDocumentFeatures.withTokens(tokens: Set<String>): NameDocumentFeatures =
        copy(name = name.copy(characterTokens = tokens))

    private fun candidateDocumentIds(items: List<NameDocumentFeatures>): Set<VersionDocumentPair> =
        boundedNameCandidatePairs(
            items = items,
            weights = NameCorpusWeights.from(items.map { item -> item.name }),
        ).mapTo(linkedSetOf()) { pair ->
            VersionDocumentPair.of(items[pair.first].document.id, items[pair.second].document.id)
        }

    private fun candidateDocumentUris(items: List<NameDocumentFeatures>): Set<Set<String>> =
        boundedNameCandidatePairs(
            items = items,
            weights = NameCorpusWeights.from(items.map { item -> item.name }),
        ).mapTo(linkedSetOf()) { pair ->
            setOf(items[pair.first].document.uri, items[pair.second].document.uri)
        }

    private fun degrees(pairs: Set<VersionDocumentPair>): Map<Long, Int> = buildMap {
        pairs.forEach { pair ->
            put(pair.first, getOrDefault(pair.first, 0) + 1)
            put(pair.second, getOrDefault(pair.second, 0) + 1)
        }
    }

    private companion object {
        const val DOCUMENT_COUNT = 5_000
        const val DOCUMENTS_PER_BUCKET = 50
        const val HOT_BUCKET_DOCUMENTS = 1_000
        const val SMALL_EXACT_DOCUMENTS = 100
        const val LARGE_METADATA_BUCKET_DOCUMENTS = 500
        const val MANY_PARENT_GROUPS = 1_200
        const val DOCUMENTS_PER_PARENT_GROUP = 10
        const val SATURATED_EXACT_BUCKETS = 10
        const val OVERFLOW_EXACT_BUCKETS = 21
        const val ABOVE_GLOBAL_COVERAGE_DOCUMENTS = 20_001
        const val COMMON_TOKEN_DOCUMENTS = 200
        const val MAX_TOKENS_PER_DOCUMENT = 8
        const val HUB_TOKEN_COUNT = 8
        const val SATELLITES_PER_TOKEN = 40
        const val HUB_DOCUMENT_ID = 100_000L
        val COURSE_NAMES = listOf(
            "人工智能", "软件工程", "数据结构", "数据库", "高等数学",
            "大学英语", "市场营销", "财务管理", "机械设计", "电子商务",
        )
        val VERSION_MARKERS = listOf(
            "草稿", "初稿", "二稿", "三稿", "修改版",
            "修订版", "最终版", "终稿", "定稿", "最新版",
        )
    }
}
