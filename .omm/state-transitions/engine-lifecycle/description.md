The camera's own open and close, observed rather than controlled. `Camera2Engine` runs it on a private `HandlerThread`: open, `configure()` negotiating three streams, `createCaptureSession`, `setRepeatingRequest`, then a close path that sets `active = false`, closes session and device, and funnels every route into a single-shot `finishClose()`.

`CameraXEngine` mirrors the contract through `ProcessCameraProvider.bindToLifecycle` and `unbind`, watching `cameraState` with `observeForever` so the release still completes while the Activity is stopped.

The fragile part is the close callback. `AutoCheckRunner.next()` chains the next endpoint off it, so firing it early leaves two engines contending for the camera, and never firing it stalls the run until the 3 s close timeout. Both engines guard it with a `finished` flag, and both check `active` before recording anything, so callbacks arriving after close are dropped.
