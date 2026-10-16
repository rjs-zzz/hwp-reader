package io.github.rjszzz.hwpreader.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.rjszzz.hwpreader.data.OpenedDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val VIEWER_URL = "$ASSET_ORIGIN/assets/viewer/viewer.html"
private const val CHUNK_BYTES = 384 * 1024

/** 네이티브 → 뷰어 명령. 메시지 형식은 web/src/viewer.js 머리말 참조. */
class ViewerController {
    internal var port: WebMessagePortCompat? = null
    internal var webView: WebView? = null

    /** 폭 맞춤 배율(문서를 연 직후)과 현재 배율 — onScaleChanged 로 따라간다 */
    internal var baseScale = 0f
    internal var currentScale = 0f

    fun setMode(mode: String) = send(JSONObject().put("type", "mode").put("mode", mode))
    fun setFontStep(step: Int) = send(JSONObject().put("type", "fontStep").put("step", step))
    fun goTo(page: Int) = send(JSONObject().put("type", "goto").put("page", page))
    fun setDark(dark: Boolean) = send(JSONObject().put("type", "theme").put("dark", dark))
    fun find(query: String) = send(JSONObject().put("type", "find").put("query", query))
    fun findNext(forward: Boolean) = send(JSONObject().put("type", "findNext").put("forward", forward))
    fun findClear() = send(JSONObject().put("type", "findClear"))

    /** 두 번 탭 — 확대돼 있으면 폭 맞춤으로, 아니면 2배로 (F3) */
    fun toggleFitWidth() {
        val view = webView ?: return
        if (baseScale <= 0f || currentScale <= 0f) return
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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocumentWebView(
    doc: OpenedDocument,
    /** 형식별 마지막 보기 방식 (F5) — 실제 형식은 뷰어가 매직바이트로 정한 뒤 고른다 */
    modes: Map<String, String>,
    fontStep: Int,
    dark: Boolean,
    controller: ViewerController,
    onMessage: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 시스템 글꼴 크기 — 읽기 모드에만 적용한다. fontScale 은 configChanges 에 없어 바뀌면 Activity 가 새로 뜨고 문서를 다시 보낸다.
    val fontScale = LocalDensity.current.fontScale
    val scope = rememberCoroutineScope()
    val currentOnMessage = rememberUpdatedState(onMessage)
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val webView = remember {
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
                textZoom = 100 // 원본 보기 SVG 의 줄 배치가 깨지지 않게 고정. 시스템 글꼴 크기는 읽기 모드 CSS 로만 반영한다
                blockNetworkLoads = true
            }
            setBackgroundColor(if (dark) 0xFF1C1C1F.toInt() else 0xFFEEEEF1.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                // 문서 안 링크도 뷰어 JS 가 가로채 네이티브 확인 창으로 보낸다. 여기서는 모든 이동을 막는다.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                    controller.currentScale = newScale
                }

                override fun onPageFinished(view: WebView, url: String) {
                    @Suppress("DEPRECATION")
                    controller.baseScale = view.scale
                    controller.currentScale = controller.baseScale
                    connect(view, controller) { msg ->
                        when (msg.optString("type")) {
                            "ready" -> {
                                controller.setDark(dark)
                                scope.sendDocument(controller, doc, modes, fontStep, fontScale)
                            }
                            "dbltap" -> controller.toggleFitWidth()
                        }
                        currentOnMessage.value(msg)
                    }
                }
            }
        }
    }
    controller.webView = webView

    DisposableEffect(webView) {
        webView.loadUrl(VIEWER_URL)
        onDispose {
            controller.port?.close()
            controller.port = null
            controller.webView = null
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

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

/** 1안: ArrayBuffer 한 번에. 2안: 지원하지 않는 WebView 에서는 Base64 로 나눠 보낸다 (PRD 8.4). */
private fun CoroutineScope.sendDocument(
    controller: ViewerController,
    doc: OpenedDocument,
    modes: Map<String, String>,
    fontStep: Int,
    fontScale: Float,
) {
    launch {
        val bytes = withContext(Dispatchers.IO) { doc.file.readBytes() }
        val port = controller.port ?: return@launch
        controller.send(
            JSONObject().put("type", "open").put("name", doc.name).put("modes", JSONObject(modes))
                .put("fontStep", fontStep).put("fontScale", fontScale.toDouble()),
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            port.postMessage(WebMessageCompat(bytes))
        } else {
            var off = 0
            while (off < bytes.size) {
                val len = minOf(CHUNK_BYTES, bytes.size - off)
                val b64 = withContext(Dispatchers.Default) { Base64.encodeToString(bytes, off, len, Base64.NO_WRAP) }
                controller.send(JSONObject().put("type", "chunk").put("data", b64))
                off += len
            }
            controller.send(JSONObject().put("type", "end"))
        }
    }
}
