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
    fun viewerErrorCodesMapToMessages() {
        assertEquals(DocError.ENCRYPTED, DocError.fromCode("ENCRYPTED"))
        assertEquals(DocError.LEGACY_OFFICE, DocError.fromCode("LEGACY_OFFICE"))
        assertEquals(DocError.UNKNOWN_FORMAT, DocError.fromCode("UNKNOWN_FORMAT"))
        // 모르는 코드·빈 값은 '손상'으로 — 내부 메시지를 사용자에게 보이지 않는다 (PRD 10장)
        assertEquals(DocError.CORRUPTED, DocError.fromCode("SOMETHING_NEW"))
        assertEquals(DocError.CORRUPTED, DocError.fromCode(null))
    }
}
