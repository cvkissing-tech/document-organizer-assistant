package com.wenxu.app.core.importing

data class IncomingDocument(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ImportReport(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<String> = emptyList(),
    val failedUris: List<String> = emptyList(),
)
