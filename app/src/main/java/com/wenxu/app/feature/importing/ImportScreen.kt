package com.wenxu.app.feature.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.importing.IncomingDocument
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.DocumentTypeIcon
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuDangerSoft
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import java.util.Locale

@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.chooseInbox(uri?.toString())
    }
    ImportScreen(
        state = state,
        onChooseInbox = { picker.launch(state.inboxTreeUri?.let(Uri::parse)) },
        onImport = { viewModel.importDocuments(onCompleted) },
        onCancel = { viewModel.cancel(onCancelled) },
    )
}

@Composable
fun ImportScreen(
    state: ImportUiState,
    onChooseInbox: () -> Unit = {},
    onImport: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Box(Modifier.padding(horizontal = 6.dp)) {
            DocumentAssistantTopBar(
                title = stringResource(R.string.import_title),
                onBack = if (state.isImporting) null else onCancel,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item { ImportStatus(state.isImporting) }
            item {
                Text(
                    text = if (state.isLoading) {
                        stringResource(R.string.import_loading)
                    } else {
                        pluralStringResource(
                            R.plurals.import_pending_count,
                            state.documents.size,
                            state.documents.size,
                        )
                    },
                    color = WenxuInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                )
            }

            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = WenxuBrand)
                    }
                }
            } else {
                itemsIndexed(state.documents, key = { _, item -> item.uri }) { index, document ->
                    ImportDocumentRow(document, state.isImporting)
                    if (index != state.documents.lastIndex) HorizontalDivider(color = WenxuLine)
                }
            }

            if (state.unsupportedCount > 0) {
                item {
                    Text(
                        text = pluralStringResource(
                            R.plurals.import_unsupported_count,
                            state.unsupportedCount,
                            state.unsupportedCount,
                        ),
                        color = WenxuMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            state.message?.let { message ->
                item {
                    Surface(
                        color = if (state.hasFailures) WenxuDangerSoft else WenxuBrandSoft,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Text(
                            text = message.resolve(),
                            color = if (state.hasFailures) WenxuDanger else WenxuBrand,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (state.inboxTreeUri != null) {
                    Text(
                        text = stringResource(R.string.import_inbox_location),
                        color = WenxuMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = if (state.inboxTreeUri == null) onChooseInbox else onImport,
                    enabled = !state.isImporting && (state.inboxTreeUri == null || state.documents.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
                ) {
                    Text(
                        text = when {
                            state.isImporting -> stringResource(R.string.import_saving)
                            state.inboxTreeUri == null -> stringResource(R.string.import_choose_location)
                            else -> pluralStringResource(
                                R.plurals.import_save_count,
                                state.documents.size,
                                state.documents.size,
                            )
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportStatus(isImporting: Boolean) {
    Surface(
        color = WenxuBrandSoft,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WenxuLineIcon(WenxuLineIconType.SHIELD, WenxuBrand, Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.import_original_kept),
                    color = WenxuInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.import_explanation),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
                    color = WenxuBrand,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

@Composable
private fun ImportDocumentRow(document: IncomingDocument, isImporting: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentTypeIcon(extension = document.displayName.substringAfterLast('.', ""))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = document.displayName,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSize(document.sizeBytes),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (isImporting) {
            Text(
                stringResource(R.string.import_item_saving),
                color = WenxuBrand,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", size / 1024f / 1024f)
}
