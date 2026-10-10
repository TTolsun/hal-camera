package dev.halcamera.camera

/** One source for LIVE mode availability and effective settings, including PIP inputs. */
enum class LiveModePolicy(val allowsRaw: Boolean, val allowsStabilization: Boolean) {
    PHOTO(true, false), VIDEO(false, true);

    fun stabilization(requested: LiveStabilization? = null) =
        if (allowsStabilization) requested ?: LiveStabilization.AUTO else LiveStabilization.OFF
    fun raw(requested: LiveSize?) = requested.takeIf { allowsRaw }
    fun apply(settings: LiveStreamSettings) = settings.copy(raw = raw(settings.raw), stabilization = stabilization(settings.stabilization))

    companion object {
        fun forVideo(video: Boolean) = if (video) VIDEO else PHOTO
    }
}
