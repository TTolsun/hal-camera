package dev.halcamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The album subtitle describes the grid, so the same filter must produce the same counts in both modes. The bug
 * this pins was a header that read "사진 8장 · 동영상 6개" while the grid showed only the six videos.
 */
class GalleryCountTest {

    @Test
    fun `an unfiltered album keeps the split`() {
        assertEquals("사진 8장 · 동영상 6개", GalleryCount.text(photos = 8, videos = 6, filter = 0, selectionMode = false))
    }

    @Test
    fun `a filtered album counts only what the grid shows`() {
        assertEquals("사진 8장", GalleryCount.text(photos = 8, videos = 0, filter = 1, selectionMode = false))
        assertEquals("동영상 6개", GalleryCount.text(photos = 0, videos = 6, filter = 2, selectionMode = false))
    }

    @Test
    fun `entering selection mode does not change how much the header claims is there`() {
        // The two modes word it differently, but the filter decides the number in both.
        assertEquals("총 6개 · 동영상", GalleryCount.text(photos = 0, videos = 6, filter = 2, selectionMode = true))
        assertEquals("총 14개 · 전체", GalleryCount.text(photos = 8, videos = 6, filter = 0, selectionMode = true))
    }

    @Test
    fun `an empty album still names its filter`() {
        assertEquals("동영상 0개", GalleryCount.text(photos = 0, videos = 0, filter = 2, selectionMode = false))
        assertEquals("총 0개 · 사진", GalleryCount.text(photos = 0, videos = 0, filter = 1, selectionMode = true))
    }
}
