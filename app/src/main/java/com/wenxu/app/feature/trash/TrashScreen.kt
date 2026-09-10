package com.wenxu.app.feature.trash

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.ui.components.DocumentAssistantDialog
import com.wenxu.app.ui.components.DocumentAssistantEmptyState
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.DocumentTypeIcon
import com.wenxu.app.ui.components.SelectionActionBar
import com.wenxu.app.ui.components.SystemTrashConfirmationEffect
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrashScreen(viewModel: TrashViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPermanentDelete by remember { mutableStateOf(false) }
    var showClearTrash by remember { mutableStateOf(false) }
    val restoreElsewherePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.restoreSelected(it.toString()) }
    }

    val messageText = state.message?.resolve()
    SystemTrashConfirmationEffect(
        request = state.pendingConfirmation,
        onResult = viewModel::onSystemTrashResult,
    )
    LaunchedEffect(state.message) {
        messageText?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    TrashScreen(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onRestoreItem = viewModel::restoreOne,
        onRestore = { viewModel.restoreSelected() },
        onRestoreElsewhere = { restoreElsewherePicker.launch(null) },
        onDeleteForever = { showPermanentDelete = true },
        onClear = { showClearTrash = true },
    )

    if (showPermanentDelete) {
        DocumentAssistantDialog(
            title = stringResource(R.string.trash_delete_selected_title),
            message = pluralStringResource(
                R.plurals.trash_delete_selected_message,
                state.selectedIds.size,
                state.selectedIds.size,
            ),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            onConfirm = {
                showPermanentDelete = false
                viewModel.deleteSelectedForever()
            },
            onDismissRequest = { showPermanentDelete = false },
            dismissLabel = stringResource(R.string.trash_cancel),
            onDismiss = { showPermanentDelete = false },
            destructive = true,
        )
    }

    if (showClearTrash) {
        DocumentAssistantDialog(
            title = stringResource(R.string.trash_clear_title),
            message = pluralStringResource(
                R.plurals.trash_clear_message,
                state.items.size,
                state.items.size,
            ),
            confirmLabel = stringResource(R.string.trash_clear),
            onConfirm = {
                showClearTrash = false
                val unselectedIds = state.items.map { it.id }.filterNot { it in state.selectedIds }
                unselectedIds.forEach(viewModel::toggle)
                viewModel.deleteSelectedForever()
            },
            onDismissRequest = { showClearTrash = false },
            dismissLabel = stringResource(R.string.trash_cancel),
            onDismiss = { showClearTrash = false },
            destructive = true,
        )
    }
}

@Composable
fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onRestoreItem: (Long) -> Unit = {},
    onRestore: () -> Unit = {},
    onRestoreElsewhere: () -> Unit = {},
    onDeleteForever: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val selectionMode = state.selectedIds.isNotEmpty()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (selectionMode) {
                Column {
                    TextButton(
                        onClick = onRestoreElsewhere,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        WenxuLineIcon(WenxuLineIconType.FOLDER, WenxuBrand, Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.trash_restore_elsewhere),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    SelectionActionBar(
                        selectedCount = state.selectedIds.size,
                        primaryLabel = stringResource(R.string.trash_delete_forever),
                        onPrimary = onDeleteForever,
                        secondaryLabel = stringResource(R.string.trash_restore_selected),
                        onSecondary = onRestore,
                        destructive = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(DocumentAssistantDimens.PageTop))
            Box(Modifier.fillMaxWidth()) {
                DocumentAssistantTopBar(title = stringResource(R.string.destination_trash), onBack = onBack)
                if (state.items.isNotEmpty()) {
                    TextButton(
                        onClick = if (selectionMode) onSelectAll else onClear,
                        enabled = !state.isWorking,
                        modifier = Modifier.align(Alignment.CenterEnd).height(48.dp),
                    ) {
                        Text(
                            text = if (selectionMode) {
                                stringResource(
                                    if (state.selectedIds.size == state.items.size) {
                                        R.string.trash_deselect_all
                                    } else {
                                        R.string.trash_select_all
                                    },
                                )
                            } else {
                                stringResource(R.string.trash_clear)
                            },
                            color = if (selectionMode) WenxuBrand else WenxuDanger,
                        )
                    }
                }
            }

            Surface(
                color = WenxuBrandSoft,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(38.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WenxuLineIcon(WenxuLineIconType.TRASH, WenxuBrand, Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.trash_retention),
                        color = WenxuMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DocumentAssistantEmptyState(
                        icon = WenxuLineIconType.TRASH,
                        title = stringResource(R.string.trash_empty_title),
                        message = stringResource(R.string.trash_empty_message),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        TrashRow(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = selectionMode,
                            onToggle = { onToggle(item.id) },
                            onRestore = { onRestoreItem(item.id) },
                        )
                        HorizontalDivider(color = WenxuLine)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    item: TrashItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = { if (selectionMode) onToggle() },
                onLongClick = onToggle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
        DocumentTypeIcon(extension = item.extension)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                text = item.displayName,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.trash_deleted_on,
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.deletedAt)),
                ) + " · ${formatSize(item.sizeBytes)}",
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!selectionMode) {
            TextButton(onClick = onRestore, enabled = !selected) {
                Text(
                    stringResource(R.string.trash_restore),
                    color = WenxuBrand,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", size / 1024f / 1024f)
}
