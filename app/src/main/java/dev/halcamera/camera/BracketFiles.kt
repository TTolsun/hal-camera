package dev.halcamera.camera

/**
 * How a bracket (#178) shows up in file names, so the gallery can say what each image is without reading its
 * metadata. A bracket shot keeps the usual "HAL_<time>_<random>" name and adds "_AEB<n>_EV<±x.x>" before the output
 * suffix: "HAL_..._AEB2_EV-2.0_JPEG.jpg". The image fused from the three shots ends in "_AEB_HDR.jpg".
 *
 * Pure Kotlin: [LiveBurst] names the request, both engines' still captures turn the request id into the file name
 * through [named], and the gallery reads the badge back.
 */
object BracketFiles {
    /** The fused image's suffix; its metadata JSON sits beside it as "<name>_AEB_HDR_metadata.json". */
    const val FUSED = "_AEB_HDR"

    private val request = Regex("""^bracket-[^-]+-(\d+)-ev([+-]\d+\.\d)$""")
    private val shot = Regex("""_AEB(\d+)_EV([+-]\d+\.\d)_(YUV|JPEG|RAW)\.\w+$""", RegexOption.IGNORE_CASE)

    /** "AEB2_EV-2.0" for the request id "bracket-<id>-2-ev-2.0"; null for any other request. */
    fun nameTag(requestId: String?): String? = requestId?.let(request::matchEntire)
        ?.let { "AEB${it.groupValues[1]}_EV${it.groupValues[2]}" }

    /** [name] from [MediaLibrary.name] with the bracket tag added when [requestId] is a bracket shot. */
    fun named(name: String, requestId: String?): String = nameTag(requestId)?.let { "${name}_$it" } ?: name

    /**
     * The gallery badge: "AEB −2.0 · JPEG" for a bracket shot and "AEB · HDR" for the fused image, or null when the
     * file is not part of a bracket. The minus is the typographic one, as elsewhere in LIVE.
     */
    fun badge(fileName: String): String? {
        if (fileName.endsWith("$FUSED.jpg", ignoreCase = true)) return "AEB · HDR"
        val match = shot.find(fileName) ?: return null
        val ev = match.groupValues[2].replace('-', '−')
        return "AEB $ev · ${match.groupValues[3].uppercase()}"
    }
}
