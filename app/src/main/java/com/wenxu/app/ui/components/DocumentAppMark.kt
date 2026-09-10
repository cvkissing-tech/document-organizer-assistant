package com.wenxu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft

@Composable
fun DocumentAppMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(38.dp)) {
        drawRoundRect(
            color = WenxuBrandSoft,
            topLeft = Offset(size.width * 0.29f, size.height * 0.05f),
            size = Size(size.width * 0.58f, size.height * 0.78f),
            cornerRadius = CornerRadius(size.width * 0.10f),
        )
        val page = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.17f)
            lineTo(size.width * 0.52f, size.height * 0.17f)
            lineTo(size.width * 0.72f, size.height * 0.38f)
            lineTo(size.width * 0.72f, size.height * 0.92f)
            lineTo(size.width * 0.08f, size.height * 0.92f)
            close()
        }
        drawPath(page, WenxuBrand)
        val fold = Path().apply {
            moveTo(size.width * 0.52f, size.height * 0.17f)
            lineTo(size.width * 0.52f, size.height * 0.38f)
            lineTo(size.width * 0.72f, size.height * 0.38f)
            close()
        }
        drawPath(fold, WenxuBrandSoft)
    }
}
