package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameNormalizerTest {
    private val normalizer = NameNormalizer()

    @Test
    fun mapsLegacyAndModernOfficeFormatsToOneFamily() {
        assertThat(DocumentFamily.fromExtension("doc")).isEqualTo(DocumentFamily.WORD)
        assertThat(DocumentFamily.fromExtension("docx")).isEqualTo(DocumentFamily.WORD)
        assertThat(DocumentFamily.fromExtension("pdf")).isEqualTo(DocumentFamily.PDF)
    }

    @Test
    fun separatesVersionNumbersFromTopicNumbers() {
        val version = normalizer.analyze("实习报告_v2.docx")
        val topic = normalizer.analyze("2026年度课程第2章.docx")

        assertThat(version.protectedIdentifiers).isEmpty()
        assertThat(topic.protectedIdentifiers.map { it.value })
            .containsAtLeast("2026", "2")
    }

    @Test
    fun recognizesRevNumberAsVersionRatherThanTopicIdentifier() {
        val result = normalizer.analyze("report_rev2.docx")

        assertThat(result.coreName).isEqualTo("report")
        assertThat(result.protectedIdentifiers).isEmpty()
    }

    @Test
    fun treatsFullWidthVersionPunctuationAndDigitsLikeHalfWidthText() {
        val fullWidth = normalizer.analyze("报告．v２（１）.docx")
        val fullWidthExtensionSeparator = normalizer.analyze("报告_v２．docx")
        val halfWidth = normalizer.analyze("报告.v2(1).docx")

        assertThat(fullWidth.rawStem).isEqualTo("报告．v２（１）")
        assertThat(fullWidthExtensionSeparator.rawStem).isEqualTo("报告_v２")
        assertThat(fullWidthExtensionSeparator.coreName).isEqualTo("报告")
        assertThat(fullWidth.coreName).isEqualTo(halfWidth.coreName)
        assertThat(fullWidth.markers.map { it.kind })
            .containsExactlyElementsIn(halfWidth.markers.map { it.kind })
            .inOrder()
    }

    @Test
    fun normalizesFullWidthProtectedNumbersForAnalysis() {
        val fullWidth = normalizer.analyze("２０２６年度课程第２章.docx")
        val halfWidth = normalizer.analyze("2026年度课程第2章.docx")

        assertThat(fullWidth.rawStem).isEqualTo("２０２６年度课程第２章")
        assertThat(fullWidth.coreKey).isEqualTo(halfWidth.coreKey)
        assertThat(fullWidth.protectedIdentifiers).isEqualTo(halfWidth.protectedIdentifiers)
    }

    @Test
    fun normalizesChineseChapterAndAssignmentNumbersWithoutChangingCoreName() {
        val chinese = normalizer.analyze("课程第一章_作业二.docx")
        val arabic = normalizer.analyze("课程第1章_作业2.docx")
        val chineseHundred = normalizer.analyze("课程第一百章_作业十二.docx")
        val arabicHundred = normalizer.analyze("课程第100章_作业12.docx")

        assertThat(chinese.coreName).isEqualTo("课程第一章 作业二")
        assertThat(chinese.protectedIdentifiers).isEqualTo(arabic.protectedIdentifiers)
        assertThat(chineseHundred.protectedIdentifiers)
            .isEqualTo(arabicHundred.protectedIdentifiers)
        assertThat(chinese.protectedIdentifiers).containsAtLeast(
            ProtectedIdentifier(ProtectedIdentifierKind.CHAPTER, "1"),
            ProtectedIdentifier(ProtectedIdentifierKind.ASSIGNMENT, "2"),
        )
    }

    @Test
    fun unknownSuffixRemainsInCoreName() {
        assertThat(normalizer.analyze("实习报告_提交学校.docx").coreName)
            .isEqualTo("实习报告 提交学校")
    }

    @Test
    fun protectedYearConsumesOnlyItsOwnOccurrence() {
        val features = normalizer.analyze("2026年度报告2026.docx")

        assertThat(features.protectedIdentifiers).containsExactly(
            ProtectedIdentifier(ProtectedIdentifierKind.YEAR, "2026"),
        )
        assertThat(features.unprotectedNumericIdentifiers).containsExactly("2026")
    }

    @Test
    fun extractsChineseVersionMarkersWithoutRankingThem() {
        assertThat(normalizer.normalize("实习报告最终修改版.docx").baseName)
            .isEqualTo("实习报告")
        assertThat(normalizer.normalize("实习报告修改版.docx").baseName)
            .isEqualTo("实习报告")
        assertThat(normalizer.normalize("实习报告草稿.docx").baseName)
            .isEqualTo("实习报告")
    }

    @Test
    fun removesStackedLatinAndCopyMarkers() {
        val result = normalizer.normalize("slides_v2(1).pptx")

        assertThat(result.baseName).isEqualTo("slides")
        assertThat(result.differenceSegments.map { it.text }).containsExactly("_v2", "(1)").inOrder()
    }

    @Test
    fun dotSeparatedVersionDoesNotLeavePunctuationInCoreName() {
        assertThat(normalizer.analyze("report.v2.docx").coreName).isEqualTo("report")
    }

    @Test
    fun englishMarkerTextInsideOrdinaryWordIsPreserved() {
        assertThat(normalizer.analyze("semifinal.docx").coreName).isEqualTo("semifinal")
    }

    @Test
    fun preservesOrdinaryNumbersInsideMeaningfulName() {
        assertThat(normalizer.normalize("2026秋季课程第2章.pdf").baseName)
            .isEqualTo("2026秋季课程第2章")
    }

    @Test
    fun extractsReviewerAndStackedVersionMarkersAsStructuredFeatures() {
        val features = normalizer.analyze("实习报告_张老师修改_v2.docx")

        assertThat(features.coreName).isEqualTo("实习报告")
        assertThat(features.markers.map { it.kind }).containsExactly(
            NameMarkerKind.REVIEW,
            NameMarkerKind.VERSION,
        ).inOrder()
        assertThat(features.differenceSegments.map { it.text })
            .containsExactly("_张老师修改", "_v2").inOrder()
    }

    @Test
    fun extractsSeparatedTrailingDateButPreservesYearInDocumentTopic() {
        assertThat(normalizer.analyze("实习报告_2026-08-28.docx").coreName)
            .isEqualTo("实习报告")
        assertThat(normalizer.analyze("2026年度工作报告.docx").coreName)
            .isEqualTo("2026年度工作报告")
    }
}
