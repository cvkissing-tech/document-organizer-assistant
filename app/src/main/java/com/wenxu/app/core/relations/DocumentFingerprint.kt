package com.wenxu.app.core.relations

import com.wenxu.app.core.database.entity.DocumentEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class DocumentFingerprint(
    val documentId: Long,
    val algorithmVersion: Int,
    val signature: LongArray,
    val normalizedTextLength: Int,
    val structure: StructureSignature,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DocumentFingerprint &&
            documentId == other.documentId &&
            algorithmVersion == other.algorithmVersion &&
            signature.contentEquals(other.signature) &&
            normalizedTextLength == other.normalizedTextLength &&
            structure == other.structure

    override fun hashCode(): Int {
        var result = documentId.hashCode()
        result = 31 * result + algorithmVersion
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + normalizedTextLength
        result = 31 * result + structure.hashCode()
        return result
    }

}

data class StructureSignature(
    val sectionCount: Int,
    val tableOrSheetCount: Int,
    val imageCount: Int,
    val headingTokens: Set<String>,
    val orderedSectionTokens: List<String> = emptyList(),
) {
    init {
        require(sectionCount >= 0)
        require(tableOrSheetCount >= 0)
        require(imageCount >= 0)
    }

    companion object {
        val EMPTY = StructureSignature(0, 0, 0, emptySet(), emptyList())
    }
}

class FingerprintSecret(
    val keyVersion: Int,
    keyBytes: ByteArray,
) {
    private val material = keyBytes.copyOf()

    init {
        require(keyVersion in 0..999_999) { "指纹密钥版本超出范围" }
        require(material.size >= 16) { "指纹密钥至少需要128位" }
    }

    val keyId: Int = MessageDigest.getInstance("SHA-256")
        .digest(material)
        .let { digest ->
            val value = ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .int and KEY_ID_MASK
            if (value == 0) 1 else value
        }

    internal fun keyMaterial(): ByteArray = material.copyOf()

    private companion object {
        const val KEY_ID_MASK = 0x3fff_ffff
    }
}

fun interface FingerprintSecretProvider {
    fun get(): FingerprintSecret
}

sealed interface FingerprintExtraction {
    data class Success(val fingerprint: DocumentFingerprint) : FingerprintExtraction
    data object Unsupported : FingerprintExtraction
    data object Encrypted : FingerprintExtraction
    data object ReadFailed : FingerprintExtraction
}

interface DocumentFeatureExtractor {
    val algorithmVersion: Int
    suspend fun extract(document: DocumentEntity): FingerprintExtraction
}
