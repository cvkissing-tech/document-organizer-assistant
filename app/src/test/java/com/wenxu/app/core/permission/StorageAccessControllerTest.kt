package com.wenxu.app.core.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageAccessControllerTest {
    @Test
    fun `android 10 and below uses legacy access`() {
        val controller = StorageAccessController(
            sdkInt = 29,
            isExternalStorageManager = { false },
        )

        assertEquals(StorageAccessState.Legacy, controller.state())
    }

    @Test
    fun `android 11 reports missing full access`() {
        val controller = StorageAccessController(
            sdkInt = 30,
            isExternalStorageManager = { false },
        )

        assertEquals(StorageAccessState.NeedsPermission, controller.state())
    }

    @Test
    fun `android 11 reports granted full access`() {
        val controller = StorageAccessController(
            sdkInt = 30,
            isExternalStorageManager = { true },
        )

        assertEquals(StorageAccessState.Granted, controller.state())
    }
}
