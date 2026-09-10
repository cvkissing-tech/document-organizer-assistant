package com.wenxu.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenxu.app.R
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft

@Composable
fun DocumentRow(
    document: DocumentRecord,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    onClick: () -> Unit = {},
    onMore: (() -> Unit)? = null,
    moreDescription: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionChange: (() -> Unit)? = null,
    tags: List<String> = emptyList(),
) {
    val resolvedMoreDescription = moreDescription ?: stringResource(R.string.action_more)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tags.isEmpty()) 68.dp else 82.dp)
            .clickable(
                role = Role.Button,
                onClick = if (selectionMode) onSelectionChange ?: onClick else onClick,
            )
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier.size(width = 36.dp, height = DocumentAssistantDimens.TouchTarget),
                contentAlignment = Alignment.CenterStart,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectionChange?.invoke() },
                )
            }
        }
        DocumentTypeIcon(
            extension = document.extension,
            modifier = Modifier.padding(start = if (selectionMode) 2.dp else 0.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metadata?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.take(2).forEach { tag ->
                        Text(
                            text = tag,
                            color = WenxuBrand,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(WenxuBrandSoft)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        if (!selectionMode) {
            Box(
                modifier = Modifier
                    .size(DocumentAssistantDimens.TouchTarget)
                    .then(
                        if (onMore == null) Modifier else Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = resolvedMoreDescription,
                            onClick = onMore,
                        ).semantics { contentDescription = resolvedMoreDescription },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                WenxuLineIcon(
                    type = if (onMore == null) WenxuLineIconType.CHEVRON else WenxuLineIconType.MORE,
                    color = WenxuMuted,
                    modifier = Modifier.size(DocumentAssistantDimens.Icon),
                )
            }
        }
    }
}
