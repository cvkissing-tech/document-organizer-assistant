@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wenxu.app.feature.documents

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.ui.components.DocumentAssistantEmptyState
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.DocumentOpenErrorDialog
import com.wenxu.app.ui.components.DocumentRow
import com.wenxu.app.ui.components.DocumentSearchField
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.components.SystemTrashConfirmationEffect
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuDanger
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.util.launchDocument
import com.wenxu.app.ui.util.shareDocument
import java.text.DateFormat
import java.util.Date

@Composable
fun DocumentsScreen(
    viewModel: DocumentsViewModel,
    onOpenTrash: () -> Unit,
    onOpenPdf: (Long) -> Unit,
    onOpenRelated: (Set<Long>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var showFilters by remember { mutableStateOf(false) }
    var showBatchCategorySheet by remember { mutableStateOf(false) }
    var moreTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var categoryTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var trashTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var locationTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var moveTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var openErrorTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val actionMessage = action?.message?.resolve()
    val undoLabel = stringResource(R.string.organization_action_undo)
    val retryLabel = stringResource(R.string.action_retry)
    val moveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        moveTarget?.let { viewModel.moveToFolder(it.id, uri?.toString()) }
        moveTarget = null
    }

    SystemTrashConfirmationEffect(
        request = state.pendingConfirmation,
        onResult = viewModel::onSystemTrashResult,
    )

    LaunchedEffect(action, actionMessage, undoLabel, retryLabel) {
        action?.let { current ->
            if (current.targetRelatedIds.isNotEmpty()) {
                viewModel.clearAction()
                onOpenRelated(current.targetRelatedIds)
                return@let
            }
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
        DocumentsScreen(
            state = state,
            onQueryChange = viewModel::setQuery,
            onFormatChange = viewModel::setFormat,
            onOrganizationChange = viewModel::setOrganization,
            onShowFilters = { showFilters = true },
            onEnterSelection = viewModel::startSelection,
            onCancelSelection = viewModel::cancelSelection,
            onToggleSelection = viewModel::toggleSelection,
            onBatchClassify = { showBatchCategorySheet = true },
            onBatchMerge = viewModel::mergeSelectedAsVersions,
            onOpenTrash = onOpenTrash,
            onOpen = { document ->
                if (document.extension.equals("pdf", ignoreCase = true)) {
                    viewModel.markOpened(document.id)
                    onOpenPdf(document.id)
                } else if (launchDocument(context, document)) {
                    viewModel.markOpened(document.id)
                } else {
                    openErrorTarget = document
                }
            },
            onMore = { moreTarget = it },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (showFilters) {
        FilterSheet(
            format = state.format,
            organization = state.organization,
            categories = state.categories,
            selectedCategoryId = state.selectedCategoryId,
            sort = state.sort,
            visibleCount = state.documents.size,
            onFormatChange = viewModel::setFormat,
            onOrganizationChange = viewModel::setOrganization,
            onCategoryChange = viewModel::setCategoryFilter,
            onSortChange = viewModel::setSort,
            onDismiss = { showFilters = false },
        )
    }

    if (showBatchCategorySheet) {
        BatchCategorySheet(
            selectedCount = state.selectedDocumentIds.size,
            categories = state.categories,
            onAdd = { categoryId ->
                showBatchCategorySheet = false
                viewModel.addSelectedToCategory(categoryId)
            },
            onDismiss = { showBatchCategorySheet = false },
        )
    }

    moreTarget?.let { document ->
        DocumentActionsSheet(
            document = document,
            onDismiss = { moreTarget = null },
            onAddToCategory = {
                moreTarget = null
                categoryTarget = document
            },
            onShare = {
                moreTarget = null
                if (!shareDocument(context, document)) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.organization_share_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onMove = {
                moreTarget = null
                moveTarget = document
                moveFolderPicker.launch(null)
            },
            onShowLocation = {
                moreTarget = null
                locationTarget = document
            },
            onTrash = {
                moreTarget = null
                trashTarget = document
            },
        )
    }

    categoryTarget?.let { document ->
        CategorySheet(
            categories = state.categories,
            onAdd = { categoryId ->
                viewModel.addToCategory(document.id, categoryId)
                categoryTarget = null
            },
            onDismiss = { categoryTarget = null },
        )
    }

    locationTarget?.let { document ->
        AlertDialog(
            onDismissRequest = { locationTarget = null },
            title = { Text(stringResource(R.string.organization_file_location)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(document.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(
                            R.string.organization_source_value,
                            sourceLabel(document.parentUri),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = document.parentUri,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WenxuMuted,
                    )
                    Text(
                        text = stringResource(R.string.organization_location_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WenxuMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { locationTarget = null }) {
                    Text(stringResource(R.string.action_got_it))
                }
            },
        )
    }

    trashTarget?.let { document ->
        AlertDialog(
            onDismissRequest = { trashTarget = null },
            title = { Text(stringResource(R.string.organization_move_to_trash_question)) },
            text = {
                Column {
                    Text(document.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.organization_move_to_trash_explanation),
                        color = WenxuMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        trashTarget = null
                        viewModel.moveToTrash(document.id)
                    },
                ) {
                    Text(stringResource(R.string.organization_action_move_to_trash), color = WenxuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { trashTarget = null }) {
                    Text(stringResource(R.string.organization_action_cancel))
                }
            },
        )
    }

    openErrorTarget?.let { document ->
        DocumentOpenErrorDialog(document = document, onDismiss = { openErrorTarget = null })
    }
}

@Composable
fun DocumentsScreen(
    state: DocumentsUiState,
    onQueryChange: (String) -> Unit = {},
    onFormatChange: (FormatFilter) -> Unit = {},
    onOrganizationChange: (OrganizationFilter) -> Unit = {},
    onShowFilters: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onBatchClassify: () -> Unit = {},
    onBatchMerge: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpen: (DocumentRecord) -> Unit = {},
    onMore: (DocumentRecord) -> Unit = {},
) {
    var showFormatPicker by remember { mutableStateOf(false) }
    var showBatchMoreSheet by remember { mutableStateOf(false) }
    val filterAndSortDescription = stringResource(R.string.organization_filter_and_sort)
    val trashDescription = stringResource(R.string.organization_trash)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.padding(
                    start = DocumentAssistantDimens.PageHorizontal,
                    top = DocumentAssistantDimens.PageTop,
                    end = DocumentAssistantDimens.PageHorizontal,
                ),
            ) {
                DocumentAssistantTopBar(
                    title = stringResource(R.string.organization_all_documents),
                    actionLabel = if (state.selectionMode) {
                        stringResource(R.string.organization_action_cancel)
                    } else {
                        stringResource(R.string.organization_action_multi_select)
                    },
                    actionDescription = if (state.selectionMode) {
                        stringResource(R.string.organization_action_exit_multi_select)
                    } else {
                        stringResource(R.string.organization_action_enter_multi_select)
                    },
                    onAction = if (state.selectionMode) onCancelSelection else onEnterSelection,
                )
            }

            DocumentSearchField(
                value = state.query,
                onValueChange = onQueryChange,
                enabled = !state.selectionMode,
                placeholder = stringResource(R.string.organization_search_file_name),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DocumentAssistantDimens.PageHorizontal,
                        top = 3.dp,
                        end = DocumentAssistantDimens.PageHorizontal,
                        bottom = 10.dp,
                    ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OrganizationFilter.entries.forEach { filter ->
                    DocumentFilterChip(
                        label = stringResource(filter.labelRes),
                        selected = state.organization == filter,
                        enabled = !state.selectionMode,
                        onClick = { onOrganizationChange(filter) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DocumentAssistantDimens.PageHorizontal, top = 7.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.selectionMode) {
                        pluralStringResource(
                            R.plurals.organization_selected_short,
                            state.selectedDocumentIds.size,
                            state.selectedDocumentIds.size,
                        )
                    } else {
                        val selectedCategory = state.categories
                            .firstOrNull { it.id == state.selectedCategoryId }
                            ?.name
                        listOfNotNull(
                            pluralStringResource(
                                R.plurals.organization_documents_count,
                                state.documents.size,
                                state.documents.size,
                            ),
                            selectedCategory,
                            stringResource(
                                R.string.organization_sorted_by,
                                stringResource(state.sort.labelRes),
                            ),
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = WenxuMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!state.selectionMode) {
                    TextButton(
                        onClick = { showFormatPicker = true },
                        modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.organization_type_value,
                                stringResource(state.format.labelRes),
                            ),
                            color = WenxuBrand,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (!state.selectionMode) {
                    Box(
                        modifier = Modifier
                            .size(DocumentAssistantDimens.TouchTarget)
                            .semantics { contentDescription = filterAndSortDescription }
                            .clickable(role = Role.Button, onClick = onShowFilters),
                        contentAlignment = Alignment.Center,
                    ) {
                        WenxuLineIcon(
                            type = WenxuLineIconType.FILTER,
                            color = WenxuBrand,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(DocumentAssistantDimens.TouchTarget)
                            .semantics { contentDescription = trashDescription }
                            .clickable(role = Role.Button, onClick = onOpenTrash),
                        contentAlignment = Alignment.Center,
                    ) {
                        WenxuLineIcon(
                            type = WenxuLineIconType.TRASH,
                            color = WenxuMuted,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = DocumentAssistantDimens.PageHorizontal),
                color = WenxuLine,
            )

            if (state.documents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    DocumentAssistantEmptyState(
                        icon = if (state.totalCount == 0) WenxuLineIconType.FILE else WenxuLineIconType.SEARCH,
                        title = if (state.totalCount == 0) {
                            stringResource(R.string.organization_no_documents)
                        } else {
                            stringResource(R.string.organization_no_matching_documents)
                        },
                        message = if (state.totalCount == 0) {
                            stringResource(R.string.organization_no_documents_detail)
                        } else {
                            stringResource(R.string.organization_no_matching_documents_detail)
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = DocumentAssistantDimens.PageHorizontal,
                        end = DocumentAssistantDimens.PageHorizontal,
                        bottom = if (state.selectionMode) 112.dp else 28.dp,
                    ),
                ) {
                    itemsIndexed(
                        items = state.documents,
                        key = { _, document -> document.id },
                    ) { index, document ->
                        Column {
                            DocumentRow(
                                document = document,
                                isFirst = index == 0,
                                isLast = index == state.documents.lastIndex,
                                metadata = documentMetadata(document),
                                onClick = if (state.selectionMode) {
                                    { onToggleSelection(document.id) }
                                } else {
                                    { onOpen(document) }
                                },
                                onMore = if (state.selectionMode) null else ({ onMore(document) }),
                                moreDescription = stringResource(
                                    R.string.organization_more_actions_for,
                                    document.displayName,
                                ),
                                selectionMode = state.selectionMode,
                                selected = document.id in state.selectedDocumentIds,
                                onSelectionChange = { onToggleSelection(document.id) },
                                tags = state.categoryNamesByDocument[document.id].orEmpty(),
                            )
                            if (index != state.documents.lastIndex) {
                                HorizontalDivider(color = WenxuLine)
                            }
                        }
                    }
                }
            }
        }

        if (state.selectionMode) {
            BatchCategoryActionBar(
                selectedCount = state.selectedDocumentIds.size,
                onClassify = onBatchClassify,
                onMore = { showBatchMoreSheet = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showFormatPicker) {
        FormatPickerSheet(
            selected = state.format,
            onSelect = { format ->
                onFormatChange(format)
                showFormatPicker = false
            },
            onDismiss = { showFormatPicker = false },
        )
    }
    if (showBatchMoreSheet) {
        BatchMoreSheet(
            onMergeVersions = {
                showBatchMoreSheet = false
                onBatchMerge()
            },
            onDismiss = { showBatchMoreSheet = false },
        )
    }
}

@Composable
private fun FormatPickerSheet(
    selected: FormatFilter,
    onSelect: (FormatFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
        ) {
            Text(
                stringResource(R.string.organization_choose_file_type),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(8.dp))
            FilterChipRow {
                FormatFilter.entries.forEach { format ->
                    DocumentFilterChip(
                        label = stringResource(format.labelRes),
                        selected = selected == format,
                        onClick = { onSelect(format) },
                    )
                }
            }
            Spacer(Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DocumentFilterChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.height(36.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = WenxuBrandSoft,
            selectedLabelColor = WenxuBrand,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = WenxuLine,
            selectedBorderColor = WenxuBrand.copy(alpha = 0.35f),
        ),
    )
}

@Composable
private fun FilterSheet(
    format: FormatFilter,
    organization: OrganizationFilter,
    categories: List<CategorySummary>,
    selectedCategoryId: Long?,
    sort: DocumentSort,
    visibleCount: Int,
    onFormatChange: (FormatFilter) -> Unit,
    onOrganizationChange: (OrganizationFilter) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onSortChange: (DocumentSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
        ) {
            Text(
                stringResource(R.string.organization_filter_and_sort),
                style = MaterialTheme.typography.titleLarge,
            )
            FilterSectionTitle(stringResource(R.string.organization_file_format))
            FilterChipRow {
                FormatFilter.entries.forEach { item ->
                    DocumentFilterChip(
                        label = stringResource(item.labelRes),
                        selected = format == item,
                        onClick = { onFormatChange(item) },
                    )
                }
            }
            FilterSectionTitle(stringResource(R.string.organization_status))
            FilterChipRow {
                OrganizationFilter.entries.forEach { item ->
                    DocumentFilterChip(
                        label = stringResource(item.labelRes),
                        selected = organization == item,
                        onClick = { onOrganizationChange(item) },
                    )
                }
            }
            FilterSectionTitle(stringResource(R.string.organization_category_filter))
            FilterChipRow {
                DocumentFilterChip(
                    label = stringResource(R.string.organization_no_category_limit),
                    selected = selectedCategoryId == null,
                    onClick = { onCategoryChange(null) },
                )
                categories.forEach { category ->
                    DocumentFilterChip(
                        label = category.name,
                        selected = selectedCategoryId == category.id,
                        onClick = { onCategoryChange(category.id) },
                    )
                }
            }
            FilterSectionTitle(stringResource(R.string.organization_sort))
            FilterChipRow {
                DocumentSort.entries.forEach { item ->
                    DocumentFilterChip(
                        label = stringResource(item.labelRes),
                        selected = sort == item,
                        onClick = { onSortChange(item) },
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 14.dp)
                    .height(42.dp),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.organization_view_documents,
                        visibleCount,
                        visibleCount,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 15.dp, bottom = 8.dp),
    )
}

@Composable
private fun FilterChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun DocumentActionsSheet(
    document: DocumentRecord,
    onDismiss: () -> Unit,
    onAddToCategory: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onShowLocation: () -> Unit,
    onTrash: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 4.dp),
        ) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            DocumentActionRow(
                icon = WenxuLineIconType.TAG,
                title = stringResource(R.string.organization_action_add_to_category),
                onClick = onAddToCategory,
            )
            DocumentActionRow(
                icon = WenxuLineIconType.SHARE,
                title = stringResource(R.string.organization_share_file),
                onClick = onShare,
            )
            DocumentActionRow(
                icon = WenxuLineIconType.FOLDER,
                title = stringResource(R.string.organization_move_to_folder),
                detail = stringResource(R.string.organization_move_to_folder_detail),
                onClick = onMove,
            )
            DocumentActionRow(
                icon = WenxuLineIconType.LOCATION,
                title = stringResource(R.string.organization_view_file_location),
                detail = sourceLabel(document.parentUri),
                onClick = onShowLocation,
            )
            HorizontalDivider(color = WenxuLine, modifier = Modifier.padding(vertical = 4.dp))
            DocumentActionRow(
                icon = WenxuLineIconType.TRASH,
                title = stringResource(R.string.organization_action_move_to_trash),
                detail = stringResource(R.string.organization_recover_within_30_days),
                color = WenxuDanger,
                onClick = onTrash,
            )
            Spacer(Modifier.size(10.dp))
        }
    }
}

@Composable
private fun CategorySheet(
    categories: List<CategorySummary>,
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.organization_action_add_to_category),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.organization_original_file_not_moved),
                style = MaterialTheme.typography.bodyMedium,
                color = WenxuMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 9.dp),
            )
            if (categories.isEmpty()) {
                DocumentAssistantEmptyState(
                    icon = WenxuLineIconType.FOLDER,
                    title = stringResource(R.string.organization_no_categories),
                    message = stringResource(R.string.organization_create_category_first),
                )
            } else {
                categories.forEachIndexed { index, category ->
                    DocumentActionRow(
                        icon = WenxuLineIconType.FOLDER,
                        title = category.name,
                        onClick = { onAdd(category.id) },
                    )
                    if (index != categories.lastIndex) {
                        HorizontalDivider(color = WenxuLine)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun BatchCategorySheet(
    selectedCount: Int,
    categories: List<CategorySummary>,
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.organization_action_add_to_category),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.organization_selected_not_moved,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WenxuMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 9.dp),
            )
            if (categories.isEmpty()) {
                DocumentAssistantEmptyState(
                    icon = WenxuLineIconType.FOLDER,
                    title = stringResource(R.string.organization_no_categories),
                    message = stringResource(R.string.organization_create_category_first),
                )
            } else {
                categories.forEachIndexed { index, category ->
                    DocumentActionRow(
                        icon = WenxuLineIconType.FOLDER,
                        title = category.name,
                        onClick = { onAdd(category.id) },
                    )
                    if (index != categories.lastIndex) {
                        HorizontalDivider(color = WenxuLine)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun BatchCategoryActionBar(
    selectedCount: Int,
    onClassify: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.documents_selected_count,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = WenxuInk,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onClassify,
                enabled = selectedCount > 0,
                modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.TAG,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.organization_action_add_to_category),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            TextButton(
                onClick = onMore,
                enabled = selectedCount > 0,
                modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.MORE,
                    color = WenxuBrand,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.organization_batch_more),
                    modifier = Modifier.padding(start = 5.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BatchMoreSheet(
    onMergeVersions: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.organization_batch_more_actions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            DocumentActionRow(
                icon = WenxuLineIconType.SIMILAR,
                title = stringResource(R.string.organization_merge_as_versions),
                onClick = onMergeVersions,
            )
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun DocumentActionRow(
    icon: WenxuLineIconType,
    title: String,
    detail: String? = null,
    color: androidx.compose.ui.graphics.Color = WenxuInk,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(DocumentAssistantDimens.TouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = icon,
                color = if (color == WenxuDanger) WenxuDanger else WenxuBrand,
                modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (color == WenxuDanger) WenxuDanger else WenxuMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        WenxuLineIcon(
            type = WenxuLineIconType.CHEVRON,
            color = WenxuMuted,
            modifier = Modifier.size(17.dp),
        )
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
