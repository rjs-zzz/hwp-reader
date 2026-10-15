package io.github.rjszzz.hwpreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rjszzz.hwpreader.BuildConfig
import io.github.rjszzz.hwpreader.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 한컴 공개 문서 이용 조건에 따른 고지 문구 (PRD 11장) */
const val HANCOM_NOTICE = "본 제품은 한글과컴퓨터의 한/글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    recentEnabled: Boolean,
    onRecentEnabled: (Boolean) -> Unit,
    onClearRecents: () -> Unit,
    onLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var fontStep by remember { mutableIntStateOf(prefs.fontStep) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("최근 문서")
            Row(
                Modifier.fillMaxWidth().clickable { onRecentEnabled(!recentEnabled) }.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("최근 문서 기록")
                    Text("끄면 기존 기록도 지웁니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = recentEnabled, onCheckedChange = onRecentEnabled)
            }
            Item("기록 지우기", onClick = onClearRecents)
            HorizontalDivider()

            Section("읽기 모드 글자 크기")
            listOf("작게", "보통", "크게").forEachIndexed { i, label ->
                Row(
                    Modifier.fillMaxWidth().clickable { fontStep = i; prefs.fontStep = i }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = fontStep == i, onClick = { fontStep = i; prefs.fontStep = i })
                    Text(label)
                }
            }
            HorizontalDivider()

            Section("정보")
            Item("오픈소스 라이선스", onClick = onLicenses)
            Text(
                HANCOM_NOTICE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 8.dp),
            )
            Text(
                "버전 ${BuildConfig.VERSION_NAME} · 인터넷 권한 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 24.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp),
    )
}

@Composable
private fun Item(title: String, onClick: () -> Unit) {
    Text(title, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp))
}

/** 오픈소스 고지 (F8) — 뷰어 번들을 만들 때 모은 라이선스 원문(assets/viewer/licenses.txt)을 그대로 보여준다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("viewer/licenses.txt").bufferedReader().readText() }.getOrDefault("")
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오픈소스 라이선스") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(HANCOM_NOTICE + "\n\n" + ANDROID_NOTICE + "\n\n" + text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

private const val ANDROID_NOTICE =
    "AndroidX (core, activity, lifecycle, webkit, compose) — Apache License 2.0\n" +
        "Kotlin standard library, kotlinx.coroutines — Apache License 2.0"
