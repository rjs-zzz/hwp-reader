package io.github.rjszzz.hwpreader

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import io.github.rjszzz.hwpreader.data.DocError
import io.github.rjszzz.hwpreader.data.DocKind
import io.github.rjszzz.hwpreader.ui.HomeScreen
import io.github.rjszzz.hwpreader.ui.HwpReaderTheme
import io.github.rjszzz.hwpreader.ui.LicensesScreen
import io.github.rjszzz.hwpreader.ui.SettingsScreen
import io.github.rjszzz.hwpreader.ui.ViewerScreen
import io.github.rjszzz.hwpreader.ui.holdRendererRelease
import io.github.rjszzz.hwpreader.ui.resumeRendererRelease

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // SAF — 저장소 권한 없이 사용자가 고른 문서만 읽는다 (F1, SEC-2).
    // CATEGORY_OPENABLE: 바이트로 읽을 수 없는 항목(Google 문서 같은 가상 문서)은 고르기 화면에서 고를 수 없게 한다.
    // androidx 의 OpenDocument 는 이 카테고리를 넣지 않는다.
    private val pickDocument = registerForActivityResult(OpenableDocument()) { uri ->
        uri?.let(::openDocument)
    }

    private class OpenableDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 최근 앱 목록에서 다시 띄우면 처음 열 때의 VIEW·SEND 인텐트가 다시 들어온다 — 이미 닫은 문서를 또 열지 않는다
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)

        setContent {
            // 열기가 끝났는데 문서 WebView 가 없으면(실패해 홈에 남음, PDF 를 엶) 미뤄 둔 렌더러 끝내기를 다시 건다.
            // loading 이 아니라 끝난 횟수를 본다 — 금방 실패한 열기는 loading 변화가 화면에 보이지 않아 렌더러가 계속 남는다.
            LaunchedEffect(vm.openSettled) {
                if (!vm.loading && (vm.screen as? Screen.Viewer)?.doc?.kind != DocKind.WEB) resumeRendererRelease()
            }

            HwpReaderTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(
                            recents = vm.recents,
                            recentEnabled = vm.recentEnabled,
                            loading = vm.loading,
                            onPickFile = {
                                // 문서 선택기를 끈 기기(MDM 등)에서는 고를 앱이 없다 — 앱을 죽이지 않고 알린다
                                try {
                                    pickDocument.launch(PICK_TYPES)
                                } catch (_: ActivityNotFoundException) {
                                    vm.showError(DocError.PICKER_UNAVAILABLE)
                                }
                            },
                            onOpenRecent = { openDocument(Uri.parse(it.uri)) },
                            onSettings = { vm.go(Screen.Settings) },
                        )
                        // 문서마다 화면 상태(WebView·쪽 수·검색·대화상자·스크롤)를 새로 만든다 — 이전 문서가 남지 않게
                        is Screen.Viewer -> key(s.doc) {
                            Box(Modifier.fillMaxSize()) {
                                ViewerScreen(
                                    doc = s.doc,
                                    prefs = vm.prefs,
                                    onBack = vm::closeViewer,
                                    onOpened = { format -> vm.onOpened(s.doc, format) },
                                    onError = { err -> vm.onViewerError(s.doc, err) },
                                )
                                // 문서를 보는 중에 다른 문서를 여는 동안
                                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                            }
                        }
                        Screen.Settings -> SettingsScreen(
                            prefs = vm.prefs,
                            recentEnabled = vm.recentEnabled,
                            onRecentEnabled = vm::changeRecentEnabled,
                            onClearRecents = vm::clearRecents,
                            onLicenses = { vm.go(Screen.Licenses) },
                            onBack = { vm.go(Screen.Home) },
                        )
                        Screen.Licenses -> LicensesScreen(onBack = { vm.go(Screen.Settings) })
                    }

                    vm.error?.let { err ->
                        AlertDialog(
                            onDismissRequest = vm::dismissError,
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(err.message)
                                    // 문제를 알려 줄 때 쓰는 진단 코드 — 파일 이름·경로는 들어 있지 않다
                                    vm.errorDetail?.let {
                                        Text(
                                            "오류 코드: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = vm::dismissError) { Text("확인") } },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 보낸 앱이 넣은 엑스트라가 이 앱에 없는 클래스면 꺼내는 순간 예외가 난다
        val uri = runCatching {
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> null
            }
        }.getOrNull() ?: return
        // content:// 만 받는다 — file:// 로 이 앱의 내부 파일을 열게 하는 요청을 막는다
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            vm.showError(DocError.SOURCE_UNAVAILABLE)
            return
        }
        openDocument(uri)
    }

    /** 모든 열기 경로(인텐트·파일 열기·최근 문서)가 지나는 곳 */
    private fun openDocument(uri: Uri) {
        // 문서를 닫은 뒤 걸어 둔 렌더러 끝내기를 미룬다 — 곧 새 문서 WebView 가 그 렌더러를 쓴다
        holdRendererRelease()
        vm.open(uri)
    }

    companion object {
        // 확장자·MIME 은 믿지 않는다(F2) — 고르기 화면에서 보이게만 넓게 연다
        private val PICK_TYPES = arrayOf("*/*")
    }
}
