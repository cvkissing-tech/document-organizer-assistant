package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.ExactDuplicateMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateSetEntity
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelationDao {
    @Upsert
    suspend fun upsertNameGroup(group: NameSimilarityGroupEntity): Long

    @Query("SELECT * FROM name_similarity_groups")
    suspend fun listNameGroups(): List<NameSimilarityGroupEntity>

    @Query("DELETE FROM name_similarity_groups WHERE id IN (:groupIds)")
    suspend fun deleteNameGroups(groupIds: List<Long>)

    @Query("DELETE FROM name_similarity_members WHERE groupId = :groupId")
    suspend fun clearNameMembers(groupId: Long)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM name_similarity_members WHERE groupId = :groupId AND documentId = :documentId)",
    )
    suspend fun isNameGroupMember(groupId: Long, documentId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNameMembers(members: List<NameSimilarityMemberEntity>)

    @Query(
        """
        SELECT g.* FROM name_similarity_groups AS g
        WHERE (
            SELECT COUNT(*) FROM name_similarity_members AS m
            INNER JOIN documents AS d ON d.id = m.documentId
            WHERE m.groupId = g.id AND d.indexStatus = 'ACTIVE'
        ) >= 2
        ORDER BY g.updatedAt DESC
        """,
    )
    fun observeNameGroups(): Flow<List<NameSimilarityGroupEntity>>

    @Query("SELECT * FROM name_similarity_members ORDER BY groupId, documentId")
    fun observeNameMembers(): Flow<List<NameSimilarityMemberEntity>>

    @Query("SELECT * FROM name_similarity_members ORDER BY groupId, documentId")
    suspend fun listNameMembers(): List<NameSimilarityMemberEntity>

    @Query("SELECT documentId FROM name_similarity_members WHERE groupId = :groupId ORDER BY documentId")
    suspend fun listNameMemberIds(groupId: Long): List<Long>

    @Query(
        """
        SELECT m.documentId FROM name_similarity_members AS m
        INNER JOIN documents AS d ON d.id = m.documentId
        WHERE m.groupId = :groupId AND d.indexStatus = 'ACTIVE'
        ORDER BY m.documentId
        """,
    )
    suspend fun listActiveNameMemberIds(groupId: Long): List<Long>

    @Query("UPDATE name_similarity_groups SET confirmedDocumentId = :documentId WHERE id = :groupId")
    suspend fun confirmCurrent(groupId: Long, documentId: Long)

    @Query(
        "UPDATE name_similarity_groups SET confirmedDocumentId = :canonicalDocumentId " +
            "WHERE confirmedDocumentId = :collisionDocumentId",
    )
    suspend fun mergeConfirmedDocumentReferences(canonicalDocumentId: Long, collisionDocumentId: Long)

    @Query(
        "UPDATE name_similarity_groups SET referenceDocumentId = :canonicalDocumentId " +
            "WHERE referenceDocumentId = :collisionDocumentId",
    )
    suspend fun mergeReferenceDocumentReferences(canonicalDocumentId: Long, collisionDocumentId: Long)

    @Query(
        """
        INSERT OR IGNORE INTO name_similarity_members(
            groupId, documentId, differenceSegments, score, evidenceCodes
        )
        SELECT groupId, :canonicalDocumentId, differenceSegments, score, evidenceCodes
        FROM name_similarity_members
        WHERE documentId = :collisionDocumentId
        """,
    )
    suspend fun mergeNameMembers(canonicalDocumentId: Long, collisionDocumentId: Long)

    @Query("DELETE FROM name_similarity_groups")
    suspend fun clearNameGroups()

    @Insert
    suspend fun insertExactSet(set: ExactDuplicateSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExactMembers(members: List<ExactDuplicateMemberEntity>)

    @Query(
        """
        INSERT OR IGNORE INTO exact_duplicate_members(setId, documentId)
        SELECT setId, :canonicalDocumentId
        FROM exact_duplicate_members
        WHERE documentId = :collisionDocumentId
        """,
    )
    suspend fun mergeExactMembers(canonicalDocumentId: Long, collisionDocumentId: Long)

    @Query(
        """
        SELECT s.* FROM exact_duplicate_sets AS s
        WHERE (
            SELECT COUNT(*) FROM exact_duplicate_members AS m
            INNER JOIN documents AS d ON d.id = m.documentId
            WHERE m.setId = s.id AND d.indexStatus = 'ACTIVE'
        ) >= 2
        ORDER BY s.updatedAt DESC
        """,
    )
    fun observeExactSets(): Flow<List<ExactDuplicateSetEntity>>

    @Query("SELECT * FROM exact_duplicate_members ORDER BY setId, documentId")
    fun observeExactMembers(): Flow<List<ExactDuplicateMemberEntity>>

    @Query("SELECT * FROM exact_duplicate_members WHERE documentId IN (:documentIds)")
    suspend fun listExactMembersForDocuments(documentIds: List<Long>): List<ExactDuplicateMemberEntity>

    @Query("DELETE FROM exact_duplicate_sets")
    suspend fun clearExactSets()

    @Query(
        """
        SELECT m.documentId FROM name_similarity_members AS m
        INNER JOIN documents AS d ON d.id = m.documentId AND d.indexStatus = 'ACTIVE'
        UNION
        SELECT m.documentId FROM exact_duplicate_members AS m
        INNER JOIN documents AS d ON d.id = m.documentId AND d.indexStatus = 'ACTIVE'
        """,
    )
    fun observeRelatedDocumentIds(): Flow<List<Long>>
}
