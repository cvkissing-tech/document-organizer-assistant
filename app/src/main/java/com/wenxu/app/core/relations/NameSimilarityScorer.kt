package com.wenxu.app.core.relations

import com.wenxu.app.core.model.DocumentRecord

internal fun reliableSameFolder(
    first: DocumentRecord,
    second: DocumentRecord,
): Boolean {
    val firstParent = first.parentUri.trim().trimEnd('/')
    val secondParent = second.parentUri.trim().trimEnd('/')
    if (firstParent.isEmpty() || firstParent != secondParent) return false
    return !MEDIASTORE_COLLECTION_URI.matches(firstParent)
}

private val MEDIASTORE_COLLECTION_URI = Regex(
    pattern = "^content://media/[^/]+/file$",
    option = RegexOption.IGNORE_CASE,
)

data class NameDocumentFeatures(
    val document: DocumentRecord,
    val name: NameFeatures,
)

data class NameSimilarityDecision(
    val accepted: Boolean,
    val score: Double,
    val conflicts: Set<ConflictCode> = emptySet(),
)

class NameSimilarityScorer(
    private val fuzzyThreshold: Double = 0.76,
) {
    fun compare(
        first: NameDocumentFeatures,
        second: NameDocumentFeatures,
        weights: NameCorpusWeights,
    ): NameSimilarityDecision {
        val firstFamily = DocumentFamily.fromExtension(first.document.extension)
        val secondFamily = DocumentFamily.fromExtension(second.document.extension)
        if (firstFamily == null || secondFamily == null || firstFamily != secondFamily) {
            return NameSimilarityDecision(false, 0.0, setOf(ConflictCode.FAMILY))
        }
        if (first.name.coreKey.isBlank() || second.name.coreKey.isBlank()) {
            return NameSimilarityDecision(false, 0.0)
        }
        val conflicts = protectedIdentifierConflicts(first.name, second.name)
        if (conflicts.isNotEmpty()) {
            return NameSimilarityDecision(false, 0.0, conflicts)
        }
        if (first.name.coreKey == second.name.coreKey) {
            return NameSimilarityDecision(true, 1.0)
        }

        val nameScore = maxOf(
            weights.similarity(first.name, second.name),
            longestCommonSubsequenceRatio(first.name.coreKey, second.name.coreKey),
        )
        val hasVersionSupport = first.name.hasVersionEvidence || second.name.hasVersionEvidence
        val hasFolderSupport = reliableSameFolder(first.document, second.document)
        val hasIndependentSupport = hasVersionSupport || hasFolderSupport
        val strictThreshold = if (shortestCoreLength(first.name, second.name) <= 4) {
            0.90
        } else {
            fuzzyThreshold
        }
        return NameSimilarityDecision(
            accepted = hasIndependentSupport && nameScore >= strictThreshold,
            score = nameScore,
        )
    }

    private fun protectedIdentifierConflicts(
        first: NameFeatures,
        second: NameFeatures,
    ): Set<ConflictCode> = buildSet {
        ProtectedIdentifierKind.entries.forEach { kind ->
            val firstValues = first.protectedIdentifiers
                .filter { identifier -> identifier.kind == kind }
                .map { identifier -> identifier.value }
                .toSet()
            val secondValues = second.protectedIdentifiers
                .filter { identifier -> identifier.kind == kind }
                .map { identifier -> identifier.value }
                .toSet()
            if (firstValues.isNotEmpty() && secondValues.isNotEmpty() && firstValues != secondValues) {
                add(kind.toConflictCode())
            }
        }

        val firstRemainingNumbers = first.unprotectedNumericIdentifiers
        val secondRemainingNumbers = second.unprotectedNumericIdentifiers
        if (firstRemainingNumbers.isNotEmpty() &&
            secondRemainingNumbers.isNotEmpty() &&
            firstRemainingNumbers != secondRemainingNumbers
        ) {
            add(ConflictCode.ASSIGNMENT)
        }
    }

    private fun ProtectedIdentifierKind.toConflictCode(): ConflictCode = when (this) {
        ProtectedIdentifierKind.YEAR -> ConflictCode.YEAR
        ProtectedIdentifierKind.CHAPTER -> ConflictCode.CHAPTER
        ProtectedIdentifierKind.ASSIGNMENT,
        ProtectedIdentifierKind.COURSE,
        -> ConflictCode.ASSIGNMENT
        ProtectedIdentifierKind.PERSON -> ConflictCode.PERSON
    }

    private fun shortestCoreLength(first: NameFeatures, second: NameFeatures): Int =
        minOf(first.coreKey.length, second.coreKey.length)

    private fun longestCommonSubsequenceRatio(first: String, second: String): Double {
        if (first.isBlank() || second.isBlank()) return 0.0
        val previous = IntArray(second.length + 1)
        val current = IntArray(second.length + 1)
        first.forEach { firstCharacter ->
            for (secondIndex in second.indices) {
                current[secondIndex + 1] = if (firstCharacter == second[secondIndex]) {
                    previous[secondIndex] + 1
                } else {
                    maxOf(previous[secondIndex + 1], current[secondIndex])
                }
            }
            current.copyInto(previous)
            current.fill(0)
        }
        return previous.last().toDouble() / minOf(first.length, second.length)
    }
}
