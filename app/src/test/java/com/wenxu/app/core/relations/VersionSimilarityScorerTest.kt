package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionSimilarityScorerTest {
    private val scorer = VersionSimilarityScorer()

    @Test
    fun strongNameWithoutVerificationDoesNotBecomeHighConfidence() {
        val result = scorer.decide(
            signals(
                nameScore = 1.0,
                sameCore = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.band).isNotEqualTo(ConfidenceBand.HIGH)
    }

    @Test
    fun protectedYearConflictAlwaysRejects() {
        val result = scorer.decide(
            signals(
                nameScore = 1.0,
                sameCore = true,
                hasVersionMarker = true,
                sameFolder = true,
                content = ContentEvidence.Available(1.0),
                conflicts = setOf(ConflictCode.YEAR),
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.REJECTED)
        assertThat(result.conflicts).contains(ConflictCode.YEAR)
    }

    @Test
    fun sizeOutlierRemainsARiskWithoutBlockingHighConfidence() {
        val result = scorer.decide(
            signals(
                sameCore = true,
                hasVersionMarker = true,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 10.01,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.HIGH)
        assertThat(result.conflicts).contains(ConflictCode.SIZE_OUTLIER)
    }

    @Test
    fun contentVerificationAndEnoughScoreBecomeHighConfidence() {
        val result = scorer.decide(
            signals(
                sameCore = true,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.05,
                sameExtension = true,
                content = ContentEvidence.Available(0.90),
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.HIGH)
        assertThat(result.score).isAtLeast(82)
        assertThat(result.evidence).contains(EvidenceCode.CONTENT_SIMILAR)
    }

    @Test
    fun unavailableContentNeedsHighNameVersionMarkerAndSameFolder() {
        val result = scorer.decide(
            signals(
                nameScore = 0.90,
                hasVersionMarker = true,
                sameFolder = true,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.HIGH)
        assertThat(result.score).isEqualTo(82)
    }

    @Test
    fun userAllowedDoesNotOpenAlgorithmVerificationGate() {
        val result = scorer.decide(
            signals(
                sameCore = true,
                hasVersionMarker = true,
                sameFolder = false,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.0,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
                userAllowed = true,
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.CANDIDATE)
        assertThat(result.score).isEqualTo(81)
        assertThat(result.evidence).contains(EvidenceCode.USER_ALLOWED)
    }

    @Test
    fun contentThresholdIncludesSeventyFivePercentButNotThirtyPercent() {
        val supported = scorer.decide(
            signals(
                sameCore = true,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.0,
                sameExtension = true,
                content = ContentEvidence.Available(0.75),
            ),
        )
        val neutral = scorer.decide(
            signals(
                sameCore = true,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.0,
                sameExtension = true,
                content = ContentEvidence.Available(0.30),
            ),
        )
        val diverged = scorer.decide(
            signals(
                sameCore = true,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.0,
                sameExtension = true,
                content = ContentEvidence.Available(0.299),
            ),
        )

        assertThat(supported.band).isEqualTo(ConfidenceBand.HIGH)
        assertThat(supported.evidence).contains(EvidenceCode.CONTENT_SIMILAR)
        assertThat(neutral.band).isNotEqualTo(ConfidenceBand.HIGH)
        assertThat(neutral.conflicts).doesNotContain(ConflictCode.CONTENT_DIVERGED)
        assertThat(diverged.band).isEqualTo(ConfidenceBand.REJECTED)
        assertThat(diverged.conflicts).contains(ConflictCode.CONTENT_DIVERGED)
    }

    @Test
    fun timeAndSizeOnlyAddPointsAndCannotOpenVerificationGate() {
        val result = scorer.decide(
            signals(
                nameScore = 0.90,
                sameFolder = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.01,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.band).isNotEqualTo(ConfidenceBand.HIGH)
        assertThat(result.evidence).containsAtLeast(
            EvidenceCode.CLOSE_TIME,
            EvidenceCode.CLOSE_SIZE,
        )
    }

    @Test
    fun sizeRatioIsOrderIndependent() {
        val largerOverSmaller = scorer.decide(
            signals(sameCore = true, sizeRatio = 1.05),
        )
        val smallerOverLarger = scorer.decide(
            signals(sameCore = true, sizeRatio = 1.0 / 1.05),
        )

        assertThat(smallerOverLarger.score).isEqualTo(largerOverSmaller.score)
        assertThat(smallerOverLarger.evidence).contains(EvidenceCode.CLOSE_SIZE)
    }

    @Test
    fun closeTimeEvidenceStopsAfterThirtyDaysAlthoughOlderFilesKeepOnePoint() {
        val atThirtyDays = scorer.decide(
            signals(sameCore = true, modifiedGapMillis = 30 * DAY_MILLIS),
        )
        val afterThirtyDays = scorer.decide(
            signals(sameCore = true, modifiedGapMillis = 30 * DAY_MILLIS + 1),
        )

        assertThat(atThirtyDays.score).isEqualTo(63)
        assertThat(atThirtyDays.evidence).contains(EvidenceCode.CLOSE_TIME)
        assertThat(afterThirtyDays.score).isEqualTo(61)
        assertThat(afterThirtyDays.evidence).doesNotContain(EvidenceCode.CLOSE_TIME)
    }

    @Test
    fun timePointsStepDownAtOneDayAndSevenDays() {
        val atOneDay = scorer.decide(signals(sameCore = true, modifiedGapMillis = DAY_MILLIS))
        val afterOneDay = scorer.decide(signals(sameCore = true, modifiedGapMillis = DAY_MILLIS + 1))
        val atSevenDays = scorer.decide(signals(sameCore = true, modifiedGapMillis = 7 * DAY_MILLIS))
        val afterSevenDays = scorer.decide(
            signals(sameCore = true, modifiedGapMillis = 7 * DAY_MILLIS + 1),
        )

        assertThat(atOneDay.score).isEqualTo(67)
        assertThat(afterOneDay.score).isEqualTo(65)
        assertThat(atSevenDays.score).isEqualTo(65)
        assertThat(afterSevenDays.score).isEqualTo(63)
    }

    @Test
    fun sizeBoundariesUseTenAndThirtyPercentBandsAndFlagOnlyAboveTenTimes() {
        val tenPercent = scorer.decide(signals(sameCore = true, sizeRatio = 1.10))
        val overTenPercent = scorer.decide(signals(sameCore = true, sizeRatio = 1.1001))
        val thirtyPercent = scorer.decide(signals(sameCore = true, sizeRatio = 1.30))
        val overThirtyPercent = scorer.decide(signals(sameCore = true, sizeRatio = 1.3001))
        val doubleSize = scorer.decide(signals(sameCore = true, sizeRatio = 2.0))
        val overDoubleSize = scorer.decide(signals(sameCore = true, sizeRatio = 2.0001))
        val tenTimes = scorer.decide(signals(sameCore = true, sizeRatio = 10.0))
        val overTenTimes = scorer.decide(signals(sameCore = true, sizeRatio = 10.01))

        assertThat(tenPercent.score).isEqualTo(65)
        assertThat(overTenPercent.score).isEqualTo(63)
        assertThat(thirtyPercent.score).isEqualTo(63)
        assertThat(overThirtyPercent.score).isEqualTo(61)
        assertThat(doubleSize.score).isEqualTo(61)
        assertThat(overDoubleSize.score).isEqualTo(60)
        assertThat(tenTimes.conflicts).doesNotContain(ConflictCode.SIZE_OUTLIER)
        assertThat(overTenTimes.conflicts).contains(ConflictCode.SIZE_OUTLIER)
    }

    @Test
    fun scoreBetweenSeventyTwoAndEightyOneRemainsCandidate() {
        val result = scorer.decide(
            signals(
                sameCore = true,
                sameFolder = true,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.score).isEqualTo(73)
        assertThat(result.band).isEqualTo(ConfidenceBand.CANDIDATE)
    }

    @Test
    fun failedVerificationCannotExposeAHighRangeCandidateScore() {
        val result = scorer.decide(
            signals(
                sameCore = true,
                hasVersionMarker = true,
                modifiedGapMillis = 60_000,
                sizeRatio = 1.01,
                sameExtension = true,
                content = ContentEvidence.Unavailable,
            ),
        )

        assertThat(result.band).isEqualTo(ConfidenceBand.CANDIDATE)
        assertThat(result.score).isEqualTo(81)
    }

    private fun signals(
        nameScore: Double = 0.0,
        sameCore: Boolean = false,
        hasVersionMarker: Boolean = false,
        sameFolder: Boolean = false,
        modifiedGapMillis: Long? = null,
        sizeRatio: Double? = null,
        sameExtension: Boolean = false,
        content: ContentEvidence = ContentEvidence.Unavailable,
        userAllowed: Boolean = false,
        conflicts: Set<ConflictCode> = emptySet(),
    ) = VersionCandidateSignals(
        nameScore = nameScore,
        sameCore = sameCore,
        hasVersionMarker = hasVersionMarker,
        sameFolder = sameFolder,
        modifiedGapMillis = modifiedGapMillis,
        sizeRatio = sizeRatio,
        sameExtension = sameExtension,
        content = content,
        userAllowed = userAllowed,
        conflicts = conflicts,
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
