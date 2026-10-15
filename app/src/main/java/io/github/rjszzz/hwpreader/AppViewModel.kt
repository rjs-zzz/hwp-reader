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

    /** 최근 문서에 올려도 되는 URI — 영구 읽기 권한을 얻은 것만 나중에 다시 열 수 있다 */
    private var persistable = false

    init {
        loader.clearCache()
    }

    /**
     * SAF 로 고른 문서는 영구 읽기 권한을 받을 수 있다. 다른 앱이 VIEW·SEND 로 넘긴 URI 는 대개 일회성 권한이라
     * 시도만 하고, 실패하면 최근 문서에 올리지 않는다(나중에 다시 열 수 없으므로).
     */
    fun open(uri: Uri) {
        viewModelScope.launch {
            closeCurrent()
            loading = true
            persistable = tryPersist(uri)
            when (val r = loader.load(uri)) {
                is LoadResult.Ok -> screen = Screen.Viewer(r.doc)
                is LoadResult.Failed -> {
                    error = r.error
                    if (r.error == DocError.UNREADABLE) removeRecent(uri.toString())
                }
            }
            loading = false
        }
    }

    fun onOpened(doc: OpenedDocument, format: String) {
        if (!persistable) return
        prefs.addRecent(RecentDoc(doc.uri.toString(), doc.name, format, System.currentTimeMillis()))
        recents = prefs.recents()
    }

    fun onViewerError(err: DocError) {
        error = err
        closeViewer()
    }

    fun closeViewer() {
        closeCurrent()
        screen = Screen.Home
    }

    fun dismissError() {
        error = null
    }

    fun go(target: Screen) {
        screen = target
    }

    fun changeRecentEnabled(enabled: Boolean) {
        prefs.recentEnabled = enabled
        recentEnabled = enabled
        recents = prefs.recents()
    }

    fun clearRecents() {
        prefs.clearRecents()
        recents = emptyList()
    }

    fun removeRecent(uri: String) {
        prefs.removeRecent(uri)
        recents = prefs.recents()
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
