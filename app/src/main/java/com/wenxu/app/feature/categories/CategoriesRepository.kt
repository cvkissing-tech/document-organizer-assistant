package com.wenxu.app.feature.categories

import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.CategoryEntity
import com.wenxu.app.core.database.entity.DocumentCategoryEntity
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.toRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class CategoriesData(
    val categories: List<CategorySummary> = emptyList(),
    val unclassifiedCount: Int = 0,
)

interface CategoriesRepository {
    val data: Flow<CategoriesData>
    fun observeDocuments(categoryId: Long?): Flow<List<DocumentRecord>>
    fun observeAllDocuments(): Flow<List<DocumentRecord>>
    suspend fun create(name: String): Result<Long>
    suspend fun rename(categoryId: Long, name: String): Result<Unit>
    suspend fun delete(categoryId: Long): Result<Unit>
    suspend fun markOpened(documentId: Long)
    suspend fun addToCategory(documentId: Long, categoryId: Long)
    suspend fun addDocumentsToCategory(documentIds: Set<Long>, categoryId: Long): Int
    suspend fun removeFromCategory(documentId: Long, categoryId: Long)
}

class RoomCategoriesRepository(
    database: WenxuDatabase,
) : CategoriesRepository {
    private val categoryDao = database.categoryDao()
    private val documentDao = database.documentDao()

    override val data: Flow<CategoriesData> = combine(
        categoryDao.observeSummaries(),
        documentDao.observeUnclassifiedCount(),
    ) { categories, unclassifiedCount ->
        CategoriesData(categories, unclassifiedCount)
    }

    override fun observeDocuments(categoryId: Long?): Flow<List<DocumentRecord>> {
        val entities = if (categoryId == null) {
            categoryDao.observeUnclassifiedDocuments()
        } else {
            categoryDao.observeDocuments(categoryId)
        }
        return entities.map { documents -> documents.map { it.toRecord() } }
    }

    override fun observeAllDocuments(): Flow<List<DocumentRecord>> =
        documentDao.observeActive().map { documents -> documents.map { it.toRecord() } }

    override suspend fun create(name: String): Result<Long> = runCatching {
        val cleanName = requireValidName(name)
        require(categoryDao.findByNameIgnoreCase(cleanName) == null) { "已经有同名分类" }
        categoryDao.insert(
            CategoryEntity(
                name = cleanName,
                sortOrder = categoryDao.maxSortOrder() + 1,
            ),
        )
    }

    override suspend fun rename(categoryId: Long, name: String): Result<Unit> = runCatching {
        val category = requireNotNull(categoryDao.findById(categoryId))
        val cleanName = requireValidName(name)
        val duplicate = categoryDao.findByNameIgnoreCase(cleanName)
        require(duplicate == null || duplicate.id == categoryId) { "已经有同名分类" }
        categoryDao.update(category.copy(name = cleanName))
    }

    override suspend fun delete(categoryId: Long): Result<Unit> = runCatching {
        val category = requireNotNull(categoryDao.findById(categoryId))
        categoryDao.delete(category)
    }

    override suspend fun markOpened(documentId: Long) {
        documentDao.updateLastOpened(documentId, System.currentTimeMillis())
    }

    override suspend fun addToCategory(documentId: Long, categoryId: Long) {
        categoryDao.add(DocumentCategoryEntity(documentId, categoryId))
    }

    override suspend fun addDocumentsToCategory(documentIds: Set<Long>, categoryId: Long): Int {
        if (documentIds.isEmpty()) return 0
        return categoryDao.addAll(
            documentIds.map { documentId -> DocumentCategoryEntity(documentId, categoryId) },
        ).count { rowId -> rowId != -1L }
    }

    override suspend fun removeFromCategory(documentId: Long, categoryId: Long) {
        categoryDao.remove(
            DocumentCategoryEntity(documentId, categoryId),
        )
    }

    private fun requireValidName(name: String): String {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "分类名称不能为空" }
        require(cleanName.length <= 20) { "分类名称不能超过20个字" }
        return cleanName
    }
}
