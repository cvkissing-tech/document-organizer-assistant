package com.wenxu.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WenxuDestinationTest {
    @Test
    fun relatedTargetRoundTripsSelectedDocumentIds() {
        val route = WenxuDestinations.related(setOf(9, 3, 5))

        assertThat(route).isEqualTo("related?targetIds=3,5,9")
        assertThat(WenxuDestinations.parseRelatedTarget(route.substringAfter("targetIds=")))
            .containsExactly(3L, 5L, 9L)
    }

    @Test
    fun relatedTargetIsBoundedAndDropsInvalidValues() {
        val parsed = WenxuDestinations.parseRelatedTarget(
            (1L..30L).joinToString(",") + ",-2,broken",
        )

        assertThat(parsed).hasSize(20)
        assertThat(parsed).doesNotContain(-2L)
    }
}
