package com.wenxu.app.core.permission

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

private fun platformHasExternalStorageManagerAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

sealed interface StorageAccessState {
    data object Granted : StorageAccessState

    data object NeedsPermission : StorageAccessState

    data object Legacy : StorageAccessState
}

class StorageAccessController(
    private val context: Context? = null,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val isExternalStorageManager: () -> Boolean = ::platformHasExternalStorageManagerAccess,
) {
    fun state(): StorageAccessState = when {
        sdkInt < Build.VERSION_CODES.R -> StorageAccessState.Legacy
        isExternalStorageManager() -> StorageAccessState.Granted
        else -> StorageAccessState.NeedsPermission
    }

    @SuppressLint("InlinedApi")
    fun createRequestIntent(): Intent? {
        val currentContext = context ?: return null
        if (sdkInt < Build.VERSION_CODES.R) return null

        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${currentContext.packageName}")
        }
    }

    @SuppressLint("InlinedApi")
    fun createFallbackRequestIntent(): Intent? {
        if (sdkInt < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}

fun StorageAccessController.createResolvableRequestIntent(context: Context): Intent? {
    val appSpecificIntent = createRequestIntent()
    if (appSpecificIntent?.resolveActivity(context.packageManager) != null) {
        return appSpecificIntent
    }
    return createFallbackRequestIntent()
        ?.takeIf { intent -> intent.resolveActivity(context.packageManager) != null }
}
