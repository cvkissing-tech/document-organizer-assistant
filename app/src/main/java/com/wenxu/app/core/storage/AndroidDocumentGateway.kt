package com.wenxu.app.core.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.wenxu.app.core.model.DiscoveredDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest

class AndroidDocumentGateway(
    context: Context,
) : DocumentGateway {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    override suspend fun persistTreePermission(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override fun hasReadPermission(treeUri: String): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == treeUri && permission.isReadPermission
        }

    override fun discover(
        treeUri: String,
        batchSize: Int,
    ): Flow<List<DiscoveredDocument>> = flow {
        require(batchSize > 0) { "batchSize 必须大于 0" }
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))
            ?: throw FileNotFoundException("无法读取所选文件夹")
        val batch = ArrayList<DiscoveredDocument>(batchSize)

        DocumentTreeWalker().walk(DocumentFileTreeNode(root)).forEach { node ->
            val documentNode = node as DocumentFileTreeNode
            val document = documentNode.documentFile
            batch += DiscoveredDocument(
                uri = documentNode.uri,
                parentUri = requireNotNull(documentNode.parentUri),
                displayName = documentNode.name,
                mimeType = document.type.orEmpty(),
                sizeBytes = document.length().coerceAtLeast(0),
                modifiedAt = document.lastModified().coerceAtLeast(0),
            )
            if (batch.size == batchSize) {
                emit(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)

    override suspend fun openInput(uri: String) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(Uri.parse(uri))
            ?: throw FileNotFoundException("无法读取文档：$uri")
    }

    override suspend fun moveToTrash(
        sourceTreeUri: String,
        documentUri: String,
        sourceParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = withContext(Dispatchers.IO) {
        runDocumentMutation { mutationStarted ->
            val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(sourceTreeUri))
                ?: throw FileNotFoundException("无法访问扫描位置")
            val trashDirectory = tree.findFile(DocumentTreeWalker.TRASH_DIRECTORY_NAME)
                ?: tree.createDirectory(DocumentTreeWalker.TRASH_DIRECTORY_NAME)
                ?: throw UnsupportedOperationException("此位置不支持创建回收站")

            val source = Uri.parse(documentUri)
            val sourceParent = Uri.parse(sourceParentUri)
            val moved = tryNativeMove(source, sourceParent, trashDirectory.uri, mutationStarted)
            val trashed = moved ?: run {
                mutationStarted()
                copyVerified(
                    source = source,
                    targetDirectory = trashDirectory,
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSize = expectedSize,
                ).also {
                    if (!deleteUri(source)) {
                        deleteUri(it)
                        throw UnsupportedOperationException("此位置不支持安全删除")
                    }
                }
            }
            TrashLocation(
                documentUri = trashed.toString(),
                parentUri = trashDirectory.uri.toString(),
            )
        }
    }

    override suspend fun restoreFromTrash(
        trashedUri: String,
        trashedParentUri: String,
        targetParentUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = withContext(Dispatchers.IO) {
        runDocumentMutation { mutationStarted ->
            val trashed = Uri.parse(trashedUri)
            val requestedTarget = Uri.parse(targetParentUri)
            val targetDirectory = DocumentFile.fromTreeUri(appContext, requestedTarget)
                ?: DocumentFile.fromSingleUri(appContext, requestedTarget)
                ?: throw FileNotFoundException("无法访问恢复位置")
            val targetParent = targetDirectory.uri
            val moved = tryNativeMove(trashed, Uri.parse(trashedParentUri), targetParent, mutationStarted)
            val restored = moved ?: run {
                mutationStarted()
                copyVerified(
                    source = trashed,
                    targetDirectory = targetDirectory,
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSize = expectedSize,
                ).also {
                    if (!deleteUri(trashed)) {
                        deleteUri(it)
                        throw UnsupportedOperationException("无法安全移除回收站副本")
                    }
                }
            }
            TrashLocation(restored.toString(), targetParent.toString())
        }
    }

    override suspend fun deleteDocument(uri: String): Result<Unit> = withContext(Dispatchers.IO) {
        val documentUri = Uri.parse(uri)
        deleteDocumentIdempotently(
            query = { queryDocumentPresence(documentUri) },
            delete = { DocumentsContract.deleteDocument(contentResolver, documentUri) },
        )
    }

    override suspend fun deleteDocumentVerified(
        uri: String,
        sourceTreeUri: String,
        parentUri: String,
        deletePreviouslyAcknowledged: Boolean,
    ): Result<VerifiedDeleteOutcome> = withContext(Dispatchers.IO) {
        val documentUri = Uri.parse(uri)
        deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = deletePreviouslyAcknowledged,
            directQuery = {
                captureDirectDocumentPresence { queryDocumentPresence(documentUri) }
            },
            parentQuery = {
                queryParentDocumentPresence(
                    sourceTreeUri = sourceTreeUri,
                    parentUri = parentUri,
                    documentUri = documentUri,
                )
            },
            delete = { DocumentsContract.deleteDocument(contentResolver, documentUri) },
        )
    }

    private fun queryDocumentPresence(uri: Uri): DocumentPresence {
        val expectedId = DocumentsContract.getDocumentId(uri)
        val snapshot = contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val rowExists = cursor.moveToFirst()
            DocumentQuerySnapshot(
                hasDocumentIdColumn = idColumn >= 0,
                rowExists = rowExists,
                matchesRequestedId = rowExists && idColumn >= 0 && !cursor.isNull(idColumn) && cursor.getString(idColumn) == expectedId,
                incomplete = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR),
            )
        }
        return documentPresence(snapshot)
    }

    private fun queryParentDocumentPresence(
        sourceTreeUri: String,
        parentUri: String,
        documentUri: Uri,
    ): DocumentPresence = authorizedSafParentPresence(
        sourceTreeUri = sourceTreeUri,
        parentUri = parentUri,
        documentUri = documentUri.toString(),
    ) parentQuery@{
        if (!hasReadPermission(sourceTreeUri)) return@parentQuery DocumentPresence.UNKNOWN
        val expectedDocumentId = DocumentsContract.getDocumentId(documentUri)
        val parentDocumentId = DocumentsContract.getDocumentId(Uri.parse(parentUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            Uri.parse(sourceTreeUri),
            parentDocumentId,
        )
        val snapshot = contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val documentIds = buildList {
                if (idColumn >= 0) {
                    while (cursor.moveToNext()) {
                        add(if (cursor.isNull(idColumn)) null else cursor.getString(idColumn))
                    }
                }
            }
            parentDocumentQuerySnapshot(
                hasDocumentIdColumn = idColumn >= 0,
                documentIds = documentIds,
                providerIncomplete = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR),
            )
        }
        parentDocumentPresence(snapshot, expectedDocumentId)
    }

    override suspend fun moveToFolder(
        documentUri: String,
        sourceParentUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = DocumentFile.fromTreeUri(appContext, Uri.parse(targetTreeUri))
                ?: throw FileNotFoundException("无法访问目标文件夹")
            val source = Uri.parse(documentUri)
            val moved = tryNativeMove(source, Uri.parse(sourceParentUri), targetDirectory.uri)
            val target = moved ?: copyVerified(
                source = source,
                targetDirectory = targetDirectory,
                displayName = displayName,
                mimeType = mimeType,
                expectedSize = expectedSize,
            ).also {
                if (!deleteUri(source)) {
                    deleteUri(it)
                    throw UnsupportedOperationException("原位置不支持安全移动")
                }
            }
            TrashLocation(target.toString(), targetDirectory.uri.toString())
        }
    }

    override suspend fun copyToFolderVerified(
        sourceUri: String,
        targetTreeUri: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Result<TrashLocation> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = DocumentFile.fromTreeUri(appContext, Uri.parse(targetTreeUri))
                ?: throw FileNotFoundException("无法访问文档收件箱")
            val target = copyVerified(
                source = Uri.parse(sourceUri),
                targetDirectory = targetDirectory,
                displayName = displayName,
                mimeType = mimeType,
                expectedSize = expectedSize,
            )
            TrashLocation(target.toString(), targetDirectory.uri.toString())
        }
    }

    private fun tryNativeMove(
        source: Uri,
        sourceParent: Uri,
        targetParent: Uri,
        mutationStarted: () -> Unit = {},
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !supportsMove(source)) return null
        mutationStarted()
        return DocumentsContract.moveDocument(contentResolver, source, sourceParent, targetParent)
    }

    private fun supportsMove(uri: Uri): Boolean {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val flags = cursor.getInt(0)
            flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0
        } ?: false
    }

    private fun copyVerified(
        source: Uri,
        targetDirectory: DocumentFile,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
    ): Uri {
        val target = targetDirectory.createFile(mimeType.ifBlank { "application/octet-stream" }, displayName)
            ?: throw UnsupportedOperationException("此位置不支持创建回收站副本")
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法读取原文件" }
                contentResolver.openOutputStream(target.uri, "w").use { output ->
                    requireNotNull(output) { "无法写入回收站" }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sourceDigest.update(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                }
            }
            if (expectedSize > 0 && copied != expectedSize) {
                throw IllegalStateException("复制大小校验失败")
            }
            val targetDigest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(target.uri).use { input ->
                requireNotNull(input) { "无法校验回收站副本" }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    targetDigest.update(buffer, 0, read)
                }
            }
            if (!sourceDigest.digest().contentEquals(targetDigest.digest())) {
                throw IllegalStateException("复制内容校验失败")
            }
            return target.uri
        } catch (error: Throwable) {
            deleteUri(target.uri)
            throw error
        }
    }

    private fun deleteUri(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(contentResolver, uri)
    }.getOrElse {
        DocumentFile.fromSingleUri(appContext, uri)?.delete() ?: false
    }

}

private class DocumentFileTreeNode(
    val documentFile: DocumentFile,
    val parentUri: String? = null,
) : TreeNode {
    override val uri: String = documentFile.uri.toString()
    override val name: String = documentFile.name?.trim().orEmpty()
    override val isDirectory: Boolean get() = documentFile.isDirectory
    override val isFile: Boolean get() = documentFile.isFile

    override fun children(): List<TreeNode> = documentFile.listFiles().map { child ->
        DocumentFileTreeNode(
            documentFile = child,
            parentUri = uri,
        )
    }
}
