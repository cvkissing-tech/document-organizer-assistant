package com.wenxu.app.core.relations

import com.wenxu.app.core.model.DocumentRecord

enum class ConfidenceBand {
    HIGH,
    CANDIDATE,
    REJECTED,
}

enum class EvidenceCode {
    CORE_EXACT,
    CORE_FUZZY,
    VERSION_MARKER,
    SAME_FOLDER,
    CLOSE_TIME,
    CLOSE_SIZE,
    SAME_EXTENSION,
    CONTENT_SIMILAR,
    USER_ALLOWED,
}

enum class ConflictCode {
    FAMILY,
    YEAR,
    CHAPTER,
    ASSIGNMENT,
    PERSON,
    SIZE_OUTLIER,
    CONTENT_DIVERGED,
    USER_BLOCKED,
}

sealed interface ContentEvidence {
    data class Available(val similarity: Double) : ContentEvidence
    data object Unavailable : ContentEvidence
}

data class VersionCandidateSignals(
    val nameScore: Double,
    val sameCore: Boolean,
    val hasVersionMarker: Boolean,
    val sameFolder: Boolean,
    val modifiedGapMillis: Long?,
    val sizeRatio: Double?,
    val sameExtension: Boolean,
    val content: ContentEvidence,
    val userAllowed: Boolean,
    val conflicts: Set<ConflictCode>,
)

data class VersionSimilarityDecision(
    val band: ConfidenceBand,
    val score: Int,
    val evidence: Set<EvidenceCode>,
    val conflicts: Set<ConflictCode>,
)

internal fun shouldEvaluateVersionCandidate(
    first: NameDocumentFeatures,
    second: NameDocumentFeatures,
    nameDecision: NameSimilarityDecision,
    userAllowed: Boolean,
): Boolean {
    val family = DocumentFamily.fromExtension(first.document.extension)
    if (family == null || family != DocumentFamily.fromExtension(second.document.extension)) return false
    return userAllowed || nameDecision.conflicts.isEmpty()
}

internal fun buildVersionCandidateSignals(
    first: NameDocumentFeatures,
    second: NameDocumentFeatures,
    nameDecision: NameSimilarityDecision,
    contentSimilarity: Double?,
    userAllowed: Boolean,
): VersionCandidateSignals = VersionCandidateSignals(
    nameScore = nameDecision.score,
    sameCore = first.name.coreKey == second.name.coreKey,
    hasVersionMarker = first.name.hasVersionEvidence || second.name.hasVersionEvidence,
    sameFolder = reliableSameFolder(first.document, second.document),
    modifiedGapMillis = documentModifiedGap(first.document, second.document),
    sizeRatio = documentSizeRatio(first.document, second.document),
    sameExtension = first.document.extension.equals(second.document.extension, ignoreCase = true),
    content = contentSimilarity?.let(ContentEvidence::Available) ?: ContentEvidence.Unavailable,
    userAllowed = userAllowed,
    conflicts = nameDecision.conflicts,
)

private fun documentModifiedGap(
    first: DocumentRecord,
    second: DocumentRecord,
): Long? {
    if (first.modifiedAt <= 0L || second.modifiedAt <= 0L) return null
    return if (first.modifiedAt >= second.modifiedAt) {
        first.modifiedAt - second.modifiedAt
    } else {
        second.modifiedAt - first.modifiedAt
    }
}

private fun documentSizeRatio(
    first: DocumentRecord,
    second: DocumentRecord,
): Double? {
    val smaller = minOf(first.sizeBytes, second.sizeBytes)
    if (smaller <= 0L) return null
    return maxOf(first.sizeBytes, second.sizeBytes).toDouble() / smaller.toDouble()
}

data class VersionDocumentPair(
    val first: Long,
    val second: Long,
) {
    init {
        require(first < second) { "版本候选文档ID必须按升序且不能相同" }
    }

    companion object {
        fun of(first: Long, second: Long): VersionDocumentPair {
            require(first != second) { "版本候选不能引用同一文档两次" }
            return if (first < second) {
                VersionDocumentPair(first, second)
            } else {
                VersionDocumentPair(second, first)
            }
        }
    }
}
