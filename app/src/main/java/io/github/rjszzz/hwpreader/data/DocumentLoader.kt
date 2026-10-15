package io.github.rjszzz.hwpreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** 문서를 어느 쪽 엔진으로 여는가 — PDF 는 네이티브 PdfRenderer, 나머지는 WebView 의 kordoc */
enum class DocKind { PDF, WEB }

data class OpenedDocument(
    val uri: Uri,
    val name: String,
    val file: File,
    val kind: DocKind,
    val size: Long,
)

/** 사용자에게 보이는 오류 (PRD 10장). 문구에 내부 경로·스택을 넣지 않는다. */
enum class DocError(val message: String) {
    ENCRYPTED("암호가 걸린 문서입니다. 이 버전에서는 열 수 없습니다."),
    UNKNOWN_FORMAT("형식을 알 수 없습니다. 보안(DRM)이 적용된 문서라면 PC에서 열어 주세요."),
    LEGACY_OFFICE("구형 오피스 형식(.ppt·.doc)은 지원하지 않습니다."),
    UNSUPPORTED("지원하지 않는 형식입니다."),
    CORRUPTED("문서가 손상되어 열 수 없습니다."),
    TOO_LARGE("파일이 너무 커서 열 수 없습니다."),
    UNREADABLE("파일을 읽을 수 없습니다. 보낸 앱에서 다시 열어 주세요.");

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

    suspend fun load(uri: Uri): LoadResult = withContext(Dispatchers.IO) {
        try {
            val (name, declaredSize) = queryMeta(uri)
            if (declaredSize != null && declaredSize > MAX_BYTES) return@withContext LoadResult.Failed(DocError.TOO_LARGE)

            dir.mkdirs()
            val file = File(dir, UUID.randomUUID().toString())
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> copyLimited(input, output) }
            } ?: return@withContext LoadResult.Failed(DocError.UNREADABLE)

            if (copied < 0) {
                file.delete()
                return@withContext LoadResult.Failed(DocError.TOO_LARGE)
            }
            val kind = if (startsWithPdfMagic(file)) DocKind.PDF else DocKind.WEB
            LoadResult.Ok(OpenedDocument(uri, name, file, kind, copied))
        } catch (_: SecurityException) {
            LoadResult.Failed(DocError.UNREADABLE)
        } catch (_: FileNotFoundException) {
            LoadResult.Failed(DocError.UNREADABLE)
        } catch (_: IOException) {
            LoadResult.Failed(DocError.UNREADABLE)
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "문서") to size
    }

    /** 상한을 넘으면 -1 — 크기를 알려주지 않는 제공자도 있어 복사하면서 센다 */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
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
