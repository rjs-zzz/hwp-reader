package io.github.rjszzz.hwpreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSizeTest {
    @Test
    fun normalPageKeepsItsRatio() {
        assertEquals(595f / 842f, pdfPageRatio(595, 842), 1e-6f)
    }

    @Test
    fun degeneratePageSizesFallBackToA4() {
        // 폭이나 높이가 1pt 미만인 조작된 쪽 — aspectRatio(0) 으로 앱이 죽으면 안 된다
        val a4 = 1f / 1.4142f
        assertEquals(a4, pdfPageRatio(0, 792), 1e-6f)
        assertEquals(a4, pdfPageRatio(612, 0), 1e-6f)
        assertEquals(a4, pdfPageRatio(-5, 100), 1e-6f)
    }

    @Test
    fun extremeRatiosAreClamped() {
        assertEquals(0.05f, pdfPageRatio(1, 10_000), 1e-6f)
        assertEquals(20f, pdfPageRatio(10_000, 1), 1e-6f)
    }

    @Test
    fun a4AtScreenWidthIsRenderedAsRequested() {
        val (w, h) = pdfBitmapSize(1080, 595f / 842f)
        assertEquals(1080, w)
        assertEquals(1528, h)
    }

    @Test
    fun tallPageStaysUnderByteAndSideLimits() {
        // 영수증·웹페이지 저장본 같은 긴 쪽 — 비트맵이 너무 크면 그릴 때 앱이 죽는다
        val (w, h) = pdfBitmapSize(2160, 1f / 6f)
        assertTrue("bytes ${w.toLong() * h * 4}", w.toLong() * h * 4 <= MAX_BITMAP_BYTES)
        assertTrue(h <= MAX_BITMAP_SIDE && w <= MAX_BITMAP_SIDE)
        assertEquals(6f, h.toFloat() / w, 0.01f)
    }

    @Test
    fun extremelyTallPageIsLimitedBySide() {
        val (w, h) = pdfBitmapSize(1080, 0.05f)
        assertTrue(h <= MAX_BITMAP_SIDE)
        assertTrue(w >= 1)
    }

    @Test
    fun normalDocumentsAllowFourTimesZoomOnWideScreens() {
        // A4 문서는 태블릿 가로(2560px)에서도 400% (PRD F3)
        assertTrue(pdfMaxContentWidth(595f / 842f) >= 2560 * 4)
    }

    @Test
    fun zoomLimitKeepsTallestPageWithinLayoutLimits() {
        // 어떤 비율이든 (폭, 폭/비율) 이 Constraints 로 표현 가능한 조합 안에 있어야 한다
        for (ratio in listOf(0.05f, 0.1f, 0.14f, 0.3f, 0.707f, 1f, 1.414f, 5f, 20f)) {
            val w = pdfMaxContentWidth(ratio)
            val h = w / ratio
            val fits = (w <= 8191 && h <= 262143) || (w <= 32767 && h <= 65535) || (w <= 65535 && h <= 32767) || (w <= 262143 && h <= 8191)
            assertTrue("ratio $ratio → ${w}x$h", fits)
            assertTrue("ratio $ratio must still allow zooming", w >= 8000)
        }
    }

    @Test
    fun zoomedRenderIsCappedByBytes() {
        val (w, h) = pdfBitmapSize(1080 * 4, 595f / 842f)
        assertTrue(w.toLong() * h * 4 <= MAX_BITMAP_BYTES)
    }
}
