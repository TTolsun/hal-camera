package dev.halcamera.ui

/**
 * The album subtitle, the one line that says how much is in the album.
 *
 * It is pure so the wording is pinned by a test rather than by whichever screen is being edited, because the two
 * modes used to count different lists: the normal subtitle counted the whole album while the grid showed a
 * filtered one, so a video-filtered album read "사진 8장 · 동영상 6개" over six video tiles, and entering selection
 * mode changed the same header to "총 6개 · 동영상" without anything on screen having changed.
 */
object GalleryCount {
    /** The filter chip's own wording, reused so the subtitle names a filter exactly as the chip does. */
    val filterNames = listOf("전체", "사진", "동영상")

    /**
     * [photos] and [videos] count what the filter left on screen, never the whole album.
     *
     * The unfiltered subtitle keeps the split because it carries more than a total does. A filtered album already
     * says which kind it holds in the chip above, so repeating the kind and naming the other kind's zero would be
     * noise; one number is enough.
     */
    fun text(photos: Int, videos: Int, filter: Int, selectionMode: Boolean): String = when {
        selectionMode -> "총 ${photos + videos}개 · ${filterNames.getOrElse(filter) { filterNames[0] }}"
        filter == 1 -> "사진 ${photos}장"
        filter == 2 -> "동영상 ${videos}개"
        else -> "사진 ${photos}장 · 동영상 ${videos}개"
    }
}
