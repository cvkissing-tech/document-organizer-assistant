package com.wenxu.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wenxu.app.core.database.entity.CategoryEntity
import com.wenxu.app.core.database.entity.DocumentCategoryEntity
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.CategorySummary
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun findById(categoryId: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Query(
        """
        SELECT c.id AS id, c.name AS name, COUNT(d.id) AS documentCount
        FROM categories AS c
        LEFT JOIN document_categories AS dc ON dc.categoryId = c.id
        LEFT JOIN documents AS d ON d.id = dc.documentId AND d.indexStatus = 'ACTIVE'
        GROUP BY c.id
        ORDER BY c.sortOrder, c.id
        """,
    )
    fun observeSummaries(): Flow<List<CategorySummary>>

    @Query("SELECT DISTINCT documentId FROM document_categories ORDER BY documentId")
    fun observeCategorizedDocumentIds(): Flow<List<Long>>

    @Query(
        """
        SELECT d.* FROM documents AS d
        INNER JOIN document_categories AS dc ON dc.documentId = d.id
        WHERE dc.categoryId = :categoryId AND d.indexStatus = 'ACTIVE'
        ORDER BY d.modifiedAt DESC, d.id
        """,
    )
    fun observeDocuments(categoryId: Long): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT d.* FROM documents AS d
        WHERE d.indexStatus = 'ACTIVE'
          AND NOT EXISTS (
              SELECT 1 FROM document_categories AS dc WHERE dc.documentId = d.id
          )
        ORDER BY d.modifiedAt DESC, d.id
        """,
    )
    fun observeUnclassifiedDocuments(): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(link: DocumentCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(links: List<DocumentCategoryEntity>): List<Long>

    @Query("SELECT * FROM document_categories ORDER BY categoryId, documentId")
    fun observeDocumentCategories(): Flow<List<DocumentCategoryEntity>>

    @Delete
    suspend fun remove(link: DocumentCategoryEntity)

    @Query("SELECT categoryId FROM document_categories WHERE documentId = :documentId ORDER BY categoryId")
    fun observeCategoryIds(documentId: Long): Flow<List<Long>>

    @Query(
        """
        SELECT c.* FROM categories AS c
        INNER JOIN document_categories AS dc ON dc.categoryId = c.id
        WHERE dc.documentId = :documentId
        ORDER BY c.sortOrder, c.id
        """,
    )
    suspend fun listForDocument(documentId: Long): List<CategoryEntity>

    @Query("DELETE FROM document_categories WHERE documentId = :documentId")
    suspend fun clearDocumentCategories(documentId: Long)

    @Query(
        """
        INSERT OR IGNORE INTO document_categories(documentId, categoryId)
        SELECT :canonicalDocumentId, categoryId
        FROM document_categories
        WHERE documentId = :collisionDocumentId
        """,
    )
    suspend fun mergeDocumentCategories(canonicalDocumentId: Long, collisionDocumentId: Long)
}
