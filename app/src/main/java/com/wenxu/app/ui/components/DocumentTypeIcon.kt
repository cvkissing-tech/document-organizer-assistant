package com.wenxu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenxu.app.ui.theme.WenxuExcel
import com.wenxu.app.ui.theme.WenxuPdf
import com.wenxu.app.ui.theme.WenxuPowerPoint
import com.wenxu.app.ui.theme.WenxuWord

internal fun documentFormatLabel(extension: String): String = when (extension.lowercase()) {
    "pdf" -> "PDF"
    "doc", "docx" -> "W"
    "xls", "xlsx" -> "X"
    "ppt", "pptx" -> "PPT"
    "" -> "FILE"
    else -> extension.uppercase().take(4)
}

internal fun documentFormatColor(extension: String): Color = when (extension.lowercase()) {
    "pdf" -> WenxuPdf
    "doc", "docx" -> WenxuWord
    "xls", "xlsx" -> WenxuExcel
    "ppt", "pptx" -> WenxuPowerPoint
    else -> WenxuWord
}

@Composable
fun DocumentTypeIcon(
    extension: String,
    modifier: Modifier = Modifier,
) {
    val color = documentFormatColor(extension)
    Box(
        modifier = modifier.size(width = 29.dp, height = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val foldX = size.width - 10.dp.toPx()
            val foldY = 10.dp.toPx()
            val outline = Path().apply {
                moveTo(1.dp.toPx(), 1.dp.toPx())
                lineTo(foldX, 1.dp.toPx())
                lineTo(size.width - 1.dp.toPx(), foldY)
                lineTo(size.width - 1.dp.toPx(), size.height - 1.dp.toPx())
                lineTo(1.dp.toPx(), size.height - 1.dp.toPx())
                close()
            }
            drawPath(outline, color = color, style = stroke)
            val fold = Path().apply {
                moveTo(foldX, 1.dp.toPx())
                lineTo(foldX, foldY)
                lineTo(size.width - 1.dp.toPx(), foldY)
            }
            drawPath(fold, color = color, style = stroke)
        }
        Text(
            text = documentFormatLabel(extension),
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = when (extension.lowercase()) {
                    "doc", "docx", "xls", "xlsx" -> 18.sp
                    "pdf", "ppt", "pptx" -> 10.sp
                    else -> 9.sp
                },
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 4.dp),
        )
    }
}
