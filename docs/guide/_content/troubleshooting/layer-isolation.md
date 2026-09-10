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
**먼저 앱의 필터와 계산 규칙을 확인한 뒤 원시 이벤트를 대조하세요.** 앱은 프레임워크에서 받은 값과, 그 값으로 계산한 지표를 함께 기록합니다. 둘을 구분해야 문제가 생긴 계층을 좁힐 수 있습니다.

### 원인을 좁히는 순서

1. **앱의 입력 조건을 확인합니다.** 값이 `null`이면 `unknownReason`을 봅니다. 세션 ID와 관측 창이 기대한 값인지 확인하고, `capacityEvictions`가 0보다 큰지 확인합니다. 이벤트가 용량 한도로 제거됐다면 계산에 필요한 기록이 부족할 수 있습니다.
2. **원시 이벤트를 대조합니다.** `capture_failed.reason`, `buffer_lost`의 발생 시점, `capture_result.frameDurationNs`와 센서 시각의 간격을 확인합니다. 원시값이 이상하더라도 요청 설정·콜백 처리·시스템 로그를 함께 확인해야 합니다.
3. **시계 도메인을 확인합니다.** 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 센서 타임스탬프와 앱의 `atNs`를 직접 비교합니다. 다른 시계의 값을 빼면 의미 없는 차이가 나옵니다.
4. **프레임워크와 HAL을 추가 자료로 구분합니다.** 앱 기록만으로 두 계층 중 어디가 원인인지 확정할 수 없습니다. 시스템 트레이스와 카메라 서비스 로그가 필요합니다. 팀에서 사용할 구체적인 수집 절차는 아직 정리하지 않았습니다.

### 프레임워크에서 받은 값

아래 항목은 앱이 계산한 지표가 아니라 콜백을 통해 관측한 정보입니다.

| 이벤트 | 기록하는 정보 |
| --- | --- |
| `capture_started` | `frameNumber`와 `timestamp`를 기록합니다. |
| `capture_result` | `sensorNs`, AE/AF/AWB 상태, `exposureNs`, `iso`, `frameDurationNs`, `focusDiopters`, `zoomRatio`, `cropRegion`을 기록합니다. |
| `capture_failed` | `reason`과 `imageCaptured`를 기록합니다. |
| `buffer_lost` | `frameNumber`를 기록합니다. |
| `image_available` | 해상도, 포맷, 스트림 이름을 기록합니다. |

`request_observed`는 **요청을 제출한 시점의 기록이 아닙니다.** `onCaptureStarted`에서 관측한 요청 내용을 담으며, `Telemetry`는 이 구분을 `observation` 필드에 기록합니다.

### 앱에서 계산한 값

`intervalMs`와 `resultFps`는 센서 타임스탬프 차이로 계산합니다. `observedResultGap`은 관측된 결과 프레임 번호의 차이로 계산합니다. 이 값들은 HAL의 프레임 드롭 횟수나 프리뷰 렌더링 드롭 횟수를 직접 나타내지 않습니다.

H.x 지표와 상태 판정도 앱의 계산 결과입니다. 이 값이 예상과 다르면 아래의 필터와 표본 수 기준부터 확인하세요.

### 세션과 표본 규칙

| 규칙 | 코드 위치 | 결과에 나타나는 영향 |
| --- | --- | --- |
| 닫힌 세션의 늦은 콜백은 기록하지 않습니다. | `Telemetry.callback`의 `alive()` | 닫는 도중 도착한 마지막 프레임이 이벤트 목록에 없을 수 있습니다. |
| 같은 세션 ID의 이벤트만 지표에 사용합니다. | `CheckEvaluator.evaluate`의 `result.session` | 다른 세션은 계산에서 제외합니다. 세션 ID가 어긋나면 지표의 입력이 비게 됩니다. |
| Auto Check는 첫 결과 프레임 5개를 H.1~H.5 통계에서 제외합니다. | `CheckEvaluator.WARMUP_FRAMES` | 첫 프레임의 긴 간격이 해당 통계에 나타나지 않습니다. 3A 수렴은 제외 전 프레임을 사용합니다. |
| 워밍업을 제외한 steady 프레임이 15개 미만이면 값을 `null`로 표시합니다. | `MetricExtractor(minSamples = 15)` | `INSUFFICIENT_SAMPLES`로 표시합니다. 수집한 통계 자체는 남아 있습니다. |

### 기록 보존과 실행 스레드

| 규칙 | 코드 위치 | 결과에 나타나는 영향 |
| --- | --- | --- |
| 링 버퍼의 이벤트 상한은 18,000개입니다. | `FlightRecorder.store` | 상한을 넘으면 오래된 이벤트부터 제거합니다. 제거 횟수는 `capacityEvictions`에서 확인합니다. 이 때문에 일부 기록이 빠진 지표에 별도 표시는 하지 않습니다. |
| 이벤트 보존 기간은 30초입니다. | `FlightRecorder(retentionNs)` | 30초보다 오래된 이벤트는 `snapshot()`에 남지 않습니다. |
| 라이브 listener는 기록 스레드에서 동기 실행됩니다. | `FlightRecorder.listener` | 무거운 처리를 추가하면 기록 경로가 느려지고 관측 간격에도 영향을 줄 수 있습니다. |
