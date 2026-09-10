package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MinHashFingerprintTest {
    private val fingerprint = testMinHash()

    @Test
    fun smallParagraphEditKeepsHighSimilarity() {
        val first = fingerprint.create("毕业实习报告 项目背景 开发过程 测试结果")
        val second = fingerprint.create("毕业实习报告 项目背景 开发过程 测试结果 总结")

        assertThat(fingerprint.similarity(first, second)).isGreaterThan(0.70)
    }

    @Test
    fun unrelatedDocumentsStayLow() {
        val first = fingerprint.create("高等数学微积分作业")
        val second = fingerprint.create("市场营销销售统计分析")

        assertThat(fingerprint.similarity(first, second)).isLessThan(0.30)
    }

    @Test
    fun normalizationProducesStableSignature() {
        val first = fingerprint.create("  实习报告\n项目  背景  ")
        val second = fingerprint.create("实习报告 项目 背景")

        assertThat(first.asList()).containsExactlyElementsIn(second.asList()).inOrder()
    }

    @Test
    fun emptyTextHasNoSignature() {
        assertThat(fingerprint.create(" \n\t ")).isEmpty()
    }

    @Test
    fun shortUnsaturatedSignaturesUseTrueJaccard() {
        val first = fingerprint.create("abcde")
        val second = fingerprint.create("abcxy")

        assertThat(first).hasLength(4)
        assertThat(second).hasLength(4)
        assertThat(fingerprint.similarity(first, second)).isWithin(0.0001).of(1.0 / 7.0)
    }

    @Test
    fun unicodeCodePointsAreNotSplitIntoSurrogateHalves() {
        val first = fingerprint.create("课程😀报告")
        val second = fingerprint.create("课程😀报告总结")

        assertThat(fingerprint.similarity(first, second)).isAtLeast(0.40)
    }

    @Test
    fun sameTextWithDifferentInstallSecretHasDifferentSignatureAndAlgorithmVersion() {
        val other = MinHashFingerprint(
            FingerprintSecretProvider {
                FingerprintSecret(9, "other-install-secret".encodeToByteArray())
            },
        )

        assertThat(other.create("同一份文档").asList())
            .containsNoneIn(fingerprint.create("同一份文档").asList())
        assertThat(other.algorithmVersion).isNotEqualTo(fingerprint.algorithmVersion)
    }

    @Test
    fun saturatedKmvKeepsSmallEditHighlySimilar() {
        val original = (0..300).joinToString(" ") { index -> "段落${index}内容" }
        val edited = original + " 补充总结"
        val first = fingerprint.create(original)
        val second = fingerprint.create(edited)

        assertThat(first).hasLength(MinHashFingerprint.DEFAULT_SIGNATURE_SIZE)
        assertThat(second).hasLength(MinHashFingerprint.DEFAULT_SIGNATURE_SIZE)
        assertThat(fingerprint.similarity(first, second)).isGreaterThan(0.85)
    }
}

internal val TEST_FINGERPRINT_SECRET = FingerprintSecret(
    keyVersion = 7,
    keyBytes = "unit-test-install-secret-32-bytes".encodeToByteArray(),
)

internal fun testMinHash() =
    MinHashFingerprint(
        FingerprintSecretProvider {
            FingerprintSecret(TEST_FINGERPRINT_SECRET.keyVersion, TEST_FINGERPRINT_SECRET.keyMaterial())
        },
    )
