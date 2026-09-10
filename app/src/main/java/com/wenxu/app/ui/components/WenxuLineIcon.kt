package com.wenxu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class WenxuLineIconType {
    HOME, FILE, FOLDER, BACK, MORE, CHEVRON, SEARCH, FILTER,
    SCAN, SETTINGS, REFRESH, TAG, TRASH, OPEN, LOCATION, CHECK,
    SHIELD, GRID, BOOKMARK, SHARE, RESTORE, PLUS, CLOSE, FILE_CHECK,
    PAUSE, PLAY, STOP, SIMILAR, DUPLICATE,
}

@Composable
fun WenxuLineIcon(
    type: WenxuLineIconType,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val w = size.width
        val h = size.height
        val unit = min(w, h)

        fun point(x: Float, y: Float) = Offset(w * x, h * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = color,
                start = point(x1, y1),
                end = point(x2, y2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        fun path(closed: Boolean = false, vararg points: Pair<Float, Float>) {
            if (points.isEmpty()) return
            val shape = Path().apply {
                moveTo(w * points.first().first, h * points.first().second)
                points.drop(1).forEach { (x, y) -> lineTo(w * x, h * y) }
                if (closed) close()
            }
            drawPath(shape, color = color, style = stroke)
        }
        fun circle(x: Float, y: Float, radius: Float, filled: Boolean = false) {
            drawCircle(
                color = color,
                radius = unit * radius,
                center = point(x, y),
                style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke,
            )
        }
        fun roundedRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float = 0.04f,
        ) {
            drawRoundRect(
                color = color,
                topLeft = point(left, top),
                size = Size(w * (right - left), h * (bottom - top)),
                cornerRadius = CornerRadius(unit * radius),
                style = stroke,
            )
        }

        when (type) {
            WenxuLineIconType.HOME -> {
                path(true, 0.17f to 0.44f, 0.50f to 0.17f, 0.83f to 0.44f, 0.83f to 0.81f, 0.17f to 0.81f)
            }
            WenxuLineIconType.FILE -> {
                path(true, 0.25f to 0.15f, 0.63f to 0.15f, 0.75f to 0.28f, 0.75f to 0.85f, 0.25f to 0.85f)
                line(0.63f, 0.15f, 0.63f, 0.28f)
                line(0.63f, 0.28f, 0.75f, 0.28f)
            }
            WenxuLineIconType.FILE_CHECK -> {
                path(true, 0.23f to 0.12f, 0.61f to 0.12f, 0.78f to 0.29f, 0.78f to 0.88f, 0.23f to 0.88f)
                line(0.61f, 0.12f, 0.61f, 0.29f)
                line(0.61f, 0.29f, 0.78f, 0.29f)
                path(false, 0.34f to 0.59f, 0.45f to 0.70f, 0.66f to 0.48f)
            }
            WenxuLineIconType.FOLDER -> {
                path(true, 0.15f to 0.27f, 0.40f to 0.27f, 0.48f to 0.35f, 0.85f to 0.35f, 0.85f to 0.77f, 0.15f to 0.77f)
            }
            WenxuLineIconType.BACK -> path(false, 0.62f to 0.21f, 0.33f to 0.50f, 0.62f to 0.79f)
            WenxuLineIconType.MORE -> {
                circle(0.21f, 0.50f, 0.05f, true)
                circle(0.50f, 0.50f, 0.05f, true)
                circle(0.79f, 0.50f, 0.05f, true)
            }
            WenxuLineIconType.CHEVRON -> path(false, 0.38f to 0.21f, 0.67f to 0.50f, 0.38f to 0.79f)
            WenxuLineIconType.SEARCH -> {
                circle(0.44f, 0.44f, 0.27f)
                line(0.64f, 0.64f, 0.83f, 0.83f)
            }
            WenxuLineIconType.FILTER -> {
                line(0.17f, 0.25f, 0.83f, 0.25f)
                line(0.29f, 0.50f, 0.71f, 0.50f)
                line(0.42f, 0.75f, 0.58f, 0.75f)
            }
            WenxuLineIconType.SCAN -> {
                line(0.14f, 0.34f, 0.14f, 0.16f)
                line(0.14f, 0.16f, 0.32f, 0.16f)
                line(0.68f, 0.16f, 0.86f, 0.16f)
                line(0.86f, 0.16f, 0.86f, 0.34f)
                line(0.14f, 0.66f, 0.14f, 0.84f)
                line(0.14f, 0.84f, 0.32f, 0.84f)
                line(0.68f, 0.84f, 0.86f, 0.84f)
                line(0.86f, 0.84f, 0.86f, 0.66f)
                line(0.27f, 0.50f, 0.73f, 0.50f)
            }
            WenxuLineIconType.SETTINGS -> {
                circle(0.50f, 0.50f, 0.13f)
                repeat(8) { index ->
                    val angle = Math.PI * index / 4.0
                    val inner = unit * 0.29f
                    val outer = unit * 0.41f
                    val center = point(0.50f, 0.50f)
                    drawLine(
                        color = color,
                        start = Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                        end = Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
                circle(0.50f, 0.50f, 0.31f)
            }
            WenxuLineIconType.REFRESH -> {
                drawArc(
                    color = color,
                    startAngle = 35f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = point(0.17f, 0.17f),
                    size = Size(w * 0.66f, h * 0.66f),
                    style = stroke,
                )
                line(0.76f, 0.29f, 0.61f, 0.26f)
                line(0.76f, 0.29f, 0.79f, 0.14f)
            }
            WenxuLineIconType.TAG -> {
                path(true, 0.17f to 0.17f, 0.46f to 0.17f, 0.83f to 0.54f, 0.54f to 0.83f, 0.17f to 0.46f)
                circle(0.33f, 0.33f, 0.04f)
            }
            WenxuLineIconType.TRASH -> {
                line(0.17f, 0.29f, 0.83f, 0.29f)
                line(0.38f, 0.29f, 0.38f, 0.17f)
                line(0.38f, 0.17f, 0.63f, 0.17f)
                line(0.63f, 0.17f, 0.63f, 0.29f)
                path(false, 0.29f to 0.29f, 0.33f to 0.83f, 0.67f to 0.83f, 0.71f to 0.29f)
                line(0.42f, 0.46f, 0.42f, 0.67f)
                line(0.58f, 0.46f, 0.58f, 0.67f)
            }
            WenxuLineIconType.OPEN -> {
                path(false, 0.33f to 0.17f, 0.17f to 0.17f, 0.17f to 0.83f, 0.83f to 0.83f, 0.83f to 0.67f)
                path(false, 0.50f to 0.17f, 0.83f to 0.17f, 0.83f to 0.50f)
                line(0.83f, 0.17f, 0.46f, 0.54f)
            }
            WenxuLineIconType.LOCATION -> {
                val marker = Path().apply {
                    moveTo(w * 0.50f, h * 0.88f)
                    cubicTo(w * 0.50f, h * 0.88f, w * 0.75f, h * 0.66f, w * 0.75f, h * 0.42f)
                    cubicTo(w * 0.75f, h * 0.22f, w * 0.64f, h * 0.13f, w * 0.50f, h * 0.13f)
                    cubicTo(w * 0.36f, h * 0.13f, w * 0.25f, h * 0.22f, w * 0.25f, h * 0.42f)
                    cubicTo(w * 0.25f, h * 0.66f, w * 0.50f, h * 0.88f, w * 0.50f, h * 0.88f)
                    close()
                }
                drawPath(marker, color = color, style = stroke)
                circle(0.50f, 0.42f, 0.08f)
            }
            WenxuLineIconType.CHECK -> path(false, 0.21f to 0.50f, 0.38f to 0.67f, 0.79f to 0.25f)
            WenxuLineIconType.SHIELD -> {
                path(true, 0.50f to 0.13f, 0.21f to 0.25f, 0.21f to 0.46f, 0.21f to 0.68f, 0.50f to 0.88f, 0.79f to 0.68f, 0.79f to 0.25f)
                path(false, 0.38f to 0.50f, 0.46f to 0.58f, 0.63f to 0.42f)
            }
            WenxuLineIconType.GRID -> {
                roundedRect(0.17f, 0.17f, 0.42f, 0.42f)
                roundedRect(0.58f, 0.17f, 0.83f, 0.42f)
                roundedRect(0.17f, 0.58f, 0.42f, 0.83f)
                roundedRect(0.58f, 0.58f, 0.83f, 0.83f)
            }
            WenxuLineIconType.BOOKMARK -> path(true, 0.29f to 0.17f, 0.71f to 0.17f, 0.71f to 0.83f, 0.50f to 0.71f, 0.29f to 0.83f)
            WenxuLineIconType.SHARE -> {
                line(0.50f, 0.67f, 0.50f, 0.17f)
                path(false, 0.33f to 0.33f, 0.50f to 0.17f, 0.67f to 0.33f)
                path(false, 0.21f to 0.54f, 0.21f to 0.83f, 0.79f to 0.83f, 0.79f to 0.54f)
            }
            WenxuLineIconType.RESTORE -> {
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = point(0.18f, 0.18f),
                    size = Size(w * 0.64f, h * 0.64f),
                    style = stroke,
                )
                line(0.18f, 0.33f, 0.18f, 0.54f)
                line(0.18f, 0.54f, 0.39f, 0.54f)
            }
            WenxuLineIconType.PLUS -> {
                line(0.50f, 0.21f, 0.50f, 0.79f)
                line(0.21f, 0.50f, 0.79f, 0.50f)
            }
            WenxuLineIconType.CLOSE -> {
                line(0.29f, 0.29f, 0.71f, 0.71f)
                line(0.71f, 0.29f, 0.29f, 0.71f)
            }
            WenxuLineIconType.PAUSE -> {
                line(0.38f, 0.27f, 0.38f, 0.73f)
                line(0.62f, 0.27f, 0.62f, 0.73f)
            }
            WenxuLineIconType.PLAY -> path(true, 0.35f to 0.23f, 0.73f to 0.50f, 0.35f to 0.77f)
            WenxuLineIconType.STOP -> roundedRect(0.29f, 0.29f, 0.71f, 0.71f, 0.03f)
            WenxuLineIconType.SIMILAR -> {
                roundedRect(0.12f, 0.23f, 0.44f, 0.77f, 0.04f)
                roundedRect(0.56f, 0.23f, 0.88f, 0.77f, 0.04f)
                line(0.20f, 0.43f, 0.36f, 0.43f)
                line(0.20f, 0.57f, 0.34f, 0.57f)
                line(0.64f, 0.43f, 0.80f, 0.43f)
                line(0.66f, 0.57f, 0.80f, 0.57f)
            }
            WenxuLineIconType.DUPLICATE -> {
                roundedRect(0.19f, 0.19f, 0.68f, 0.68f, 0.05f)
                roundedRect(0.32f, 0.32f, 0.81f, 0.81f, 0.05f)
            }
        }
    }
}
