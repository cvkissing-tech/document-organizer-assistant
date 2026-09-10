package com.wenxu.app.core.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import com.wenxu.app.core.database.WenxuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PdfSession : AutoCloseable {
    val pageCount: Int
    suspend fun render(pageIndex: Int, targetWidth: Int): Bitmap
}

data class OpenedPdfDocument(
    val title: String,
    val uri: String,
    val extension: String,
    val session: PdfSession,
)

interface PdfReaderRepository {
    suspend fun open(documentId: Long): Result<OpenedPdfDocument>
    suspend fun markOpened(documentId: Long)
}

class AndroidPdfReaderRepository(
    private val resolver: ContentResolver,
    database: WenxuDatabase,
) : PdfReaderRepository {
    private val documentDao = database.documentDao()

    override suspend fun open(documentId: Long): Result<OpenedPdfDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val document = requireNotNull(documentDao.findById(documentId)) { "找不到这份文件" }
            require(document.extension.equals("pdf", ignoreCase = true)) { "这不是 PDF 文件" }
            val descriptor = resolver.openFileDescriptor(Uri.parse(document.uri), "r")
                ?: throw IllegalStateException("找不到这份文件")
            OpenedPdfDocument(
                title = document.displayName,
                uri = document.uri,
                extension = document.extension,
                session = AndroidPdfSession(descriptor),
            )
        }
    }

    override suspend fun markOpened(documentId: Long) {
        documentDao.updateLastOpened(documentId, System.currentTimeMillis())
    }
}

private class AndroidPdfSession(
    private val descriptor: ParcelFileDescriptor,
) : PdfSession {
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    override val pageCount: Int = renderer.pageCount

    override suspend fun render(pageIndex: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(pageIndex in 0 until pageCount) { "PDF 页码超出范围" }
            renderer.openPage(pageIndex).use { page ->
                val width = targetWidth.coerceIn(480, 2400)
                val height = (width.toFloat() * page.height / page.width)
                    .toInt()
                    .coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap,
                        Rect(0, 0, width, height),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                }
            }
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
}
