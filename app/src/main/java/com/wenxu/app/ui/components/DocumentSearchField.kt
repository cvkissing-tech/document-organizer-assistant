package com.wenxu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wenxu.app.R
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun DocumentSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val searchDescription = stringResource(R.string.organization_search_documents)
    val clearDescription = stringResource(R.string.organization_clear_search)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = if (enabled) WenxuInk else WenxuMuted,
        ),
        cursorBrush = SolidColor(WenxuBrand),
        modifier = modifier
            .height(42.dp)
            .semantics { contentDescription = searchDescription },
        decorationBox = { innerField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface, shape)
                    .border(1.dp, WenxuLine, shape)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.SEARCH,
                    color = WenxuMuted,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = WenxuMuted,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    innerField()
                }
                if (value.isNotEmpty() && enabled) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .semantics { contentDescription = clearDescription }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button,
                            ) { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        WenxuLineIcon(
                            type = WenxuLineIconType.CLOSE,
                            color = WenxuMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}
