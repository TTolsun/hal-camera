---
based_on: [data-flow, state-transitions]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt#Telemetry.callback
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt#FlightRecorder
  - app/src/main/java/dev/halcamera/check/CheckEvaluator.kt#CheckEvaluator.evaluate
  - app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt#MetricExtractor
decisions: []
verifications: []
---
앱이 남기는 기록은 두 종류입니다. 프레임워크가 준 값을 그대로 옮긴 것과, 앱이 계산한 것입니다. 계층을 좁히려면 이 둘을 먼저 구분해야 합니다.

### 프레임워크 경계 바깥의 사실

다음은 프레임워크 콜백의 인자를 그대로 기록한 값이므로 앱이 만들어 낸 것이 아닙니다.

- `capture_started`의 `frameNumber`와 `timestamp`
- `capture_result`의 센서 타임스탬프(`sensorNs`), AE/AF/AWB 상태, `exposureNs`, `iso`, `frameDurationNs`, `focusDiopters`, `zoomRatio`, `cropRegion`
- `capture_failed`의 `reason`과 `imageCaptured`
- `buffer_lost`의 `frameNumber`
- `image_available`의 해상도, 포맷, 스트림 이름

단, `request_observed`에 담긴 요청 내용은 `onCaptureStarted` 시점에 본 것이지 요청을 제출한 시각의 것이 아닙니다. `Telemetry`가 이 사실을 `observation` 필드에 명시해 둡니다.

### 앱의 해석

다음은 앱이 위 사실에서 계산한 값입니다. 여기서 이상이 보이면 먼저 앱의 규칙을 의심합니다.

- `intervalMs`, `resultFps`, `observedResultGap` — 결과 콜백 사이의 간격입니다. `FrameStats`의 주석대로 이것은 관측된 결과 간격이지 HAL의 드롭 횟수도 프리뷰 렌더링 드롭 횟수도 아닙니다.
- H.x 지표와 상태 판정 전부.

### 앱 규칙이 만들 수 있는 증상

| 규칙 | 코드 위치 | 만들 수 있는 증상 |
| --- | --- | --- |
| 이미 닫힌 세션의 콜백은 기록하지 않음 | `Telemetry.callback`의 `alive()` 검사 | 닫는 도중의 마지막 프레임들이 이벤트에 없음 |
| 지표는 같은 세션 ID의 이벤트만 사용 | `CheckEvaluator.evaluate`가 `result.session`을 넘김 | 다른 세션의 콜백이 섞이지 않는 대신, 세션 ID가 어긋나면 지표가 비어 있음 |
| 스트림 시작 직후 5프레임 제외 | `CheckEvaluator.WARMUP_FRAMES` | 첫 프레임들의 긴 간격이 H.x에 나타나지 않음 |
| 관측 프레임 15개 미만이면 값 null | `MetricExtractor(minSamples = 15)` | 값 대신 `INSUFFICIENT_SAMPLES`가 표시됨. 통계 자체는 남아 있음 |
| 링 버퍼 18,000개 상한을 넘으면 가장 오래된 이벤트 폐기 | `FlightRecorder.store` | 창의 앞부분이 잘림. 잘렸다는 사실은 `capacityEvictions`로만 알 수 있고, 잘린 창에서 계산된 지표에 별도 표시는 없음 |
| 보존 기간 30초 | `FlightRecorder(retentionNs)` | 30초보다 오래된 이벤트는 `snapshot()`에 없음 |
| 라이브 tap은 기록 스레드에서 동기 실행 | `FlightRecorder.listener` | tap에 무거운 작업을 넣으면 기록 경로가 느려지고 측정 대상인 간격 자체가 왜곡됨 |

### 계층을 좁히는 순서

1. **앱 규칙인지 확인합니다.** 값이 null이면 `unknownReason`을 봅니다. `capacityEvictions`가 0보다 크면 창이 잘린 것입니다. 관측 창 범위와 세션 ID가 기대와 맞는지 확인합니다. 여기서 설명되면 앱 문제입니다.
2. **이벤트의 원시값을 봅니다.** `capture_failed`의 `reason`, `buffer_lost`의 발생 시점, `capture_result`의 `frameDurationNs`와 센서 타임스탬프 간격을 직접 봅니다. 이 값들은 프레임워크가 준 것이므로, 여기서 이상이 보이면 앱 바깥입니다.
3. **센서 시계 도메인을 확인합니다.** 센서 타임스탬프는 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 `atNs`와 비교할 수 있습니다. 그렇지 않은 기기에서 두 값을 빼면 의미 없는 차이가 나옵니다.
4. **프레임워크와 HAL을 나눕니다.** 앱의 관측만으로는 이 둘을 확정할 수 없습니다. 이 단계부터는 시스템 트레이스와 카메라 서비스 로그가 필요하며, 팀에서 쓰는 수집 절차는 확인 필요입니다.
