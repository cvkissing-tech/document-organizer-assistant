package com.wenxu.app.feature.reader

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenxu.app.R
import com.wenxu.app.core.localization.resolve
import com.wenxu.app.ui.components.WenxuLineIcon
import com.wenxu.app.ui.components.WenxuLineIconType
import com.wenxu.app.ui.theme.DocumentAssistantDimens
import com.wenxu.app.ui.theme.WenxuBrand
import com.wenxu.app.ui.theme.WenxuInk
import com.wenxu.app.ui.theme.WenxuLine
import com.wenxu.app.ui.theme.WenxuMuted
import com.wenxu.app.ui.util.shareDocument
import kotlinx.coroutines.delay

private val ReaderStage = Color(0xFF30383B)

@Composable
fun PdfReaderScreen(viewModel: PdfReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareFailedMessage = stringResource(R.string.pdf_reader_share_failed)
    val resolvedTitle = state.title.resolve()
    PdfReaderScreen(
        state = state,
        onBack = onBack,
        onPrevious = viewModel::previousPage,
        onNext = viewModel::nextPage,
        onShare = {
            val uri = state.shareUri
            if (uri == null || !shareDocument(context, uri, resolvedTitle, state.extension)) {
                Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
fun PdfReaderScreen(
    state: PdfReaderUiState,
    onBack: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    var zoomRequest by remember(state.pageIndex) { mutableIntStateOf(0) }
    var displayedScale by remember(state.pageIndex) { mutableFloatStateOf(1f) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReaderStage),
    ) {
        ReaderTopBar(
            title = state.title.resolve(),
            canShare = state.shareUri != null,
            onBack = onBack,
            onShare = onShare,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ReaderStage),
            contentAlignment = Alignment.Center,
        ) {
            state.bitmap?.let { bitmap ->
                ZoomablePdfPage(
                    bitmap = bitmap.asImageBitmap(),
                    pageIndex = state.pageIndex,
                    contentDescription = stringResource(
                        R.string.pdf_reader_page_description,
                        state.pageIndex + 1,
                    ),
                    zoomRequest = zoomRequest,
                    onScaleChanged = { displayedScale = it },
                )
            }
            if (state.isLoading) {
                CircularProgressIndicator(color = Color.White)
            }
            state.errorMessage?.let { message ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                ) {
                    WenxuLineIcon(
                        type = WenxuLineIconType.FILE,
                        color = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = message.resolve(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        text = stringResource(R.string.pdf_reader_missing_file_detail),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        ReaderBottomBar(
            pageIndex = state.pageIndex,
            pageCount = state.pageCount,
            isLoading = state.isLoading,
            scale = displayedScale,
            onPrevious = onPrevious,
            onNext = onNext,
            onZoomOut = {
                zoomRequest = if (zoomRequest >= 0) -(zoomRequest + 1) else zoomRequest - 1
            },
            onZoomIn = {
                zoomRequest = if (zoomRequest <= 0) -zoomRequest + 1 else zoomRequest + 1
            },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    canShare: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderIconButton(
                type = WenxuLineIconType.BACK,
                description = stringResource(R.string.action_back),
                onClick = onBack,
            )
            Text(
                text = title,
                color = WenxuInk,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ReaderIconButton(
                type = WenxuLineIconType.SHARE,
                description = stringResource(R.string.action_share_file),
                enabled = canShare,
                onClick = onShare,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    pageIndex: Int,
    pageCount: Int,
    isLoading: Boolean,
    scale: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
) {
    Surface(color = Color.White, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderZoomButton("−", scale > 1f && !isLoading, onZoomOut)
            Text(
                text = "${(scale * 100).toInt()}%",
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(width = 42.dp, height = DocumentAssistantDimens.TouchTarget)
                    .padding(top = 15.dp),
            )
            ReaderZoomButton("＋", scale < 4f && !isLoading, onZoomIn)
            Box(Modifier.weight(1f))
            ReaderIconButton(
                type = WenxuLineIconType.BACK,
                description = stringResource(R.string.pdf_reader_previous_page),
                enabled = pageIndex > 0 && !isLoading,
                onClick = onPrevious,
            )
            Text(
                text = if (pageCount > 0) {
                    stringResource(R.string.pdf_reader_page_count, pageIndex + 1, pageCount)
                } else {
                    "— / —"
                },
                color = WenxuMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            ReaderIconButton(
                type = WenxuLineIconType.CHEVRON,
                description = stringResource(R.string.pdf_reader_next_page),
                enabled = pageIndex + 1 < pageCount && !isLoading,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun ReaderZoomButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DocumentAssistantDimens.TouchTarget)
            .alpha(if (enabled) 1f else 0.36f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = WenxuBrand, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ReaderIconButton(
    type: WenxuLineIconType,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DocumentAssistantDimens.TouchTarget)
            .alpha(if (enabled) 1f else 0.36f)
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        WenxuLineIcon(
            type = type,
            color = WenxuBrand,
            modifier = Modifier.size(DocumentAssistantDimens.LargeIcon),
        )
    }
}

@Composable
private fun ZoomablePdfPage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    pageIndex: Int,
    contentDescription: String,
    zoomRequest: Int,
    onScaleChanged: (Float) -> Unit,
) {
    var viewport by remember(pageIndex) { mutableStateOf(PdfViewportState()) }
    var viewportSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }
    var contentSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }
    var showZoomHint by remember(pageIndex) { mutableStateOf(pageIndex == 0) }

    LaunchedEffect(pageIndex) {
        if (showZoomHint) {
            delay(3_500)
            showZoomHint = false
        }
    }

    LaunchedEffect(zoomRequest) {
        if (zoomRequest == 0) return@LaunchedEffect
        viewport = viewport.zoomBy(
            factor = if (zoomRequest > 0) 1.25f else 0.8f,
            contentWidth = contentSize.width.toFloat(),
            contentHeight = contentSize.height.toFloat(),
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
        )
    }

    LaunchedEffect(viewport.scale) { onScaleChanged(viewport.scale) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .padding(14.dp)
            .onGloballyPositioned { viewportSize = it.size }
            .pointerInput(pageIndex, viewportSize, contentSize) {
                detectTransformGestures { _, pan, zoom, _ ->
                    viewport = viewport
                        .zoomBy(
                            factor = zoom,
                            contentWidth = contentSize.width.toFloat(),
                            contentHeight = contentSize.height.toFloat(),
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                        )
                        .panBy(
                            deltaX = pan.x,
                            deltaY = pan.y,
                            contentWidth = contentSize.width.toFloat(),
                            contentHeight = contentSize.height.toFloat(),
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                        )
                }
            }
            .pointerInput(pageIndex, viewportSize, contentSize) {
                detectTapGestures(
                    onDoubleTap = {
                        viewport = viewport.toggleZoom(
                            contentWidth = contentSize.width.toFloat(),
                            contentHeight = contentSize.height.toFloat(),
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                        )
                    },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { contentSize = it.size }
                .graphicsLayer {
                    scaleX = viewport.scale
                    scaleY = viewport.scale
                    translationX = viewport.offsetX
                    translationY = viewport.offsetY
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            contentScale = ContentScale.FillWidth,
        )
        if (showZoomHint) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, WenxuLine),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.pdf_reader_zoom_hint),
                    color = WenxuMuted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
    }
}
