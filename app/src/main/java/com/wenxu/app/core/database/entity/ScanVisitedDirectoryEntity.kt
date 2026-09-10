package com.wenxu.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "scan_visited_directories",
    primaryKeys = ["sessionId", "directoryPath"],
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class ScanVisitedDirectoryEntity(
    val sessionId: String,
    val directoryPath: String,
)
