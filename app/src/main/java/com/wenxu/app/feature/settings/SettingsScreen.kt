package com.wenxu.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.BuildConfig
import com.wenxu.app.R
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.settings.DocumentScanScope
import com.wenxu.app.ui.components.DocumentAssistantDialog
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPrivacy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshAccessState() }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenSources = onOpenSources,
        onOpenTrash = onOpenTrash,
        onOpenHelp = onOpenHelp,
        onOpenLanguage = onOpenLanguage,
        onOpenPrivacy = { showPrivacy = true },
        onOpenAbout = { showAbout = true },
        onScanOnLaunchChange = viewModel::setScanOnLaunch,
    )
    if (showPrivacy) {
        DocumentAssistantDialog(
            title = stringResource(R.string.settings_privacy_dialog_title),
            message = stringResource(R.string.settings_privacy_dialog_message),
            confirmLabel = stringResource(R.string.action_got_it),
            onConfirm = { showPrivacy = false },
            onDismissRequest = { showPrivacy = false },
        )
    }
    if (showAbout) {
        DocumentAssistantDialog(
            title = stringResource(R.string.app_name),
            message = stringResource(
                R.string.settings_about_dialog_message,
                BuildConfig.VERSION_NAME,
            ),
            confirmLabel = stringResource(R.string.action_got_it),
            onConfirm = { showAbout = false },
            onDismissRequest = { showAbout = false },
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenLanguage: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onScanOnLaunchChange: (Boolean) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
    ) {
        Spacer(Modifier.height(DocumentAssistantDimens.PageTop))
        DocumentAssistantTopBar(title = stringResource(R.string.settings_title), onBack = onBack)

        SettingsSectionLabel(stringResource(R.string.settings_section_scan_files))
        SettingsRow(
            icon = WenxuLineIconType.SCAN,
            title = stringResource(R.string.settings_scan_scope),
            detail = if (state.scanScopeMode == DocumentScanScope.ALL_DOCUMENTS) {
                stringResource(R.string.settings_scope_all_documents)
            } else {
                pluralStringResource(
                    R.plurals.settings_scope_selected_folders,
                    state.sourceCount,
                    state.sourceCount,
                )
            },
            onClick = onOpenSources,
        )
        SettingsDivider()
        SettingsRow(
            icon = WenxuLineIconType.REFRESH,
            title = stringResource(R.string.settings_automatic_scan),
            detail = stringResource(R.string.settings_automatic_scan_detail),
            trailing = {
                Switch(
                    checked = state.scanOnLaunch,
                    onCheckedChange = onScanOnLaunchChange,
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            icon = WenxuLineIconType.TRASH,
            title = stringResource(R.string.settings_trash),
            detail = pluralStringResource(
                R.plurals.settings_trash_count,
                state.trashCount,
                state.trashCount,
            ),
            onClick = onOpenTrash,
        )

        SettingsSectionLabel(stringResource(R.string.settings_section_use_help))
        SettingsRow(
            icon = WenxuLineIconType.BOOKMARK,
            title = stringResource(R.string.settings_language),
            detail = stringResource(
                if (state.appLanguage == AppLanguage.SIMPLIFIED_CHINESE) {
                    R.string.language_option_chinese
                } else {
                    R.string.language_option_english
                },
            ),
            onClick = onOpenLanguage,
        )
        SettingsDivider()
        SettingsRow(
            icon = WenxuLineIconType.BOOKMARK,
            title = stringResource(R.string.settings_help),
            detail = stringResource(R.string.settings_help_detail),
            onClick = onOpenHelp,
        )
        SettingsDivider()
        SettingsRow(
            icon = WenxuLineIconType.SHIELD,
            title = stringResource(R.string.settings_privacy),
            detail = stringResource(R.string.settings_privacy_detail),
            onClick = onOpenPrivacy,
        )
        SettingsDivider()
        SettingsRow(
            icon = WenxuLineIconType.FILE,
            title = stringResource(R.string.settings_about),
            detail = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            onClick = onOpenAbout,
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = WenxuMuted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = WenxuLine, modifier = Modifier.padding(start = 34.dp))
}

@Composable
private fun SettingsRow(
    icon: WenxuLineIconType,
    title: String,
    detail: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(
                if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WenxuLineIcon(
            type = icon,
            color = WenxuBrand,
            modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(text = title, color = WenxuInk, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when {
            trailing != null -> trailing()
            onClick != null -> WenxuLineIcon(
                type = WenxuLineIconType.CHEVRON,
                color = WenxuMuted,
                modifier = Modifier.size(DocumentAssistantDimens.Icon),
            )
        }
    }
}
