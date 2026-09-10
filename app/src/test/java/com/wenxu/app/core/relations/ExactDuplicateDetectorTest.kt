package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.DuplicateSet
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExactDuplicateDetectorTest {
    @Test
    fun identicalBytesWithDifferentNamesShareASet() = runTest {
        val first = duplicateDocument(1, "content://a/notes.pdf")
        val second = duplicateDocument(2, "content://b/copy.pdf")
        val gateway = FakeDocumentGateway().apply {
            put(first.uri, byteArrayOf(1, 2, 3))
            put(second.uri, byteArrayOf(1, 2, 3))
        }
        val detector = ExactDuplicateDetector(
            FakeExactDuplicateStore(listOf(first, second)),
            ContentHasher(gateway),
        )

        val report = detector.rebuild()

        assertThat(report.sets.single().memberIds).containsExactly(1L, 2L)
    }

    @Test
    fun equalSizeDifferentBytesAreNotDuplicates() = runTest {
        val first = duplicateDocument(1, "content://a/a.pdf")
        val second = duplicateDocument(2, "content://b/b.pdf")
        val gateway = FakeDocumentGateway().apply {
            put(first.uri, byteArrayOf(1, 2, 3))
            put(second.uri, byteArrayOf(3, 2, 1))
        }
        val detector = ExactDuplicateDetector(
            FakeExactDuplicateStore(listOf(first, second)),
            ContentHasher(gateway),
        )

        assertThat(detector.rebuild().sets).isEmpty()
    }

    @Test
    fun uniqueFileSizesAreNotRead() = runTest {
        val first = duplicateDocument(1, "content://a/a.pdf", sizeBytes = 3)
        val second = duplicateDocument(2, "content://b/b.pdf", sizeBytes = 8)
        val gateway = FakeDocumentGateway().apply {
            put(first.uri, byteArrayOf(1, 2, 3))
            put(second.uri, ByteArray(8))
        }

        val report = ExactDuplicateDetector(
            FakeExactDuplicateStore(listOf(first, second)),
            ContentHasher(gateway),
        ).rebuild()

        assertThat(report.sets).isEmpty()
        assertThat(gateway.readCount(first.uri)).isEqualTo(0)
        assertThat(gateway.readCount(second.uri)).isEqualTo(0)
    }

    @Test
    fun validCachedHashAvoidsReadingFileAgain() = runTest {
        val cachedHash = "cached"
        val first = duplicateDocument(
            id = 1,
            uri = "content://a/a.pdf",
            contentHash = cachedHash,
            hashBasisSize = 3,
            hashBasisModifiedAt = 1_000,
        )
        val second = duplicateDocument(
            id = 2,
            uri = "content://b/b.pdf",
            contentHash = cachedHash,
            hashBasisSize = 3,
            hashBasisModifiedAt = 1_000,
        )
        val gateway = FakeDocumentGateway()

        val report = ExactDuplicateDetector(
            FakeExactDuplicateStore(listOf(first, second)),
            ContentHasher(gateway),
        ).rebuild()

        assertThat(report.sets.single().memberIds).containsExactly(1L, 2L)
        assertThat(gateway.readCount(first.uri)).isEqualTo(0)
        assertThat(gateway.readCount(second.uri)).isEqualTo(0)
    }

    @Test
    fun changedModifiedTimeForcesOneNewRead() = runTest {
        val first = duplicateDocument(
            id = 1,
            uri = "content://a/a.pdf",
            modifiedAt = 2_000,
            contentHash = "stale",
            hashBasisSize = 3,
            hashBasisModifiedAt = 1_000,
        )
        val second = duplicateDocument(2, "content://b/b.pdf")
        val gateway = FakeDocumentGateway().apply {
            put(first.uri, byteArrayOf(1, 2, 3))
            put(second.uri, byteArrayOf(1, 2, 3))
        }

        ExactDuplicateDetector(
            FakeExactDuplicateStore(listOf(first, second)),
            ContentHasher(gateway),
        ).rebuild()

        assertThat(gateway.readCount(first.uri)).isEqualTo(1)
    }

    @Test
    fun analyzeReturnsDraftsWithoutReplacingPersistedExactGroups() = runTest {
        val first = duplicateDocument(1, "content://a/a.pdf")
        val second = duplicateDocument(2, "content://b/b.pdf")
        val store = FakeExactDuplicateStore(listOf(first, second))
        val gateway = FakeDocumentGateway().apply {
            put(first.uri, byteArrayOf(1, 2, 3))
            put(second.uri, byteArrayOf(1, 2, 3))
        }

        val drafts = ExactDuplicateDetector(store, ContentHasher(gateway)).analyze()

        assertThat(drafts.single().memberIds).containsExactly(1L, 2L)
        assertThat(store.replaceCount).isEqualTo(0)
    }
}

private class FakeExactDuplicateStore(
    documents: List<DocumentEntity>,
) : ExactDuplicateStore {
    private val records = documents.associateBy { it.id }.toMutableMap()
    private var nextSetId = 1L
    var replaceCount: Int = 0
        private set

    override suspend fun activeCandidates(): List<DocumentEntity> = records.values.toList()

    override suspend fun saveHash(
        documentId: Long,
        hash: String,
        sizeBytes: Long,
        modifiedAt: Long,
    ) {
        records[documentId] = checkNotNull(records[documentId]).copy(
            contentHash = hash,
            hashBasisSize = sizeBytes,
            hashBasisModifiedAt = modifiedAt,
        )
    }

    override suspend fun replaceGroups(groups: List<ExactDuplicateGroup>): List<DuplicateSet> =
        groups.map { group ->
            replaceCount += 1
            DuplicateSet(id = nextSetId++, memberIds = group.memberIds)
        }
}
