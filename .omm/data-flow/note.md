`Telemetry`와 `FlightRecorder`가 기록하는 이벤트 종류입니다. 하위 단계의 모든 조인이 이 어휘를 사용합니다.

`open_requested`, `open_call`, `opened`, `configure_requested`, `session_configured`, `configured`, `configure_failed`, `repeating_submit`, `capture_started`, `request_observed`, `capture_result`, `capture_failed`, `buffer_lost`, `image_available`, `capture_submit`, `capture_timeout`, `capture_error`, `zoom_set`, `camera_error`, `closed`, `bound`(CameraX), `incident_trigger`.

지표 ID 계열은 다음과 같습니다. `1.x`는 실행, `2.x`는 촬영, `3.x`는 녹화(정의만 있고 실제로 측정하지 않음), `H.x`는 관측 창 기반 건강 지표입니다. 각 지표의 계산 방법은 `METRICS.md`가, 표시 이름은 `MetricCatalog`가 기준입니다.
