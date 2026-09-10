package com.wenxu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenxu.app.navigation.WenxuDestination
import com.wenxu.app.navigation.WenxuDestinations
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted

@Composable
fun DocumentAssistantBottomBar(
    selectedRoute: String?,
    onDestination: (WenxuDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = WenxuLine)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp),
        ) {
            WenxuDestinations.bottomNavigation.forEach { destination ->
                val selected = destination.route == selectedRoute
                val color = if (selected) WenxuBrand else WenxuMuted
                val label = stringResource(destination.labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(66.dp)
                        .semantics { contentDescription = label }
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = label,
                            onClick = { onDestination(destination) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(82.dp)
                                .height(2.dp)
                                .background(WenxuBrand),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        WenxuLineIcon(
                            type = destination.icon,
                            color = color,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = label,
                            color = color,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
