package com.wenxu.app.core.model

import com.wenxu.app.core.database.entity.DocumentEntity

fun DocumentEntity.toRecord(): DocumentRecord = DocumentRecord(
    id = id,
    uri = uri,
    displayName = displayName,
    extension = extension,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
    sourceId = sourceId,
    parentUri = parentUri,
)
