package com.wenxu.app.core.relations

import kotlin.math.roundToInt

class VersionSimilarityScorer {
    fun decide(signals: VersionCandidateSignals): VersionSimilarityDecision {
        val evidence = linkedSetOf<EvidenceCode>()
        val conflicts = signals.conflicts.toMutableSet()

        val contentSimilarity = (signals.content as? ContentEvidence.Available)
            ?.similarity
            ?.coerceIn(0.0, 1.0)
        if (contentSimilarity != null && contentSimilarity < CONTENT_DIVERGED_THRESHOLD) {
            conflicts += ConflictCode.CONTENT_DIVERGED
        }
        val sizeRatio = signals.sizeRatio
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { ratio -> if (ratio < 1.0) 1.0 / ratio else ratio }
        if (sizeRatio != null && sizeRatio > SIZE_OUTLIER_RATIO) {
            conflicts += ConflictCode.SIZE_OUTLIER
        }
        if (conflicts.any(BLOCKING_CONFLICTS::contains)) {
            return VersionSimilarityDecision(
                band = ConfidenceBand.REJECTED,
                score = 0,
                evidence = emptySet(),
                conflicts = conflicts,
            )
        }

        val effectiveNameScore = if (signals.sameCore) {
            evidence += EvidenceCode.CORE_EXACT
            1.0
        } else {
            val score = signals.nameScore.coerceIn(0.0, 1.0)
            if (score > 0.0) evidence += EvidenceCode.CORE_FUZZY
            score
        }
        var score = (effectiveNameScore * CORE_NAME_POINTS).roundToInt()

        if (signals.hasVersionMarker) {
            evidence += EvidenceCode.VERSION_MARKER
            score += VERSION_MARKER_POINTS
        }
        if (signals.sameFolder) {
            evidence += EvidenceCode.SAME_FOLDER
            score += SAME_FOLDER_POINTS
        }
        score += timePoints(signals.modifiedGapMillis).also { points ->
            if (points > 1) evidence += EvidenceCode.CLOSE_TIME
        }
        score += sizePoints(sizeRatio).also { points ->
            if (points > 0) evidence += EvidenceCode.CLOSE_SIZE
        }
        if (signals.sameExtension) {
            evidence += EvidenceCode.SAME_EXTENSION
            score += SAME_EXTENSION_POINTS
        }
        if (contentSimilarity != null && contentSimilarity >= CONTENT_SUPPORT_THRESHOLD) {
            evidence += EvidenceCode.CONTENT_SIMILAR
        }
        if (signals.userAllowed) evidence += EvidenceCode.USER_ALLOWED

        val hasNameSupport = signals.hasVersionMarker || signals.sameFolder || signals.userAllowed
        val nameGate = signals.sameCore ||
            (effectiveNameScore >= FUZZY_NAME_GATE && hasNameSupport)
        val fallbackVerification = signals.content is ContentEvidence.Unavailable &&
            effectiveNameScore >= FALLBACK_NAME_GATE &&
            signals.hasVersionMarker &&
            signals.sameFolder
        val verificationGate = contentSimilarity?.let { it >= CONTENT_SUPPORT_THRESHOLD } == true ||
            fallbackVerification

        val boundedScore = score.coerceIn(0, 100)
        val decisionScore = if (!verificationGate && boundedScore >= HIGH_THRESHOLD) {
            HIGH_THRESHOLD - 1
        } else {
            boundedScore
        }
        val band = when {
            nameGate && verificationGate && decisionScore >= HIGH_THRESHOLD -> ConfidenceBand.HIGH
            nameGate && decisionScore >= CANDIDATE_THRESHOLD -> ConfidenceBand.CANDIDATE
            else -> ConfidenceBand.REJECTED
        }
        return VersionSimilarityDecision(
            band = band,
            score = decisionScore,
            evidence = evidence,
            conflicts = conflicts,
        )
    }

    private fun timePoints(modifiedGapMillis: Long?): Int {
        val gap = modifiedGapMillis ?: return 0
        if (gap < 0) return 0
        return when {
            gap <= DAY_MILLIS -> 7
            gap <= 7 * DAY_MILLIS -> 5
            gap <= 30 * DAY_MILLIS -> 3
            else -> 1
        }
    }

    private fun sizePoints(sizeRatio: Double?): Int = when {
        sizeRatio == null -> 0
        sizeRatio <= 1.10 -> 5
        sizeRatio <= 1.30 -> 3
        sizeRatio <= 2.0 -> 1
        else -> 0
    }

    private companion object {
        const val CORE_NAME_POINTS = 60
        const val VERSION_MARKER_POINTS = 15
        const val SAME_FOLDER_POINTS = 10
        const val SAME_EXTENSION_POINTS = 3
        const val FUZZY_NAME_GATE = 0.76
        const val FALLBACK_NAME_GATE = 0.90
        const val CONTENT_SUPPORT_THRESHOLD = 0.75
        const val CONTENT_DIVERGED_THRESHOLD = 0.30
        const val SIZE_OUTLIER_RATIO = 10.0
        const val HIGH_THRESHOLD = 82
        const val CANDIDATE_THRESHOLD = 72
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        val BLOCKING_CONFLICTS = setOf(
            ConflictCode.FAMILY,
            ConflictCode.YEAR,
            ConflictCode.CHAPTER,
            ConflictCode.ASSIGNMENT,
            ConflictCode.PERSON,
            ConflictCode.CONTENT_DIVERGED,
            ConflictCode.USER_BLOCKED,
        )
    }
}
