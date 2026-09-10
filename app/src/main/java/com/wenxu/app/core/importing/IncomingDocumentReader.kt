package com.wenxu.app.core.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.wenxu.app.core.index.DocumentTypePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface IncomingMetadataReader {
    suspend fun read(uri: String): IncomingDocument?
}

class AndroidIncomingMetadataReader(
    private val resolver: ContentResolver,
) : IncomingMetadataReader {
    override suspend fun read(uri: String): IncomingDocument? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        var displayName: String? = null
        var size = -1L
        resolver.query(
            parsed,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val safeName = displayName?.trim().orEmpty().ifBlank {
            Uri.decode(parsed.lastPathSegment.orEmpty()).substringAfterLast('/')
        }
        if (!DocumentTypePolicy.supports(safeName)) return@withContext null
        IncomingDocument(
            uri = uri,
            displayName = safeName,
            mimeType = resolver.getType(parsed).orEmpty(),
            sizeBytes = size.coerceAtLeast(0),
        )
    }
}
