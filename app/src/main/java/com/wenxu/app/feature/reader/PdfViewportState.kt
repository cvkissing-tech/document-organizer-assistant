package com.wenxu.app.feature.reader

import kotlin.math.max

data class PdfViewportState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun zoomBy(
        factor: Float,
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): PdfViewportState = copy(
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE),
    ).constrained(contentWidth, contentHeight, viewportWidth, viewportHeight)

    fun panBy(
        deltaX: Float,
        deltaY: Float,
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): PdfViewportState = copy(
        offsetX = offsetX + deltaX,
        offsetY = offsetY + deltaY,
    ).constrained(contentWidth, contentHeight, viewportWidth, viewportHeight)

    fun toggleZoom(
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): PdfViewportState = if (scale > MIN_SCALE) {
        PdfViewportState()
    } else {
        copy(scale = DOUBLE_TAP_SCALE)
            .constrained(contentWidth, contentHeight, viewportWidth, viewportHeight)
    }

    private fun constrained(
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): PdfViewportState {
        if (contentWidth <= 0f || contentHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
            return copy(offsetX = 0f, offsetY = 0f)
        }
        val horizontalOverflow = max(0f, (contentWidth * scale - viewportWidth) / 2f)
        val verticalOverflow = max(0f, contentHeight * scale - viewportHeight)
        return copy(
            offsetX = offsetX.coerceIn(-horizontalOverflow, horizontalOverflow),
            offsetY = offsetY.coerceIn(-verticalOverflow, 0f),
        )
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2f
    }
}
