package com.wenxu.app.feature.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfViewportStateTest {
    @Test
    fun scaleRemainsWithinSupportedBounds() {
        val enlarged = PdfViewportState().zoomBy(
            factor = 20f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewportWidth = 100f,
            viewportHeight = 100f,
        )
        val reduced = enlarged.zoomBy(
            factor = 0.01f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewportWidth = 100f,
            viewportHeight = 100f,
        )

        assertThat(enlarged.scale).isEqualTo(4f)
        assertThat(reduced.scale).isEqualTo(1f)
    }

    @Test
    fun zoomIsClampedBetweenOneAndFourTimes() {
        val enlarged = PdfViewportState().zoomBy(
            factor = 8f,
            contentWidth = 1000f,
            contentHeight = 1400f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )
        val reduced = enlarged.zoomBy(
            factor = 0.01f,
            contentWidth = 1000f,
            contentHeight = 1400f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )

        assertThat(enlarged.scale).isEqualTo(4f)
        assertThat(reduced.scale).isEqualTo(1f)
    }

    @Test
    fun panCannotMovePageOutsideViewportBounds() {
        val state = PdfViewportState(scale = 2f).panBy(
            deltaX = 8_000f,
            deltaY = -8_000f,
            contentWidth = 1000f,
            contentHeight = 1400f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )

        assertThat(state.offsetX).isEqualTo(500f)
        assertThat(state.offsetY).isEqualTo(-2000f)
    }

    @Test
    fun doubleTapTogglesBetweenReadableZoomAndOriginalSize() {
        val enlarged = PdfViewportState().toggleZoom(
            contentWidth = 1000f,
            contentHeight = 1400f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )
        val restored = enlarged.toggleZoom(
            contentWidth = 1000f,
            contentHeight = 1400f,
            viewportWidth = 1000f,
            viewportHeight = 800f,
        )

        assertThat(enlarged.scale).isEqualTo(2f)
        assertThat(restored).isEqualTo(PdfViewportState())
    }
}
