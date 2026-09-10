package com.wenxu.app.feature.language

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.localization.AppLanguage
import com.wenxu.app.core.localization.applyAppLanguage
import com.wenxu.app.ui.components.DocumentAppMark
import com.wenxu.app.ui.components.DocumentAssistantTopBar
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.theme.WenxuSurface

@Composable
fun LanguageScreen(
    viewModel: LanguageViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LanguageScreen(
        state = state,
        showBack = showBack,
        onBack = onBack,
        onLanguageSelected = viewModel::selectLanguage,
        onConfirm = {
            viewModel.confirmSelection { language ->
                onConfirmed()
                applyAppLanguage(language)
            }
        },
    )
}

@Composable
fun LanguageScreen(
    state: LanguageUiState,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = DocumentAssistantDimens.PageHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showBack) {
            Spacer(Modifier.height(DocumentAssistantDimens.PageTop))
            DocumentAssistantTopBar(
                title = stringResource(R.string.destination_language),
                onBack = onBack,
            )
        } else {
            LanguageBrandHeader()
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WenxuBrand, strokeWidth = 2.dp)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (showBack) 34.dp else 62.dp))
            LanguageHero()
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.language_title),
                color = WenxuInk,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.language_subtitle),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            LanguageOption(
                title = stringResource(R.string.language_option_chinese),
                detail = stringResource(R.string.language_option_chinese_detail),
                selected = state.selectedLanguage == AppLanguage.SIMPLIFIED_CHINESE,
                onClick = { onLanguageSelected(AppLanguage.SIMPLIFIED_CHINESE) },
            )
            Spacer(Modifier.height(12.dp))
            LanguageOption(
                title = stringResource(R.string.language_option_english),
                detail = stringResource(R.string.language_option_english_detail),
                selected = state.selectedLanguage == AppLanguage.ENGLISH,
                onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onConfirm,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(
                    text = stringResource(
                        if (state.selectedLanguage == AppLanguage.SIMPLIFIED_CHINESE) {
                            R.string.language_continue_chinese
                        } else {
                            R.string.language_continue_english
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LanguageBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentAppMark(Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.language_brand_name),
            color = WenxuInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun LanguageHero() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(WenxuBrandSoft, RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "中\nEN",
            color = WenxuBrand,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LanguageOption(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val selectedDescription = stringResource(R.string.language_selected_description, title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) WenxuBrandSoft else WenxuSurface)
            .border(1.dp, if (selected) WenxuBrand else WenxuLine, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                if (selected) contentDescription = selectedDescription
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = WenxuBrand),
        )
    }
}
