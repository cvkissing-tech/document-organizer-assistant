package com.wenxu.app.core.relations

import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.storage.DocumentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class ContentHasher(
    private val gateway: DocumentGateway,
) {
    suspend fun hash(document: DocumentEntity): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        gateway.openInput(document.uri).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
