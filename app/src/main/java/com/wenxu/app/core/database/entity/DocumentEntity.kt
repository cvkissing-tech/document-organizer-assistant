package com.wenxu.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wenxu.app.core.model.DocumentIndexStatus

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("sourceId"),
        Index("sizeBytes"),
        Index("normalizedName", "extension"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScanSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val normalizedName: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long? = null,
    val sourceId: Long,
    val parentUri: String,
    val contentHash: String? = null,
    val hashBasisSize: Long? = null,
    val hashBasisModifiedAt: Long? = null,
    val indexStatus: DocumentIndexStatus = DocumentIndexStatus.ACTIVE,
    val lastSeenScanId: String,
)
