package com.wenxu.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft

@Composable
fun RetryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = WenxuBrand,
    containerColor: Color = WenxuBrandSoft,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.REFRESH,
                color = accentColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}
