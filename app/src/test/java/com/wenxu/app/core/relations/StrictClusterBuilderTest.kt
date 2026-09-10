package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StrictClusterBuilderTest {
    private val builder = StrictClusterBuilder()

    @Test
    fun chainDoesNotMergeWithoutAllPairEdges() {
        val clusters = builder.build(
            mapOf(
                pair(1, 2) to 95,
                pair(2, 3) to 94,
            ),
        )

        assertThat(clusters.maxOf { it.size }).isEqualTo(2)
    }

    @Test
    fun completeTriangleBecomesOneCluster() {
        val clusters = builder.build(
            mapOf(
                pair(1, 2) to 95,
                pair(2, 3) to 94,
                pair(1, 3) to 93,
            ),
        )

        assertThat(clusters).containsExactly(setOf(1L, 2L, 3L))
    }

    @Test
    fun higherScoreEdgeWinsWhenChainOffersTwoPossiblePairs() {
        val clusters = builder.build(
            mapOf(
                pair(1, 2) to 91,
                pair(2, 3) to 96,
            ),
        )

        assertThat(clusters).containsExactly(setOf(2L, 3L))
    }

    private fun pair(first: Long, second: Long): VersionDocumentPair =
        VersionDocumentPair.of(first, second)
}
