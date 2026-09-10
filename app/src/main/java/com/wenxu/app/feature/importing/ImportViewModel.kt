package com.wenxu.app.feature.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.importing.InboxImportService
import com.wenxu.app.core.importing.IncomingDocument
import com.wenxu.app.core.importing.IncomingIntentStore
import com.wenxu.app.core.importing.IncomingMetadataReader
import com.wenxu.app.core.settings.InboxPreferences
import com.wenxu.app.core.localization.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ImportUiState(
    val documents: List<IncomingDocument> = emptyList(),
    val unsupportedCount: Int = 0,
    val inboxTreeUri: String? = null,
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val message: UiText? = null,
    val hasFailures: Boolean = false,
)

class ImportViewModel(
    private val intentStore: IncomingIntentStore,
    private val metadataReader: IncomingMetadataReader,
    private val preferences: InboxPreferences,
    private val service: InboxImportService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(intentStore.pendingUris, preferences.inboxTreeUri) { uris, inbox -> uris to inbox }
                .collect { (uris, inbox) ->
                    if (uris.isEmpty()) return@collect
                    mutableState.value = mutableState.value.copy(isLoading = true, inboxTreeUri = inbox)
                    val documents = uris.mapNotNull { uri ->
                        runCatching { metadataReader.read(uri) }.getOrNull()
                    }
                    mutableState.value = mutableState.value.copy(
                        documents = documents,
                        unsupportedCount = uris.size - documents.size,
                        inboxTreeUri = inbox,
                        isLoading = false,
                    )
                }
        }
    }

    fun chooseInbox(uri: String?) {
        if (uri == null) return
        mutableState.value = mutableState.value.copy(
            inboxTreeUri = uri,
            message = null,
            hasFailures = false,
        )
    }

    fun importDocuments(onCompleted: () -> Unit) {
        val current = mutableState.value
        val inbox = current.inboxTreeUri ?: return
        if (current.documents.isEmpty() || current.isImporting) return
        viewModelScope.launch {
            mutableState.value = current.copy(
                isImporting = true,
                message = null,
                hasFailures = false,
            )
            val report = service.import(current.documents, inbox)
            val message = UiText.Resource(
                R.string.import_result,
                listOf(report.imported, report.skipped, report.failed),
            )
            mutableState.value = mutableState.value.copy(
                documents = if (report.failed == 0) emptyList() else current.documents.filter { it.uri in report.failedUris },
                isImporting = false,
                message = message,
                hasFailures = report.failed > 0,
            )
            if (report.failed == 0) {
                intentStore.clear()
                onCompleted()
            }
        }
    }

    fun cancel(onCancelled: () -> Unit) {
        intentStore.clear()
        onCancelled()
    }

    companion object {
        fun factory(
            intentStore: IncomingIntentStore,
            metadataReader: IncomingMetadataReader,
            preferences: InboxPreferences,
            service: InboxImportService,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel(intentStore, metadataReader, preferences, service) }
        }
    }
}
