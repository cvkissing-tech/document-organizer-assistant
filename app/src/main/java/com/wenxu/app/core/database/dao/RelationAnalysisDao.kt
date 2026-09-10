package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelationAnalysisDao {
    @Upsert
    suspend fun upsertFingerprint(fingerprint: DocumentFingerprintEntity)

    @Upsert
    suspend fun upsertFingerprints(fingerprints: List<DocumentFingerprintEntity>)

    @Query("SELECT * FROM document_fingerprints WHERE documentId = :documentId")
    suspend fun findFingerprint(documentId: Long): DocumentFingerprintEntity?

    @Upsert
    suspend fun upsertCandidates(candidates: List<SimilarityCandidateEntity>)

    @Query("SELECT * FROM similarity_candidates ORDER BY documentAId, documentBId")
    suspend fun listCandidates(): List<SimilarityCandidateEntity>

    @Query("DELETE FROM similarity_candidates")
    suspend fun clearCandidates()

    @Upsert
    suspend fun upsertFeedback(feedback: SimilarityFeedbackEntity)

    @Upsert
    suspend fun upsertFeedback(feedback: List<SimilarityFeedbackEntity>)

    @Query("SELECT * FROM similarity_feedback ORDER BY documentAId, documentBId")
    suspend fun listFeedback(): List<SimilarityFeedbackEntity>

    @Query(
        """
        SELECT * FROM similarity_feedback
        WHERE documentAId IN (:documentIds) AND documentBId IN (:documentIds)
        ORDER BY documentAId, documentBId
        """,
    )
    suspend fun listFeedbackWithin(documentIds: List<Long>): List<SimilarityFeedbackEntity>

    @Query(
        """
        SELECT * FROM similarity_feedback
        WHERE documentAId IN (:documentIds) OR documentBId IN (:documentIds)
        ORDER BY documentAId, documentBId
        """,
    )
    suspend fun listFeedbackTouching(documentIds: List<Long>): List<SimilarityFeedbackEntity>

    @Upsert
    suspend fun upsertIgnoredGroup(group: IgnoredSimilarityGroupEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_similarity_groups WHERE groupFingerprint = :fingerprint)")
    suspend fun isGroupIgnored(fingerprint: String): Boolean

    @Query("SELECT groupFingerprint FROM ignored_similarity_groups")
    suspend fun listIgnoredGroupFingerprints(): List<String>

    @Query("SELECT * FROM ignored_similarity_groups ORDER BY groupFingerprint")
    suspend fun listIgnoredGroups(): List<IgnoredSimilarityGroupEntity>

    @Query("DELETE FROM ignored_similarity_groups WHERE groupFingerprint = :fingerprint")
    suspend fun deleteIgnoredGroup(fingerprint: String)

    @Query("DELETE FROM ignored_similarity_groups WHERE groupFingerprint IN (:fingerprints)")
    suspend fun deleteIgnoredGroups(fingerprints: List<String>)

    @Upsert
    suspend fun upsertState(state: RelationAnalysisStateEntity)

    @Query("SELECT * FROM relation_analysis_state WHERE id = 1")
    fun observeState(): Flow<RelationAnalysisStateEntity?>

    @Query("SELECT * FROM relation_analysis_state WHERE id = 1")
    suspend fun getState(): RelationAnalysisStateEntity?

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = :status,
            phase = :phase,
            processedCount = :processedCount,
            candidateCount = :candidateCount,
            failedCount = :failedCount,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = 1 AND generation = :generation
        """,
    )
    suspend fun updateStateIfGeneration(
        generation: Long,
        status: RelationAnalysisStatus,
        phase: RelationAnalysisPhase,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
        errorMessage: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = 'RUNNING',
            phase = 'EXACT_DUPLICATES',
            processedCount = 0,
            candidateCount = 0,
            failedCount = 0,
            errorMessage = :runMarker,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun claimRun(
        generation: Long,
        runMarker: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM relation_analysis_state
            WHERE id = 1
              AND generation = :generation
              AND status = 'RUNNING'
              AND errorMessage = :runMarker
        )
        """,
    )
    suspend fun isCurrentRun(generation: Long, runMarker: String): Boolean

    @Query(
        """
        UPDATE relation_analysis_state
        SET phase = :phase,
            processedCount = :processedCount,
            candidateCount = :candidateCount,
            failedCount = :failedCount,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status = 'RUNNING'
          AND errorMessage = :runMarker
        """,
    )
    suspend fun updatePhaseIfCurrentRun(
        generation: Long,
        runMarker: String,
        phase: RelationAnalysisPhase,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = 'COMPLETED',
            phase = 'SAVING',
            processedCount = :processedCount,
            candidateCount = :candidateCount,
            failedCount = :failedCount,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status = 'RUNNING'
          AND errorMessage = :runMarker
        """,
    )
    suspend fun completeIfCurrentRun(
        generation: Long,
        runMarker: String,
        processedCount: Int,
        candidateCount: Int,
        failedCount: Int,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = 'FAILED',
            failedCount = :failedCount,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status = 'RUNNING'
          AND errorMessage = :runMarker
        """,
    )
    suspend fun failIfCurrentRun(
        generation: Long,
        runMarker: String,
        failedCount: Int,
        errorMessage: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = 'QUEUED',
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status = 'RUNNING'
          AND errorMessage = :runMarker
        """,
    )
    suspend fun cancelIfCurrentRun(
        generation: Long,
        runMarker: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE relation_analysis_state
        SET status = 'QUEUED',
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = 1
          AND generation = :generation
          AND status = 'RUNNING'
          AND errorMessage = :runMarker
        """,
    )
    suspend fun queueCurrentRunForRetry(
        generation: Long,
        runMarker: String,
        errorMessage: String,
        updatedAt: Long,
    ): Int
}
