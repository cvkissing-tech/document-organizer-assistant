package com.wenxu.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wenxu.app.R
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun DocumentAssistantTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actionIcon: WenxuLineIconType? = null,
    actionLabel: String? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        TopBarIconButton(
            icon = WenxuLineIconType.BACK,
            description = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = title,
            color = WenxuInk,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 58.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(DocumentAssistantDimens.TouchTarget),
            ) {
                Text(
                    text = actionLabel,
                    color = com.wenxu.app.ui.theme.WenxuBrand,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            TopBarIconButton(
                icon = actionIcon,
                description = actionDescription,
                onClick = onAction,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun TopBarIconButton(
    icon: WenxuLineIconType?,
    description: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (icon == null || onClick == null) {
        Box(modifier.size(DocumentAssistantDimens.TouchTarget))
        return
    }

    val resolvedDescription = description.orEmpty()
    Box(
        modifier = modifier
            .size(DocumentAssistantDimens.TouchTarget)
            .semantics { contentDescription = resolvedDescription }
            .clickable(
                role = Role.Button,
                onClickLabel = resolvedDescription.ifBlank { null },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(
            type = icon,
            color = WenxuMuted,
            modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
        )
    }
}
