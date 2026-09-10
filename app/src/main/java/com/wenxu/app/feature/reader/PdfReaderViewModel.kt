package com.wenxu.app.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wenxu.app.R
import com.wenxu.app.core.localization.UiText
import com.wenxu.app.core.reader.PdfReaderRepository
import com.wenxu.app.core.reader.PdfSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PdfReaderUiState(
    val title: UiText = UiText.Resource(R.string.pdf_reader_title),
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val bitmap: Bitmap? = null,
    val shareUri: String? = null,
    val extension: String = "pdf",
    val isLoading: Boolean = true,
    val errorMessage: UiText? = null,
)

class PdfReaderViewModel(
    private val documentId: Long,
    private val repository: PdfReaderRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PdfReaderUiState())
    val state: StateFlow<PdfReaderUiState> = mutableState.asStateFlow()
    private var session: PdfSession? = null
    private var staleBitmap: Bitmap? = null

    init {
        viewModelScope.launch {
            repository.open(documentId)
                .onSuccess { opened ->
                    session = opened.session
                    mutableState.value = mutableState.value.copy(
                        title = UiText.Plain(opened.title),
                        pageCount = opened.session.pageCount,
                        shareUri = opened.uri,
                        extension = opened.extension,
                    )
                    repository.markOpened(documentId)
                    render(0)
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        errorMessage = UiText.Resource(R.string.pdf_reader_open_failed),
                    )
                }
        }
    }

    fun previousPage() {
        val target = mutableState.value.pageIndex - 1
        if (target >= 0) render(target)
    }

    fun nextPage() {
        val target = mutableState.value.pageIndex + 1
        if (target < mutableState.value.pageCount) render(target)
    }

    private fun render(pageIndex: Int) {
        val currentSession = session ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
            runCatching { currentSession.render(pageIndex, TARGET_WIDTH) }
                .onSuccess { bitmap ->
                    val previous = mutableState.value.bitmap
                    staleBitmap?.recycle()
                    staleBitmap = previous
                    mutableState.value = mutableState.value.copy(
                        pageIndex = pageIndex,
                        bitmap = bitmap,
                        isLoading = false,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        errorMessage = UiText.Resource(R.string.pdf_reader_render_failed),
                    )
                }
        }
    }

    override fun onCleared() {
        mutableState.value.bitmap?.recycle()
        staleBitmap?.recycle()
        session?.close()
        super.onCleared()
    }

    companion object {
        private const val TARGET_WIDTH = 1600

        fun factory(
            documentId: Long,
            repository: PdfReaderRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PdfReaderViewModel(documentId, repository) }
        }
    }
}
