package com.wenxu.app.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.components.DocumentOpenErrorDialog
import com.wenxu.app.ui.components.DocumentAppMark
import com.wenxu.app.ui.components.DocumentRow
import com.wenxu.app.ui.components.RetryActionButton
import com.wenxu.app.ui.components.SectionHeader
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuAmber
import com.wenxu.app.ui.theme.WenxuAmberSoft
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.util.launchDocument

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenRelated: () -> Unit,
    onOpenUnclassified: () -> Unit,
    onOpenAllDocuments: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPdf: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openErrorTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderSelected(uri?.toString())
    }
    HomeScreen(
        state = state,
        onScan = viewModel::rescan,
        onPauseScan = viewModel::pauseScan,
        onResumeScan = viewModel::resumeScan,
        onCancelScan = viewModel::cancelScan,
        onRetryScan = viewModel::retryScan,
        onChooseFolder = { picker.launch(null) },
        onOpenRelated = onOpenRelated,
        onOpenUnclassified = onOpenUnclassified,
        onOpenAllDocuments = onOpenAllDocuments,
        onOpenSettings = onOpenSettings,
        onOpenDocument = { document ->
            if (document.extension.equals("pdf", ignoreCase = true)) {
                viewModel.markOpened(document.id)
                onOpenPdf(document.id)
            } else if (launchDocument(context, document)) {
                viewModel.markOpened(document.id)
            } else {
                openErrorTarget = document
            }
        },
    )
    openErrorTarget?.let { document ->
        DocumentOpenErrorDialog(document = document, onDismiss = { openErrorTarget = null })
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onScan: () -> Unit = {},
    onPauseScan: () -> Unit = {},
    onResumeScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onChooseFolder: () -> Unit = {},
    onOpenRelated: () -> Unit = {},
    onOpenUnclassified: () -> Unit = {},
    onOpenAllDocuments: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDocument: (DocumentRecord) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var previousScanStatus by remember { mutableStateOf<ScanSessionStatus?>(null) }
    val scanCompletedMessage = pluralStringResource(
        R.plurals.home_scan_completed_snackbar,
        state.scan.discoveredDocuments,
        state.scan.discoveredDocuments,
    )

    LaunchedEffect(state.scan.status) {
        if (
            previousScanStatus in setOf(ScanSessionStatus.QUEUED, ScanSessionStatus.RUNNING) &&
            state.scan.status == ScanSessionStatus.COMPLETED
        ) {
            snackbarHostState.showSnackbar(scanCompletedMessage)
        }
        previousScanStatus = state.scan.status
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!state.hasAuthorizedSources) {
            MissingSource(onChooseFolder)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DocumentAssistantDimens.PageHorizontal,
                    top = DocumentAssistantDimens.PageTop,
                    end = DocumentAssistantDimens.PageHorizontal,
                    bottom = 28.dp,
                ),
            ) {
                item { HomeHeader(onOpenSettings) }
                item {
                    ScanStatusStrip(
                        scan = state.scan,
                        relationAnalysis = state.relationAnalysis,
                        summary = state.relatedSummary,
                        unclassifiedCount = state.unclassifiedCount,
                        onScan = onScan,
                        onPause = onPauseScan,
                        onResume = onResumeScan,
                        onCancel = onCancelScan,
                        onRetry = onRetryScan,
                        onOpenRelated = onOpenRelated,
                        onOpenUnclassified = onOpenUnclassified,
                    )
                }
                item {
                    ConfirmDocumentsWorkbench(
                        summary = state.relatedSummary,
                        onOpen = onOpenRelated,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                item {
                    UnclassifiedEntry(
                        count = state.unclassifiedCount,
                        onOpen = onOpenUnclassified,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_recent_opened),
                        actionLabel = stringResource(R.string.action_view_all),
                        onAction = onOpenAllDocuments,
                    )
                }

                if (state.recentDocuments.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.home_recent_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WenxuMuted,
                            modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                        )
                    }
                } else {
                    val recentDocuments = state.recentDocuments.take(4)
                    itemsIndexed(
                        items = recentDocuments,
                        key = { _, document -> document.id },
                    ) { index, document ->
                        DocumentRow(
                            document = document,
                            isFirst = index == 0,
                            isLast = index == recentDocuments.lastIndex,
                            metadata = documentMetadata(document),
                            onClick = { onOpenDocument(document) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 22.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val settingsDescription = stringResource(R.string.action_settings)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DocumentAppMark()
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(DocumentAssistantDimens.TouchTarget)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onOpenSettings)
                .semantics { contentDescription = settingsDescription },
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.SETTINGS,
                color = WenxuMuted,
                modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
            )
        }
    }
}

@Composable
private fun ScanStatusStrip(
    scan: ScanProgressUiState,
    relationAnalysis: RelationAnalysisUiState,
    summary: RelatedSummary,
    unclassifiedCount: Int,
    onScan: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenRelated: () -> Unit,
    onOpenUnclassified: () -> Unit,
) {
    if (!scan.visible) return

    AnimatedVisibility(
        visible = scan.status == ScanSessionStatus.COMPLETED,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        ScanCompletionReceipt(
            discoveredDocuments = scan.discoveredDocuments,
            reviewGroups = summary.totalGroups,
            unclassifiedCount = unclassifiedCount,
            failedFiles = scan.failedFiles,
            relationAnalysis = relationAnalysis,
            onRescan = onScan,
            onOpenRelated = onOpenRelated,
            onOpenUnclassified = onOpenUnclassified,
        )
    }
    if (scan.status == ScanSessionStatus.COMPLETED) return

    val primaryAction = when (scan.primaryAction) {
        ScanProgressAction.START -> ScanAction(
            stringResource(R.string.action_start_scan),
            WenxuLineIconType.SCAN,
            onScan,
        )
        ScanProgressAction.PAUSE -> ScanAction(
            stringResource(R.string.action_pause),
            WenxuLineIconType.PAUSE,
            onPause,
        )
        ScanProgressAction.RESUME -> ScanAction(
            stringResource(R.string.action_continue),
            WenxuLineIconType.PLAY,
            onResume,
        )
        ScanProgressAction.RETRY -> ScanAction(
            stringResource(R.string.action_retry),
            WenxuLineIconType.REFRESH,
            onRetry,
            isRetry = true,
        )
        ScanProgressAction.RESTART -> ScanAction(
            stringResource(R.string.action_rescan),
            WenxuLineIconType.REFRESH,
            onScan,
            isRetry = true,
        )
        ScanProgressAction.NONE -> null
    }
    val statusIcon = when (scan.status) {
        ScanSessionStatus.QUEUED, ScanSessionStatus.RUNNING -> WenxuLineIconType.SCAN
        ScanSessionStatus.PAUSED -> WenxuLineIconType.PAUSE
        ScanSessionStatus.CANCELLED -> WenxuLineIconType.STOP
        ScanSessionStatus.FAILED -> WenxuLineIconType.CLOSE
        ScanSessionStatus.COMPLETED -> WenxuLineIconType.CHECK
        null -> WenxuLineIconType.REFRESH
    }
    val statusColor = if (scan.status == ScanSessionStatus.FAILED) WenxuDanger else WenxuBrand

    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (scan.status == ScanSessionStatus.FAILED) MaterialTheme.colorScheme.errorContainer else WenxuBrandSoft)
                .padding(horizontal = 12.dp, vertical = if (scan.isCompactCompletion) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(WenxuBrandSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                WenxuLineIcon(
                    type = statusIcon,
                    color = statusColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp),
            ) {
                Text(
                    text = scan.title.resolve(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = scan.detail.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = if (scan.isCompactCompletion) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                RelationAnalysisLine(
                    state = relationAnalysis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (scan.status == ScanSessionStatus.FAILED && scan.errorMessage != null) {
                    scan.errorMessage.let { message ->
                        Text(
                            text = message.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WenxuDanger,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                } else if (scan.failedFiles > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_scan_unread_files,
                            scan.failedFiles,
                            scan.failedFiles,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WenxuAmber,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (primaryAction != null || scan.canCancel) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryAction?.let { action ->
                        if (action.isRetry) {
                            RetryActionButton(
                                label = action.label,
                                onClick = action.onClick,
                            )
                        } else {
                            ScanTextAction(action)
                        }
                    }
                    if (scan.canCancel) {
                        ScanIconAction(
                            action = ScanAction(
                                stringResource(R.string.action_stop_scan),
                                WenxuLineIconType.STOP,
                                onCancel,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanCompletionReceipt(
    discoveredDocuments: Int,
    reviewGroups: Int,
    unclassifiedCount: Int,
    failedFiles: Int,
    relationAnalysis: RelationAnalysisUiState,
    onRescan: () -> Unit,
    onOpenRelated: () -> Unit,
    onOpenUnclassified: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = WenxuBrandSoft,
        border = BorderStroke(1.dp, WenxuBrand.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(WenxuBrand, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    WenxuLineIcon(
                        type = WenxuLineIconType.CHECK,
                        color = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (relationAnalysis.visible) {
                                R.string.home_receipt_documents_ready
                            } else {
                                R.string.home_receipt_title
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = WenxuInk,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_scan_documents_found,
                            discoveredDocuments,
                            discoveredDocuments,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WenxuMuted,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                ScanTextAction(
                    action = ScanAction(
                        label = stringResource(R.string.action_rescan),
                        icon = WenxuLineIconType.REFRESH,
                        onClick = onRescan,
                    ),
                )
            }

            RelationAnalysisLine(
                state = relationAnalysis,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ReceiptStep(
                    icon = WenxuLineIconType.FILE_CHECK,
                    label = stringResource(R.string.home_receipt_discovered),
                    count = discoveredDocuments,
                    modifier = Modifier.weight(1f),
                )
                ReceiptConnector()
                ReceiptStep(
                    icon = WenxuLineIconType.SIMILAR,
                    label = stringResource(R.string.home_receipt_review),
                    count = reviewGroups,
                    onClick = onOpenRelated,
                    modifier = Modifier.weight(1f),
                )
                ReceiptConnector()
                ReceiptStep(
                    icon = WenxuLineIconType.TAG,
                    label = stringResource(R.string.home_receipt_unclassified),
                    count = unclassifiedCount,
                    onClick = onOpenUnclassified,
                    modifier = Modifier.weight(1f),
                )
            }

            if (failedFiles > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.home_scan_unread_files,
                        failedFiles,
                        failedFiles,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = WenxuAmber,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun RelationAnalysisLine(
    state: RelationAnalysisUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val phase = state.phase.resolve()
    val description = stringResource(
        R.string.home_relation_status_description,
        phase,
        state.checked,
        state.candidates,
        state.failures,
    )
    Row(
        modifier = modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WenxuLineIcon(
            type = WenxuLineIconType.REFRESH,
            color = if (state.isFailure) WenxuAmber else WenxuBrand,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = phase,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.isFailure) WenxuAmber else WenxuBrand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ReceiptStep(
    icon: WenxuLineIconType,
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val description = stringResource(R.string.home_receipt_step_description, label, count)
    val interaction = if (onClick == null) {
        Modifier.semantics { contentDescription = description }
    } else {
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    }
    Column(
        modifier = modifier
            .then(interaction)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = icon,
                color = WenxuBrand,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = WenxuInk,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = WenxuMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReceiptConnector() {
    Box(
        modifier = Modifier
            .padding(top = 18.dp)
            .size(width = 18.dp, height = 1.dp)
            .background(WenxuBrand.copy(alpha = 0.28f)),
    )
}

private data class ScanAction(
    val label: String,
    val icon: WenxuLineIconType,
    val onClick: () -> Unit,
    val isRetry: Boolean = false,
)

@Composable
private fun ScanTextAction(
    action: ScanAction,
    color: Color = WenxuBrand,
) {
    TextButton(
        onClick = action.onClick,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
    ) {
        WenxuLineIcon(
            type = action.icon,
            color = color,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = action.label,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ScanIconAction(action: ScanAction) {
    Box(
        modifier = Modifier
            .size(DocumentAssistantDimens.TouchTarget)
            .clip(RoundedCornerShape(DocumentAssistantDimens.ButtonCorner))
            .semantics { contentDescription = action.label }
            .clickable(role = Role.Button, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(
            type = action.icon,
            color = WenxuMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ConfirmDocumentsWorkbench(
    summary: RelatedSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRelations = summary.totalGroups > 0

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DocumentAssistantDimens.WorkbenchCorner),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, WenxuLine),
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.FILE_CHECK,
                    color = WenxuBrand,
                    modifier = Modifier.size(width = 34.dp, height = 40.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_confirm_documents),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = stringResource(R.string.home_confirm_documents_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WenxuMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.document_groups_count,
                        summary.totalGroups,
                        summary.totalGroups,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = WenxuBrand,
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, WenxuLine),
            ) {
                Column {
                    RelationSummaryItem(
                        label = stringResource(R.string.home_name_similar),
                        count = pluralStringResource(
                            R.plurals.document_groups_count,
                            summary.nameSimilarGroups,
                            summary.nameSimilarGroups,
                        ),
                        icon = WenxuLineIconType.SIMILAR,
                        background = WenxuBrandSoft,
                        color = WenxuBrand,
                        onOpen = onOpen,
                    )
                    HorizontalDivider(color = WenxuLine)
                    RelationSummaryItem(
                        label = stringResource(R.string.home_exact_duplicate),
                        count = pluralStringResource(
                            R.plurals.document_groups_count,
                            summary.exactDuplicateSets,
                            summary.exactDuplicateSets,
                        ),
                        icon = WenxuLineIconType.DUPLICATE,
                        background = WenxuAmberSoft,
                        color = WenxuAmber,
                        onOpen = onOpen,
                    )
                }
            }
            if (!hasRelations) {
                Text(
                    text = stringResource(R.string.home_no_pending_review),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = DocumentAssistantDimens.TouchTarget),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(
                    text = stringResource(
                        if (hasRelations) R.string.action_start_review else R.string.action_view,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun RelationSummaryItem(
    label: String,
    count: String,
    icon: WenxuLineIconType,
    background: Color,
    color: Color,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DocumentAssistantDimens.TouchTarget)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = icon,
                color = color,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = WenxuInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 7.dp),
        )
        Text(
            text = count,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun UnclassifiedEntry(
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (count == 0) {
        stringResource(R.string.home_all_classified)
    } else {
        pluralStringResource(R.plurals.home_unclassified_count, count, count)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(45.dp)
                .clickable(enabled = count > 0, role = Role.Button, onClick = onOpen)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WenxuLineIcon(
                type = if (count == 0) WenxuLineIconType.CHECK else WenxuLineIconType.FOLDER,
                color = if (count == 0) WenxuBrand else WenxuMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 9.dp),
            )
            if (count > 0) {
                Text(
                    text = stringResource(R.string.action_organize),
                    style = MaterialTheme.typography.labelLarge,
                    color = WenxuBrand,
                )
                WenxuLineIcon(
                    type = WenxuLineIconType.CHEVRON,
                    color = WenxuBrand,
                    modifier = Modifier
                        .padding(start = 3.dp, end = 4.dp)
                        .size(17.dp),
                )
            }
        }
        HorizontalDivider(color = WenxuLine)
    }
}

@Composable
private fun documentMetadata(document: DocumentRecord): String {
    val type = document.extension.uppercase().ifBlank {
        stringResource(R.string.home_generic_document_type)
    }
    return "$type · ${formatFileSize(document.sizeBytes)}"
}

private fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024f * 1024f))
    sizeBytes >= 1024L -> "%.0f KB".format(sizeBytes / 1024f)
    else -> "$sizeBytes B"
}

@Composable
private fun MissingSource(onChooseFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(30.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.home_scan_source_removed),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            stringResource(R.string.home_scan_source_removed_detail),
            color = WenxuMuted,
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
        )
        Button(
            onClick = onChooseFolder,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
        ) { Text(stringResource(R.string.action_choose_folder)) }
    }
}
