package com.wenxu.app.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wenxu.app.R
import com.wenxu.app.core.model.DocumentRecord

fun launchDocument(context: Context, document: DocumentRecord): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(document.uri), mimeTypeFor(document.extension))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

fun shareDocument(context: Context, document: DocumentRecord): Boolean = shareDocument(
    context = context,
    uri = document.uri,
    displayName = document.displayName,
    extension = document.extension,
)

fun shareDocument(
    context: Context,
    uri: String,
    displayName: String,
    extension: String,
): Boolean = try {
    val parsedUri = Uri.parse(uri)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeFor(extension)
        putExtra(Intent.EXTRA_STREAM, parsedUri)
        clipData = ClipData.newRawUri(displayName, parsedUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.action_share_file)))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

internal fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}
