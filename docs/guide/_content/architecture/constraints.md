---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/check/AutoCheckRunner.kt
  - app/src/main/java/dev/halcamera/check/CameraEndpointResolver.kt
  - app/src/main/java/dev/halcamera/telemetry/IncidentExporter.kt
  - app/src/main/java/dev/halcamera/diagnosis/ThresholdTable.kt
  - app/src/main/java/dev/halcamera/benchmark/RegressionRules.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt
decisions: []
verifications: []
---
**카메라 점유, 시계, 계산 로직의 경계를 유지하세요.** 다음 규칙을 바꾸면 실행 순서나 측정값의 의미가 달라질 수 있습니다.

### 실행과 측정의 제약

1. 카메라를 점유하는 `CameraEngine`은 하나만 유지합니다. `close(done)`은 기기를 실제로 반환한 뒤 완료 콜백을 호출해야 합니다. 다음 open이 이 콜백에서 시작됩니다.
2. 앱의 시각은 `elapsedRealtimeNanos`를 사용합니다. 센서 시각은 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 앱 시각과 직접 비교합니다.
3. 카메라 열거에는 공개 Camera2 API만 사용합니다. 숨겨진 ID는 탐색하지 않습니다. 논리 카메라에 속한 물리 카메라는 열지 않고 `independentlyOpenable = false`로 기록합니다.
4. 이미지 픽셀은 저장하지 않습니다. incident 번들에는 메타데이터와 이벤트만 담습니다.

### 계산과 저장의 제약

1. 지표 계산·평가·러너·모델 코드는 Android import가 없는 순수 Kotlin으로 유지합니다. `MetricExtractor`, `ThresholdEngine`, `AutoCheckRunner`, `BenchmarkEvaluator`, `RunValidityEvaluator`가 이 원칙을 따릅니다. `AutoCheckRunner`는 `Driver`, `Scheduler`, `clock`으로 카메라와 시계에 접근합니다.
2. `org.json`은 파일 입출력 경계에서 사용합니다. `BenchmarkReport`, `HealthReport`, `IncidentExporter`, `*Store` 등이 이에 해당합니다. 데이터 계약은 snake_case 키를 사용하는 `Map<String, Any?>`로 전달합니다.
3. 임계값은 평가 계층마다 한 곳에서 관리합니다. v0.2는 `ThresholdTable`, v0.3은 `RegressionRules`를 사용합니다. 값을 바꿀 때는 관련 문서도 함께 수정합니다.
