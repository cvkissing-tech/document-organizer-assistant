package com.wenxu.app.feature.documents

import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.entity.DocumentCategoryEntity
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.toRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class DocumentsData(
    val documents: List<DocumentRecord> = emptyList(),
    val categorizedIds: Set<Long> = emptySet(),
    val categoryIdsByDocument: Map<Long, Set<Long>> = emptyMap(),
    val relatedIds: Set<Long> = emptySet(),
    val categories: List<CategorySummary> = emptyList(),
)

interface DocumentsRepository {
    val data: Flow<DocumentsData>
    suspend fun markOpened(documentId: Long)
    suspend fun addToCategory(documentId: Long, categoryId: Long)
    suspend fun addDocumentsToCategory(documentIds: Set<Long>, categoryId: Long): Int
}

class RoomDocumentsRepository(
    database: WenxuDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : DocumentsRepository {
    private val documentDao = database.documentDao()
    private val categoryDao = database.categoryDao()
    private val relationDao = database.relationDao()

    override val data: Flow<DocumentsData> = combine(
        documentDao.observeActive(),
        categoryDao.observeDocumentCategories(),
        relationDao.observeRelatedDocumentIds(),
        categoryDao.observeSummaries(),
    ) { documents, categoryLinks, relatedIds, categories ->
        val categoryIdsByDocument = categoryLinks
            .groupBy { it.documentId }
            .mapValues { (_, links) -> links.mapTo(mutableSetOf()) { it.categoryId } }
        DocumentsData(
            documents = documents.map { it.toRecord() },
            categorizedIds = categoryIdsByDocument.keys,
            categoryIdsByDocument = categoryIdsByDocument,
            relatedIds = relatedIds.toSet(),
            categories = categories,
        )
    }

    override suspend fun markOpened(documentId: Long) {
        documentDao.updateLastOpened(documentId, now())
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
}
