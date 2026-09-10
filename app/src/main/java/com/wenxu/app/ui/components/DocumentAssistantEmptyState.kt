package com.wenxu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun DocumentAssistantEmptyState(
    icon: WenxuLineIconType,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(WenxuBrandSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = icon,
                color = WenxuBrand,
                modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = WenxuMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 7.dp)
                    .heightIn(min = DocumentAssistantDimens.TouchTarget),
            ) {
                Text(
                    text = actionLabel,
                    color = WenxuBrand,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
