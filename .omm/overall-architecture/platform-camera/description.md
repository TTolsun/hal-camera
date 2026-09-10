The Android camera stack — the only thing outside this codebase, and the subject under measurement rather than a dependency to be abstracted away.

Two entry points are used. `android.hardware.camera2` (`CameraManager`, `CameraDevice`, `CameraCaptureSession`, `CaptureRequest`, `TotalCaptureResult`, `ImageReader`) is the measurement path: every metric definition is written against its callbacks. `androidx.camera` 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) is the comparison path, reached through `Camera2Interop` so the same capture callback still applies.

What the app can and cannot see through this boundary shapes the whole design. It observes callback arrival times, not HAL execution time; it observes result gaps, not HAL frame drops; and it cannot see rendered preview frames at all. Every export restates those limits in prose rather than leaving them implied.

Only the public API is used. Hidden camera ids are never probed, and no vendor extension or reflection appears anywhere in the source.
