package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NameSimilarityServiceTest {
    private val normalizer = NameNormalizer()

    @Test
    fun doesNotGroupDifferentExtensions() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("报告.pdf", id = 1),
                testDocument("报告.docx", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun groupsLegacyAndModernExtensionsWithinOneOfficeFamily() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("实习报告草稿.doc", id = 1),
                testDocument("实习报告最终版.docx", id = 2),
            ),
        )

        assertThat(groups.single().memberIds).containsExactly(1L, 2L)
        assertThat(groups.single().extension).isEqualTo("docx")
    }

    @Test
    fun publicMediaStoreCollectionDoesNotPretendAllDocumentsShareAFolder() {
        val collection = "content://media/external/file"
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("全盘实习报告草稿.docx", id = 1).copy(parentUri = collection),
                testDocument("全盘实习报告最终版.docx", id = 2).copy(parentUri = collection),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun nameScorerAlsoRejectsPublicMediaStoreCollectionAsFolderEvidence() {
        val collection = "content://media/external/file"
        val first = NameDocumentFeatures(
            testDocument("大学生实习报告.docx", id = 1).copy(parentUri = collection),
            normalizer.analyze("大学生实习报告.docx"),
        )
        val second = NameDocumentFeatures(
            testDocument("大学生毕业实习报告.docx", id = 2).copy(parentUri = collection),
            normalizer.analyze("大学生毕业实习报告.docx"),
        )
        val weights = NameCorpusWeights.from(listOf(first.name, second.name))

        assertThat(NameSimilarityScorer().compare(first, second, weights).accepted).isFalse()
    }

    @Test
    fun unrelatedNamesRemainSeparate() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("课程报告.pdf", id = 1),
                testDocument("课堂总结.pdf", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun groupsReviewerAndVersionNamesWithoutRequiringExactNormalizedText() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("毕业实习报告.docx", id = 1),
                testDocument("毕业实习报告_张老师修改_v2.docx", id = 2),
            ),
        )

        assertThat(groups.single().baseName).isEqualTo("毕业实习报告")
        assertThat(groups.single().memberIds).containsExactly(1L, 2L)
    }

    @Test
    fun genericSharedWordsDoNotMergeDifferentTopics() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("人工智能课程报告.docx", id = 1),
                testDocument("市场营销课程报告.docx", id = 2),
                testDocument("毕业实习报告.docx", id = 3),
                testDocument("物理实验报告.docx", id = 4),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun differentTopicYearsRemainSeparateEvenWhenMostCharactersMatch() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("2025年度工作报告.docx", id = 1),
                testDocument("2026年度工作报告.docx", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun differentProtectedChaptersRemainSeparate() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("课程报告第1章_v1.docx", id = 1),
                testDocument("课程报告第2章_v2.docx", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun sharedProtectedYearStillLeavesDifferentReportNumbersInConflict() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("2026年度毕业实习报告1_草稿.docx", id = 1),
                testDocument("2026年度毕业实习报告2_最终版.docx", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun repeatedYearLikeNumberOutsideYearRoleStillConflicts() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("2026年度毕业实习报告2026_草稿.docx", id = 1),
                testDocument("2026年度毕业实习报告2027_最终版.docx", id = 2),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun chineseAndArabicChapterFormsDoNotConflictWhenTheirValuesMatch() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("人工智能课程毕业实习报告第一章_草稿.docx", id = 1),
                testDocument("人工智能课程毕业实习报告第1章_最终版.docx", id = 2),
            ),
        )

        assertThat(groups.single().memberIds).containsExactly(1L, 2L)
    }

    @Test
    fun strongEdgesOnBothSidesOfAProtectedYearConflictStillCapTheGroupAtTwo() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("毕业实习报告_2025年度_草稿.docx", id = 1),
                testDocument("毕业实习报告_草稿.docx", id = 2),
                testDocument("毕业实习报告_2026年度_最终版.docx", id = 3),
            ),
        )

        assertThat(groups).isNotEmpty()
        assertThat(groups.maxOf { group -> group.memberIds.size }).isEqualTo(2)
    }

    @Test
    fun protectedNeutralCandidatesAreSampledBeyondEightyDocuments() {
        fun candidates(count: Int): Set<NameCandidatePair> {
            val items = (1900 until 1900 + count).mapIndexed { index, year ->
                val document = testDocument("${year}年度报告_草稿.docx", id = index + 1L)
                NameDocumentFeatures(document, normalizer.analyze(document.displayName))
            }
            return boundedNameCandidatePairs(items, NameCorpusWeights.from(items.map { item -> item.name }))
        }

        assertThat(candidates(80)).isNotEmpty()
        assertThat(candidates(81)).isNotEmpty()
        assertThat(candidates(81).size).isAtMost(MAX_NAME_CANDIDATE_PAIRS)
    }

    @Test
    fun exactCoreCandidatesHavePerBucketAndGlobalUpperBounds() {
        val items = (1..500).map { id ->
            val document = testDocument("同名实习报告_草稿.docx", id = id.toLong())
            NameDocumentFeatures(document, normalizer.analyze(document.displayName))
        }

        val candidates = boundedNameCandidatePairs(
            items,
            NameCorpusWeights.from(items.map { item -> item.name }),
        )

        assertThat(candidates.size).isAtLeast((items.size + 1) / 2)
        assertThat(candidates.size).isAtMost(MAX_NAME_CANDIDATE_PAIRS)
        assertThat(candidates.flatMapTo(hashSetOf()) { pair -> listOf(pair.first, pair.second) })
            .hasSize(items.size)
    }

    @Test
    fun exactCorePairIsPrioritizedBeforeLargeWeakBucketsConsumeTheBudget() {
        val noise = (0 until 4).flatMap { topic ->
            (0 until 80).map { offset ->
                val id = topic * 80 + offset + 1L
                val document = testDocument(
                    "${1900 + offset}年度专题${topic}报告_草稿.docx",
                    id = id,
                )
                NameDocumentFeatures(document, normalizer.analyze(document.displayName))
            }
        }
        val precise = listOf(
            testDocument("zz精准项目_草稿.docx", id = 10_001),
            testDocument("zz精准项目_最终版.docx", id = 10_002),
        ).map { document ->
            NameDocumentFeatures(document, normalizer.analyze(document.displayName))
        }
        val items = noise + precise

        val candidates = boundedNameCandidatePairs(
            items,
            NameCorpusWeights.from(items.map { item -> item.name }),
        )

        assertThat(candidates.size).isAtMost(10_000)
        assertThat(candidates).contains(NameCandidatePair.of(noise.size, noise.size + 1))
    }

    @Test
    fun exactCoreBucketsShareBudgetBeforeOneLargeBucketCanConsumeIt() {
        val largeBucket = (1..142).map { id ->
            val document = testDocument("aa大型项目_草稿.docx", id = id.toLong())
            NameDocumentFeatures(document, normalizer.analyze(document.displayName))
        }
        val trailingExactBucket = listOf(
            testDocument("zz精准项目_草稿.docx", id = 10_001),
            testDocument("zz精准项目_最终版.docx", id = 10_002),
        ).map { document ->
            NameDocumentFeatures(document, normalizer.analyze(document.displayName))
        }
        val items = largeBucket + trailingExactBucket

        val candidates = boundedNameCandidatePairs(
            items,
            NameCorpusWeights.from(items.map { item -> item.name }),
        )

        assertThat(candidates.size).isAtMost(10_000)
        assertThat(candidates).contains(
            NameCandidatePair.of(largeBucket.size, largeBucket.size + 1),
        )
    }

    @Test
    fun fuzzyCandidateWithoutVerificationDoesNotBecomeAGroup() {
        val first = testDocument("大学生实习报告.docx", id = 1)
            .copy(parentUri = "content://folder/a")
        val second = testDocument("大学生毕业实习报告.docx", id = 2)
            .copy(parentUri = "content://folder/b")

        assertThat(NameSimilarityService(normalizer).group(listOf(first, second))).isEmpty()

        val sameFolderGroups = NameSimilarityService(normalizer).group(
            listOf(first, second.copy(parentUri = first.parentUri)),
        )
        assertThat(sameFolderGroups).isEmpty()
    }

    @Test
    fun weakSimilarityChainDoesNotCreateAGroup() {
        val groups = NameSimilarityService(normalizer).group(
            listOf(
                testDocument("人工智能课程学习报告.docx", id = 1),
                testDocument("人工智能课程学习总结.docx", id = 2),
                testDocument("人工智能学习总结.docx", id = 3),
            ),
        )

        assertThat(groups).isEmpty()
    }

    @Test
    fun displayOrderUsesTimeButDoesNotCreateVersionLabels() {
        val older = testDocument("报告草稿.pdf", id = 1).copy(modifiedAt = 1_000)
        val newer = testDocument("报告最终版.pdf", id = 2).copy(modifiedAt = 2_000)

        val group = NameSimilarityService(normalizer).group(listOf(older, newer)).single()

        assertThat(group.baseName).isEqualTo("报告")
        assertThat(group.memberIds).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun explicitConfirmationSurvivesTimestampChanges() = runTest {
        val store = FakeNameSimilarityStore(
            listOf(
                testDocument("报告草稿.pdf", id = 1).copy(modifiedAt = 1_000),
                testDocument("报告最终版.pdf", id = 2).copy(modifiedAt = 2_000),
            ),
        )
        val service = NameSimilarityService(normalizer, store)
        service.rebuild()
        service.confirmCurrent(groupId = 1, documentId = 1)

        store.documents = store.documents.map { document ->
            document.copy(modifiedAt = if (document.id == 1L) 3_000 else 500)
        }
        service.rebuild()

        assertThat(store.confirmedDocumentId).isEqualTo(1L)
    }

    @Test
    fun mixedOfficeFamilyKeepsStableGroupKeyAndConfirmationWhenFormatMajorityChanges() = runTest {
        val store = FakeNameSimilarityStore(
            listOf(
                testDocument("报告初稿.doc", id = 1),
                testDocument("报告二稿.docx", id = 2),
                testDocument("报告最终版.docx", id = 3),
            ),
        )
        val service = NameSimilarityService(normalizer, store)
        service.rebuild()
        service.confirmCurrent(groupId = 1, documentId = 3)

        store.documents = listOf(
            testDocument("报告初稿.doc", id = 1),
            testDocument("报告二稿.doc", id = 2),
            testDocument("报告最终版.docx", id = 3),
        )
        service.rebuild()

        assertThat(store.groupKey).isEqualTo("报告" to "docx")
        assertThat(store.confirmedDocumentId).isEqualTo(3L)
    }

    @Test
    fun cannotConfirmDocumentOutsideGroup() = runTest {
        val store = FakeNameSimilarityStore(
            listOf(
                testDocument("报告草稿.pdf", id = 1),
                testDocument("报告最终版.pdf", id = 2),
            ),
        )
        val service = NameSimilarityService(normalizer, store)
        service.rebuild()

        val failure = runCatching {
            service.confirmCurrent(groupId = 1, documentId = 99)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}

private class FakeNameSimilarityStore(
    initialDocuments: List<DocumentRecord>,
) : NameSimilarityStore {
    var documents: List<DocumentRecord> = initialDocuments
    private var memberIds: Set<Long> = emptySet()
    var confirmedDocumentId: Long? = null
        private set
    var groupKey: Pair<String, String>? = null
        private set

    override suspend fun activeDocuments(): List<DocumentRecord> = documents

    override suspend fun replaceGroups(groups: List<NameGroupDraft>) {
        val group = groups.singleOrNull()
        val newKey = group?.let { draft -> draft.baseName to draft.extension }
        if (groupKey != null && groupKey != newKey) confirmedDocumentId = null
        groupKey = newKey
        memberIds = group?.members?.map { it.documentId }?.toSet().orEmpty()
        confirmedDocumentId = confirmedDocumentId?.takeIf(memberIds::contains)
    }

    override suspend fun isMember(groupId: Long, documentId: Long): Boolean =
        groupId == 1L && documentId in memberIds

    override suspend fun setConfirmedCurrent(groupId: Long, documentId: Long) {
        check(groupId == 1L)
        confirmedDocumentId = documentId
    }
}
