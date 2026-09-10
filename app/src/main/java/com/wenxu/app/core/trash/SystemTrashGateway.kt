package com.wenxu.app.core.trash

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.ScanSourceKind
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class TrashTarget(
    val id: Long,
    val uri: String,
    val sourceKind: ScanSourceKind,
)

data class TrashRequestPlan(
    val system: List<TrashTarget>,
    val immediate: List<TrashTarget>,
    val unsupported: List<TrashTarget>,
)

class TrashRequestPlanner(
    private val apiLevel: Int,
) {
    fun partition(targets: List<TrashTarget>): TrashRequestPlan {
        val uniqueTargets = targets.distinctBy(TrashTarget::id)
        val system = mutableListOf<TrashTarget>()
        val immediate = mutableListOf<TrashTarget>()
        val unsupported = mutableListOf<TrashTarget>()

        uniqueTargets.forEach { target ->
            when (target.sourceKind) {
                ScanSourceKind.SHARED_STORAGE -> {
                    if (apiLevel >= 30) system += target else unsupported += target
                }
                ScanSourceKind.TREE,
                ScanSourceKind.INBOX,
                -> immediate += target
            }
        }

        return TrashRequestPlan(
            system = system,
            immediate = immediate,
            unsupported = unsupported,
        )
    }
}

enum class MediaStoreVerification {
    MATCHED,
    NOT_MATCHED,
    UNKNOWN,
}

interface SystemTrashGateway {
    fun createRequest(action: PendingTrashAction, uris: List<String>): Result<IntentSender>

    suspend fun verify(
        action: PendingTrashAction,
        uris: List<String>,
    ): Map<String, MediaStoreVerification>
}

class AndroidSystemTrashGateway(
    private val resolver: ContentResolver,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SystemTrashGateway {
    private val verifier = MediaStoreVerifier(apiLevel, ioDispatcher) { rawUri ->
        queryMediaStoreRow(resolver, Uri.parse(rawUri))
    }

    override fun createRequest(
        action: PendingTrashAction,
        uris: List<String>,
    ): Result<IntentSender> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || apiLevel < 30) {
            return Result.failure(
                UnsupportedOperationException(
                    "MediaStore system requests require Android 11 (API 30) or newer",
                ),
            )
        }

        val prepared = prepareMediaStoreUriStrings(uris)
        if (prepared.isFailure) {
            return Result.failure(requireNotNull(prepared.exceptionOrNull()))
        }

        val parsedUris = prepared.getOrThrow().map(Uri::parse)
        return try {
            Result.success(createRequestOnAndroid11(action, parsedUris))
        } catch (error: Exception) {
            Result.failure(
                IllegalStateException("Unable to create MediaStore $action request", error),
            )
        }
    }

    override suspend fun verify(
        action: PendingTrashAction,
        uris: List<String>,
    ): Map<String, MediaStoreVerification> = verifier.verify(action, uris)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createRequestOnAndroid11(
        action: PendingTrashAction,
        uris: List<Uri>,
    ): IntentSender = when (action) {
        PendingTrashAction.TRASH -> MediaStore.createTrashRequest(resolver, uris, true).intentSender
        PendingTrashAction.RESTORE -> MediaStore.createTrashRequest(resolver, uris, false).intentSender
        PendingTrashAction.DELETE_FOREVER -> MediaStore.createDeleteRequest(resolver, uris).intentSender
    }
}

internal enum class MediaStoreRowState {
    MISSING,
    TRASHED,
    NOT_TRASHED,
    UNKNOWN,
}

internal class MediaStoreVerifier(
    private val apiLevel: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val query: (String) -> MediaStoreRowState,
) {
    suspend fun verify(
        action: PendingTrashAction,
        uris: List<String>,
    ): Map<String, MediaStoreVerification> = withContext(ioDispatcher) {
        val uniqueUris = uris.distinct()
        val results = linkedMapOf<String, MediaStoreVerification>()
        uniqueUris.forEach { uri ->
            coroutineContext.ensureActive()
            if (apiLevel < 30) {
                results[uri] = MediaStoreVerification.UNKNOWN
                return@forEach
            }
            if (prepareMediaStoreUriStrings(listOf(uri)).isFailure) {
                results[uri] = MediaStoreVerification.UNKNOWN
                return@forEach
            }
            val rowState = try {
                query(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MediaStoreRowState.UNKNOWN
            }
            results[uri] = verificationFor(action, rowState)
        }
        results
    }
}

internal fun verificationFor(
    action: PendingTrashAction,
    rowState: MediaStoreRowState,
): MediaStoreVerification = when (rowState) {
    MediaStoreRowState.UNKNOWN -> MediaStoreVerification.UNKNOWN
    MediaStoreRowState.MISSING -> when (action) {
        PendingTrashAction.DELETE_FOREVER -> MediaStoreVerification.MATCHED
        PendingTrashAction.TRASH,
        PendingTrashAction.RESTORE,
        -> MediaStoreVerification.UNKNOWN
    }
    MediaStoreRowState.TRASHED -> when (action) {
        PendingTrashAction.TRASH -> MediaStoreVerification.MATCHED
        PendingTrashAction.RESTORE,
        PendingTrashAction.DELETE_FOREVER,
        -> MediaStoreVerification.NOT_MATCHED
    }
    MediaStoreRowState.NOT_TRASHED -> when (action) {
        PendingTrashAction.RESTORE -> MediaStoreVerification.MATCHED
        PendingTrashAction.TRASH,
        PendingTrashAction.DELETE_FOREVER,
        -> MediaStoreVerification.NOT_MATCHED
    }
}

internal fun mediaStoreRowState(
    hasTrashedColumn: Boolean,
    rowExists: Boolean,
    trashedValue: Int?,
): MediaStoreRowState {
    if (!hasTrashedColumn) return MediaStoreRowState.UNKNOWN
    if (!rowExists) return MediaStoreRowState.MISSING
    return when (trashedValue) {
        1 -> MediaStoreRowState.TRASHED
        0 -> MediaStoreRowState.NOT_TRASHED
        else -> MediaStoreRowState.UNKNOWN
    }
}

internal fun prepareMediaStoreUriStrings(uris: List<String>): Result<List<String>> {
    if (uris.isEmpty()) {
        return Result.failure(IllegalArgumentException("At least one MediaStore URI is required"))
    }

    val uniqueUris = uris.distinct()
    uniqueUris.forEach { rawUri ->
        val parsed = try {
            URI(rawUri)
        } catch (error: URISyntaxException) {
            return Result.failure(IllegalArgumentException("Invalid URI: $rawUri", error))
        }
        if (!isMediaStoreItemUri(parsed)) {
            return Result.failure(
                IllegalArgumentException("URI is not a MediaStore item URI: $rawUri"),
            )
        }
    }
    return Result.success(uniqueUris)
}

private fun isMediaStoreItemUri(uri: URI): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) {
        return false
    }
    if (uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null || uri.port != -1) {
        return false
    }

    val path = uri.path ?: return false
    if (!path.startsWith('/') || path.endsWith('/')) return false
    val segments = path.removePrefix("/").split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false

    val rawId = segments.lastOrNull() ?: return false
    if (!rawId.all(Char::isDigit) || rawId.toLongOrNull()?.let { it > 0 } != true) return false

    return when {
        segments.size == 3 && segments[1] in setOf("file", "downloads") -> true
        segments.size == 4 && segments[1] in setOf("images", "video", "audio") &&
            segments[2] == "media" -> true
        else -> false
    }
}

private fun queryMediaStoreRow(
    resolver: ContentResolver,
    uri: Uri,
): MediaStoreRowState = resolver.query(
    uri,
    arrayOf(MediaStore.MediaColumns.IS_TRASHED),
    Bundle().apply {
        // MediaStore hides trashed rows by default. Include them so a successful
        // system trash request is not mistaken for a failed operation.
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    },
    null,
)?.use { cursor ->
    val trashedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
    val rowExists = cursor.moveToFirst()
    val trashedValue = if (rowExists && trashedColumn >= 0 && !cursor.isNull(trashedColumn)) {
        cursor.getInt(trashedColumn)
    } else {
        null
    }
    mediaStoreRowState(
        hasTrashedColumn = trashedColumn >= 0,
        rowExists = rowExists,
        trashedValue = trashedValue,
    )
} ?: MediaStoreRowState.UNKNOWN
