package com.wenxu.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TrashStorageKind {
    APP_TRASH,
    MEDIA_STORE,
}

@Entity(
    tableName = "trash_records",
    indices = [Index(value = ["documentId"], unique = true), Index("expiresAt")],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrashRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val originalUri: String,
    val trashedUri: String,
    val trashedParentUri: String,
    val originalParentUri: String,
    val deletedAt: Long,
    val expiresAt: Long,
    @ColumnInfo(defaultValue = "'APP_TRASH'")
    val storageKind: TrashStorageKind = TrashStorageKind.APP_TRASH,
)

@Entity(
    tableName = "trash_category_snapshots",
    primaryKeys = ["trashRecordId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = TrashRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["trashRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrashCategorySnapshotEntity(
    val trashRecordId: Long,
    val categoryId: Long,
    val categoryName: String,
)
