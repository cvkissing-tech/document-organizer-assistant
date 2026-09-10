package com.wenxu.app.core.storage

import java.net.URI
import java.util.concurrent.CancellationException

/** The gateway has evidence that the attempted physical operation did not change its source. */
class DocumentOperationUnchangedException(cause: Throwable) : Exception(cause.message, cause)

/** A preflight failure occurred before the provider deletion API was invoked. */
class DocumentDeleteNotAttemptedException(cause: Throwable) : Exception(cause.message, cause)

/** The provider did not supply enough evidence to resolve the physical operation. */
class DocumentStateUnknownException(cause: Throwable? = null) : Exception("无法核验文件状态", cause)

internal enum class DocumentPresence { PRESENT, MISSING, UNKNOWN }

internal sealed interface DirectDocumentQueryResult {
    data class Resolved(val presence: DocumentPresence) : DirectDocumentQueryResult
    data class Failed(val cause: Exception) : DirectDocumentQueryResult
}

data class VerifiedDeleteOutcome(
    val providerAcknowledged: Boolean,
    val verifiedAbsent: Boolean = true,
)

internal data class DocumentQuerySnapshot(
    val hasDocumentIdColumn: Boolean,
    val rowExists: Boolean,
    val matchesRequestedId: Boolean,
    val incomplete: Boolean = false,
)

internal data class ParentDocumentQuerySnapshot(
    val hasDocumentIdColumn: Boolean,
    val documentIds: Set<String>,
    val incomplete: Boolean = false,
)

internal fun parentDocumentQuerySnapshot(
    hasDocumentIdColumn: Boolean,
    documentIds: List<String?>,
    providerIncomplete: Boolean,
): ParentDocumentQuerySnapshot = ParentDocumentQuerySnapshot(
    hasDocumentIdColumn = hasDocumentIdColumn,
    documentIds = documentIds.filterNotNull().toSet(),
    incomplete = providerIncomplete || documentIds.any { it == null },
)

internal fun sameSafProviderAuthority(
    sourceTreeUri: String,
    parentUri: String,
    documentUri: String,
): Boolean {
    val authorities = listOf(sourceTreeUri, parentUri, documentUri).map { value ->
        runCatching { URI(value).authority }.getOrNull() ?: return false
    }
    return authorities.all { it == authorities.first() }
}

internal fun authorizedSafParentPresence(
    sourceTreeUri: String,
    parentUri: String,
    documentUri: String,
    query: () -> DocumentPresence,
): DocumentPresence = if (sameSafProviderAuthority(sourceTreeUri, parentUri, documentUri)) {
    query()
} else {
    DocumentPresence.UNKNOWN
}

internal fun documentPresence(snapshot: DocumentQuerySnapshot?): DocumentPresence = when {
    snapshot == null || !snapshot.hasDocumentIdColumn || snapshot.incomplete -> DocumentPresence.UNKNOWN
    !snapshot.rowExists -> DocumentPresence.MISSING
    snapshot.matchesRequestedId -> DocumentPresence.PRESENT
    else -> DocumentPresence.UNKNOWN
}

internal fun parentDocumentPresence(
    snapshot: ParentDocumentQuerySnapshot?,
    expectedDocumentId: String,
): DocumentPresence = when {
    snapshot == null || !snapshot.hasDocumentIdColumn || snapshot.incomplete -> DocumentPresence.UNKNOWN
    expectedDocumentId in snapshot.documentIds -> DocumentPresence.PRESENT
    else -> DocumentPresence.MISSING
}

internal fun captureDirectDocumentPresence(query: () -> DocumentPresence): DirectDocumentQueryResult = try {
    DirectDocumentQueryResult.Resolved(query())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    DirectDocumentQueryResult.Failed(error)
}

internal fun verifiedSafDocumentPresence(
    directQuery: () -> DocumentPresence,
    parentQuery: () -> DocumentPresence,
): DocumentPresence {
    val direct = captureDirectDocumentPresence(directQuery)
    if (direct is DirectDocumentQueryResult.Failed) return DocumentPresence.UNKNOWN
    val directPresence = (direct as DirectDocumentQueryResult.Resolved).presence
    if (directPresence != DocumentPresence.MISSING) return directPresence
    return try {
        parentQuery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DocumentPresence.UNKNOWN
    }
}

internal fun deleteDocumentIdempotently(
    query: () -> DocumentPresence,
    delete: () -> Boolean,
): Result<Unit> {
    fun querySafely(): DocumentPresence = try {
        query()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DocumentPresence.UNKNOWN
    }

    when (querySafely()) {
        DocumentPresence.MISSING -> return Result.success(Unit)
        DocumentPresence.UNKNOWN -> return Result.failure(DocumentStateUnknownException())
        DocumentPresence.PRESENT -> Unit
    }
    val deleted = try {
        Result.success(delete())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
    return when (querySafely()) {
        DocumentPresence.MISSING -> Result.success(Unit)
        DocumentPresence.UNKNOWN -> Result.failure(DocumentStateUnknownException(deleted.exceptionOrNull()))
        DocumentPresence.PRESENT -> if (deleted.getOrDefault(false)) {
            // An acknowledgement conflicting with the query may indicate delayed provider work.
            Result.failure(DocumentStateUnknownException())
        } else {
            Result.failure(DocumentOperationUnchangedException(
                deleted.exceptionOrNull() ?: UnsupportedOperationException("文件提供方拒绝永久删除"),
            ))
        }
    }
}

private fun isAospAbsentSignal(error: Exception): Boolean =
    error is java.io.FileNotFoundException || error is IllegalArgumentException

private fun corroboratedSafAbsence(
    direct: DirectDocumentQueryResult,
    parentQuery: () -> DocumentPresence,
): DocumentPresence {
    when (direct) {
        is DirectDocumentQueryResult.Failed -> {
            if (direct.cause is SecurityException || !isAospAbsentSignal(direct.cause)) {
                return DocumentPresence.UNKNOWN
            }
        }
        is DirectDocumentQueryResult.Resolved -> when (direct.presence) {
            DocumentPresence.PRESENT -> return DocumentPresence.PRESENT
            DocumentPresence.MISSING, DocumentPresence.UNKNOWN -> Unit
        }
    }
    return try {
        parentQuery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DocumentPresence.UNKNOWN
    }
}

/**
 * Strict SAF deletion state machine. Parent absence is accepted only after this provider returned
 * true, or after that acknowledgement was persisted by the caller. This prevents a file moved out
 * of its old parent from being mistaken for a successful permanent deletion.
 */
internal fun deleteSafDocumentVerified(
    deletePreviouslyAcknowledged: Boolean,
    directQuery: () -> DirectDocumentQueryResult,
    parentQuery: () -> DocumentPresence,
    delete: () -> Boolean,
): Result<VerifiedDeleteOutcome> {
    if (deletePreviouslyAcknowledged) {
        return if (corroboratedSafAbsence(directQuery(), parentQuery) == DocumentPresence.MISSING) {
            Result.success(VerifiedDeleteOutcome(providerAcknowledged = true))
        } else {
            Result.failure(DocumentStateUnknownException())
        }
    }

    val before = directQuery()
    if (before is DirectDocumentQueryResult.Failed &&
        (before.cause is SecurityException || !isAospAbsentSignal(before.cause))
    ) {
        return Result.failure(DocumentDeleteNotAttemptedException(before.cause))
    }

    val deleted = try {
        delete()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return Result.failure(DocumentStateUnknownException(error))
    }
    val after = directQuery()
    if (deleted) {
        return Result.success(VerifiedDeleteOutcome(
            providerAcknowledged = true,
            verifiedAbsent = corroboratedSafAbsence(after, parentQuery) == DocumentPresence.MISSING,
        ))
    }
    return when (after) {
        is DirectDocumentQueryResult.Resolved -> when (after.presence) {
            DocumentPresence.PRESENT -> Result.failure(DocumentOperationUnchangedException(
                UnsupportedOperationException("文件提供方拒绝永久删除"),
            ))
            DocumentPresence.MISSING, DocumentPresence.UNKNOWN ->
                Result.failure(DocumentStateUnknownException())
        }
        is DirectDocumentQueryResult.Failed -> Result.failure(DocumentStateUnknownException(after.cause))
    }
}
