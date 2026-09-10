package com.wenxu.app

import com.wenxu.app.core.database.entity.DocumentEntity

fun testDocumentEntity(
    id: Long = 0,
    uri: String = "content://test/document.pdf",
    displayName: String = "document.pdf",
    sourceId: Long = 1,
) = DocumentEntity(
    id = id,
    uri = uri,
    displayName = displayName,
    normalizedName = displayName.substringBeforeLast('.').lowercase(),
    mimeType = "application/pdf",
    extension = "pdf",
    sizeBytes = 3,
    modifiedAt = 1_000,
    sourceId = sourceId,
    parentUri = "content://test/tree",
    lastSeenScanId = "scan-1",
)
