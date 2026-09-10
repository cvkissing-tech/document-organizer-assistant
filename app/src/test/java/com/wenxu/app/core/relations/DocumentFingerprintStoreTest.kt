package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.FingerprintExtractStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DocumentFingerprintStoreTest {
    @Test
    fun checkpointMakesPreparedFingerprintReusableBeforeFinalRelationsSave() = runTest {
        val document = fingerprintDocument()
        val persistence = FakeFingerprintPersistence()
        val extractor = CountingExtractor(
            testMinHash().algorithmVersion,
            FingerprintExtraction.Success(sampleFingerprint(document.id)),
        )
        val store = DocumentFingerprintStore(persistence, extractor)

        val prepared = store.prepare(document)
        assertThat(persistence.saved).isNull()

        store.checkpoint(prepared)
        val resumed = store.prepare(document)

        assertThat(persistence.saved).isNotNull()
        assertThat(resumed.fingerprint).isNotNull()
        assertThat(resumed.entity).isNull()
        assertThat(extractor.calls).isEqualTo(1)
    }

    @Test
    fun validCacheAvoidsExtractionAndRestoresStableSignature() = runTest {
        val document = fingerprintDocument(size = 100, modifiedAt = 2_000)
        val fingerprint = sampleFingerprint(document.id)
        val persistence = FakeFingerprintPersistence(
            DocumentFingerprintStore.toEntity(
                fingerprint = fingerprint,
                status = FingerprintExtractStatus.SUCCESS,
                basisSize = document.sizeBytes,
                basisModifiedAt = document.modifiedAt,
                updatedAt = 5_000,
            ),
        )
        val extractor = CountingExtractor(testMinHash().algorithmVersion, FingerprintExtraction.ReadFailed)

        val cached = DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)

        assertThat(cached!!.signature.asList()).containsExactlyElementsIn(fingerprint.signature.asList()).inOrder()
        assertThat(cached.structure).isEqualTo(fingerprint.structure)
        assertThat(extractor.calls).isEqualTo(0)
    }

    @Test
    fun changedBasisOrAlgorithmInvalidatesCache() = runTest {
        val document = fingerprintDocument(size = 101, modifiedAt = 2_001)
        val stale = DocumentFingerprintStore.toEntity(
            fingerprint = sampleFingerprint(document.id).copy(algorithmVersion = 1),
            status = FingerprintExtractStatus.SUCCESS,
            basisSize = 100,
            basisModifiedAt = 2_000,
            updatedAt = 4_000,
        )
        val fresh = sampleFingerprint(document.id)
        val persistence = FakeFingerprintPersistence(stale)
        val extractor = CountingExtractor(testMinHash().algorithmVersion, FingerprintExtraction.Success(fresh))

        val result = DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)

        assertThat(result!!.signature.asList()).containsExactlyElementsIn(fresh.signature.asList()).inOrder()
        assertThat(extractor.calls).isEqualTo(1)
        assertThat(persistence.saved!!.basisSize).isEqualTo(101)
        assertThat(persistence.saved!!.basisModifiedAt).isEqualTo(2_001)
        assertThat(persistence.saved!!.algorithmVersion).isEqualTo(testMinHash().algorithmVersion)
    }

    @Test
    fun changedAlgorithmVersionInvalidatesOtherwiseMatchingCache() = runTest {
        val document = fingerprintDocument()
        val stale = DocumentFingerprintStore.toEntity(
            fingerprint = sampleFingerprint(document.id).copy(algorithmVersion = 0),
            status = FingerprintExtractStatus.SUCCESS,
            basisSize = document.sizeBytes,
            basisModifiedAt = document.modifiedAt,
            updatedAt = 4_000,
        )
        val persistence = FakeFingerprintPersistence(stale)
        val extractor = CountingExtractor(testMinHash().algorithmVersion, FingerprintExtraction.Success(sampleFingerprint(document.id)))

        DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)

        assertThat(extractor.calls).isEqualTo(1)
        assertThat(persistence.saved!!.algorithmVersion)
            .isEqualTo(testMinHash().algorithmVersion)
    }

    @Test
    fun cachedUnavailableStateDoesNotReadDocumentAgain() = runTest {
        val document = fingerprintDocument()
        val persistence = FakeFingerprintPersistence(
            DocumentFingerprintEntity(
                documentId = document.id,
                algorithmVersion = testMinHash().algorithmVersion,
                textMinHash = ByteArray(0),
                normalizedTextLength = 0,
                structureSignature = StructureSignatureCodec.encode(StructureSignature.EMPTY),
                extractStatus = FingerprintExtractStatus.UNSUPPORTED,
                basisSize = document.sizeBytes,
                basisModifiedAt = document.modifiedAt,
                updatedAt = 4_000,
            ),
        )
        val extractor = CountingExtractor(testMinHash().algorithmVersion, FingerprintExtraction.ReadFailed)

        assertThat(DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)).isNull()
        assertThat(extractor.calls).isEqualTo(0)
    }

    @Test
    fun longArrayEncodingIsStableAndRoundTrips() {
        val values = longArrayOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE)

        val encoded = FingerprintSignatureCodec.encode(values)

        assertThat(encoded).hasLength(values.size * Long.SIZE_BYTES)
        assertThat(FingerprintSignatureCodec.decode(encoded).asList())
            .containsExactlyElementsIn(values.asList()).inOrder()
        assertThat(FingerprintSignatureCodec.encode(values)).isEqualTo(encoded)
    }

    @Test
    fun signatureCodecUsesGoldenBigEndianBytesAndRejectsOversizedPayloads() {
        val encoded = FingerprintSignatureCodec.encode(longArrayOf(0x0102030405060708L))

        assertThat(encoded).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FingerprintSignatureCodec.encode(LongArray(129))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FingerprintSignatureCodec.decode(ByteArray(129 * Long.SIZE_BYTES))
        }
    }

    @Test
    fun onlySizeChangeInvalidatesCache() = runTest {
        assertSingleBasisChangeForcesExtraction(size = 101, modifiedAt = 2_000)
    }

    @Test
    fun onlyModifiedTimeChangeInvalidatesCache() = runTest {
        assertSingleBasisChangeForcesExtraction(size = 100, modifiedAt = 2_001)
    }

    @Test
    fun temporaryReadFailureIsNotCachedAndNextAnalysisCanRecover() = runTest {
        val document = fingerprintDocument()
        val persistence = FakeFingerprintPersistence()
        val extractor = SequenceExtractor(
            algorithmVersion = testMinHash().algorithmVersion,
            results = ArrayDeque(
                listOf(
                    FingerprintExtraction.ReadFailed,
                    FingerprintExtraction.Success(sampleFingerprint(document.id)),
                ),
            ),
        )
        val store = DocumentFingerprintStore(persistence, extractor)

        assertThat(store.cachedOrExtract(document)).isNull()
        assertThat(persistence.saved).isNull()
        assertThat(store.cachedOrExtract(document)).isNotNull()
        assertThat(extractor.calls).isEqualTo(2)
    }

    @Test
    fun legacyCachedReadFailureIsRetriedInsteadOfTreatedAsPermanent() = runTest {
        val document = fingerprintDocument()
        val cachedFailure = DocumentFingerprintEntity(
            documentId = document.id,
            algorithmVersion = testMinHash().algorithmVersion,
            textMinHash = ByteArray(0),
            normalizedTextLength = 0,
            structureSignature = StructureSignatureCodec.encode(StructureSignature.EMPTY),
            extractStatus = FingerprintExtractStatus.READ_FAILED,
            basisSize = document.sizeBytes,
            basisModifiedAt = document.modifiedAt,
            updatedAt = 4_000,
        )
        val persistence = FakeFingerprintPersistence(cachedFailure)
        val extractor = CountingExtractor(
            testMinHash().algorithmVersion,
            FingerprintExtraction.Success(sampleFingerprint(document.id)),
        )

        assertThat(DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)).isNotNull()
        assertThat(extractor.calls).isEqualTo(1)
    }

    @Test
    fun encodingFailureIsTreatedAsRetryableAndIsNotPersisted() = runTest {
        val document = fingerprintDocument()
        val persistence = FakeFingerprintPersistence()
        val invalid = sampleFingerprint(document.id).copy(signature = LongArray(129) { it.toLong() })
        val valid = sampleFingerprint(document.id)
        val extractor = SequenceExtractor(
            algorithmVersion = testMinHash().algorithmVersion,
            results = ArrayDeque(
                listOf(
                    FingerprintExtraction.Success(invalid),
                    FingerprintExtraction.Success(valid),
                ),
            ),
        )
        val store = DocumentFingerprintStore(persistence, extractor)

        assertThat(store.cachedOrExtract(document)).isNull()
        assertThat(persistence.saved).isNull()
        assertThat(store.cachedOrExtract(document)).isEqualTo(valid)
        assertThat(extractor.calls).isEqualTo(2)
    }

    private suspend fun assertSingleBasisChangeForcesExtraction(size: Long, modifiedAt: Long) {
        val document = fingerprintDocument(size = size, modifiedAt = modifiedAt)
        val cachedDocument = fingerprintDocument()
        val stale = DocumentFingerprintStore.toEntity(
            fingerprint = sampleFingerprint(document.id),
            status = FingerprintExtractStatus.SUCCESS,
            basisSize = cachedDocument.sizeBytes,
            basisModifiedAt = cachedDocument.modifiedAt,
            updatedAt = 4_000,
        )
        val persistence = FakeFingerprintPersistence(stale)
        val extractor = CountingExtractor(
            testMinHash().algorithmVersion,
            FingerprintExtraction.Success(sampleFingerprint(document.id)),
        )

        DocumentFingerprintStore(persistence, extractor).cachedOrExtract(document)

        assertThat(extractor.calls).isEqualTo(1)
    }

    private fun sampleFingerprint(documentId: Long) = DocumentFingerprint(
        documentId = documentId,
        algorithmVersion = testMinHash().algorithmVersion,
        signature = longArrayOf(-8, 3, 42),
        normalizedTextLength = 120,
        structure = StructureSignature(
            sectionCount = 4,
            tableOrSheetCount = 2,
            imageCount = 1,
            headingTokens = setOf("b-token", "a-token"),
        ),
    )

    private fun fingerprintDocument(
        size: Long = 100,
        modifiedAt: Long = 2_000,
    ) = DocumentEntity(
        id = 9,
        uri = "content://test/report.docx",
        displayName = "report.docx",
        normalizedName = "report",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        extension = "docx",
        sizeBytes = size,
        modifiedAt = modifiedAt,
        sourceId = 1,
        parentUri = "content://test/tree",
        lastSeenScanId = "scan-1",
    )
}

private class CountingExtractor(
    override val algorithmVersion: Int,
    private val result: FingerprintExtraction,
) : DocumentFeatureExtractor {
    var calls = 0

    override suspend fun extract(document: DocumentEntity): FingerprintExtraction {
        calls += 1
        return result
    }
}

private class SequenceExtractor(
    override val algorithmVersion: Int,
    private val results: ArrayDeque<FingerprintExtraction>,
) : DocumentFeatureExtractor {
    var calls = 0

    override suspend fun extract(document: DocumentEntity): FingerprintExtraction {
        calls += 1
        return results.removeFirst()
    }
}

private class FakeFingerprintPersistence(
    private var cached: DocumentFingerprintEntity? = null,
) : DocumentFingerprintPersistence {
    var saved: DocumentFingerprintEntity? = null

    override suspend fun find(documentId: Long): DocumentFingerprintEntity? = cached

    override suspend fun save(entity: DocumentFingerprintEntity) {
        saved = entity
        cached = entity
    }
}
