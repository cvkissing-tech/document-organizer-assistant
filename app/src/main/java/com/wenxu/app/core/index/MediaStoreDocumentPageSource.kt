package com.wenxu.app.core.index

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.wenxu.app.core.model.DiscoveredDocument
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaStoreFileRow(
    val id: Long,
    val volumeName: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedAtSeconds: Long,
    val relativePath: String?,
)

internal class MediaStoreRowMapper {
    fun map(
        row: MediaStoreFileRow,
        documentUri: String,
        parentUri: String,
    ): DiscoveredDocument? {
        val displayName = row.displayName.trim()
        if (displayName.isEmpty() || !DocumentTypePolicy.supports(displayName)) return null

        return DiscoveredDocument(
            uri = documentUri,
            parentUri = parentUri,
            displayName = displayName,
            mimeType = row.mimeType.orEmpty(),
            sizeBytes = row.sizeBytes.coerceAtLeast(0),
            modifiedAt = TimeUnit.SECONDS.toMillis(row.modifiedAtSeconds.coerceAtLeast(0)),
        )
    }
}

internal class MediaStorePageAssembler(
    private val rowMapper: MediaStoreRowMapper,
    private val collectionUri: String,
    private val documentUriForRow: (MediaStoreFileRow) -> String,
) {
    fun assemble(
        rows: List<MediaStoreFileRow>,
        afterId: Long?,
        limit: Int,
    ): DocumentPage {
        require(limit > 0) { "limit 必须大于 0" }

        val checkedRows = rows
            .asSequence()
            .filter { afterId == null || it.id > afterId }
            .distinctBy(MediaStoreFileRow::id)
            .sortedBy(MediaStoreFileRow::id)
            .take(limit)
            .toList()
        val hasMore = rows.any { row ->
            (afterId == null || row.id > afterId) &&
                checkedRows.lastOrNull()?.id?.let { row.id > it } == true
        }

        return DocumentPage(
            documents = checkedRows.mapNotNull { row ->
                rowMapper.map(
                    row = row,
                    documentUri = documentUriForRow(row),
                    parentUri = collectionUri,
                )
            },
            nextCursor = checkedRows.lastOrNull()?.id,
            isComplete = !hasMore,
            checkedFiles = checkedRows.size,
            directoryPaths = checkedRows.mapNotNullTo(linkedSetOf()) { row ->
                row.relativePath?.trim()?.takeIf(String::isNotEmpty)
            },
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class MediaStoreDocumentPageSource internal constructor(
    private val accessState: () -> StorageAccessState,
    private val queryRows: suspend (afterId: Long?, queryLimit: Int) -> List<MediaStoreFileRow>,
    private val pageAssembler: MediaStorePageAssembler,
) : SharedDocumentPageSource {
    constructor(
        contentResolver: ContentResolver,
        storageAccessController: StorageAccessController,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        accessState = storageAccessController::state,
        queryRows = ContentResolverMediaStoreQuery(
            contentResolver = contentResolver,
            collectionUri = sharedFilesCollection(),
            ioDispatcher = ioDispatcher,
        )::read,
        pageAssembler = MediaStorePageAssembler(
            rowMapper = MediaStoreRowMapper(),
            collectionUri = sharedFilesCollection().toString(),
            documentUriForRow = { row ->
                MediaStore.Files.getContentUri(row.volumeName, row.id).toString()
            },
        ),
    )

    override suspend fun readPage(afterId: Long?, limit: Int): DocumentPage {
        require(limit in 1 until Int.MAX_VALUE) { "limit 必须在有效范围内" }
        if (accessState() != StorageAccessState.Granted) {
            throw SecurityException("尚未获得全部文件访问权限")
        }

        val rows = queryRows(afterId, limit + 1)
        return pageAssembler.assemble(rows = rows, afterId = afterId, limit = limit)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private class ContentResolverMediaStoreQuery(
    private val contentResolver: ContentResolver,
    private val collectionUri: Uri,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(afterId: Long?, limit: Int): List<MediaStoreFileRow> =
        withContext(ioDispatcher) {
            val queryArgs = Bundle().apply {
                if (afterId != null) {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns._ID} > ?",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(afterId.toString()),
                    )
                }
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }

            contentResolver.query(collectionUri, PROJECTION, queryArgs, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toMediaStoreFileRow())
                }
            }.orEmpty()
        }

    private fun Cursor.toMediaStoreFileRow() = MediaStoreFileRow(
        id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)),
        volumeName = getStringOrEmpty(MediaStore.MediaColumns.VOLUME_NAME),
        displayName = getStringOrEmpty(MediaStore.Files.FileColumns.DISPLAY_NAME),
        mimeType = getNullableString(MediaStore.Files.FileColumns.MIME_TYPE),
        sizeBytes = getLongOrZero(MediaStore.Files.FileColumns.SIZE),
        modifiedAtSeconds = getLongOrZero(MediaStore.Files.FileColumns.DATE_MODIFIED),
        relativePath = getNullableString(MediaStore.Files.FileColumns.RELATIVE_PATH),
    )

    private fun Cursor.getStringOrEmpty(columnName: String): String =
        getNullableString(columnName).orEmpty()

    private fun Cursor.getNullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrZero(columnName: String): Long {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) 0 else getLong(index)
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
    }
}

private fun sharedFilesCollection(): Uri =
    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
