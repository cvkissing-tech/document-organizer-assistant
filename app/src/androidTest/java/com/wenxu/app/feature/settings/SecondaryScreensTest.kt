package com.wenxu.app.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.R
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.feature.trash.TrashItem
import com.wenxu.app.feature.trash.TrashScreen
import com.wenxu.app.feature.trash.TrashUiState
import com.wenxu.app.ui.theme.WenxuTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondaryScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun settingsHasBackAndGroupedActions() {
        val backCalls = AtomicInteger()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            WenxuTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    onBack = { backCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        rule.onNodeWithText(context.getString(R.string.settings_section_scan_files)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.settings_section_use_help)).assertIsDisplayed()
        assertThat(backCalls.get()).isEqualTo(1)
    }

    @Test
    fun scanSourceShowsRealNameAndReauthorization() {
        val reauthorizeCalls = AtomicInteger()
        rule.setContent {
            WenxuTheme {
                ScanSourcesScreen(
                    state = ScanSourcesUiState(
                        sources = listOf(
                            ScanSourceEntity(
                                id = 7,
                                treeUri = "content://tree/qq",
                                displayName = "QQ接收文件",
                                permissionState = SourcePermissionState.NEEDS_ATTENTION,
                            ),
                        ),
                    ),
                    onReauthorize = { reauthorizeCalls.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithText("QQ接收文件").assertIsDisplayed()
        rule.onNodeWithText("重新授权").assertIsDisplayed().performClick()
        assertThat(reauthorizeCalls.get()).isEqualTo(1)
    }

    @Test
    fun trashSelectionShowsPersistentActions() {
        rule.setContent {
            WenxuTheme {
                TrashScreen(
                    state = TrashUiState(
                        items = listOf(
                            TrashItem(
                                id = 1,
                                documentId = 10,
                                displayName = "旧报告.docx",
                                extension = "docx",
                                sizeBytes = 1024,
                                originalParentUri = "content://documents",
                                deletedAt = 1,
                                expiresAt = Long.MAX_VALUE,
                            ),
                        ),
                        selectedIds = setOf(1),
                    ),
                )
            }
        }

        rule.onNodeWithText("恢复所选").assertIsDisplayed()
        rule.onNodeWithText("永久删除").assertIsDisplayed()
    }
}
