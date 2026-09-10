package com.wenxu.app.core.relations

import androidx.room.withTransaction
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.dao.RelationDao
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.ExactDuplicateMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateSetEntity
import com.wenxu.app.core.model.DuplicateSet

data class ExactDuplicateGroup(
    val contentHash: String,
    val sizeBytes: Long,
    val memberIds: List<Long>,
)

interface ExactDuplicateStore {
    suspend fun activeCandidates(): List<DocumentEntity>
    suspend fun saveHash(documentId: Long, hash: String, sizeBytes: Long, modifiedAt: Long)
    suspend fun replaceGroups(groups: List<ExactDuplicateGroup>): List<DuplicateSet>
}

class RoomExactDuplicateStore(
    private val database: WenxuDatabase,
) : ExactDuplicateStore {
    private val documentDao = database.documentDao()
    private val relationDao = database.relationDao()

    override suspend fun activeCandidates(): List<DocumentEntity> =
        documentDao.activeWithPositiveSize()

    override suspend fun saveHash(
        documentId: Long,
        hash: String,
        sizeBytes: Long,
        modifiedAt: Long,
    ) {
        documentDao.updateContentHash(documentId, hash, sizeBytes, modifiedAt)
    }

    override suspend fun replaceGroups(groups: List<ExactDuplicateGroup>): List<DuplicateSet> =
        database.withTransaction {
            replaceExactGroupsInTransaction(relationDao, groups, System.currentTimeMillis())
        }
}

internal suspend fun replaceExactGroupsInTransaction(
    relationDao: RelationDao,
    groups: List<ExactDuplicateGroup>,
    updatedAt: Long,
): List<DuplicateSet> {
    relationDao.clearExactSets()
    return groups.map { group ->
        group.copy(memberIds = group.memberIds.distinct().sorted())
    }.filter { group ->
        group.memberIds.size > 1
    }.map { group ->
        val setId = relationDao.insertExactSet(
            ExactDuplicateSetEntity(
                contentHash = group.contentHash,
                sizeBytes = group.sizeBytes,
                updatedAt = updatedAt,
            ),
        )
        relationDao.insertExactMembers(
            group.memberIds.map { documentId ->
                ExactDuplicateMemberEntity(setId = setId, documentId = documentId)
            },
        )
        DuplicateSet(id = setId, memberIds = group.memberIds)
    }
}
