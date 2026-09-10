package com.wenxu.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wenxu.app.core.model.SourcePermissionState

enum class ScanSourceKind {
    TREE,
    SHARED_STORAGE,
    INBOX,
}

@Entity(
    tableName = "scan_sources",
    indices = [Index(value = ["treeUri"], unique = true)],
)
data class ScanSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    @ColumnInfo(defaultValue = "'TREE'")
    val sourceKind: ScanSourceKind = ScanSourceKind.TREE,
    val permissionState: SourcePermissionState = SourcePermissionState.ACTIVE,
    val lastScanAt: Long? = null,
    val sortOrder: Int = 0,
)
