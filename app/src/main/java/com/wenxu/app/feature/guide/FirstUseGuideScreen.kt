package com.wenxu.app.feature.guide

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wenxu.app.R
import com.wenxu.app.ui.components.DocumentAppMark
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuBrandSoft
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

private const val GUIDE_PAGE_COUNT = 2

@Composable
fun FirstUseGuideScreen(
    onFinished: () -> Unit,
    helpMode: Boolean = false,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GuideBrandHeader(
            actionLabel = stringResource(if (helpMode) R.string.guide_close else R.string.guide_skip),
            onAction = onFinished,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))
            GuideHeroIcon(
                icon = if (pageIndex == 0) WenxuLineIconType.SCAN else WenxuLineIconType.FILE_CHECK,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(
                    if (pageIndex == 0) R.string.guide_find_title else R.string.guide_review_title,
                ),
                color = WenxuInk,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (pageIndex == 0) {
                        R.string.guide_find_description
                    } else {
                        R.string.guide_review_description
                    },
                ),
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    if (pageIndex == 0) pageIndex = 1 else onFinished()
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WenxuBrand),
            ) {
                Text(
                    text = stringResource(
                        when {
                            pageIndex == 0 -> R.string.guide_next
                            helpMode -> R.string.guide_finish
                            else -> R.string.guide_start
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        GuideDots(
            selectedPage = pageIndex,
            onPageSelected = { pageIndex = it },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GuideBrandHeader(
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentAppMark(Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.app_name),
            color = WenxuInk,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = actionLabel,
            color = WenxuBrand,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun GuideHeroIcon(icon: WenxuLineIconType) {
    Box(
        modifier = Modifier
            .size(108.dp)
            .background(WenxuBrandSoft, RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(
            type = icon,
            color = WenxuBrand,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun GuideDots(selectedPage: Int, onPageSelected: (Int) -> Unit) {
    Row {
        repeat(GUIDE_PAGE_COUNT) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == selectedPage) 9.dp else 7.dp)
                    .background(
                        color = if (index == selectedPage) WenxuBrand else WenxuLine,
                        shape = CircleShape,
                    )
                    .clickable(role = Role.Button) { onPageSelected(index) },
            )
        }
    }
}
