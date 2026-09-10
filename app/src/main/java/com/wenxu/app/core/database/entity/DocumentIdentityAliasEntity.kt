package com.wenxu.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A retired scan identity that now resolves to the surviving document row. */
@Entity(
    tableName = "document_identity_aliases",
    indices = [Index("canonicalDocumentId")],
)
data class DocumentIdentityAliasEntity(
    @PrimaryKey val oldDocumentId: Long,
    val canonicalDocumentId: Long,
)
