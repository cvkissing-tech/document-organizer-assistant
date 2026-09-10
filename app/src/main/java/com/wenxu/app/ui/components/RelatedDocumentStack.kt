package com.wenxu.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wenxu.app.R
import com.wenxu.app.feature.home.RelatedSummary
import com.wenxu.app.ui.theme.WenxuAmber
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun RelatedDocumentStack(
    summary: RelatedSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.totalGroups == 0) {
        RelatedEmptyState(
            text = stringResource(R.string.related_empty_title),
            detail = stringResource(R.string.related_empty_detail),
            modifier = modifier,
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentStackIllustration(previewNames = summary.previewNames)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.related_stack_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.related_stack_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = WenxuMuted,
                modifier = Modifier.padding(top = 5.dp),
            )
            Column(
                modifier = Modifier.padding(top = 9.dp),
            ) {
                RelationLabel(
                    text = pluralStringResource(
                        R.plurals.name_similar_groups_count,
                        summary.nameSimilarGroups,
                        summary.nameSimilarGroups,
                    ),
                    background = WenxuBrandSoft,
                    color = WenxuBrand,
                )
                RelationLabel(
                    text = pluralStringResource(
                        R.plurals.exact_duplicate_groups_count,
                        summary.exactDuplicateSets,
                        summary.exactDuplicateSets,
                    ),
                    background = Color(0xFFF3ECE2),
                    color = WenxuAmber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.CHEVRON,
                color = WenxuMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun DocumentStackIllustration(
    previewNames: List<String>,
    modifier: Modifier = Modifier,
) {
    val hasRealNames = previewNames.isNotEmpty()
    Box(
        modifier = modifier.size(width = 126.dp, height = 94.dp),
        contentAlignment = Alignment.Center,
    ) {
        DocumentLeaf(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 2.dp),
            color = Color(0xFFF0F4F3),
            name = previewNames.getOrNull(1),
            label = if (hasRealNames) stringResource(R.string.related_document_label) else null,
        )
        DocumentLeaf(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 12.dp, y = 4.dp),
            color = MaterialTheme.colorScheme.surface,
            foreground = true,
            name = previewNames.firstOrNull(),
            label = if (hasRealNames) stringResource(R.string.related_pending_label) else null,
        )
    }
}

@Composable
private fun DocumentLeaf(
    modifier: Modifier,
    color: Color,
    name: String?,
    label: String?,
    foreground: Boolean = false,
) {
    Surface(
        modifier = modifier.size(width = 91.dp, height = 68.dp),
        shape = RoundedCornerShape(10.dp),
        color = color,
        border = BorderStroke(1.dp, WenxuLine),
        shadowElevation = if (foreground) 4.dp else 0.dp,
    ) {
        if (name == null) {
            Box(
                modifier = Modifier.padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.CHECK,
                    color = WenxuBrand,
                    modifier = Modifier.size(if (foreground) 24.dp else 18.dp),
                )
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                label?.let {
                    Text(
                        text = it,
                        color = WenxuBrand,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .background(WenxuBrandSoft, RoundedCornerShape(7.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedEmptyState(
    text: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(WenxuBrandSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.CHECK,
                color = WenxuBrand,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = WenxuMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun RelationLabel(
    text: String,
    background: Color,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
