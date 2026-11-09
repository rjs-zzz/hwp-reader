package io.github.rjszzz.hwpreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLoaderTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun pdfHeaderAtStart() {
        val b = bytes("%PDF-1.7\n...")
        assertTrue(DocumentLoader.hasPdfMagic(b, b.size))
    }

    @Test
    fun pdfHeaderAfterJunkWithinFirstKilobyte() {
        val b = bytes(" ".repeat(500) + "%PDF-1.4")
        assertTrue(DocumentLoader.hasPdfMagic(b, b.size))
    }

    @Test
    fun hwpOleHeaderIsNotPdf() {
        val b = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size))
    }

    @Test
    fun zipHeaderIsNotPdf() {
        val b = bytes("PK\u0003\u0004mimetypeapplication/hwp+zip")
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size))
    }

    @Test
    fun tooShortIsNotPdf() {
        val b = bytes("%PDF")
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size))
    }

    @Test
    fun onlyReadLengthCounts() {
        // 버퍼 뒤쪽에 남은 쓰레기 값은 보지 않는다
        val b = bytes("abc%PDF-1.7")
        assertFalse(DocumentLoader.hasPdfMagic(b, 3))
    }

    @Test
    fun zipWithEmbeddedPdfHeaderIsNotPdf() {
        // DOCX·HWPX 의 첫 항목에 PDF 가 압축 없이 들어 있어도 ZIP 이다
        val b = bytes("PK\u0003\u0004" + " ".repeat(40) + "%PDF-1.7")
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size, "report.docx"))
    }

    @Test
    fun oleWithEmbeddedPdfHeaderIsNotPdf() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val b = ole + bytes(" ".repeat(500) + "%PDF-1.4")
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size, "a.hwp"))
    }

    @Test
    fun markdownMentioningPdfHeaderIsNotPdf() {
        val b = bytes("# 메모\n\nPDF 파일은 %PDF-1.7 로 시작한다.")
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size, "notes.md"))
        assertFalse(DocumentLoader.hasPdfMagic(b, b.size, "NOTES.TXT"))
        // 이름이 텍스트 문서가 아니면 앞에 쓰레기가 붙은 PDF 로 본다(PDF 뷰어들과 같다)
        assertTrue(DocumentLoader.hasPdfMagic(b, b.size, "scan.pdf"))
    }

    @Test
    fun textNamedFileStartingWithPdfHeaderIsPdf() {
        val b = bytes("%PDF-1.7\n...")
        assertTrue(DocumentLoader.hasPdfMagic(b, b.size, "mislabelled.txt"))
    }

    @Test
    fun diagnosticCodeHasNoNameOrPath() {
        val code = DocumentLoader.detail("O", java.io.FileNotFoundException("/storage/emulated/0/Download/환자명단.hwp"), null)
        assertEquals("O/FileNotFoundException", code)
        assertEquals("R/null", DocumentLoader.detail("R", null, null))
    }

    @Test
    fun noSpaceIsRecognizedFromTheMessage() {
        assertTrue(DocumentLoader.isNoSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(DocumentLoader.isNoSpace(java.io.IOException("read failed: EIO (I/O error)")))
        assertFalse(DocumentLoader.isNoSpace(null))
    }

    @Test
    fun existingViewerCodesKeepTheirNames() {
        // 뷰어 JS 가 보내는 코드 이름 — 바꾸면 '손상'으로 잘못 보인다
        for (code in listOf("ENCRYPTED", "UNKNOWN_FORMAT", "LEGACY_OFFICE", "UNSUPPORTED", "CORRUPTED", "TOO_LARGE", "UNREADABLE", "WEBVIEW_OUTDATED")) {
            assertEquals(code, DocError.fromCode(code).name)
        }
    }

    @Test
    fun viewerErrorCodesMapToMessages() {
        assertEquals(DocError.ENCRYPTED, DocError.fromCode("ENCRYPTED"))
        assertEquals(DocError.LEGACY_OFFICE, DocError.fromCode("LEGACY_OFFICE"))
        assertEquals(DocError.UNKNOWN_FORMAT, DocError.fromCode("UNKNOWN_FORMAT"))
        // 모르는 코드·빈 값은 '손상'으로 — 내부 메시지를 사용자에게 보이지 않는다 (PRD 10장)
        assertEquals(DocError.CORRUPTED, DocError.fromCode("SOMETHING_NEW"))
        assertEquals(DocError.CORRUPTED, DocError.fromCode(null))
    }
}
