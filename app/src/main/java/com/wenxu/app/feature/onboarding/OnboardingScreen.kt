package com.wenxu.app.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.core.permission.createResolvableRequestIntent
import com.wenxu.app.ui.components.DocumentAppMark
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    storageAccessController: StorageAccessController,
    onFilesSelected: (List<String>) -> Unit,
    onCompleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onFolderPickerResult(uri?.toString()) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val values = uris.map { it.toString() }
        if (values.isNotEmpty()) onFilesSelected(values)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.onStorageSettingsReturned() }

    OnboardingScreen(
        state = state,
        onRequestFullAccess = {
            viewModel.selectFullAccessMode()
            val intent = storageAccessController.createResolvableRequestIntent(context)
            if (intent == null) {
                viewModel.onPermissionLaunchFailed()
            } else {
                permissionLauncher.launch(intent)
            }
        },
        onChooseFolder = {
            viewModel.selectLimitedMode()
            folderPicker.launch(null)
        },
        onChooseFile = { documentPicker.launch(DOCUMENT_MIME_TYPES) },
    )

    LaunchedEffect(state.finishRequested) {
        if (state.finishRequested) onCompleted()
    }
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onRequestFullAccess: () -> Unit = {},
    onChooseFolder: () -> Unit = {},
    onChooseFile: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()
        Spacer(Modifier.height(44.dp))

        Box(
            modifier = Modifier
                .size(108.dp)
                .background(WenxuBrandSoft, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WenxuLineIcon(
                type = WenxuLineIconType.SHIELD,
                color = WenxuBrand,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            color = WenxuInk,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_description),
            color = WenxuMuted,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )

        state.errorMessage?.let { message ->
            Text(
                text = message.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        if (state.canRequestFullAccess || state.canReturnToFullAccess) {
            Button(
                onClick = onRequestFullAccess,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(stringResource(R.string.onboarding_scan_all), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = onChooseFolder,
            enabled = !state.isAdding,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, WenxuBrand),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WenxuBrand),
        ) {
            Text(
                text = stringResource(
                    if (state.isAdding) R.string.onboarding_saving_folder else R.string.onboarding_choose_folder,
                ),
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = WenxuLine)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clickable(role = Role.Button, onClick = onChooseFile),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(WenxuBrandSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                WenxuLineIcon(
                    type = WenxuLineIconType.FILE,
                    color = WenxuBrand,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_choose_file),
                    color = WenxuInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.onboarding_choose_file_description),
                    color = WenxuMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            WenxuLineIcon(
                type = WenxuLineIconType.CHEVRON,
                color = WenxuMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        HorizontalDivider(color = WenxuLine)
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentAppMark(Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = WenxuInk,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
