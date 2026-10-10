package dev.halcamera.camera

/** Camera-thread transitions; the immutable value is also published to UI readers. */
internal enum class LiveRecorderState {
    EMPTY, PREPARING, PREPARED, STARTING, RECORDING, STOPPING_IDLE, STOPPING_RECORDING;

    val busy get() = this != EMPTY && this != PREPARED
    val prepared get() = this == PREPARED || this == STARTING || this == RECORDING
    val recording get() = this == RECORDING || this == STOPPING_RECORDING
    val stopping get() = this == STOPPING_IDLE || this == STOPPING_RECORDING
    fun stop() = when {
        this == EMPTY || stopping -> this
        recording -> STOPPING_RECORDING
        else -> STOPPING_IDLE
    }
}
