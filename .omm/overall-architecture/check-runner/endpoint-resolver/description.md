`app/src/main/java/dev/halcamera/check/CameraEndpointResolver.kt` (65 lines). Turns a `CameraManager` into the endpoint list, using public API only.

It reads `cameraIdList`, fetches characteristics for each id, computes the rear roles as a group (so `dedupeMain` sees all rear cameras at once) and then emits one endpoint per logical id. On API 28 and above it additionally emits one endpoint per `physicalCameraIds` entry, marked `independentlyOpenable = false` unless that physical id also appears in the public `cameraIdList`.

The rule stated in `PRODUCT-v0.2.md` 9.3 and enforced here is that hidden ids are never probed. A physical camera behind a logical camera is *listed* so that the report can say it exists, but v0.2 does not attempt to open it. Every characteristics lookup is wrapped in a try/catch returning null, because a vendor can expose an id that then refuses to describe itself.
