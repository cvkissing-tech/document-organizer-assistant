package com.wenxu.app.core.index

import com.wenxu.app.core.model.DiscoveredDocument

data class DocumentPage(
    val documents: List<DiscoveredDocument>,
    val nextCursor: Long?,
    val isComplete: Boolean,
    val checkedFiles: Int,
    val directoryPaths: Set<String>,
)

interface SharedDocumentPageSource {
    suspend fun readPage(afterId: Long?, limit: Int = 200): DocumentPage
}
