package io.github.rjszzz.hwpreader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rjszzz.hwpreader.data.DocError
import io.github.rjszzz.hwpreader.data.DocumentLoader
import io.github.rjszzz.hwpreader.data.LoadResult
import io.github.rjszzz.hwpreader.data.OpenedDocument
import io.github.rjszzz.hwpreader.data.Prefs
import io.github.rjszzz.hwpreader.data.RecentDoc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Viewer(val doc: OpenedDocument) : Screen
    data object Settings : Screen
    data object Licenses : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val loader = DocumentLoader(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var recents by mutableStateOf(prefs.recents())
        private set
    var recentEnabled by mutableStateOf(prefs.recentEnabled)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<DocError?>(null)
        private set

    /** 진행 중인 열기 — 새 요청이 오면 이전 요청은 취소한다(마지막 요청만 화면에 오른다) */
    private var openJob: Job? = null
    /** 열기 요청 번호 — 가장 최근 요청이 끝날 때만 loading 을 끈다 */
    private var openSeq = 0
    /** 읽어 들이는 중인 문서 — 그 사이 syncGrants 가 막 받은 권한을 돌려주지 않게 한다 */
    private val loadingUris = mutableSetOf<String>()

    init {
        loader.clearCache()
        syncGrants()
    }

    /**
     * SAF 로 고른 문서는 영구 읽기 권한을 받을 수 있다. 다른 앱이 VIEW·SEND 로 넘긴 URI 는 대개 일회성 권한이라
     * 시도만 하고, 실패하면 최근 문서에 올리지 않는다(나중에 다시 열 수 없으므로).
     * 최근 문서 기록이 꺼져 있으면 영구 권한을 아예 받지 않는다 — 기록에 없는 파일의 권한을 쌓아 두지 않는다.
     * 보던 문서는 새 문서가 열린 뒤에 버린다 — 새 문서가 실패하면 보던 문서를 그대로 둔다.
     */
    fun open(uri: Uri) {
        openJob?.cancel()
        val seq = ++openSeq
        loading = true
        openJob = viewModelScope.launch {
            val key = uri.toString()
            loadingUris += key
            try {
                val persistable = recentEnabled && tryPersist(uri)
                val result = try {
                    loader.load(uri, persistable)
                } finally {
                    loadingUris -= key
                }
                when (val r = result) {
                    is LoadResult.Ok -> {
                        val previous = (screen as? Screen.Viewer)?.doc
                        screen = Screen.Viewer(r.doc)
                        previous?.let(loader::discard)
                    }
                    is LoadResult.Failed -> {
                        error = r.error
                        if (r.error == DocError.UNREADABLE) removeRecent(uri.toString()) else syncGrants()
                    }
                }
            } finally {
                if (seq == openSeq) loading = false
            }
        }
    }

    fun onOpened(doc: OpenedDocument, format: String) {
        if (!doc.persistable || !isShowing(doc)) return
        prefs.addRecent(RecentDoc(doc.uri.toString(), doc.name, format, System.currentTimeMillis()))
        recents = prefs.recents()
        syncGrants() // 20개를 넘어 밀려난 문서의 권한을 돌려준다
    }

    /** 이미 다른 문서로 넘어간 뒤 늦게 도착한 오류는 무시한다 */
    fun onViewerError(doc: OpenedDocument, err: DocError) {
        if (!isShowing(doc)) return
        error = err
        closeViewer()
    }

    private fun isShowing(doc: OpenedDocument) = (screen as? Screen.Viewer)?.doc == doc

    fun closeViewer() {
        closeCurrent()
        screen = Screen.Home
        syncGrants() // 열다 실패해 기록에 오르지 못한 문서의 권한을 돌려준다
    }

    fun dismissError() {
        error = null
    }

    fun showError(err: DocError) {
        error = err
    }

    fun go(target: Screen) {
        screen = target
    }

    fun changeRecentEnabled(enabled: Boolean) {
        prefs.recentEnabled = enabled
        recentEnabled = enabled
        recents = prefs.recents()
        syncGrants()
    }

    fun clearRecents() {
        prefs.clearRecents()
        recents = emptyList()
        syncGrants()
    }

    fun removeRecent(uri: String) {
        prefs.removeRecent(uri)
        recents = prefs.recents()
        syncGrants()
    }

    /**
     * 최근 문서에 없는 파일의 영구 읽기 권한을 돌려준다 (PRD SEC-2·SEC-5).
     * 기록을 끄거나 지우면 목록에서만 사라지는 게 아니라 그 파일들을 다시 읽을 권한도 함께 놓는다.
     */
    private fun syncGrants() {
        val resolver = getApplication<Application>().contentResolver
        val keep = recents.map { it.uri }.toSet() + loadingUris
        for (grant in runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())) {
            if (grant.uri.toString() !in keep) {
                runCatching { resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    private fun closeCurrent() {
        (screen as? Screen.Viewer)?.let { loader.discard(it.doc) }
    }

    private fun tryPersist(uri: Uri): Boolean {
        return runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
    }

    override fun onCleared() {
        loader.clearCache()
    }
}
