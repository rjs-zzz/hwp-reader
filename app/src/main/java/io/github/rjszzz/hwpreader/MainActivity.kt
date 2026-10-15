package io.github.rjszzz.hwpreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.core.content.IntentCompat
import io.github.rjszzz.hwpreader.ui.HomeScreen
import io.github.rjszzz.hwpreader.ui.HwpReaderTheme
import io.github.rjszzz.hwpreader.ui.LicensesScreen
import io.github.rjszzz.hwpreader.ui.SettingsScreen
import io.github.rjszzz.hwpreader.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // SAF — 저장소 권한 없이 사용자가 고른 문서만 읽는다 (F1, SEC-2)
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            HwpReaderTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(
                            recents = vm.recents,
                            recentEnabled = vm.recentEnabled,
                            loading = vm.loading,
                            onPickFile = { pickDocument.launch(PICK_TYPES) },
                            onOpenRecent = { vm.open(Uri.parse(it.uri)) },
                            onSettings = { vm.go(Screen.Settings) },
                        )
                        is Screen.Viewer -> ViewerScreen(
                            doc = s.doc,
                            prefs = vm.prefs,
                            onBack = vm::closeViewer,
                            onOpened = { format -> vm.onOpened(s.doc, format) },
                            onError = vm::onViewerError,
                        )
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
                            text = { Text(err.message) },
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
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        vm.open(uri)
    }

    companion object {
        // 확장자·MIME 은 믿지 않는다(F2) — 고르기 화면에서 보이게만 넓게 연다
        private val PICK_TYPES = arrayOf("*/*")
    }
}
