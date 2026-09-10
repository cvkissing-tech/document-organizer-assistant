package com.wenxu.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wenxu.app.R
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.util.shareDocument

@Composable
fun DocumentAssistantDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit = onConfirm,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                supportingContent?.invoke()
                Text(
                    text = message,
                    color = WenxuMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = if (supportingContent == null) 0.dp else 10.dp),
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (dismissLabel != null && onDismiss != null) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 5.dp)
                            .heightIn(min = DocumentAssistantDimens.TouchTarget),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(dismissLabel)
                    }
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (dismissLabel == null) 0.dp else 5.dp)
                        .heightIn(min = DocumentAssistantDimens.TouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) WenxuDanger else WenxuBrand,
                    ),
                ) {
                    Text(confirmLabel)
                }
            }
        },
    )
}

@Composable
fun DocumentOpenErrorDialog(
    document: DocumentRecord,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val format = document.extension.uppercase().ifBlank {
        stringResource(R.string.document_format_generic)
    }
    val shareErrorMessage = stringResource(R.string.document_share_error)
    var showLocation by remember(document.id) { mutableStateOf(false) }

    if (showLocation) {
        DocumentAssistantDialog(
            title = stringResource(R.string.document_location_title),
            message = document.parentUri,
            confirmLabel = stringResource(R.string.action_got_it),
            onConfirm = onDismiss,
            onDismissRequest = onDismiss,
            supportingContent = {
                Text(
                    text = document.displayName,
                    color = WenxuMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(WenxuBrandSoft, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    DocumentTypeIcon(extension = document.extension)
                }
                Text(
                    text = stringResource(R.string.document_open_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        },
        text = {
            Text(
                text = stringResource(R.string.document_open_error_message, format),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showLocation = true },
                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(stringResource(R.string.action_view_location))
                }
                Button(
                    onClick = {
                        if (shareDocument(context, document)) {
                            onDismiss()
                        } else {
                            Toast.makeText(context, shareErrorMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .heightIn(min = 42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
                ) {
                    Text(stringResource(R.string.action_share_file))
                }
            }
        },
    )
}
