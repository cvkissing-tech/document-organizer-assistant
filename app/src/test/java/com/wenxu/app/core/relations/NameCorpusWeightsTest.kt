package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameCorpusWeightsTest {
    private val normalizer = NameNormalizer()

    @Test
    fun frequentFragmentsReceiveLessWeightThanSpecificFragments() {
        val features = listOf(
            normalizer.analyze("人工智能课程报告.docx"),
            normalizer.analyze("市场营销课程报告.docx"),
            normalizer.analyze("毕业实习报告.docx"),
            normalizer.analyze("物理实验报告.docx"),
        )
        val weights = NameCorpusWeights.from(features)

        assertThat(weights.weightOf("报告")).isLessThan(weights.weightOf("人工"))
    }
}
