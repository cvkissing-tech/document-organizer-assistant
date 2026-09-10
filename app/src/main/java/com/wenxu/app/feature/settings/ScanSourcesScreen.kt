package com.wenxu.app.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.createResolvableRequestIntent
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.ui.components.DocumentAssistantDialog
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.RetryActionButton
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuAmber
import com.wenxu.app.ui.theme.WenxuAmberSoft
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import java.text.DateFormat
import java.util.Date

private val SCAN_DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

@Composable
fun ScanSourcesScreen(
    viewModel: ScanSourcesViewModel,
    storageAccessController: StorageAccessController,
    onFilesSelected: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var reauthorizeSourceId by remember { mutableStateOf<Long?>(null) }
    var removeSourceId by remember { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.add(uri?.toString())
    }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val values = uris.map { it.toString() }
        if (values.isNotEmpty()) onFilesSelected(values)
    }
    val reauthorizePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        reauthorizeSourceId?.let { viewModel.reauthorize(it, uri?.toString()) }
        reauthorizeSourceId = null
    }
    val fullAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.onStorageSettingsReturned() }

    LaunchedEffect(Unit) { viewModel.refreshAccessState() }
    val messageText = message?.resolve()
    LaunchedEffect(message) {
        messageText?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    ScanSourcesScreen(
        state = state,
        onBack = onBack,
        onScopeChange = { scope ->
            if (scope == DocumentScanScope.ALL_DOCUMENTS && !state.hasFullAccessPermission) {
                val intent = storageAccessController.createResolvableRequestIntent(context)
                if (intent == null) {
                    viewModel.onPermissionLaunchFailed()
                } else {
                    runCatching { fullAccessLauncher.launch(intent) }
                        .onFailure { viewModel.onPermissionLaunchFailed() }
                }
            } else {
                viewModel.setScope(scope)
            }
        },
        onAddFolder = { picker.launch(null) },
        onAddFile = { documentPicker.launch(SCAN_DOCUMENT_MIME_TYPES) },
        onRescan = viewModel::rescan,
        onReauthorize = { sourceId ->
            reauthorizeSourceId = sourceId
            reauthorizePicker.launch(null)
        },
        onRemove = { sourceId -> removeSourceId = sourceId },
    )

    removeSourceId?.let { sourceId ->
        val sourceName = state.sources.firstOrNull { it.id == sourceId }?.displayName
            ?: stringResource(R.string.scan_sources_folder_fallback)
        DocumentAssistantDialog(
            title = stringResource(R.string.scan_sources_stop_title, sourceName),
            message = stringResource(R.string.scan_sources_stop_message),
            confirmLabel = stringResource(R.string.scan_sources_stop_confirm),
            onConfirm = {
                removeSourceId = null
                viewModel.remove(sourceId)
            },
            onDismissRequest = { removeSourceId = null },
            dismissLabel = stringResource(R.string.scan_sources_cancel),
            onDismiss = { removeSourceId = null },
        )
    }
}

@Composable
fun ScanSourcesScreen(
    state: ScanSourcesUiState,
    onBack: () -> Unit = {},
    onScopeChange: (DocumentScanScope) -> Unit = {},
    onAddFolder: () -> Unit = {},
    onAddFile: () -> Unit = {},
    onRescan: () -> Unit = {},
    onReauthorize: (Long) -> Unit = {},
    onRemove: (Long) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = DocumentAssistantDimens.PageHorizontal,
            top = DocumentAssistantDimens.PageTop,
            end = DocumentAssistantDimens.PageHorizontal,
            bottom = 26.dp,
        ),
    ) {
        item { DocumentAssistantTopBar(title = stringResource(R.string.scan_sources_title), onBack = onBack) }

        item {
            ScanRangeRow(
                icon = WenxuLineIconType.SCAN,
                title = stringResource(R.string.scan_sources_all_documents),
                detail = if (state.hasFullAccessPermission) {
                    stringResource(R.string.scan_sources_all_description)
                } else {
                    stringResource(R.string.scan_sources_open_permission)
                },
                onClick = { onScopeChange(DocumentScanScope.ALL_DOCUMENTS) },
                trailing = {
                    Switch(
                        checked = state.isFullAccessMode,
                        onCheckedChange = null,
                    )
                },
            )
            HorizontalDivider(color = WenxuLine)
            ScanRangeRow(
                icon = WenxuLineIconType.FOLDER,
                title = stringResource(R.string.scan_sources_selected_folders),
                detail = if (state.sources.isEmpty()) {
                    stringResource(R.string.scan_sources_choose_folder_description)
                } else {
                    state.sources.take(3).joinToString("、") { it.displayName }
                },
                onClick = {
                    if (state.selectedScope != DocumentScanScope.SELECTED_FOLDERS) {
                        onScopeChange(DocumentScanScope.SELECTED_FOLDERS)
                    }
                    onAddFolder()
                },
            )
            HorizontalDivider(color = WenxuLine)
            ScanRangeRow(
                icon = WenxuLineIconType.FILE,
                title = stringResource(R.string.scan_sources_selected_files),
                detail = stringResource(R.string.scan_sources_add_files_description),
                onClick = onAddFile,
            )
        }

        item {
            Text(
                text = stringResource(R.string.scan_sources_authorized_folders),
                color = WenxuMuted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
        }

        if (state.sources.isEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .clickable(role = Role.Button, onClick = onAddFolder),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceIcon(WenxuLineIconType.PLUS)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            stringResource(R.string.scan_sources_add_folder),
                            color = WenxuInk,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.scan_sources_include_subfolders),
                            color = WenxuMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    WenxuLineIcon(WenxuLineIconType.CHEVRON, WenxuMuted, Modifier.size(20.dp))
                }
                HorizontalDivider(color = WenxuLine)
            }
        } else {
            items(state.sources, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    onReauthorize = { onReauthorize(source.id) },
                    onRemove = { onRemove(source.id) },
                )
                HorizontalDivider(color = WenxuLine)
            }
            item {
                TextButton(
                    onClick = onAddFolder,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    WenxuLineIcon(WenxuLineIconType.PLUS, WenxuBrand, Modifier.size(20.dp))
                    Text(stringResource(R.string.scan_sources_add_folder), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(stringResource(R.string.action_rescan), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScanRangeRow(
    icon: WenxuLineIconType,
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(icon)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                text = title,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            WenxuLineIcon(WenxuLineIconType.CHEVRON, WenxuMuted, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SourceIcon(icon: WenxuLineIconType) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(WenxuBrandSoft, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(icon, WenxuBrand, Modifier.size(22.dp))
    }
}

@Composable
private fun SourceRow(
    source: ScanSourceEntity,
    onReauthorize: () -> Unit,
    onRemove: () -> Unit,
) {
    val needsAttention = source.permissionState == SourcePermissionState.NEEDS_ATTENTION
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(WenxuLineIconType.FOLDER)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(
                text = source.displayName,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (needsAttention) {
                    stringResource(R.string.scan_sources_permission_expired)
                } else {
                    source.lastScanAt?.let {
                        stringResource(
                            R.string.scan_sources_subfolders_last_scan,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
                        )
                    } ?: stringResource(R.string.scan_sources_subfolders)
                },
                color = if (needsAttention) WenxuAmber else WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (needsAttention) {
            RetryActionButton(
                label = stringResource(R.string.scan_sources_reauthorize),
                onClick = onReauthorize,
                accentColor = WenxuAmber,
                containerColor = WenxuAmberSoft,
            )
        } else {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.scan_sources_remove), color = WenxuMuted)
            }
        }
    }
}
