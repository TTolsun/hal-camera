package dev.halcamera.cli

/**
 * The checks a request passes before it may reach a screen, in the order the app has always applied them.
 * Device state comes through [Device], so every refusal can be driven from a JVM test.
 */
class CliPreflight(private val device: Device) {
    interface Device {
        fun cameraGranted(): Boolean
        fun microphoneGranted(): Boolean
        /** Android 8–9 save photos only with both storage permissions; later versions need none. */
        fun storageReady(): Boolean
        fun locked(): Boolean
        fun cameraIds(): List<String>
        fun suiteKeys(): List<String>
    }

    /** Before the request leaves `accepted`; [busy] covers both a UI operation and a busy screen. */
    fun beforePreparing(spec: CliCommandSpec, busy: Boolean) {
        if (busy) throw CliFailure("BUSY", "A UI operation is running")
        if (spec.cameraPermission && !device.cameraGranted()) throw CliFailure("PERMISSION_REQUIRED", "Allow camera access in the app")
    }

    /** Before a LIVE command reaches [screen], the name of the attached screen or null when none is attached. */
    fun beforeScreen(command: CliCommand, spec: CliCommandSpec, screen: String?) {
        if (device.locked()) throw CliFailure("DEVICE_LOCKED", "Unlock the device")
        if (spec.savesMedia && !device.storageReady())
            throw CliFailure("PERMISSION_REQUIRED", "Allow storage access for photos on Android 8–9")
        if (spec.audio && !spec.silentOnly && command.audio != false && !device.microphoneGranted())
            throw CliFailure("PERMISSION_REQUIRED", "Allow microphone access in the app, or use audio=false")
        if (spec.cases) {
            val known = device.suiteKeys()
            command.cases.orEmpty().firstOrNull { it !in known }?.let { throw CliFailure("UNKNOWN_CASE", "Unknown suite item: $it") }
        } else if (spec.camera && command.camera !in device.cameraIds()) {
            throw CliFailure("UNSUPPORTED_CAMERA", "Camera is not independently openable")
        }
        if (screen != "live" && !(screen in CliCommands.previewScreens && spec.currentScreen))
            throw CliFailure("APP_NOT_FOREGROUND", "Open Live before executing a camera command")
    }
}
