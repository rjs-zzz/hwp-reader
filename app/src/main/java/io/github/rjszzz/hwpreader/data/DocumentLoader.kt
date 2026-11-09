package io.github.rjszzz.hwpreader.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

/**
 * 사용자에게 보이는 오류 (PRD 10장). 문구에 내부 경로·스택을 넣지 않는다.
 * 이름은 뷰어 JS 가 보내는 코드와 같아야 한다(fromCode) — 있는 이름은 바꾸지 않는다.
 */
enum class DocError(val message: String) {
    ENCRYPTED("암호가 걸린 문서입니다. 이 버전에서는 열 수 없습니다."),
    UNKNOWN_FORMAT("형식을 알 수 없습니다. 보안(DRM)이 적용된 문서라면 PC에서 열어 주세요."),
    LEGACY_OFFICE("구형 오피스 형식(.ppt·.doc·.xls)은 지원하지 않습니다."),
    UNSUPPORTED("지원하지 않는 형식입니다."),
    CORRUPTED("문서가 손상되어 열 수 없습니다."),
    TOO_LARGE("파일이 너무 커서 열 수 없습니다."),
    /** 읽는 도중 끊겼다 — 대개 일시적이다(클라우드 연결, 저장장치 분리 등) */
    UNREADABLE("파일을 읽다가 멈췄습니다. 잠시 뒤 다시 열어 주세요."),
    /** 제공자가 파일을 내주지 않았다 — 없는 파일, 받지 않은 클라우드 파일, 끝난 읽기 권한 */
    SOURCE_UNAVAILABLE("파일을 열 수 없습니다. 파일이 옮겨졌거나 지워졌을 수 있습니다. 클라우드 파일이라면 기기에 받은 뒤 다시 열어 주세요."),
    EMPTY_FILE("빈 파일입니다. 내려받기가 끝났는지 확인해 주세요."),
    STORAGE_FULL("기기 저장 공간이 부족해 열 수 없습니다. 공간을 비운 뒤 다시 열어 주세요."),
    PICKER_UNAVAILABLE("파일 선택 화면을 열 수 없습니다. 기기의 '파일' 앱이 꺼져 있지 않은지 확인해 주세요."),
    RENDER_FAILED("문서를 표시하다 멈췄습니다. 파일이 너무 크거나 손상되었을 수 있습니다."),
    WEBVIEW_OUTDATED("문서를 표시할 수 없습니다. Play 스토어에서 'Android System WebView'를 업데이트한 뒤 다시 열어 주세요.");

    companion object {
        fun fromCode(code: String?): DocError = entries.firstOrNull { it.name == code } ?: CORRUPTED
    }
}

sealed interface LoadResult {
    data class Ok(val doc: OpenedDocument) : LoadResult

    /**
     * detail: 원인을 가리는 짧은 진단 코드 — 단계(O 열기·R 읽기·W 저장) / 예외 종류 / 제공자(authority).
     * 파일 이름·경로·문서 ID 는 넣지 않는다(SEC-6). 로그를 남기지 않는 앱이라 사용자가 알려 줄 수 있게 화면에만 보인다.
     */
    data class Failed(val error: DocError, val detail: String? = null) : LoadResult
}

/**
 * URI 로 받은 문서를 앱 전용 cacheDir 에 사본으로 둔다 (PRD SEC-3).
 * 사본은 문서를 닫을 때와 앱 시작 시 지운다. 앱이 강제로 끝나면(시스템 회수 등) 다음 시작 때 지운다.
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
     * 취소가 아닌 실패는 단계별 오류로 돌려주고, 만들다 만 사본은 지운다.
     * 복사를 다 마친 직후에 취소돼도(withContext 는 이때 결과를 버린다) 사본을 지운다.
     * 취소되면 제공자 쪽 열기(CancellationSignal)와 멈춰 있는 읽기(스트림 닫기)도 끊는다 — 응답 없는 클라우드 제공자가
     * 다음 열기를 막지 않게.
     */
    suspend fun load(uri: Uri, persistable: Boolean = false): LoadResult = coroutineScope {
        val abort = Abort()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                abort.fire()
            }
        }
        var created: File? = null
        try {
            copyIn(uri, persistable, abort) { created = it }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            throw e
        } finally {
            abort.finish()
            watcher.cancel()
        }
    }

    private suspend fun copyIn(uri: Uri, persistable: Boolean, abort: Abort, onCreated: (File) -> Unit): LoadResult =
        withContext(Dispatchers.IO) {
            var file: File? = null
            var ok = false
            try {
                val (name, declaredSize) = queryMeta(uri, abort.signal)
                if (declaredSize != null && declaredSize > MAX_BYTES) return@withContext LoadResult.Failed(DocError.TOO_LARGE)

                val input = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r", abort.signal)?.createInputStream()
                } catch (e: Exception) {
                    return@withContext LoadResult.Failed(DocError.SOURCE_UNAVAILABLE, detail("O", e, uri))
                } ?: return@withContext LoadResult.Failed(DocError.SOURCE_UNAVAILABLE, detail("O", null, uri))
                abort.attach(input)

                val copied = input.use {
                    dir.mkdirs()
                    val target = File(dir, UUID.randomUUID().toString()).also { f -> file = f; onCreated(f) }
                    val output = try {
                        target.outputStream()
                    } catch (e: IOException) {
                        throw StageException("W", e)
                    }
                    output.use { out -> copyLimited(input, out) }
                }
                val target = file!!

                if (copied < 0) return@withContext LoadResult.Failed(DocError.TOO_LARGE)
                if (copied == 0L) return@withContext LoadResult.Failed(DocError.EMPTY_FILE)
                val kind = if (startsWithPdfMagic(target, name)) DocKind.PDF else DocKind.WEB
                ok = true
                LoadResult.Ok(OpenedDocument(uri, name, target, kind, copied, persistable))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StageException) {
                val cause = e.cause
                val error = if (e.stage == "W" && isNoSpace(cause)) DocError.STORAGE_FULL else DocError.UNREADABLE
                LoadResult.Failed(error, detail(e.stage, cause, uri))
            } catch (e: Exception) {
                LoadResult.Failed(DocError.UNREADABLE, detail("X", e, uri))
            } finally {
                if (!ok) file?.delete()
            }
        }

    /** 이름·크기는 있으면 좋은 정보일 뿐 — 조회가 실패해도 복사는 시도한다 */
    private fun queryMeta(uri: Uri, signal: CancellationSignal): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
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
    private suspend fun copyLimited(input: InputStream, output: OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                throw StageException("R", e)
            }
            if (n < 0) return total
            total += n
            if (total > MAX_BYTES) return -1
            try {
                output.write(buf, 0, n)
            } catch (e: IOException) {
                throw StageException("W", e)
            }
        }
    }

    private fun startsWithPdfMagic(file: File, name: String): Boolean {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        return hasPdfMagic(head, n, name)
    }

    /** 어느 단계에서 실패했는가 — R: 원본 읽기, W: 사본 쓰기 */
    private class StageException(val stage: String, cause: IOException) : Exception(cause)

    /** 취소되면 제공자 열기를 끊고, 이미 연 스트림을 닫아 멈춘 읽기를 풀어 준다. 정상 종료 뒤에는 아무것도 하지 않는다. */
    private class Abort {
        val signal = CancellationSignal()
        @Volatile private var stream: Closeable? = null
        @Volatile private var fired = false
        @Volatile private var finished = false

        fun attach(c: Closeable) {
            stream = c
            if (fired) runCatching { c.close() }
        }

        fun fire() {
            if (finished) return
            fired = true
            runCatching { signal.cancel() }
            runCatching { stream?.close() }
        }

        fun finish() {
            finished = true
        }
    }

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        private val TEXT_NAME = Regex("""\.(md|markdown|txt)$""", RegexOption.IGNORE_CASE)

        /**
         * %PDF 가 맨 앞이 아니라 1KB 안쪽에 오는 파일도 PDF 뷰어들은 연다. 다만
         * - ZIP(HWPX·DOCX·PPTX)·OLE(HWP·구형 오피스)는 머리 바이트가 분명하다 — 안쪽 데이터에 '%PDF-' 가 있어도 PDF 가 아니다.
         * - 이름이 텍스트 문서(.md·.markdown·.txt)면 맨 앞에 있을 때만 PDF 로 본다 — 본문에 '%PDF-' 를 적은 메모가 있다.
         */
        fun hasPdfMagic(head: ByteArray, length: Int, name: String? = null): Boolean {
            if (length < 5) return false
            if (startsWith(head, length, ZIP_MAGIC) || startsWith(head, length, OLE_MAGIC)) return false
            val at = String(head, 0, length, Charsets.ISO_8859_1).indexOf("%PDF-")
            if (at == 0) return true
            if (at !in 1..1019) return false
            return name == null || !TEXT_NAME.containsMatchIn(name)
        }

        private fun startsWith(head: ByteArray, length: Int, magic: ByteArray): Boolean =
            length >= magic.size && magic.indices.all { head[it] == magic[it] }

        /** 저장 공간 부족(ENOSPC)인가 — 안드로이드는 ErrnoException 을 IOException 의 원인으로 감싼다 */
        internal fun isNoSpace(e: Throwable?): Boolean {
            var t = e
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
                if (t.message?.contains("ENOSPC") == true) return true
                t = t.cause
            }
            return false
        }

        /** 진단 코드 — 예: "O/FileNotFoundException/com.android.providers.downloads.documents". 이름·경로는 넣지 않는다. */
        internal fun detail(stage: String, e: Throwable?, uri: Uri?): String =
            listOfNotNull(stage, e?.javaClass?.simpleName ?: "null", uri?.authority).joinToString("/")
    }
}
