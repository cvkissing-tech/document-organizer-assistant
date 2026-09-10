package com.wenxu.app.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wenxu.app.navigation.WenxuDestinations
import com.wenxu.app.ui.theme.WenxuTheme
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun bottomNavigationShowsThreeNamedDestinations() {
        rule.setContent {
            WenxuTheme {
                DocumentAssistantBottomBar(
                    selectedRoute = WenxuDestinations.Home.route,
                    onDestination = {},
                )
            }
        }

        rule.onNodeWithContentDescription("整理").assertIsDisplayed()
        rule.onNodeWithContentDescription("文档").assertIsDisplayed()
        rule.onNodeWithContentDescription("分类").assertIsDisplayed()
        rule.onAllNodesWithText("序").assertCountEquals(0)
    }

    @Test
    fun topBarAndEmptyStateExposeActions() {
        val backCalls = AtomicInteger()
        val actionCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                Column {
                    DocumentAssistantTopBar("确认文档", onBack = { backCalls.incrementAndGet() })
                    DocumentAssistantEmptyState(
                        icon = WenxuLineIconType.CHECK,
                        title = "暂时没有需要确认的文档",
                        message = "扫描后发现的关联文档会集中显示在这里",
                        actionLabel = "重新扫描",
                        onAction = { actionCalls.incrementAndGet() },
                    )
                }
            }
        }

        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("重新扫描").performClick()
        assertThat(backCalls.get()).isEqualTo(1)
        assertThat(actionCalls.get()).isEqualTo(1)
    }

    @Test
    fun selectionActionsRemainUsableAtSmallWidthAndLargeText() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                Box(Modifier.width(360.dp)) {
                    SelectionActionBar(
                        selectedCount = 2,
                        primaryLabel = "永久删除",
                        onPrimary = {},
                        secondaryLabel = "恢复所选",
                        onSecondary = {},
                        destructive = true,
                    )
                }
            }
        }

        rule.onNodeWithText("已选择 2 份文档").assertIsDisplayed()
        rule.onNodeWithText("恢复所选").assertIsDisplayed()
        rule.onNodeWithText("永久删除").assertIsDisplayed()
    }

    @Test
    fun retryActionHasAVisibleLabelAndInvokesItsAction() {
        val retryCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                RetryActionButton(
                    label = "重试",
                    onClick = { retryCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertThat(retryCalls.get()).isEqualTo(1)
    }
}
