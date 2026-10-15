package io.github.rjszzz.hwpreader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.rjszzz.hwpreader.data.DocError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val MAX_ZOOM = 4f
private const val MAX_BITMAP_WIDTH = 2400

/** PdfRenderer 는 스레드 안전하지 않다 — 모든 접근을 한 잠금 아래에서 한다 */
private class PdfDoc(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    val lock = Mutex()
    val ratios: List<Float> = List(renderer.pageCount) { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }

    fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = widthPx.coerceIn(1, MAX_BITMAP_WIDTH)
        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}

@Composable
fun PdfPages(
    file: File,
    listState: LazyListState,
    onOpened: (pages: Int) -> Unit,
    onError: (DocError) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pdf by produceState<PdfDoc?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfDoc(file)
            } catch (_: SecurityException) {
                onError(DocError.ENCRYPTED); null
            } catch (_: IOException) {
                onError(DocError.CORRUPTED); null
            } catch (_: RuntimeException) {
                onError(DocError.CORRUPTED); null
            }
        }
    }
    val doc = pdf ?: return
    DisposableEffect(doc) { onDispose { doc.close() } }
    LaunchedEffect(doc) { onOpened(doc.ratios.size) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFEEEEF1)),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val maxOffset = widthPx * (scale - 1f) / 2f

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                // 두 손가락: 확대·이동. 한 손가락: 세로는 목록 스크롤, 확대 중이면 가로 이동.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            if (event.changes.size >= 2) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val limit = size.width * (scale - 1f) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-limit, limit)
                                event.changes.forEach { it.consume() }
                            } else if (scale > 1f) {
                                val limit = size.width * (scale - 1f) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-limit, limit)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        // 더블탭: 폭 맞춤 ↔ 2배 (F3)
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f
                            } else scale = 2f
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX.coerceIn(-maxOffset, maxOffset)
                    transformOrigin = TransformOrigin.Center
                },
        ) {
            itemsIndexed(doc.ratios) { index, ratio ->
                PdfPage(doc, index, ratio, (widthPx * 2).toInt())
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, ratio: Float, renderWidth: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, renderWidth) {
        bitmap = withContext(Dispatchers.IO) { doc.lock.withLock { runCatching { doc.render(index, renderWidth) }.getOrNull() } }
    }
    DisposableEffect(index) { onDispose { bitmap?.recycle() } }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(androidx.compose.ui.graphics.Color.White),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "${index + 1}쪽", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}
