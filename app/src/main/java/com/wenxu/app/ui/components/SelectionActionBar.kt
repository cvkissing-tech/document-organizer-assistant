package com.wenxu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.wenxu.app.R
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuInk

@Composable
fun SelectionActionBar(
    selectedCount: Int,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.documents_selected_count,
                    selectedCount.coerceAtLeast(0),
                    selectedCount.coerceAtLeast(0),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WenxuInk,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(
                        onClick = onSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = DocumentAssistantDimens.TouchTarget),
                        shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                    ) {
                        Text(secondaryLabel, maxLines = 2)
                    }
                }
                Button(
                    onClick = onPrimary,
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = DocumentAssistantDimens.TouchTarget),
                    shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) WenxuDanger else WenxuBrand,
                    ),
                ) {
                    Text(primaryLabel, maxLines = 2)
                }
            }
        }
    }
}
