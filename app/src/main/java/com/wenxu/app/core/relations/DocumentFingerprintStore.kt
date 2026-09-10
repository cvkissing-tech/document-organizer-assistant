package com.wenxu.app.core.relations

import com.wenxu.app.core.database.dao.RelationAnalysisDao
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.FingerprintExtractStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface DocumentFingerprintPersistence {
    suspend fun find(documentId: Long): DocumentFingerprintEntity?
    suspend fun save(entity: DocumentFingerprintEntity)
}

class RoomDocumentFingerprintPersistence(
    private val dao: RelationAnalysisDao,
) : DocumentFingerprintPersistence {
    override suspend fun find(documentId: Long): DocumentFingerprintEntity? =
        dao.findFingerprint(documentId)

    override suspend fun save(entity: DocumentFingerprintEntity) {
        dao.upsertFingerprint(entity)
    }
}

data class PreparedFingerprint(
    val fingerprint: DocumentFingerprint?,
    val entity: DocumentFingerprintEntity?,
    val failed: Boolean,
)

fun interface RelationFingerprintProvider {
    suspend fun prepare(document: DocumentEntity): PreparedFingerprint

    suspend fun checkpoint(prepared: PreparedFingerprint) = Unit
}

class DocumentFingerprintStore(
    private val persistence: DocumentFingerprintPersistence,
    private val extractor: DocumentFeatureExtractor,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RelationFingerprintProvider {
    suspend fun cachedOrExtract(document: DocumentEntity): DocumentFingerprint? {
        val prepared = prepare(document)
        checkpoint(prepared)
        return prepared.fingerprint
    }

    override suspend fun checkpoint(prepared: PreparedFingerprint) {
        prepared.entity?.let { persistence.save(it) }
    }

    override suspend fun prepare(document: DocumentEntity): PreparedFingerprint {
        val cached = persistence.find(document.id)
        if (cached != null && cached.matches(document, extractor.algorithmVersion)) {
            when (cached.extractStatus) {
                FingerprintExtractStatus.SUCCESS -> cached.toDomainOrNull()?.let { fingerprint ->
                    return PreparedFingerprint(fingerprint, null, failed = false)
                }
                FingerprintExtractStatus.UNSUPPORTED,
                FingerprintExtractStatus.ENCRYPTED,
                -> return PreparedFingerprint(null, null, failed = false)
                FingerprintExtractStatus.READ_FAILED -> Unit
            }
        }

        return when (val extraction = extractor.extract(document)) {
            is FingerprintExtraction.Success -> {
                val fingerprint = extraction.fingerprint.copy(
                    documentId = document.id,
                    algorithmVersion = extractor.algorithmVersion,
                )
                val entity = try {
                    toEntity(
                        fingerprint = fingerprint,
                        status = FingerprintExtractStatus.SUCCESS,
                        basisSize = document.sizeBytes,
                        basisModifiedAt = document.modifiedAt,
                        updatedAt = nowMillis(),
                    )
                } catch (_: IllegalArgumentException) {
                    return PreparedFingerprint(null, null, failed = true)
                }
                PreparedFingerprint(fingerprint, entity, failed = false)
            }
            FingerprintExtraction.Unsupported -> {
                PreparedFingerprint(
                    fingerprint = null,
                    entity = unavailableEntity(document, FingerprintExtractStatus.UNSUPPORTED),
                    failed = false,
                )
            }
            FingerprintExtraction.Encrypted -> {
                PreparedFingerprint(
                    fingerprint = null,
                    entity = unavailableEntity(document, FingerprintExtractStatus.ENCRYPTED),
                    failed = false,
                )
            }
            FingerprintExtraction.ReadFailed -> {
                PreparedFingerprint(null, null, failed = true)
            }
        }
    }

    private fun unavailableEntity(
        document: DocumentEntity,
        status: FingerprintExtractStatus,
    ) = DocumentFingerprintEntity(
        documentId = document.id,
        algorithmVersion = extractor.algorithmVersion,
        textMinHash = ByteArray(0),
        normalizedTextLength = 0,
        structureSignature = StructureSignatureCodec.encode(StructureSignature.EMPTY),
        extractStatus = status,
        basisSize = document.sizeBytes,
        basisModifiedAt = document.modifiedAt,
        updatedAt = nowMillis(),
    )

    private fun DocumentFingerprintEntity.matches(
        document: DocumentEntity,
        expectedAlgorithmVersion: Int,
    ): Boolean =
        algorithmVersion == expectedAlgorithmVersion &&
            basisSize == document.sizeBytes &&
            basisModifiedAt == document.modifiedAt

    private fun DocumentFingerprintEntity.toDomainOrNull(): DocumentFingerprint? = runCatching {
        DocumentFingerprint(
            documentId = documentId,
            algorithmVersion = algorithmVersion,
            signature = FingerprintSignatureCodec.decode(textMinHash),
            normalizedTextLength = normalizedTextLength,
            structure = StructureSignatureCodec.decode(structureSignature),
        ).takeIf { it.signature.isNotEmpty() && it.normalizedTextLength > 0 }
    }.getOrNull()

    companion object {
        fun toEntity(
            fingerprint: DocumentFingerprint,
            status: FingerprintExtractStatus,
            basisSize: Long,
            basisModifiedAt: Long,
            updatedAt: Long,
        ) = DocumentFingerprintEntity(
            documentId = fingerprint.documentId,
            algorithmVersion = fingerprint.algorithmVersion,
            textMinHash = FingerprintSignatureCodec.encode(fingerprint.signature),
            normalizedTextLength = fingerprint.normalizedTextLength,
            structureSignature = StructureSignatureCodec.encode(fingerprint.structure),
            extractStatus = status,
            basisSize = basisSize,
            basisModifiedAt = basisModifiedAt,
            updatedAt = updatedAt,
        )
    }
}

internal object FingerprintSignatureCodec {
    fun encode(values: LongArray): ByteArray {
        require(values.size <= MAX_SIGNATURE_VALUES) { "指纹值数量超过限制" }
        return ByteBuffer.allocate(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply { values.forEach(::putLong) }
            .array()
    }

    fun decode(bytes: ByteArray): LongArray {
        require(bytes.size <= MAX_SIGNATURE_VALUES * Long.SIZE_BYTES) { "指纹数据超过限制" }
        require(bytes.size % Long.SIZE_BYTES == 0) { "指纹数据长度无效" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return LongArray(bytes.size / Long.SIZE_BYTES) { buffer.long }
    }

    private const val MAX_SIGNATURE_VALUES = MinHashFingerprint.DEFAULT_SIGNATURE_SIZE
}

internal object StructureSignatureCodec {
    fun encode(value: StructureSignature): String {
        require(value.headingTokens.size <= MAX_HEADING_TOKENS)
        require(value.orderedSectionTokens.size <= MAX_ORDERED_SECTIONS)
        require((value.headingTokens + value.orderedSectionTokens).all { it.length <= MAX_TOKEN_LENGTH })
        val encoded = buildString {
            append(value.sectionCount)
            append(',')
            append(value.tableOrSheetCount)
            append(',')
            append(value.imageCount)
            append('|')
            value.headingTokens.sorted().forEach { token ->
                append(token.length)
                append(':')
                append(token)
            }
            append('|')
            value.orderedSectionTokens.forEach { token ->
                append(token.length)
                append(':')
                append(token)
            }
        }
        require(encoded.length <= MAX_ENCODED_LENGTH)
        return encoded
    }

    fun decode(value: String): StructureSignature {
        require(value.length <= MAX_ENCODED_LENGTH)
        val firstSeparator = value.indexOf('|')
        val secondSeparator = value.indexOf('|', firstSeparator + 1)
        require(firstSeparator >= 0 && secondSeparator >= 0) { "结构指纹格式无效" }
        val counts = value.substring(0, firstSeparator).split(',')
        require(counts.size == 3) { "结构指纹计数无效" }
        val headings = decodeTokens(value, firstSeparator + 1, secondSeparator, MAX_HEADING_TOKENS)
        val ordered = decodeTokens(value, secondSeparator + 1, value.length, MAX_ORDERED_SECTIONS)
        return StructureSignature(
            sectionCount = counts[0].toInt(),
            tableOrSheetCount = counts[1].toInt(),
            imageCount = counts[2].toInt(),
            headingTokens = headings.toSet(),
            orderedSectionTokens = ordered,
        )
    }

    private fun decodeTokens(value: String, startAt: Int, endAt: Int, limit: Int): List<String> {
        val tokens = mutableListOf<String>()
        var cursor = startAt
        while (cursor < endAt) {
            val colon = value.indexOf(':', cursor)
            require(colon in (cursor + 1) until endAt) { "结构指纹标识无效" }
            val length = value.substring(cursor, colon).toInt()
            val start = colon + 1
            val end = start + length
            require(length in 0..MAX_TOKEN_LENGTH && end <= endAt) { "结构指纹标识长度无效" }
            tokens += value.substring(start, end)
            require(tokens.size <= limit) { "结构指纹标识数量超限" }
            cursor = end
        }
        require(cursor == endAt)
        return tokens
    }

    private const val MAX_HEADING_TOKENS = 256
    private const val MAX_ORDERED_SECTIONS = 512
    private const val MAX_TOKEN_LENGTH = 128
    private const val MAX_ENCODED_LENGTH = 128 * 1024
}
