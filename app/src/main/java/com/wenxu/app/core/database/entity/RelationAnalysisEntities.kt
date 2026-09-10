package com.wenxu.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wenxu.app.core.relations.ConfidenceBand
import com.wenxu.app.core.relations.EvidenceCode

enum class FingerprintExtractStatus {
    SUCCESS,
    UNSUPPORTED,
    ENCRYPTED,
    READ_FAILED,
}

enum class SimilarityFeedbackDecision {
    ALLOW,
    BLOCK,
}

enum class RelationAnalysisStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class RelationAnalysisPhase {
    EXACT_DUPLICATES,
    NAME_CANDIDATES,
    CONTENT_VERIFICATION,
    SAVING,
}

enum class SimilarityReviewState {
    PENDING,
    REVIEWED,
}

@Entity(
    tableName = "document_fingerprints",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentFingerprintEntity(
    @PrimaryKey val documentId: Long,
    val algorithmVersion: Int,
    val textMinHash: ByteArray,
    val normalizedTextLength: Int,
    val structureSignature: String,
    val extractStatus: FingerprintExtractStatus,
    val basisSize: Long,
    val basisModifiedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "similarity_candidates",
    primaryKeys = ["documentAId", "documentBId"],
    indices = [
        Index("documentBId"),
        Index("confidenceBand"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentAId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentBId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SimilarityCandidateEntity(
    val documentAId: Long,
    val documentBId: Long,
    val nameScore: Double,
    val contentScore: Double?,
    val metadataScore: Int,
    val confidenceBand: ConfidenceBand,
    val evidenceCodes: Set<EvidenceCode>,
    val analysisVersion: Int,
    val updatedAt: Long,
) {
    init {
        require(documentAId < documentBId) {
            "候选文件对必须按较小文档ID在前保存"
        }
    }

    companion object {
        fun normalized(
            firstDocumentId: Long,
            secondDocumentId: Long,
            nameScore: Double,
            contentScore: Double?,
            metadataScore: Int,
            confidenceBand: ConfidenceBand,
            evidenceCodes: Set<EvidenceCode>,
            analysisVersion: Int,
            updatedAt: Long,
        ): SimilarityCandidateEntity {
            require(firstDocumentId != secondDocumentId) {
                "候选文件对不能引用同一文档两次"
            }
            val documentAId = minOf(firstDocumentId, secondDocumentId)
            val documentBId = maxOf(firstDocumentId, secondDocumentId)
            return SimilarityCandidateEntity(
                documentAId = documentAId,
                documentBId = documentBId,
                nameScore = nameScore,
                contentScore = contentScore,
                metadataScore = metadataScore,
                confidenceBand = confidenceBand,
                evidenceCodes = evidenceCodes,
                analysisVersion = analysisVersion,
                updatedAt = updatedAt,
            )
        }
    }
}

@Entity(
    tableName = "similarity_feedback",
    primaryKeys = ["documentAId", "documentBId"],
    indices = [Index("documentBId")],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentAId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentBId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SimilarityFeedbackEntity(
    val documentAId: Long,
    val documentBId: Long,
    val decision: SimilarityFeedbackDecision,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(documentAId < documentBId) {
            "反馈文件对必须按较小文档ID在前保存"
        }
    }

    companion object {
        fun normalized(
            firstDocumentId: Long,
            secondDocumentId: Long,
            decision: SimilarityFeedbackDecision,
            createdAt: Long,
            updatedAt: Long,
        ): SimilarityFeedbackEntity {
            require(firstDocumentId != secondDocumentId) {
                "反馈文件对不能引用同一文档两次"
            }
            return SimilarityFeedbackEntity(
                documentAId = minOf(firstDocumentId, secondDocumentId),
                documentBId = maxOf(firstDocumentId, secondDocumentId),
                decision = decision,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}

@Entity(tableName = "ignored_similarity_groups")
data class IgnoredSimilarityGroupEntity(
    @PrimaryKey val groupFingerprint: String,
    val ignoredAt: Long,
    @ColumnInfo(defaultValue = "'[]'")
    val memberIds: Set<Long> = emptySet(),
)

@Entity(tableName = "relation_analysis_state")
data class RelationAnalysisStateEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "1")
    val id: Int = SINGLETON_ID,
    val status: RelationAnalysisStatus,
    val phase: RelationAnalysisPhase,
    val processedCount: Int,
    val candidateCount: Int,
    val failedCount: Int,
    val generation: Long,
    val errorMessage: String? = null,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
