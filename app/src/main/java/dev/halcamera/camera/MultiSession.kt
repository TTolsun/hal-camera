package dev.halcamera.camera

/** The screen owns composition; an engine owns cameras and their media lifecycle. */
interface MultiSession {
    interface Listener {
        fun onState(id: String, state: String)
        fun onReady()
        fun onFailed(reason: String)
        fun onPhoto(result: ConcurrentMediaResult)
        fun onRecording(active: Boolean, result: ConcurrentMediaResult)
        fun onPhotoResult(result: Result<PhotoResult>) {}
        fun onVideoResult(result: Result<List<android.net.Uri>>) {}
    }

    fun start()
    fun capture(displayDegrees: Int)
    fun startVideo(audio: Boolean = false)
    fun stopVideo()
    fun setControls(id: String, next: LiveControls)
    fun setZoom(id: String, ratio: Float)
    fun meterAt(id: String, u: Double, v: Double, exposure: Boolean, feedback: (TouchPhase) -> Unit): Boolean
    fun close(done: () -> Unit)
}

internal object MultiSessionLease { val lease = ConcurrentLease() }
