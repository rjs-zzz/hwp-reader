package io.github.rjszzz.hwpreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 문서를 어느 쪽 엔진으로 여는가 — PDF 는 네이티브 PdfRenderer, 나머지는 WebView 의 kordoc */
enum class DocKind { PDF, WEB }

data class OpenedDocument(
    val uri: Uri,
    val name: String,
    val file: File,
    val kind: DocKind,
    val size: Long,
    /** 영구 읽기 권한을 얻었는가 — 얻은 문서만 최근 문서에 올린다(나중에 다시 열 수 있으므로) */
    val persistable: Boolean = false,
)

/** 사용자에게 보이는 오류 (PRD 10장). 문구에 내부 경로·스택을 넣지 않는다. */
enum class DocError(val message: String) {
    ENCRYPTED("암호가 걸린 문서입니다. 이 버전에서는 열 수 없습니다."),
    UNKNOWN_FORMAT("형식을 알 수 없습니다. 보안(DRM)이 적용된 문서라면 PC에서 열어 주세요."),
    LEGACY_OFFICE("구형 오피스 형식(.ppt·.doc)은 지원하지 않습니다."),
    UNSUPPORTED("지원하지 않는 형식입니다."),
    CORRUPTED("문서가 손상되어 열 수 없습니다."),
    TOO_LARGE("파일이 너무 커서 열 수 없습니다."),
    UNREADABLE("파일을 읽을 수 없습니다. 보낸 앱에서 다시 열어 주세요."),
    RENDER_FAILED("문서를 표시하다 멈췄습니다. 파일이 너무 크거나 손상되었을 수 있습니다."),
    WEBVIEW_OUTDATED("문서를 표시할 수 없습니다. Play 스토어에서 'Android System WebView'를 업데이트한 뒤 다시 열어 주세요.");

    companion object {
        fun fromCode(code: String?): DocError = entries.firstOrNull { it.name == code } ?: CORRUPTED
    }
}

sealed interface LoadResult {
    data class Ok(val doc: OpenedDocument) : LoadResult
    data class Failed(val error: DocError) : LoadResult
}

/**
 * URI 로 받은 문서를 앱 전용 cacheDir 에 사본으로 둔다 (PRD SEC-3).
 * 사본은 문서를 닫을 때와 앱 시작 시 지운다.
 */
class DocumentLoader(private val context: Context) {
    private val dir = File(context.cacheDir, "open")

    fun clearCache() {
        dir.deleteRecursively()
    }

    fun discard(doc: OpenedDocument) {
        doc.file.delete()
    }

    /**
     * 다른 앱의 제공자는 어떤 예외든 던질 수 있다(잘못 설정된 FileProvider, query 미구현 등).
     * 취소가 아닌 모든 실패는 '읽을 수 없음'으로 돌려주고, 만들다 만 사본은 지운다.
     * 복사를 다 마친 직후에 취소돼도(withContext 는 이때 결과를 버린다) 사본을 지운다.
     */
    suspend fun load(uri: Uri, persistable: Boolean = false): LoadResult {
        var created: File? = null
        try {
            return copyIn(uri, persistable) { created = it }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            throw e
        }
    }

    private suspend fun copyIn(uri: Uri, persistable: Boolean, onCreated: (File) -> Unit): LoadResult = withContext(Dispatchers.IO) {
        var file: File? = null
        var ok = false
        try {
            val (name, declaredSize) = queryMeta(uri)
            if (declaredSize != null && declaredSize > MAX_BYTES) return@withContext LoadResult.Failed(DocError.TOO_LARGE)

            dir.mkdirs()
            val target = File(dir, UUID.randomUUID().toString()).also { file = it; onCreated(it) }
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copyLimited(input, output) }
            } ?: return@withContext LoadResult.Failed(DocError.UNREADABLE)

            if (copied < 0) return@withContext LoadResult.Failed(DocError.TOO_LARGE)
            val kind = if (startsWithPdfMagic(target)) DocKind.PDF else DocKind.WEB
            ok = true
            LoadResult.Ok(OpenedDocument(uri, name, target, kind, copied, persistable))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadResult.Failed(DocError.UNREADABLE)
        } finally {
            if (!ok) file?.delete()
        }
    }

    /** 이름·크기는 있으면 좋은 정보일 뿐 — 조회가 실패해도 복사는 시도한다 */
    private fun queryMeta(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = runCatching { c.getString(ni) }.getOrNull()
                        if (si >= 0 && !c.isNull(si)) size = runCatching { c.getLong(si) }.getOrNull()
                    }
                }
        }
        val fallback = runCatching { uri.lastPathSegment?.substringAfterLast('/') }.getOrNull()
        return (name?.takeIf { it.isNotBlank() } ?: fallback ?: "문서") to size
    }

    /** 상한을 넘으면 -1 — 크기를 알려주지 않는 제공자도 있어 복사하면서 센다. 취소되면 중간에 멈춘다. */
    private suspend fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > MAX_BYTES) return -1
            output.write(buf, 0, n)
        }
    }

    private fun startsWithPdfMagic(file: File): Boolean {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        return hasPdfMagic(head, n)
    }

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024

        /** %PDF 가 맨 앞이 아니라 1KB 안쪽에 오는 파일도 PDF 뷰어들은 연다 */
        fun hasPdfMagic(head: ByteArray, length: Int): Boolean {
            if (length < 5) return false
            val text = String(head, 0, length, Charsets.ISO_8859_1)
            return text.indexOf("%PDF-") in 0..1019
        }
    }
}
