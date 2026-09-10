package com.wenxu.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wenxu.app.core.model.NameSegment
import com.wenxu.app.core.relations.ConfidenceBand
import com.wenxu.app.core.relations.EvidenceCode

@Entity(
    tableName = "name_similarity_groups",
    indices = [
        Index(value = ["baseName", "extension"]),
        Index("confirmedDocumentId"),
        Index("referenceDocumentId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["confirmedDocumentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["referenceDocumentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class NameSimilarityGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseName: String,
    val extension: String,
    val confirmedDocumentId: Long? = null,
    val referenceDocumentId: Long? = null,
    @ColumnInfo(defaultValue = "'HIGH'")
    val confidenceBand: ConfidenceBand = ConfidenceBand.HIGH,
    @ColumnInfo(defaultValue = "1")
    val analysisVersion: Int = 1,
    @ColumnInfo(defaultValue = "'PENDING'")
    val reviewState: SimilarityReviewState = SimilarityReviewState.PENDING,
    val updatedAt: Long,
)

@Entity(
    tableName = "name_similarity_members",
    primaryKeys = ["groupId", "documentId"],
    indices = [Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = NameSimilarityGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NameSimilarityMemberEntity(
    val groupId: Long,
    val documentId: Long,
    val differenceSegments: List<NameSegment>,
    @ColumnInfo(defaultValue = "0")
    val score: Int = 0,
    @ColumnInfo(defaultValue = "'[]'")
    val evidenceCodes: Set<EvidenceCode> = emptySet(),
)

@Entity(
    tableName = "exact_duplicate_sets",
    indices = [Index(value = ["contentHash"], unique = true)],
)
data class ExactDuplicateSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentHash: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "exact_duplicate_members",
    primaryKeys = ["setId", "documentId"],
    indices = [Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = ExactDuplicateSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExactDuplicateMemberEntity(
    val setId: Long,
    val documentId: Long,
)
