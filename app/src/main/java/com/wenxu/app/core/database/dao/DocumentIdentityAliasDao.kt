package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity

@Dao
interface DocumentIdentityAliasDao {
    @Upsert
    suspend fun upsert(alias: DocumentIdentityAliasEntity)

    @Query("SELECT * FROM document_identity_aliases ORDER BY oldDocumentId")
    suspend fun listAll(): List<DocumentIdentityAliasEntity>

    @Query("DELETE FROM document_identity_aliases WHERE oldDocumentId = :oldDocumentId")
    suspend fun deleteByOldId(oldDocumentId: Long)
}
