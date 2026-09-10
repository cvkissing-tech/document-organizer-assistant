package com.wenxu.app.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.model.CategorySummary
import com.wenxu.app.core.model.DocumentRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoriesUiState(
    val categories: List<CategorySummary> = emptyList(),
    val unclassifiedCount: Int = 0,
    val message: UiText? = null,
)

class CategoriesViewModel(
    private val repository: CategoriesRepository,
) : ViewModel() {
    val state: StateFlow<CategoriesUiState> = repository.data
        .map { data ->
            CategoriesUiState(data.categories, data.unclassifiedCount)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CategoriesUiState())

    fun create(name: String, onResult: (Result<Long>) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.create(name)) }
    }

    companion object {
        fun factory(repository: CategoriesRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CategoriesViewModel(repository) }
        }
    }
}

data class CategoryDetailUiState(
    val title: UiText = UiText.Resource(R.string.organization_categories),
    val documents: List<DocumentRecord> = emptyList(),
    val availableDocuments: List<DocumentRecord> = emptyList(),
    val categories: List<CategorySummary> = emptyList(),
    val isUnclassified: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedDocumentIds: Set<Long> = emptySet(),
)

class CategoryDetailViewModel(
    private val repository: CategoriesRepository,
    private val categoryId: Long?,
) : ViewModel() {
    private val selectionMode = MutableStateFlow(false)
    private val selectedDocumentIds = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<CategoryDetailUiState> = combine(
        repository.data,
        repository.observeDocuments(categoryId),
        repository.observeAllDocuments(),
        selectionMode,
        selectedDocumentIds,
    ) { data, documents, allDocuments, isSelecting, selectedIds ->
        val currentIds = documents.mapTo(mutableSetOf()) { it.id }
        CategoryDetailUiState(
            title = if (categoryId == null) {
                UiText.Resource(R.string.organization_unclassified_documents)
            } else {
                data.categories.firstOrNull { it.id == categoryId }?.name
                    ?.let(UiText::Plain)
                    ?: UiText.Resource(R.string.organization_categories)
            },
            documents = documents,
            availableDocuments = if (categoryId == null) {
                emptyList()
            } else {
                allDocuments.filterNot { it.id in currentIds }
            },
            categories = data.categories,
            isUnclassified = categoryId == null,
            selectionMode = isSelecting && categoryId == null,
            selectedDocumentIds = selectedIds.intersect(currentIds),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CategoryDetailUiState(isUnclassified = categoryId == null),
    )

    fun rename(name: String, onResult: (Result<Unit>) -> Unit = {}) {
        val id = categoryId ?: return
        viewModelScope.launch { onResult(repository.rename(id, name)) }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = categoryId ?: return
        viewModelScope.launch {
            repository.delete(id).onSuccess { onDeleted() }
        }
    }

    fun markOpened(documentId: Long) {
        viewModelScope.launch { repository.markOpened(documentId) }
    }

    fun removeDocument(documentId: Long) {
        val id = categoryId ?: return
        viewModelScope.launch { repository.removeFromCategory(documentId, id) }
    }

    fun addDocumentToCategory(documentId: Long, targetCategoryId: Long) {
        viewModelScope.launch { repository.addToCategory(documentId, targetCategoryId) }
    }

    fun startSelection() {
        if (categoryId != null || state.value.documents.isEmpty()) return
        selectionMode.value = true
        selectedDocumentIds.value = emptySet()
    }

    fun cancelSelection() {
        selectionMode.value = false
        selectedDocumentIds.value = emptySet()
    }

    fun toggleSelection(documentId: Long) {
        if (!selectionMode.value || state.value.documents.none { it.id == documentId }) return
        selectedDocumentIds.value = selectedDocumentIds.value.toMutableSet().apply {
            if (!add(documentId)) remove(documentId)
        }
    }

    fun addSelectedToCategory(
        targetCategoryId: Long,
        onResult: (Int) -> Unit = {},
    ) {
        if (categoryId != null) return
        val visibleIds = state.value.documents.mapTo(mutableSetOf()) { it.id }
        val requestedIds = selectedDocumentIds.value.intersect(visibleIds)
        if (requestedIds.isEmpty()) return
        viewModelScope.launch {
            val added = repository.addDocumentsToCategory(requestedIds, targetCategoryId)
            cancelSelection()
            onResult(added)
        }
    }

    fun addDocuments(documentIds: Set<Long>) {
        val id = categoryId ?: return
        viewModelScope.launch {
            documentIds.forEach { documentId -> repository.addToCategory(documentId, id) }
        }
    }

    fun createCategoryAndAdd(
        name: String,
        documentId: Long,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = repository.create(name).fold(
                onSuccess = { newCategoryId ->
                    runCatching { repository.addToCategory(documentId, newCategoryId) }
                },
                onFailure = { Result.failure(it) },
            )
            onResult(result)
        }
    }

    companion object {
        fun factory(
            repository: CategoriesRepository,
            categoryId: Long?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CategoryDetailViewModel(repository, categoryId) }
        }
    }
}
