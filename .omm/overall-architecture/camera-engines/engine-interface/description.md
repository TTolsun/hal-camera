`app/src/main/java/dev/halcamera/camera/CameraEngine.kt` (58 lines). The interface plus four top-level helpers that both engines and the endpoint resolver share.

`CameraEngine` declares `start()`, `capture()`, `setZoom(ratio)` and `close(done)`. Two comments in it carry contracts that matter elsewhere: the zoom argument is only a *requested* ratio, since the effective ratio is knowable only from capture results, and the `close` callback means the camera has been relinquished.

`zoomRange(manager, id)` reads `CONTROL_ZOOM_RATIO_RANGE` on API 30 and above and falls back to `1f..SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` below, which is why sub-1x ultra-wide selection only exists on API 30+. `zoomPresets(range)` builds the stock-camera-style button row (ultra-wide, 1x, 2x, 3x, and a 5x or 10x step) clipped to what the device actually reports, capped at five entries.

`describeCamera(manager, id)` snapshots the characteristics that make a session interpretable later: hardware level, timestamp source and whether it is comparable to elapsed realtime, lens facing, orientation, capabilities, physical ids, zoom range, output sizes per format and target FPS ranges. It attaches an explicit note that per-format support does not imply the stream combination is supported. `Telemetry.registerSession` is the extension that stores that map and emits the first `open_requested` event.

StreamConfiguration은 세션의 실제 출력 대상과 그래프 메타데이터를 하나의 출력 목록에서 만듭니다. OutputDescriptor는 고유 ID, 출력 종류, 반복 요청·사진 요청 포함 여부와 버퍼 콜백 관측 가능 여부를 보관합니다. 중복 ID나 중복 출력 대상은 거부합니다. YUV 표시 이름에는 목록 순서대로 1부터 번호를 붙이며 식별자는 이름과 독립적입니다. Camera2는 같은 목록으로 Surface 세션과 요청 대상을 구성하고 완성된 요청별로 실제 대상 ID를 보관합니다. CameraX는 같은 목록의 UseCase를 bindToLifecycle에 전달합니다. CameraX가 내부에서 구성하는 비공개 HAL 출력은 앱 목록에 포함하지 않습니다.
