package io.github.rjszzz.hwpreader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.rjszzz.hwpreader.data.DocError
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ZOOM = 4f
private const val RENDER_SETTLE_MS = 250L
/** Compose Constraints 가 함께 담을 수 있는 (폭, 높이) 상한 조합 — 한쪽에 비트를 많이 쓰면 다른 쪽이 줄어든다 */
private val CONSTRAINT_LIMITS = listOf(8191f to 262143f, 32767f to 65535f, 65535f to 32767f, 262143f to 8191f)
private const val CONSTRAINT_MARGIN = 64f
// 원본 보기 바탕 — 쪽(흰 종이)은 다크 모드에서도 흰색 (PRD F11)
private val PAGE_BACKGROUND = androidx.compose.ui.graphics.Color(0xFFEEEEF1)
private val PAGE_BACKGROUND_DARK = androidx.compose.ui.graphics.Color(0xFF1C1C1F)

/** 쪽 비트맵 하나의 상한. 넘으면 해상도를 낮춰 그리고 화면에서 늘린다 — 큰 비트맵은 그릴 때 앱이 죽는다. */
internal const val MAX_BITMAP_BYTES = 24L * 1024 * 1024
internal const val MAX_BITMAP_SIDE = 8192
private const val MIN_RATIO = 0.05f
private const val MAX_RATIO = 20f
private const val DEFAULT_RATIO = 1f / 1.4142f // A4 세로

/** 쪽 폭/높이 비율. 크기가 1pt 미만이거나 극단적인 쪽은 안전한 값으로 바꾼다 (조작된 PDF 대비). */
internal fun pdfPageRatio(width: Int, height: Int): Float {
    if (width < 1 || height < 1) return DEFAULT_RATIO
    val ratio = width.toFloat() / height
    return if (ratio.isFinite()) ratio.coerceIn(MIN_RATIO, MAX_RATIO) else DEFAULT_RATIO
}

/** 목표 폭에 맞춘 비트맵 크기. 바이트·변 길이 상한을 넘으면 비율을 지킨 채 줄인다. */
internal fun pdfBitmapSize(targetWidth: Int, ratio: Float): Pair<Int, Int> {
    var w = targetWidth.coerceAtLeast(1).toDouble()
    var h = w / ratio
    val bytes = w * h * 4
    if (bytes > MAX_BITMAP_BYTES) {
        val s = sqrt(MAX_BITMAP_BYTES / bytes)
        w *= s
        h *= s
    }
    val side = maxOf(w, h)
    if (side > MAX_BITMAP_SIDE) {
        val s = MAX_BITMAP_SIDE / side
        w *= s
        h *= s
    }
    return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
}

/**
 * 쪽 폭을 얼마까지 키울 수 있는가(px). 가장 긴 쪽(폭/높이 비율이 가장 작은 쪽)의 높이 = 폭 / 비율 까지
 * Compose 배치 한계 안에 들어가야 한다 — 넘으면 측정 단계에서 예외로 앱이 죽는다.
 */
internal fun pdfMaxContentWidth(minRatio: Float): Float =
    CONSTRAINT_LIMITS.maxOf { (w, h) -> minOf(w, h * minRatio) } - CONSTRAINT_MARGIN

/** 확대 중 가로 위치 목표값 — 넓어진 폭이 배치된 다음 프레임에 맞춘다 */
private class ZoomAnchor {
    var pendingX: Float? = null
    var scheduled = false
}

/** 렌더러 닫기는 화면이 사라진 뒤에도 끝까지 해야 한다 — 화면 수명과 무관한 범위에서 돈다 */
private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * PdfRenderer 는 스레드 안전하지 않고, 쪽이 열려 있는 동안 닫으면 예외가 난다.
 * 그리기와 닫기를 모두 한 잠금 아래에서 하고, 닫힌 뒤 들어온 그리기 요청은 무시한다.
 */
private class PdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val ratios: List<Float>,
) {
    private val lock = Mutex()
    private var closed = false // lock 아래에서만 읽고 쓴다
    val minRatio: Float = ratios.min()

    suspend fun render(index: Int, targetWidth: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        renderer.openPage(index).use { page ->
            val (w, h) = pdfBitmapSize(targetWidth, ratios[index])
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                // 크기가 0 인 쪽은 그리지 않는다 — 쪽→비트맵 배율을 계산할 때 0 으로 나누게 된다
                if (page.width >= 1 && page.height >= 1) {
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        fun open(file: File): PdfDoc {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                try {
                    if (renderer.pageCount < 1) throw IllegalStateException("no pages")
                    val ratios = List(renderer.pageCount) { i -> renderer.openPage(i).use { pdfPageRatio(it.width, it.height) } }
                    return PdfDoc(fd, renderer, ratios)
                } catch (e: Throwable) {
                    runCatching { renderer.close() }
                    throw e
                }
            } catch (e: Throwable) {
                runCatching { fd.close() }
                throw e
            }
        }
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    class Ready(val doc: PdfDoc) : PdfState
    class Failed(val error: DocError) : PdfState
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
    val state by produceState<PdfState>(PdfState.Loading, file) {
        // 열기는 화면 수명과 무관한 범위에서 끝까지 한다. 화면이 먼저 사라지면(뒤로 가기 등) 다 연 뒤에 닫는다.
        // (withContext 는 호출 쪽이 취소되면 결과를 버리므로 연 문서를 닫을 길이 없어진다)
        val opening = closeScope.async {
            try {
                PdfState.Ready(PdfDoc.open(file))
            } catch (_: SecurityException) {
                PdfState.Failed(DocError.ENCRYPTED)
            } catch (_: Throwable) {
                PdfState.Failed(DocError.CORRUPTED)
            }
        }
        val closeWhenOpened = { closeScope.launch { (opening.await() as? PdfState.Ready)?.doc?.close() } }
        val opened = try {
            opening.await()
        } catch (e: CancellationException) {
            closeWhenOpened()
            throw e
        }
        value = opened
        awaitDispose { closeWhenOpened() }
    }

    val background = if (isSystemInDarkTheme()) PAGE_BACKGROUND_DARK else PAGE_BACKGROUND
    when (val s = state) {
        PdfState.Loading -> Box(modifier.fillMaxSize().background(background))
        is PdfState.Failed -> LaunchedEffect(s) { onError(s.error) }
        is PdfState.Ready -> {
            LaunchedEffect(s.doc) { onOpened(s.doc.ratios.size) }
            ZoomablePages(s.doc, listState, background, onTap, modifier)
        }
    }
}

/**
 * 확대는 목록을 그림처럼 키우지 않고 쪽의 폭을 키워 다시 배치한다.
 * 그래서 확대해도 문서 처음과 끝까지 스크롤할 수 있고, 화면 밖으로 그려 상단 바를 덮지 않는다.
 */
@Composable
private fun ZoomablePages(
    doc: PdfDoc,
    listState: LazyListState,
    background: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    val anchor = remember { ZoomAnchor() }
    var scale by remember { mutableFloatStateOf(1f) }
    // 손을 뗀 뒤 확정된 배율 — 이 배율에 맞는 해상도로 다시 그린다
    var renderScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(scale) {
        delay(RENDER_SETTLE_MS)
        renderScale = scale
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(background)
            .clipToBounds(),
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        // 확대한 쪽이 배치 한계(Constraints)를 넘지 않는 최대 배율 — 가장 긴 쪽을 기준으로 정한다.
        // 보통 문서는 400% 까지, 영수증처럼 아주 긴 쪽이 있으면 그보다 낮다. 화면을 돌려 폭이 넓어지면 그만큼 낮아진다.
        val maxScale = (pdfMaxContentWidth(doc.minRatio) / viewportWidth).coerceIn(1f, MAX_ZOOM)
        // 화면이 넓어져 한계가 내려가면 지금 배율도 바로 낮춘다 — 같은 측정에서 넘치지 않게 계산값과 상태 모두 맞춘다
        val effScale = scale.coerceAtMost(maxScale)
        LaunchedEffect(maxScale) { if (scale > maxScale) scale = maxScale }
        val contentWidth = with(LocalDensity.current) { (viewportWidth * effScale).toDp() }

        // 손가락 아래 내용이 제자리에 머물도록 배율을 바꾼다. 같은 이벤트의 이동(pan)도 한 번의 위치 요청에 합친다 —
        // 따로 스크롤하면 앞서 요청한 위치를 덮어써 기준점이 틀어진다. 배율이 바뀌었으면 true.
        val zoomAround = rememberUpdatedState { factor: Float, focus: Offset, pan: Offset ->
            val old = effScale
            val new = (old * factor).coerceIn(1f, maxScale)
            if (new == old) {
                false
            } else {
                val z = new / old
                scale = new
                val first = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                listState.requestScrollToItem(first, ((offset + focus.y) * z - focus.y - pan.y).roundToInt().coerceAtLeast(0))
                // 가로 위치는 넓어진 폭이 배치된 뒤에 맞춘다. 그 전에 온 이벤트는 기다리는 목표값을 기준으로 이어서 계산한다.
                val baseX = anchor.pendingX ?: hScroll.value.toFloat()
                anchor.pendingX = (baseX + focus.x) * z - focus.x - pan.x
                if (!anchor.scheduled) {
                    anchor.scheduled = true
                    scope.launch {
                        withFrameNanos { }
                        anchor.scheduled = false
                        val target = anchor.pendingX ?: return@launch
                        anchor.pendingX = null
                        hScroll.scrollTo(target.roundToInt().coerceIn(0, hScroll.maxValue))
                    }
                }
                true
            }
        }

        /** 배율 변화 없이 이동만 할 때 — 가로 목표값이 기다리는 중이면 거기에 더한다 */
        fun panBy(pan: Offset) {
            val pendingX = anchor.pendingX
            if (pendingX != null) anchor.pendingX = pendingX - pan.x else hScroll.dispatchRawDelta(-pan.x)
            listState.dispatchRawDelta(-pan.y)
        }
        val currentPanBy = rememberUpdatedState(::panBy)

        Box(
            Modifier
                .fillMaxSize()
                // 두 손가락 확대는 자식(스크롤)보다 먼저 가로챈다 — 확대하는 동안 목록이 따로 스크롤되지 않게.
                // 한 번 확대가 시작되면 손가락을 모두 뗄 때까지 이동도 여기서 직접 처리한다(손가락 하나만 남아도).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) pinching = true
                            if (pinching) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val zoomed = zoom != 1f && zoomAround.value(zoom, event.calculateCentroid(), pan)
                                if (!zoomed && pan != Offset.Zero) currentPanBy.value(pan)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        // 두 번 탭: 폭 맞춤 ↔ 2배 (F3)
                        onDoubleTap = { pos ->
                            val s = scale.coerceAtMost(maxScale)
                            zoomAround.value(if (s > 1.01f) 1f / s else 2f / s, pos, Offset.Zero)
                        },
                    )
                }
                .horizontalScroll(hScroll, enabled = effScale > 1f),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight(),
            ) {
                itemsIndexed(doc.ratios) { index, ratio ->
                    PdfPage(doc, index, ratio, (viewportWidth * renderScale.coerceAtMost(maxScale)).roundToInt())
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, ratio: Float, renderWidth: Int) {
    var bitmap by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index, renderWidth) {
        val rendered = withContext(Dispatchers.IO) {
            val b = runCatching { doc.render(index, renderWidth) }.getOrNull()
            // 기다리는 사이 화면에서 사라진 쪽이면 아무도 그리지 않으니 바로 돌려준다
            if (b != null && !isActive) {
                b.recycle()
                null
            } else {
                b
            }
        }
        if (rendered != null) bitmap = rendered
    }
    // 화면에 그려졌던 비트맵은 직접 recycle 하지 않는다 — 그리기 목록에 남아 있을 수 있어서. GC 가 네이티브 메모리까지 회수한다.
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(androidx.compose.ui.graphics.Color.White),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "${index + 1}쪽", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}
