package io.github.rjszzz.hwpreader.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.rjszzz.hwpreader.data.DocError
import io.github.rjszzz.hwpreader.data.OpenedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val VIEWER_URL = "$ASSET_ORIGIN/assets/viewer/viewer.html"
/** 문서 바이트를 나눠 보내는 단위. 3의 배수라 Base64 로 바꿔도 조각 경계가 깔끔하다. */
private const val SLICE_BYTES = 3 * 1024 * 1024
private const val READY_TIMEOUT_MS = 10_000L
private const val MIN_WEBVIEW_MAJOR = 80
private val CHROME_VERSION = Regex("""Chrome/(\d+)\.""")

/** 네이티브 → 뷰어 명령. 메시지 형식은 web/src/viewer.js 머리말 참조. */
class ViewerController {
    internal var port: WebMessagePortCompat? = null
    internal var webView: WebView? = null
    /** 렌더러 프로세스가 죽은 WebView 는 destroy 외의 호출을 하면 안 된다 */
    internal var rendererGone = false
    /** 뷰어 스크립트가 떠서 ready 를 보냈는가 */
    internal var ready = false

    /** 폭 맞춤 배율(문서를 연 직후)과 현재 배율 — onScaleChanged 로 따라간다 */
    internal var baseScale = 0f
    internal var currentScale = 0f

    fun setMode(mode: String) = send(JSONObject().put("type", "mode").put("mode", mode))
    fun setFontStep(step: Int) = send(JSONObject().put("type", "fontStep").put("step", step))
    fun setFontScale(scale: Float) = send(JSONObject().put("type", "fontScale").put("scale", scale.toDouble()))
    fun goTo(page: Int) = send(JSONObject().put("type", "goto").put("page", page))
    fun setDark(dark: Boolean) = send(JSONObject().put("type", "theme").put("dark", dark))
    fun find(query: String) = send(JSONObject().put("type", "find").put("query", query))
    fun findNext(forward: Boolean) = send(JSONObject().put("type", "findNext").put("forward", forward))
    fun findClear() = send(JSONObject().put("type", "findClear"))

    /** 두 번 탭 — 확대돼 있으면 폭 맞춤으로, 아니면 2배로 (F3) */
    fun toggleFitWidth() {
        val view = webView ?: return
        if (rendererGone || baseScale <= 0f || currentScale <= 0f) return
        val ratio = currentScale / baseScale
        view.zoomBy(if (ratio > 1.05f) (1f / ratio).coerceIn(0.01f, 1f) else 2f)
    }

    internal fun send(msg: JSONObject) {
        port?.postMessage(WebMessageCompat(msg.toString()))
    }
}

/**
 * kordoc 뷰어를 띄우는 WebView (PRD 8.4, SEC-4).
 * - 페이지와 번들은 WebViewAssetLoader 로 앱 assets 에서만 읽는다. 외부 주소로는 이동하지 않는다.
 * - 파일·content URI 접근을 끄고, JS 인터페이스 대신 메시지 포트 하나만 둔다.
 * - 문서 하나에 WebView 하나. 다른 문서를 열면 호출하는 쪽(MainActivity)이 key 로 새로 만든다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocumentWebView(
    doc: OpenedDocument,
    /** 형식별 마지막 보기 방식 (F5) — 실제 형식은 뷰어가 매직바이트로 정한 뒤 고른다 */
    modes: Map<String, String>,
    fontStep: Int,
    /** 시스템 글꼴 크기 배율 — 읽기 모드 글자에만 곱한다 (PRD 6장 접근성) */
    fontScale: Float,
    dark: Boolean,
    controller: ViewerController,
    onMessage: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnMessage = rememberUpdatedState(onMessage)
    // uiMode·fontScale 은 configChanges 에 있어 바꿔도 Activity·WebView 가 그대로다. 최신 값을 따라가야 한다.
    val currentModes = rememberUpdatedState(modes)
    val currentFontStep = rememberUpdatedState(fontStep)
    val currentFontScale = rememberUpdatedState(fontScale)
    val currentDark = rememberUpdatedState(dark)
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val webView = remember(doc) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = false
                textZoom = 100 // 원본 보기 SVG 의 줄 배치가 깨지지 않게 고정. 시스템 글꼴 크기는 fontScale 메시지로 읽기 모드에만 반영한다
                blockNetworkLoads = true
            }
            setBackgroundColor(backgroundFor(dark))
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                // 문서 안 링크도 뷰어 JS 가 가로채 네이티브 확인 창으로 보낸다. 여기서는 모든 이동을 막는다.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                    controller.currentScale = newScale
                }

                // 렌더러가 죽어도(메모리 부족, 백그라운드에서 시스템이 회수 등) 앱까지 죽지 않게 한다.
                // 이 WebView 는 더 쓰지 않는다. 화면(ViewerScreen)이 새 WebView 로 한 번 다시 열거나 오류로 닫는다.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    controller.rendererGone = true
                    controller.port = null
                    currentOnMessage.value(JSONObject().put("type", "rendererGone").put("crashed", detail.didCrash()))
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (controller.rendererGone) return
                    @Suppress("DEPRECATION")
                    controller.baseScale = view.scale
                    controller.currentScale = controller.baseScale
                    connect(view, controller) { msg ->
                        when (msg.optString("type")) {
                            "ready" -> {
                                controller.ready = true
                                controller.setDark(currentDark.value)
                                controller.setFontScale(currentFontScale.value)
                                scope.sendDocument(controller, doc, currentModes.value, currentFontStep.value) {
                                    currentOnMessage.value(JSONObject().put("type", "error").put("code", it.name))
                                }
                            }
                            "dbltap" -> controller.toggleFitWidth()
                        }
                        currentOnMessage.value(msg)
                    }
                    // 뷰어 스크립트를 실행하지 못하는 오래된 WebView 면 ready 가 오지 않는다 — 빈 화면으로 두지 않는다
                    scope.launch {
                        delay(READY_TIMEOUT_MS)
                        if (!controller.ready && !controller.rendererGone) {
                            currentOnMessage.value(JSONObject().put("type", "error").put("code", DocError.WEBVIEW_OUTDATED.name))
                        }
                    }
                }
            }
        }
    }
    controller.webView = webView

    // 뷰어 번들은 Chrome 80 이상을 전제로 만든다(web/build.mjs target).
    // 엔진 버전은 사용자 에이전트의 Chrome/NN 으로 본다 — 제공 앱 버전 번호는 Chromium 번호와 다를 수 있다.
    // 알 수 없으면 막지 않는다(ready 시간 초과가 뒤를 받친다).
    LaunchedEffect(Unit) {
        val agent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull().orEmpty()
        val major = CHROME_VERSION.find(agent)?.groupValues?.get(1)?.toIntOrNull()
        if (major != null && major < MIN_WEBVIEW_MAJOR) {
            currentOnMessage.value(JSONObject().put("type", "error").put("code", DocError.WEBVIEW_OUTDATED.name))
        }
    }

    // 문서를 보는 중 시스템 다크 모드·글꼴 크기가 바뀌면 바탕색·뷰어 테마·읽기 글자 크기를 바로 바꾼다 (F11, PRD 6장).
    // 첫 실행 때는 포트가 아직 없어 아무것도 보내지 않고, 'ready' 에서 보낸다.
    LaunchedEffect(webView, dark) {
        if (!controller.rendererGone) webView.setBackgroundColor(backgroundFor(dark))
        controller.setDark(dark)
    }
    LaunchedEffect(webView, fontScale) { controller.setFontScale(fontScale) }

    DisposableEffect(webView) {
        webView.loadUrl(VIEWER_URL)
        onDispose {
            controller.port?.close()
            controller.port = null
            controller.webView = null
            if (!controller.rendererGone) webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** viewer.css 의 --original-bg 와 같은 색 — 페이지가 그려지기 전 깜빡임을 막는다 */
private fun backgroundFor(dark: Boolean): Int = if (dark) 0xFF1C1C1F.toInt() else 0xFFEEEEF1.toInt()

private fun connect(view: WebView, controller: ViewerController, onMessage: (JSONObject) -> Unit) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
    ) {
        onMessage(JSONObject().put("type", "error").put("code", "UNSUPPORTED"))
        return
    }
    val (nativePort, pagePort) = WebViewCompat.createWebMessageChannel(view)
    nativePort.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
        override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
            val data = message?.data ?: return
            runCatching { JSONObject(data) }.onSuccess(onMessage)
        }
    })
    controller.port = nativePort
    WebViewCompat.postWebMessage(view, WebMessageCompat("port", arrayOf(pagePort)), Uri.parse(ASSET_ORIGIN))
}

/**
 * 문서 바이트를 조각으로 나눠 보낸다 — 문서 전체를 한 배열로 읽으면 큰 문서에서 힙이 터진다.
 * {type:"open", size} → 조각들(1안: ArrayBuffer, 2안: 지원하지 않는 WebView 에서는 Base64 chunk) → {type:"end"} (PRD 8.4).
 * 다크 모드·시스템 글꼴 배율은 문서와 따로 theme·fontScale 메시지로 보낸다.
 */
private fun CoroutineScope.sendDocument(
    controller: ViewerController,
    doc: OpenedDocument,
    modes: Map<String, String>,
    fontStep: Int,
    onError: (DocError) -> Unit,
) {
    launch {
        try {
            val size = withContext(Dispatchers.IO) { doc.file.length() }
            controller.send(
                JSONObject()
                    .put("type", "open")
                    .put("name", doc.name)
                    .put("size", size)
                    .put("modes", JSONObject(modes))
                    .put("fontStep", fontStep),
            )
            val asBuffer = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
            withContext(Dispatchers.IO) { doc.file.inputStream() }.use { input ->
                while (true) {
                    val slice = withContext(Dispatchers.IO) { input.readSlice(SLICE_BYTES) } ?: break
                    val message = if (asBuffer) {
                        WebMessageCompat(slice)
                    } else {
                        WebMessageCompat(
                            withContext(Dispatchers.Default) {
                                JSONObject().put("type", "chunk").put("data", Base64.encodeToString(slice, Base64.NO_WRAP)).toString()
                            },
                        )
                    }
                    // 화면이 닫혔거나 렌더러가 죽었으면 그만 보낸다
                    val port = controller.port ?: return@launch
                    port.postMessage(message)
                }
            }
            controller.send(JSONObject().put("type", "end"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            onError(DocError.UNREADABLE)
        } catch (_: OutOfMemoryError) {
            onError(DocError.TOO_LARGE)
        }
    }
}

/** 최대 n 바이트를 읽는다. 끝이면 null. */
private fun InputStream.readSlice(n: Int): ByteArray? {
    val buf = ByteArray(n)
    var filled = 0
    while (filled < n) {
        val r = read(buf, filled, n - filled)
        if (r < 0) break
        filled += r
    }
    return when (filled) {
        0 -> null
        n -> buf
        else -> buf.copyOf(filled)
    }
}
