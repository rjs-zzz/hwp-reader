package io.github.rjszzz.hwpreader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import io.github.rjszzz.hwpreader.data.DocError
import io.github.rjszzz.hwpreader.data.DocKind
import io.github.rjszzz.hwpreader.data.OpenedDocument
import io.github.rjszzz.hwpreader.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val ORIGINAL_FORMATS = setOf("hwp", "hwpx")
private const val MODE_ORIGINAL = "original"
private const val MODE_READING = "reading"
private const val MAX_WEBVIEW_RELOADS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    doc: OpenedDocument,
    prefs: Prefs,
    onBack: () -> Unit,
    onOpened: (format: String) -> Unit,
    onError: (DocError) -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // 렌더러 프로세스가 죽으면 WebView 를 새로 만들어 다시 연다 — 세대가 바뀌면 WebView·컨트롤러가 새로 생긴다.
    // 다시 연 문서가 한 번도 열리지 못한 채 또 죽을 때만 오류로 닫는다(열린 뒤에는 횟수를 되돌린다).
    var webViewGeneration by remember(doc) { mutableIntStateOf(0) }
    var deathsSinceOpened by remember(doc) { mutableIntStateOf(0) }
    val controller = remember(doc, webViewGeneration) { ViewerController() }
    val pdfList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var format by remember(doc) { mutableStateOf(if (doc.kind == DocKind.PDF) "pdf" else "") }
    var mode by remember(doc) { mutableStateOf(MODE_ORIGINAL) }
    var pages by remember(doc) { mutableIntStateOf(0) }
    var currentPage by remember(doc) { mutableIntStateOf(1) }
    var renderMs by remember(doc) { mutableStateOf<Int?>(null) }
    // 문서 정보 진단 값 (M0 실기기 확인용) — 뷰어가 실제로 쓴 엔진·전달 방식
    var engine by remember(doc) { mutableStateOf<String?>(null) }
    var transfer by remember(doc) { mutableStateOf<String?>(null) }
    val webViewVersion = remember { WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.versionName} (${it.packageName})" } }
    var fontStep by remember { mutableIntStateOf(prefs.fontStep) }
    var barsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember(doc) { mutableStateOf(false) }
    var found by remember(doc) { mutableStateOf<Found?>(null) }
    // 시스템 글꼴 크기 — 읽기 모드 글자에 곱한다 (PRD 6장)
    val fontScale = LocalConfiguration.current.fontScale

    val canToggle = format in ORIGINAL_FORMATS

    fun closeSearch() {
        searchOpen = false
        found = null
        controller.findClear()
    }

    BackHandler { if (searchOpen) closeSearch() else onBack() }

    fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "opened" -> {
                deathsSinceOpened = 0
                format = msg.optString("format")
                mode = msg.optString("mode")
                pages = msg.optInt("pages")
                renderMs = msg.optInt("timing")
                engine = msg.optString("engine").ifEmpty { null }
                transfer = msg.optString("transfer").ifEmpty { null }
                onOpened(format)
            }
            // 뷰어가 실제로 보여 주는 방식과 쪽 수 — 전환에 실패하면 이전 방식으로 돌아온다
            "mode" -> {
                mode = msg.optString("mode")
                msg.optInt("pages").takeIf { it > 0 }?.let { pages = it }
            }
            "found" -> found = Found(msg.optInt("current"), msg.optInt("total"), msg.optInt("readingTotal", -1))
            "error" -> onError(DocError.fromCode(msg.optString("code")))
            // 백그라운드에 있는 동안 시스템이 렌더러를 회수했을 수 있다 — 같은 문서를 다시 연다.
            // 다시 연 문서가 열리기도 전에 또 죽으면(큰 문서의 메모리 부족 등) 오류로 닫는다.
            "rendererGone" -> {
                deathsSinceOpened++
                if (deathsSinceOpened <= MAX_WEBVIEW_RELOADS) {
                    if (searchOpen) closeSearch()
                    format = ""
                    pages = 0
                    webViewGeneration++
                } else {
                    onError(DocError.RENDER_FAILED)
                }
            }
            "page" -> {
                currentPage = msg.optInt("current")
                msg.optInt("total").takeIf { it > 0 }?.let { pages = it }
            }
            "tap" -> barsVisible = !barsVisible
            "link" -> pendingLink = msg.optString("href")
        }
    }

    fun switchMode(next: String) {
        if (next == mode) return
        if (searchOpen) closeSearch()
        mode = next
        prefs.setModeFor(format, next)
        controller.setMode(next)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (searchOpen) {
                SearchBar(
                    found = found,
                    onSearch = { controller.find(it) },
                    onNext = { controller.findNext(it) },
                    onClose = ::closeSearch,
                )
            }
            AnimatedVisibility(barsVisible && !searchOpen) {
                TopAppBar(
                    title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기") }
                    },
                    actions = {
                        if (canToggle) {
                            SingleChoiceSegmentedButtonRow(Modifier.padding(end = 4.dp)) {
                                listOf(MODE_ORIGINAL to "원본", MODE_READING to "읽기").forEachIndexed { i, (key, label) ->
                                    SegmentedButton(
                                        selected = mode == key,
                                        onClick = { switchMode(key) },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                                        icon = {},
                                    ) { Text(label) }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "더보기") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (mode == MODE_READING && doc.kind == DocKind.WEB) {
                                    DropdownMenuItem(
                                        text = { Text("글자 크기: " + listOf("작게", "보통", "크게")[fontStep]) },
                                        onClick = {
                                            fontStep = (fontStep + 1) % 3
                                            prefs.fontStep = fontStep
                                            controller.setFontStep(fontStep)
                                        },
                                    )
                                }
                                // PDF 검색은 P2 (androidx.pdf, API 31+)
                                if (doc.kind == DocKind.WEB && format.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("검색") }, onClick = { menuOpen = false; searchOpen = true })
                                }
                                if (pages > 1 && (mode == MODE_ORIGINAL || doc.kind == DocKind.PDF)) {
                                    DropdownMenuItem(text = { Text("쪽 이동") }, onClick = { menuOpen = false; gotoOpen = true })
                                }
                                DropdownMenuItem(text = { Text("공유") }, onClick = { menuOpen = false; share(context, doc) })
                                DropdownMenuItem(text = { Text("문서 정보") }, onClick = { menuOpen = false; infoOpen = true })
                            }
                        }
                    },
                )
            }

            Box(Modifier.weight(1f)) {
                when (doc.kind) {
                    DocKind.PDF -> PdfPages(
                        file = doc.file,
                        listState = pdfList,
                        onOpened = { n -> pages = n; onOpened("pdf") },
                        onError = onError,
                        onTap = { barsVisible = !barsVisible },
                    )
                    DocKind.WEB -> key(webViewGeneration) {
                        DocumentWebView(
                            doc = doc,
                            modes = remember(doc) { prefs.modes() },
                            fontStep = fontStep,
                            fontScale = fontScale,
                            dark = dark,
                            controller = controller,
                            onMessage = ::handle,
                        )
                    }
                }
            }
        }

        // PDF 쪽 표시 칩 — 스크롤할 때만 잠깐 (WEB 은 뷰어 JS 가 직접 그린다)
        if (doc.kind == DocKind.PDF) {
            val firstVisible by remember { derivedStateOf { pdfList.firstVisibleItemIndex + 1 } }
            var chipVisible by remember { mutableStateOf(false) }
            LaunchedEffect(firstVisible, pdfList.isScrollInProgress) {
                currentPage = firstVisible
                if (pdfList.isScrollInProgress) chipVisible = true else { delay(1200); chipVisible = false }
            }
            if (chipVisible && pages > 0) {
                Text(
                    "$firstVisible / $pages",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    pendingLink?.let { href ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("외부 앱에서 열까요?") },
            text = { Text(href) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
                    } catch (_: ActivityNotFoundException) {
                        notice = "이 링크를 열 앱이 없습니다."
                    }
                }) { Text("열기") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("취소") } },
        )
    }

    if (gotoOpen) {
        var input by remember { mutableStateOf(currentPage.toString()) }
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("쪽 이동") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(5) },
                    suffix = { Text("/ $pages") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    gotoOpen = false
                    if (pages < 1) return@TextButton
                    val page = input.toIntOrNull()?.coerceIn(1, pages) ?: return@TextButton
                    if (doc.kind == DocKind.PDF) scope.launch { pdfList.scrollToItem(page - 1) } else controller.goTo(page)
                }) { Text("이동") }
            },
            dismissButton = { TextButton(onClick = { gotoOpen = false }) { Text("취소") } },
        )
    }

    if (infoOpen) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text("문서 정보") },
            text = {
                Text(
                    buildString {
                        appendLine("이름: ${doc.name}")
                        appendLine("형식: ${format.uppercase()}")
                        appendLine("크기: ${"%,d".format(doc.size / 1024)}KB")
                        if (pages > 0) appendLine("쪽: $pages")
                        renderMs?.let { appendLine("표시까지: ${it}ms") }
                        if (doc.kind == DocKind.PDF) {
                            appendLine("엔진: Android PdfRenderer")
                        } else {
                            engine?.let { appendLine("엔진: " + if (it == "worker") "Web Worker" else "메인 스레드") }
                            transfer?.let { appendLine("전달 방식: " + if (it == "arraybuffer") "ArrayBuffer" else "Base64 분할") }
                            appendLine("WebView: ${webViewVersion ?: "알 수 없음"}")
                        }
                    }.trim(),
                )
            },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("확인") } },
        )
    }

    notice?.let {
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("확인") } },
        )
    }
}

/**
 * 검색 결과 수. readingTotal 은 원본 보기 검색일 때만 온다 — 원본 보기는 줄이 바뀌거나 서식이 바뀌는 곳에 걸친
 * 문구를 못 찾으므로, 읽기 모드에서 더 찾을 수 있으면 알려 준다. 없으면 -1.
 */
private data class Found(val current: Int, val total: Int, val readingTotal: Int)

/** 문서 내 검색 (F9) — 읽기 모드는 본문 글자, 원본 보기는 쪽 SVG 강조 */
@Composable
private fun SearchBar(
    found: Found?,
    onSearch: (String) -> Unit,
    onNext: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "검색 닫기") }
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("문서에서 찾기") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        found?.let { (current, total, readingTotal) ->
            val base = if (total == 0) "없음" else "$current/$total"
            Text(
                if (readingTotal > total) "$base · 읽기 ${readingTotal}건" else base,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        IconButton(onClick = { onNext(false) }, enabled = (found?.total ?: 0) > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "이전 결과")
        }
        IconButton(onClick = { onNext(true) }, enabled = (found?.total ?: 0) > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "다음 결과")
        }
    }
}

/** 원본 파일을 다른 앱으로 넘긴다 — 사용자가 직접 누를 때만 (F12) */
private fun share(context: android.content.Context, doc: OpenedDocument) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(doc.uri) ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, doc.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: Exception) {
        // 받는 쪽에서 원본 URI 권한을 넘겨받지 못하는 제공자도 있다. M0 확인 항목.
    }
}
