package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContentHasherTest {
    @Test
    fun computesStableSha256ForEntireDocument() = runTest {
        val gateway = FakeDocumentGateway().apply {
            put("content://docs/a.pdf", byteArrayOf(1, 2, 3))
        }
        val document = duplicateDocument(id = 1, uri = "content://docs/a.pdf")

        val hash = ContentHasher(gateway).hash(document)

        assertThat(hash).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
        assertThat(gateway.readCount(document.uri)).isEqualTo(1)
    }
}

internal fun duplicateDocument(
    id: Long,
    uri: String,
    sizeBytes: Long = 3,
    modifiedAt: Long = 1_000,
    contentHash: String? = null,
    hashBasisSize: Long? = null,
    hashBasisModifiedAt: Long? = null,
) = DocumentEntity(
    id = id,
    uri = uri,
    displayName = "document-$id.pdf",
    normalizedName = "document-$id",
    mimeType = "application/pdf",
    extension = "pdf",
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
    sourceId = 1,
    parentUri = "content://docs/tree",
    contentHash = contentHash,
    hashBasisSize = hashBasisSize,
    hashBasisModifiedAt = hashBasisModifiedAt,
    lastSeenScanId = "scan-1",
)
