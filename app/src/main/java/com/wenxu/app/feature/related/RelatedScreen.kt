package com.wenxu.app.feature.related

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.components.DocumentAssistantEmptyState
import com.wenxu.app.ui.components.DocumentAssistantDialog
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.DocumentOpenErrorDialog
import com.wenxu.app.ui.components.DocumentTypeIcon
import com.wenxu.app.ui.components.SelectionActionBar
import com.wenxu.app.ui.components.SystemTrashConfirmationEffect
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
import com.wenxu.app.ui.util.launchDocument
import com.wenxu.app.ui.util.shareDocument
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

enum class RelatedSection {
    NAME_SIMILAR,
    EXACT_DUPLICATE,
}

@Composable
fun RelatedScreen(
    viewModel: RelatedViewModel,
    onBack: () -> Unit,
    onOpenPdf: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    var trashTargetIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var openErrorTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val actionMessage = state.action?.message?.resolve()
    val undoLabel = stringResource(R.string.organization_action_undo)
    val retryLabel = stringResource(R.string.action_retry)

    SystemTrashConfirmationEffect(
        request = state.pendingConfirmation,
        onResult = viewModel::onSystemTrashResult,
    )

    LaunchedEffect(state.action, actionMessage, undoLabel, retryLabel) {
        state.action?.let { current ->
            val result = snackbarHostState.showSnackbar(
                message = actionMessage.orEmpty(),
                actionLabel = when {
                    current.undoTrashIds.isNotEmpty() -> undoLabel
                    current.retryAnalysisIds.isNotEmpty() -> retryLabel
                    else -> null
                },
            )
            if (result == SnackbarResult.ActionPerformed) {
                when {
                    current.undoTrashIds.isNotEmpty() -> viewModel.undo(current)
                    current.retryAnalysisIds.isNotEmpty() -> {
                        viewModel.retryAnalysis(current.retryAnalysisIds)
                    }
                }
            } else {
                viewModel.clearAction()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        RelatedScreen(
            state = state,
            onBack = onBack,
            onConfirmCurrent = viewModel::confirmCurrent,
            onMarkNotRelated = viewModel::markNotRelated,
            onIgnoreGroup = viewModel::ignoreGroup,
            onReviewLater = viewModel::reviewLater,
            onRetryTarget = { viewModel.retryAnalysis(state.targetDocumentIds) },
            onToggleExact = viewModel::toggleExact,
            onMoveSelectedToTrash = { trashTargetIds = it },
            onShareDocument = { document ->
                if (!shareDocument(context, document)) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.organization_share_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (trashTargetIds.isNotEmpty()) {
        DuplicateCleanupDialog(
            selectedCount = trashTargetIds.size,
            onConfirm = {
                val selectedIds = trashTargetIds
                trashTargetIds = emptySet()
                viewModel.moveSelectedToTrash(selectedIds)
            },
            onDismiss = { trashTargetIds = emptySet() },
        )
    }

    openErrorTarget?.let { document ->
        DocumentOpenErrorDialog(
            document = document,
            onDismiss = { openErrorTarget = null },
        )
    }
}

@Composable
internal fun DuplicateCleanupDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DocumentAssistantDialog(
        title = stringResource(R.string.organization_clean_copies_question),
        message = stringResource(R.string.organization_clean_copies_explanation),
        confirmLabel = stringResource(R.string.organization_action_move_to_trash),
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.organization_action_cancel),
        onDismiss = onDismiss,
        destructive = true,
        supportingContent = {
            Text(
                text = pluralStringResource(
                    R.plurals.organization_selected_exact_documents,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = WenxuInk,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
    )
}

@Composable
fun RelatedScreen(
    state: RelatedUiState,
    initialSection: RelatedSection = RelatedSection.NAME_SIMILAR,
    onBack: () -> Unit = {},
    onConfirmCurrent: (Long, Long) -> Unit = { _, _ -> },
    onMarkNotRelated: (Long, Long, Set<Long>) -> Unit = { _, _, _ -> },
    onIgnoreGroup: (Long, Set<Long>) -> Unit = { _, _ -> },
    onReviewLater: () -> Unit = {},
    onRetryTarget: () -> Unit = {},
    onToggleExact: (Long) -> Unit = {},
    onMoveSelectedToTrash: (Set<Long>) -> Unit = {},
    onShareDocument: (DocumentRecord) -> Unit = {},
    onOpenDocument: (DocumentRecord) -> Unit = {},
) {
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.targetGroupId) {
        if (state.targetGroupId != null) section = RelatedSection.NAME_SIMILAR
    }
    LaunchedEffect(state.targetGroupId, section, state.nameGroups) {
        val targetGroupId = state.targetGroupId ?: return@LaunchedEffect
        if (section != RelatedSection.NAME_SIMILAR) return@LaunchedEffect
        val groupIndex = state.nameGroups.indexOfFirst { it.id == targetGroupId }
        if (groupIndex < 0) return@LaunchedEffect
        val targetItemIndex = 1 + state.nameGroups
            .take(groupIndex)
            .sumOf { group -> 1 + group.documents.size }
        listState.animateScrollToItem(targetItemIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = DocumentAssistantDimens.PageHorizontal,
            top = DocumentAssistantDimens.PageTop,
            end = DocumentAssistantDimens.PageHorizontal,
            bottom = 28.dp,
        ),
    ) {
        item {
            DocumentAssistantTopBar(
                title = stringResource(R.string.organization_review_documents),
                onBack = onBack,
            )
            RelationSectionSelector(
                selected = section,
                nameGroupCount = state.nameGroups.size,
                exactGroupCount = state.exactGroups.size,
                onSelected = { section = it },
            )
        }

        when (section) {
            RelatedSection.NAME_SIMILAR -> {
                if (state.isTargetAnalysisPending) {
                    item(key = "target-analysis-pending") {
                        TargetAnalysisStatus(
                            notFound = false,
                            onBack = onBack,
                            onRetry = onRetryTarget,
                        )
                    }
                }
                if (state.isTargetNotFound) {
                    item(key = "target-analysis-not-found") {
                        TargetAnalysisStatus(
                            notFound = true,
                            onBack = onBack,
                            onRetry = onRetryTarget,
                        )
                    }
                }
                if (state.nameGroups.isEmpty() &&
                    !state.isTargetAnalysisPending &&
                    !state.isTargetNotFound
                ) {
                    item {
                        DocumentAssistantEmptyState(
                            icon = WenxuLineIconType.SIMILAR,
                            title = stringResource(R.string.organization_no_similar_names),
                            message = stringResource(R.string.organization_no_similar_names_detail),
                        )
                    }
                } else {
                    state.nameGroups.forEachIndexed { groupIndex, group ->
                        val referenceDocument = selectVersionReference(group.documents)
                        item(key = "name-header-${group.id}") {
                            val allEvidence = group.evidence.map { evidence -> evidence.text.resolve() }
                            val evidenceText = allEvidence.take(3).joinToString(" · ")
                            val expectedMemberIds = group.documents.mapTo(mutableSetOf()) { it.id }
                            RelationGroupHeader(
                                title = group.baseName.ifBlank {
                                    stringResource(R.string.organization_similar_documents_fallback)
                                },
                                detail = listOfNotNull(
                                    pluralStringResource(
                                    R.plurals.organization_similar_group_detail,
                                    group.documents.size,
                                    group.documents.size,
                                    ),
                                    evidenceText.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                evidence = allEvidence,
                                initiallyExpanded = state.targetGroupId == group.id,
                                onIgnore = { onIgnoreGroup(group.id, expectedMemberIds) },
                                onReviewLater = onReviewLater,
                                modifier = Modifier.padding(top = if (groupIndex == 0) 4.dp else 18.dp),
                            )
                        }
                        group.documents.forEachIndexed { index, document ->
                            item(key = "name-${group.id}-${document.id}") {
                                val expectedMemberIds = group.documents.mapTo(mutableSetOf()) { it.id }
                                NameSimilarDocumentRow(
                                    document = document,
                                    referenceDocument = referenceDocument,
                                    baseName = group.baseName,
                                    isCurrent = group.confirmedDocumentId == document.id,
                                    showDivider = index != group.documents.lastIndex,
                                    onOpen = { onOpenDocument(document) },
                                    onShare = { onShareDocument(document) },
                                    onMarkNotRelated = {
                                        onMarkNotRelated(group.id, document.id, expectedMemberIds)
                                    },
                                    onSetCurrent = {
                                        onConfirmCurrent(group.id, document.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            RelatedSection.EXACT_DUPLICATE -> {
                if (state.exactGroups.isEmpty()) {
                    item {
                        DocumentAssistantEmptyState(
                            icon = WenxuLineIconType.DUPLICATE,
                            title = stringResource(R.string.organization_no_exact_duplicates),
                            message = stringResource(R.string.organization_no_exact_duplicates_detail),
                        )
                    }
                } else {
                    state.exactGroups.forEachIndexed { groupIndex, group ->
                        val selectedIdsInGroup = group.documents
                            .map { it.id }
                            .filterTo(mutableSetOf()) { it in state.selectedExactIds }
                        item(key = "exact-header-${group.id}") {
                            RelationGroupHeader(
                                title = stringResource(
                                    R.string.organization_duplicate_group,
                                    groupIndex + 1,
                                ),
                                detail = pluralStringResource(
                                    R.plurals.organization_exact_group_detail,
                                    group.documents.size,
                                    group.documents.size,
                                ),
                                modifier = Modifier.padding(top = if (groupIndex == 0) 4.dp else 18.dp),
                            )
                        }
                        group.documents.forEachIndexed { index, document ->
                            item(key = "exact-${group.id}-${document.id}") {
                                ExactDuplicateDocumentRow(
                                    document = document,
                                    selected = document.id in selectedIdsInGroup,
                                    showDivider = index != group.documents.lastIndex,
                                    onToggle = { onToggleExact(document.id) },
                                    onOpen = { onOpenDocument(document) },
                                    onShare = { onShareDocument(document) },
                                )
                            }
                        }
                        if (selectedIdsInGroup.isNotEmpty()) {
                            item(key = "exact-actions-${group.id}") {
                                SelectionActionBar(
                                    selectedCount = selectedIdsInGroup.size,
                                    primaryLabel = stringResource(
                                        R.string.organization_action_move_to_trash,
                                    ),
                                    onPrimary = {
                                        onMoveSelectedToTrash(selectedIdsInGroup)
                                    },
                                    destructive = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationSectionSelector(
    selected: RelatedSection,
    nameGroupCount: Int,
    exactGroupCount: Int,
    onSelected: (RelatedSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
            RelationSectionButton(
                title = stringResource(R.string.organization_similar_names),
                detail = pluralStringResource(
                    R.plurals.organization_groups_count,
                    nameGroupCount,
                    nameGroupCount,
                ),
                selected = selected == RelatedSection.NAME_SIMILAR,
                color = WenxuBrand,
                softColor = WenxuBrandSoft,
            onClick = { onSelected(RelatedSection.NAME_SIMILAR) },
            modifier = Modifier.weight(1f),
        )
            RelationSectionButton(
                title = stringResource(R.string.organization_exact_duplicates),
                detail = pluralStringResource(
                    R.plurals.organization_groups_count,
                    exactGroupCount,
                    exactGroupCount,
                ),
                selected = selected == RelatedSection.EXACT_DUPLICATE,
            color = WenxuAmber,
            softColor = WenxuAmberSoft,
            onClick = { onSelected(RelatedSection.EXACT_DUPLICATE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RelationSectionButton(
    title: String,
    detail: String,
    selected: Boolean,
    color: Color,
    softColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .semantics { contentDescription = "$title $detail" },
        shape = RoundedCornerShape(9.dp),
        color = if (selected) softColor else Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) color.copy(alpha = 0.35f) else WenxuLine,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$title $detail",
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) color else WenxuInk,
            )
        }
    }
}

@Composable
private fun TargetAnalysisStatus(
    notFound: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        color = WenxuBrandSoft,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.REFRESH,
                color = WenxuBrand,
                modifier = Modifier.size(DocumentAssistantDimens.Icon),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = stringResource(
                        if (notFound) {
                            R.string.organization_target_documents_not_found
                        } else {
                            R.string.organization_checking_selected_documents
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = WenxuInk,
                )
                Text(
                    text = stringResource(
                        if (notFound) {
                            R.string.organization_target_documents_not_found_detail
                        } else {
                            R.string.organization_checking_selected_documents_detail
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notFound) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.organization_action_back), color = WenxuMuted)
                }
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry), color = WenxuBrand)
            }
        }
    }
}

@Composable
private fun RelationGroupHeader(
    title: String,
    detail: String,
    evidence: List<String> = emptyList(),
    initiallyExpanded: Boolean = false,
    onIgnore: (() -> Unit)? = null,
    onReviewLater: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var evidenceExpanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(DocumentAssistantDimens.TouchTarget),
                contentAlignment = Alignment.Center,
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.FOLDER,
                    color = WenxuInk,
                    modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = WenxuInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onIgnore != null && onReviewLater != null) {
                Box {
                    IconAction(
                        icon = WenxuLineIconType.MORE,
                        description = stringResource(R.string.organization_group_more_actions),
                        onClick = { menuExpanded = true },
                        color = WenxuMuted,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.organization_ignore_group)) },
                            onClick = {
                                menuExpanded = false
                                onIgnore()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.organization_review_later)) },
                            onClick = {
                                menuExpanded = false
                                onReviewLater()
                            },
                        )
                    }
                }
            }
        }
        if (evidence.size > 3) {
            TextButton(
                onClick = { evidenceExpanded = !evidenceExpanded },
                modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
            ) {
                Text(
                    text = stringResource(
                        if (evidenceExpanded) {
                            R.string.organization_hide_evidence
                        } else {
                            R.string.organization_show_evidence
                        },
                    ),
                    color = WenxuBrand,
                )
            }
            if (evidenceExpanded) {
                Text(
                    text = evidence.drop(3).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    modifier = Modifier.padding(start = DocumentAssistantDimens.TouchTarget, bottom = 8.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NameSimilarDocumentRow(
    document: DocumentRecord,
    referenceDocument: DocumentRecord?,
    baseName: String,
    isCurrent: Boolean,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onMarkNotRelated: () -> Unit,
    onSetCurrent: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocumentTypeIcon(extension = document.extension)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = highlightedName(document.displayName, baseName),
                    style = MaterialTheme.typography.titleMedium,
                    color = WenxuInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = versionDifferenceText(document, referenceDocument),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (isCurrent) {
                CountPill(
                    text = stringResource(R.string.organization_current),
                    background = WenxuBrandSoft,
                    color = WenxuBrand,
                )
            } else {
                TextButton(
                    onClick = onSetCurrent,
                    modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
                ) {
                    Text(
                        text = stringResource(R.string.organization_set_current),
                        style = MaterialTheme.typography.labelLarge,
                        color = WenxuBrand,
                    )
                }
            }
            Box {
                IconAction(
                    icon = WenxuLineIconType.MORE,
                    description = stringResource(
                        R.string.organization_more_actions_for,
                        document.displayName,
                    ),
                    onClick = { menuExpanded = true },
                    color = WenxuMuted,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.organization_share_file)) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.organization_not_same_document)) },
                        onClick = {
                            menuExpanded = false
                            onMarkNotRelated()
                        },
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(color = WenxuLine)
        }
    }
}

internal fun selectVersionReference(documents: List<DocumentRecord>): DocumentRecord? =
    documents.maxWithOrNull(
        compareBy<DocumentRecord> { document ->
            document.modifiedAt.takeIf { it > 0L } ?: Long.MIN_VALUE
        }.thenBy { it.sizeBytes }
            .thenBy { it.id },
    )

@Composable
private fun versionDifferenceText(
    document: DocumentRecord,
    referenceDocument: DocumentRecord?,
): String {
    if (referenceDocument == null || document.modifiedAt <= 0L || referenceDocument.modifiedAt <= 0L) {
        return stringResource(
            R.string.organization_version_time_unknown,
            formatSize(document.sizeBytes),
        )
    }

    val sizeDetail = versionSizeDifferenceText(document.sizeBytes, referenceDocument.sizeBytes)
    if (document.id == referenceDocument.id) {
        return stringResource(R.string.organization_version_latest, sizeDetail)
    }

    val differenceMillis = (referenceDocument.modifiedAt - document.modifiedAt).coerceAtLeast(0L)
    if (differenceMillis == 0L) {
        return stringResource(R.string.organization_version_same_time, sizeDetail)
    }

    val minutes = differenceMillis / 60_000L
    val hours = differenceMillis / 3_600_000L
    val days = differenceMillis / 86_400_000L
    return when {
        minutes < 1L -> stringResource(R.string.organization_version_earlier_moments, sizeDetail)
        hours < 1L -> pluralStringResource(
            R.plurals.organization_version_minutes_earlier,
            minutes.toInt(),
            minutes.toInt(),
            sizeDetail,
        )
        days < 1L -> pluralStringResource(
            R.plurals.organization_version_hours_earlier,
            hours.toInt(),
            hours.toInt(),
            sizeDetail,
        )
        else -> pluralStringResource(
            R.plurals.organization_version_days_earlier,
            days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            sizeDetail,
        )
    }
}

@Composable
private fun versionSizeDifferenceText(size: Long, referenceSize: Long): String {
    val difference = size - referenceSize
    return when {
        abs(difference) < 1024L -> formatSize(size)
        difference < 0L -> stringResource(
            R.string.organization_version_size_smaller,
            formatSize(abs(difference)),
        )
        else -> stringResource(
            R.string.organization_version_size_larger,
            formatSize(abs(difference)),
        )
    }
}

@Composable
private fun ExactDuplicateDocumentRow(
    document: DocumentRecord,
    selected: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val selectDescription = stringResource(
        R.string.organization_select_named_document,
        document.displayName,
    )
    val deselectDescription = stringResource(
        R.string.organization_deselect_named_document,
        document.displayName,
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = WenxuAmber,
                    uncheckedColor = WenxuMuted,
                ),
                modifier = Modifier.semantics {
                    contentDescription = if (selected) deselectDescription else selectDescription
                },
            )
            DocumentTypeIcon(extension = document.extension)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = WenxuInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.organization_tap_to_open,
                        documentMetadata(document),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            IconAction(
                icon = WenxuLineIconType.SHARE,
                description = stringResource(
                    R.string.organization_share_named_document,
                    document.displayName,
                ),
                onClick = onShare,
                color = WenxuAmber,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = WenxuLine)
        }
    }
}

@Composable
private fun IconAction(
    icon: WenxuLineIconType,
    description: String,
    onClick: () -> Unit,
    color: Color,
) {
    Box(
        modifier = Modifier
            .size(DocumentAssistantDimens.TouchTarget)
            .semantics { contentDescription = description }
            .clickable(
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(
            type = icon,
            color = color,
            modifier = Modifier.size(DocumentAssistantDimens.Icon),
        )
    }
}

@Composable
private fun CountPill(
    text: String,
    background: Color,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

private fun highlightedName(displayName: String, baseName: String): AnnotatedString {
    val extensionStart = displayName.lastIndexOf('.')
        .takeIf { it > 0 }
        ?: displayName.length
    val stem = displayName.substring(0, extensionStart)
    val extension = displayName.substring(extensionStart)
    val baseStart = stem.indexOf(baseName, ignoreCase = true)
    if (baseName.isBlank() || baseStart < 0) {
        return AnnotatedString(displayName)
    }

    val differenceStyle = SpanStyle(
        color = WenxuBrand,
        background = WenxuBrandSoft,
        fontWeight = FontWeight.SemiBold,
    )
    return buildAnnotatedString {
        if (baseStart > 0) {
            pushStyle(differenceStyle)
            append(stem.substring(0, baseStart))
            pop()
        }
        append(stem.substring(baseStart, baseStart + baseName.length))
        if (baseStart + baseName.length < stem.length) {
            pushStyle(differenceStyle)
            append(stem.substring(baseStart + baseName.length))
            pop()
        }
        append(extension)
    }
}

@Composable
private fun documentMetadata(document: DocumentRecord): String =
    "${sourceLabel(document.parentUri)} · ${formatDate(document.modifiedAt)} · ${formatSize(document.sizeBytes)}"

@Composable
private fun sourceLabel(parentUri: String): String = Uri.parse(parentUri).lastPathSegment
    ?.substringAfterLast('/')
    ?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.organization_authorized_folder)

@Composable
private fun formatDate(timestamp: Long): String = if (timestamp <= 0L) {
    stringResource(R.string.organization_unknown_date)
} else {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / 1024.0 / 1024.0)
}
