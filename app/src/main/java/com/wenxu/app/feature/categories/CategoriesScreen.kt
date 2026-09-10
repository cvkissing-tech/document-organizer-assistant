package com.wenxu.app.feature.categories

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.wenxu.app.ui.components.DocumentTypeIcon
import com.wenxu.app.ui.components.SectionHeader
import com.wenxu.app.ui.components.SelectionActionBar
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
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
fun CategoriesScreen(
    viewModel: CategoriesViewModel,
    onOpenCategory: (Long?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    CategoriesScreen(
        state = state,
        onCreate = {
            createError = null
            showCreate = true
        },
        onOpenCategory = onOpenCategory,
    )
    if (showCreate) {
        CategoryNameDialog(
            title = stringResource(R.string.organization_create_category),
            initialValue = "",
            confirmLabel = stringResource(R.string.organization_action_create),
            errorMessage = createError,
            onDismiss = {
                createError = null
                showCreate = false
            },
            onConfirm = {
                viewModel.create(it) { result ->
                    result.fold(
                        onSuccess = {
                            createError = null
                            showCreate = false
                        },
                        onFailure = { error ->
                            createError = localizedCategoryError(
                                context,
                                error,
                                R.string.organization_create_category_failed,
                            )
                        },
                    )
                }
            },
        )
    }
}

@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onCreate: () -> Unit = {},
    onOpenCategory: (Long?) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
    ) {
        DocumentAssistantTopBar(
            title = stringResource(R.string.organization_categories),
        )

        SectionHeader(
            title = stringResource(R.string.organization_my_categories),
            detail = pluralStringResource(
                R.plurals.organization_category_count,
                state.categories.size,
                state.categories.size,
            ),
        )

        state.categories.forEach { category ->
            CategoryCard(
                category = category,
                onClick = { onOpenCategory(category.id) },
            )
            HorizontalDivider(color = WenxuLine)
        }
        CreateCategoryCard(
            onClick = onCreate,
            modifier = Modifier.padding(top = 12.dp),
        )

        SectionHeader(title = stringResource(R.string.organization_to_organize))
        UnclassifiedCategoryRow(
            count = state.unclassifiedCount,
            onClick = { onOpenCategory(null) },
        )
        HorizontalDivider(color = WenxuLine)
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun CategoryCard(
    category: CategorySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categoryDescription = pluralStringResource(
        R.plurals.organization_category_document_count,
        category.documentCount,
        category.documentCount,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
            .semantics { contentDescription = "${category.name} $categoryDescription" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(WenxuBrandSoft, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.FOLDER,
                color = WenxuBrand,
                modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp),
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = categoryDescription,
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        WenxuLineIcon(
            type = WenxuLineIconType.CHEVRON,
            color = WenxuMuted,
            modifier = Modifier.size(DocumentAssistantDimens.Icon),
        )
    }
}

@Composable
private fun CreateCategoryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val createCategoryLabel = stringResource(R.string.organization_create_category)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .semantics { contentDescription = createCategoryLabel },
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, WenxuBrand.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.PLUS,
                color = WenxuBrand,
                modifier = Modifier.size(18.dp),
            )
            Text(
                createCategoryLabel,
                color = WenxuBrand,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

@Composable
private fun UnclassifiedCategoryRow(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WenxuLineIcon(
            type = WenxuLineIconType.FILE,
            color = WenxuBrand,
            modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
        )
        Text(
            text = stringResource(R.string.organization_unclassified),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.organization_documents_count, count, count),
            color = WenxuMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 6.dp),
        )
        WenxuLineIcon(
            type = WenxuLineIconType.CHEVRON,
            color = WenxuMuted,
            modifier = Modifier.size(DocumentAssistantDimens.Icon),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    viewModel: CategoryDetailViewModel,
    onBack: () -> Unit,
    onOpenPdf: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val categoryTitle = state.title.resolve()
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAddDocuments by remember { mutableStateOf(false) }
    var showBatchCategories by remember { mutableStateOf(false) }
    var classifyTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var createForDocument by remember { mutableStateOf<DocumentRecord?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var actionTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var openErrorTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    CategoryDetailScreen(
        state = state,
        onBack = onBack,
        onRename = {
            renameError = null
            showRename = true
        },
        onDelete = { showDelete = true },
        onAddDocuments = { showAddDocuments = true },
        onEnterSelection = viewModel::startSelection,
        onCancelSelection = viewModel::cancelSelection,
        onToggleSelection = viewModel::toggleSelection,
        onBatchClassify = { showBatchCategories = true },
        onMoreDocument = { actionTarget = it },
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
    if (showBatchCategories) {
        BatchOrganizeDocumentsSheet(
            selectedCount = state.selectedDocumentIds.size,
            categories = state.categories,
            onDismiss = { showBatchCategories = false },
            onChooseCategory = { categoryId ->
                showBatchCategories = false
                viewModel.addSelectedToCategory(categoryId) { added ->
                    Toast.makeText(
                        context,
                        resources.getString(R.string.organization_categorized_documents, added),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
    actionTarget?.let { document ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 22.dp, end = 22.dp, bottom = 18.dp),
            ) {
                Text(
                    stringResource(R.string.organization_file_actions),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    document.displayName,
                    color = WenxuMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                HorizontalDivider(color = WenxuLine)
                if (state.isUnclassified) {
                    CategorySheetActionRow(
                        icon = WenxuLineIconType.TAG,
                        label = stringResource(R.string.organization_action_categorize),
                        onClick = {
                            actionTarget = null
                            classifyTarget = document
                        },
                    )
                    HorizontalDivider(color = WenxuLine)
                }
                CategorySheetActionRow(
                    icon = WenxuLineIconType.SHARE,
                    label = stringResource(R.string.organization_share_file),
                    onClick = {
                        actionTarget = null
                        if (!shareDocument(context, document)) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.organization_share_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                if (!state.isUnclassified) {
                    HorizontalDivider(color = WenxuLine)
                    CategorySheetActionRow(
                        icon = WenxuLineIconType.CLOSE,
                        label = stringResource(R.string.organization_remove_from_category),
                        color = WenxuDanger,
                        onClick = {
                            actionTarget = null
                            removeTarget = document
                        },
                    )
                }
            }
        }
    }
    classifyTarget?.let { document ->
        OrganizeDocumentSheet(
            document = document,
            categories = state.categories,
            onDismiss = { classifyTarget = null },
            onChooseCategory = { categoryId ->
                viewModel.addDocumentToCategory(document.id, categoryId)
                classifyTarget = null
            },
            onCreateCategory = {
                createError = null
                createForDocument = document
                classifyTarget = null
            },
        )
    }
    if (showAddDocuments) {
        AddDocumentsSheet(
            documents = state.availableDocuments,
            onDismiss = { showAddDocuments = false },
            onConfirm = { selectedIds ->
                viewModel.addDocuments(selectedIds)
                showAddDocuments = false
            },
        )
    }
    createForDocument?.let { document ->
        CategoryNameDialog(
            title = stringResource(R.string.organization_create_category_and_add),
            initialValue = "",
            confirmLabel = stringResource(R.string.organization_action_create_and_add),
            errorMessage = createError,
            onDismiss = {
                createError = null
                createForDocument = null
            },
            onConfirm = { name ->
                viewModel.createCategoryAndAdd(name, document.id) { result ->
                    result.fold(
                        onSuccess = {
                            createError = null
                            createForDocument = null
                        },
                        onFailure = { error ->
                            createError = localizedCategoryError(
                                context,
                                error,
                                R.string.organization_create_category_failed,
                            )
                        },
                    )
                }
            },
        )
    }
    if (showRename) {
        CategoryNameDialog(
            title = stringResource(R.string.organization_rename_category),
            initialValue = categoryTitle,
            confirmLabel = stringResource(R.string.organization_action_save),
            errorMessage = renameError,
            onDismiss = {
                renameError = null
                showRename = false
            },
            onConfirm = {
                viewModel.rename(it) { result ->
                    result.fold(
                        onSuccess = {
                            renameError = null
                            showRename = false
                        },
                        onFailure = { error ->
                            renameError = localizedCategoryError(
                                context,
                                error,
                                R.string.organization_rename_category_failed,
                            )
                        },
                    )
                }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = {
                Text(stringResource(R.string.organization_delete_named_category_question, categoryTitle))
            },
            text = { Text(stringResource(R.string.organization_delete_category_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onBack)
                }) {
                    Text(stringResource(R.string.organization_action_delete), color = WenxuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.organization_action_cancel))
                }
            },
        )
    }
    removeTarget?.let { document ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = {
                Text(
                    stringResource(
                        R.string.organization_remove_from_named_category_question,
                        categoryTitle,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.organization_remove_named_document_explanation,
                        document.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDocument(document.id)
                    removeTarget = null
                }) { Text(stringResource(R.string.organization_action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
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
fun CategoryDetailScreen(
    state: CategoryDetailUiState,
    onBack: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onAddDocuments: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onBatchClassify: () -> Unit = {},
    onOpenDocument: (DocumentRecord) -> Unit = {},
    onMoreDocument: (DocumentRecord) -> Unit = {},
) {
    val pageTitle = if (state.isUnclassified) {
        stringResource(R.string.organization_unclassified_page)
    } else {
        state.title.resolve()
    }
    val cancelLabel = stringResource(R.string.organization_action_cancel)
    val multiSelectLabel = stringResource(R.string.organization_action_multi_select)
    val exitMultiSelectDescription = stringResource(R.string.organization_action_exit_multi_select)
    val enterMultiSelectDescription = stringResource(R.string.organization_action_enter_multi_select)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
        ) {
            DocumentAssistantTopBar(
                title = pageTitle,
                onBack = onBack,
                actionLabel = if (state.documents.isEmpty()) {
                    null
                } else if (state.selectionMode) {
                    cancelLabel
                } else {
                    multiSelectLabel
                },
                actionDescription = if (state.selectionMode) {
                    exitMultiSelectDescription
                } else {
                    enterMultiSelectDescription
                },
                onAction = if (state.documents.isEmpty()) null else if (state.selectionMode) onCancelSelection else onEnterSelection,
            )

            SectionHeader(
                title = if (state.selectionMode) {
                    pluralStringResource(
                        R.plurals.organization_selected_short,
                        state.selectedDocumentIds.size,
                        state.selectedDocumentIds.size,
                    )
                } else if (state.isUnclassified) {
                    pluralStringResource(
                        R.plurals.organization_unclassified_count,
                        state.documents.size,
                        state.documents.size,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.organization_category_document_count,
                        state.documents.size,
                        state.documents.size,
                    )
                },
                detail = null,
                actionLabel = null,
                onAction = null,
            )

            if (!state.isUnclassified) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onRename,
                        modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
                    ) {
                        Text(stringResource(R.string.organization_action_rename), color = WenxuBrand)
                    }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.heightIn(min = DocumentAssistantDimens.TouchTarget),
                    ) {
                        Text(
                            stringResource(R.string.organization_action_delete_category),
                            color = WenxuDanger,
                        )
                    }
                }
            }

            HorizontalDivider(color = WenxuLine)
            if (state.documents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    DocumentAssistantEmptyState(
                        icon = WenxuLineIconType.FOLDER,
                        title = if (state.isUnclassified) {
                            stringResource(R.string.organization_no_unclassified_documents)
                        } else {
                            stringResource(R.string.organization_empty_category)
                        },
                        message = if (state.isUnclassified) {
                            stringResource(R.string.organization_unclassified_empty_detail)
                        } else {
                            stringResource(R.string.organization_empty_category_detail)
                        },
                        actionLabel = if (state.isUnclassified) {
                            null
                        } else {
                            stringResource(R.string.organization_action_add_documents)
                        },
                        onAction = if (state.isUnclassified) null else onAddDocuments,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = if (state.selectionMode || !state.isUnclassified) 112.dp else 24.dp),
                ) {
                    itemsIndexed(state.documents, key = { _, item -> item.id }) { index, document ->
                        DocumentRow(
                            document = document,
                            isFirst = index == 0,
                            isLast = index == state.documents.lastIndex,
                            metadata = documentMetadata(document),
                            onClick = if (state.selectionMode) {
                                { onToggleSelection(document.id) }
                            } else {
                                { onOpenDocument(document) }
                            },
                            onMore = if (state.selectionMode) null else ({ onMoreDocument(document) }),
                            moreDescription = stringResource(
                                R.string.organization_more_actions_for,
                                document.displayName,
                            ),
                            selectionMode = state.selectionMode,
                            selected = document.id in state.selectedDocumentIds,
                            onSelectionChange = { onToggleSelection(document.id) },
                        )
                        HorizontalDivider(color = WenxuLine)
                    }
                }
            }
        }

        if (state.selectionMode) {
            SelectionActionBar(
                selectedCount = state.selectedDocumentIds.size,
                primaryLabel = stringResource(R.string.organization_action_categorize),
                onPrimary = onBatchClassify,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (!state.isUnclassified) {
            Button(
                onClick = onAddDocuments,
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = DocumentAssistantDimens.PageHorizontal, vertical = 16.dp)
                    .height(48.dp),
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.PLUS,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.organization_action_add_documents),
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun documentMetadata(document: DocumentRecord): String =
    if (document.modifiedAt > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(document.modifiedAt))
    } else {
        stringResource(R.string.organization_unknown_date)
    }

@Composable
private fun CategorySheetActionRow(
    icon: WenxuLineIconType,
    label: String,
    color: androidx.compose.ui.graphics.Color = WenxuInk,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WenxuLineIcon(
            type = icon,
            color = color,
            modifier = Modifier.size(DocumentAssistantDimens.Icon),
        )
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizeDocumentSheet(
    document: DocumentRecord,
    categories: List<CategorySummary>,
    onDismiss: () -> Unit,
    onChooseCategory: (Long) -> Unit,
    onCreateCategory: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 18.dp),
        ) {
            Text(
                stringResource(R.string.organization_action_categorize),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                document.displayName,
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Text(
                stringResource(R.string.organization_category_only_explanation),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            HorizontalDivider(color = WenxuLine)
            if (categories.isEmpty()) {
                Text(
                    stringResource(R.string.organization_no_categories_create_one),
                    color = WenxuMuted,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
                HorizontalDivider(color = WenxuLine)
            } else {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.Button) { onChooseCategory(category.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WenxuLineIcon(
                            type = WenxuLineIconType.FOLDER,
                            color = WenxuBrand,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                            Text(
                                category.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.organization_category_document_count,
                                    category.documentCount,
                                    category.documentCount,
                                ),
                                color = WenxuMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        WenxuLineIcon(
                            type = WenxuLineIconType.CHEVRON,
                            color = WenxuMuted,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                    }
                    HorizontalDivider(color = WenxuLine)
                }
            }
            TextButton(
                onClick = onCreateCategory,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DocumentAssistantDimens.TouchTarget),
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.PLUS,
                    color = WenxuBrand,
                    modifier = Modifier.size(DocumentAssistantDimens.Icon),
                )
                Text(
                    stringResource(R.string.organization_create_category_and_add),
                    color = WenxuBrand,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchOrganizeDocumentsSheet(
    selectedCount: Int,
    categories: List<CategorySummary>,
    onDismiss: () -> Unit,
    onChooseCategory: (Long) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 18.dp),
        ) {
            Text(
                stringResource(R.string.organization_action_categorize),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                pluralStringResource(
                    R.plurals.organization_selected_not_moved,
                    selectedCount,
                    selectedCount,
                ),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            HorizontalDivider(color = WenxuLine)
            if (categories.isEmpty()) {
                Text(
                    stringResource(R.string.organization_no_categories_return),
                    color = WenxuMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.Button) { onChooseCategory(category.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WenxuLineIcon(
                            type = WenxuLineIconType.FOLDER,
                            color = WenxuBrand,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                            Text(
                                category.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.organization_category_document_count,
                                    category.documentCount,
                                    category.documentCount,
                                ),
                                color = WenxuMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        WenxuLineIcon(
                            type = WenxuLineIconType.CHEVRON,
                            color = WenxuMuted,
                            modifier = Modifier.size(DocumentAssistantDimens.Icon),
                        )
                    }
                    HorizontalDivider(color = WenxuLine)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDocumentsSheet(
    documents: List<DocumentRecord>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val visibleDocuments = documents.filter { document ->
        query.isBlank() || document.displayName.contains(query.trim(), ignoreCase = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 18.dp),
        ) {
            Text(
                stringResource(R.string.organization_action_add_documents),
                style = MaterialTheme.typography.titleLarge,
            )
            DocumentSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.organization_search_file_name),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            if (visibleDocuments.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (documents.isEmpty()) {
                            stringResource(R.string.organization_all_documents_in_category)
                        } else {
                            stringResource(R.string.organization_no_search_matches)
                        },
                        color = WenxuMuted,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 330.dp)
                        .padding(top = 8.dp),
                ) {
                    itemsIndexed(visibleDocuments, key = { _, item -> item.id }) { _, document ->
                        val selected = document.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (selected) {
                                        selectedIds - document.id
                                    } else {
                                        selectedIds + document.id
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DocumentTypeIcon(extension = document.extension)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    document.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    documentMetadata(document),
                                    color = WenxuMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                            Box(
                                Modifier.size(DocumentAssistantDimens.TouchTarget),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .border(1.dp, if (selected) WenxuBrand else WenxuLine, CircleShape)
                                        .background(
                                            if (selected) WenxuBrand else MaterialTheme.colorScheme.surface,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        WenxuLineIcon(
                                            type = WenxuLineIconType.CHECK,
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = WenxuLine)
                    }
                }
            }
            Button(
                onClick = { onConfirm(selectedIds) },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp),
            ) {
                Text(
                    if (selectedIds.isEmpty()) {
                        stringResource(R.string.organization_choose_documents_to_add)
                    } else {
                        pluralStringResource(
                            R.plurals.organization_added_documents,
                            selectedIds.size,
                            selectedIds.size,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 20) value = it },
                    label = { Text(stringResource(R.string.organization_category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.organization_action_cancel))
            }
        },
    )
}

private fun localizedCategoryError(
    context: Context,
    error: Throwable,
    @StringRes fallback: Int,
): String = when (error.message) {
    "分类名称不能为空" -> context.getString(R.string.organization_category_name_required)
    "分类名称不能超过20个字" -> context.getString(R.string.organization_category_name_too_long)
    "已经有同名分类" -> context.getString(R.string.organization_category_name_duplicate)
    else -> context.getString(fallback)
}
