package com.wenxu.app.feature.related

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.model.DocumentRecord
import org.junit.Test

class VersionReferenceTest {
    @Test
    fun newestValidModifiedTimeIsUsedAsTheFactualReference() {
        val older = document(id = 1, modifiedAt = 1_000, sizeBytes = 9_000)
        val newer = document(id = 2, modifiedAt = 2_000, sizeBytes = 1_000)

        assertThat(selectVersionReference(listOf(older, newer))).isEqualTo(newer)
    }

    @Test
    fun equalTimesUseAStableTieBreakerWithoutChangingTheUsersCurrentChoice() {
        val smaller = document(id = 1, modifiedAt = 2_000, sizeBytes = 1_000)
        val larger = document(id = 2, modifiedAt = 2_000, sizeBytes = 2_000)

        assertThat(selectVersionReference(listOf(smaller, larger))).isEqualTo(larger)
    }

    private fun document(id: Long, modifiedAt: Long, sizeBytes: Long) = DocumentRecord(
        id = id,
        uri = "content://documents/$id",
        displayName = "报告$id.docx",
        extension = "docx",
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        sourceId = 1,
        parentUri = "content://documents",
    )
}
