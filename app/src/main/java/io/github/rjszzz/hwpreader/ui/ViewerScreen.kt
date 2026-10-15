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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    doc: OpenedDocument,
    prefs: Prefs,
    onBack: () -> Unit,
    onOpened: (format: String) -> Unit,
    onError: (DocError) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val controller = remember(doc) { ViewerController() }
    val pdfList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var format by remember(doc) { mutableStateOf(if (doc.kind == DocKind.PDF) "pdf" else "") }
    var mode by remember(doc) { mutableStateOf(MODE_ORIGINAL) }
    var pages by remember(doc) { mutableIntStateOf(0) }
    var currentPage by remember(doc) { mutableIntStateOf(1) }
    var renderMs by remember(doc) { mutableStateOf<Int?>(null) }
    var fontStep by remember { mutableIntStateOf(prefs.fontStep) }
    var barsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val canToggle = format in ORIGINAL_FORMATS

    fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "opened" -> {
                format = msg.optString("format")
                mode = msg.optString("mode")
                pages = msg.optInt("pages")
                renderMs = msg.optInt("timing")
                onOpened(format)
            }
            "error" -> onError(DocError.fromCode(msg.optString("code")))
            "page" -> currentPage = msg.optInt("current")
            "tap" -> barsVisible = !barsVisible
            "link" -> pendingLink = msg.optString("href")
        }
    }

    fun switchMode(next: String) {
        if (next == mode) return
        mode = next
        prefs.setModeFor(format, next)
        controller.setMode(next)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(barsVisible) {
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
                    DocKind.WEB -> DocumentWebView(
                        doc = doc,
                        initialMode = remember(doc) { prefs.modeFor(guessFormat(doc.name)) ?: MODE_ORIGINAL },
                        fontStep = fontStep,
                        dark = dark,
                        controller = controller,
                        onMessage = ::handle,
                    )
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

/** 형식별 보기 방식 기억(F5)을 열기 전에 적용하려고 확장자로 어림한다. 실제 형식은 뷰어가 매직바이트로 정한다. */
private fun guessFormat(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "hwpx" -> "hwpx"
    else -> "hwp"
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
