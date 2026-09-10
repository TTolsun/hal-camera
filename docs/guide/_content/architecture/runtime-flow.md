---
based_on: [data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt#Telemetry.callback
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt#FlightRecorder.record
  - app/src/main/java/dev/halcamera/check/AutoCheckRunner.kt#AutoCheckRunner
  - app/src/main/java/dev/halcamera/check/CheckEvaluator.kt#CheckEvaluator.evaluate
  - app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt#MetricExtractor
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt#BenchmarkRunner
decisions: []
verifications: []
---
Auto Check 한 번은 다음 순서로 진행됩니다. 사용자가 검사를 시작하면 `AutoCheckRunner`가 엔드포인트마다 OPEN, CONFIGURE, FIRST_FRAME, OBSERVE(고정 10초), STILL 3회, CLOSE 순서로 `Driver`를 호출합니다. 어느 단계든 타임아웃이 나면 그 엔드포인트의 hard failure로 기록하고 CLOSE로 넘어가며, 다음 엔드포인트는 계속 실행됩니다. 자동 재시도는 없습니다.

### 두 갈래의 기록

기록은 두 갈래로 나뉘었다가 `CheckEvaluator.evaluate()`에서 다시 합쳐집니다.

- **이벤트 경로** — `Telemetry.callback(sessionId, alive)`가 만든 `CaptureCallback`이 프레임워크 콜백을 받을 때마다 `FlightRecorder.record()`를 호출합니다. `onCaptureStarted`는 `capture_started`와 `request_observed`를, `onCaptureCompleted`는 AE/AF/AWB 상태, 노출, ISO, 프레임 길이, 센서 타임스탬프를 담은 `capture_result`를, `onCaptureFailed`와 `onCaptureBufferLost`는 각각 `capture_failed`와 `buffer_lost`를 남깁니다. 콜백마다 `alive()`를 먼저 확인하므로 이미 닫힌 세션의 늦은 콜백은 기록되지 않습니다.
- **러너 경로** — `AutoCheckRunner`는 각 API 호출 전후의 타임스탬프만 기록합니다. `EndpointResult`의 `openMs`, `configureMs`, `firstStartedMs`, `closeMs`, `stillLatenciesMs`가 그 짝을 뺀 값입니다. 이것이 실행 지연과 촬영 지연(1.x, 2.x) 지표의 원천입니다.

두 갈래가 모두 필요한 이유는 서로 다른 것을 알기 때문입니다. 러너는 관측 창이 언제 열리고 닫혔는지(`observeStartNs`, `observeEndNs`)를 알고, 이벤트는 그 안에서 무슨 일이 있었는지를 압니다.

### 합류 지점에서 적용되는 규칙

`CheckEvaluator.evaluate(result, events, baseline, mediaPerformanceClass)`는 다음 순서로 지표를 만듭니다.

1. 관측 창이 있으면 `MetricExtractor.observe()`가 그 창 안의 이벤트에서 H.x 지표를 계산합니다. 스트림 시작 직후 `WARMUP_FRAMES = 5` 프레임은 간격 지표에서 제외됩니다. 관측 프레임이 15개 미만이면 값은 null이 되고 `unknownReason`이 `INSUFFICIENT_SAMPLES`로 채워집니다.
2. `EndpointResult.launchSamples()`가 1.x, 2.x 지표를 만듭니다. 실패한 단계의 지표는 hard failure로, 실행되지 않은 단계는 `NOT_RUN`으로 표시됩니다.
3. baseline이 있고 ISO × 노출 시간의 p50이 baseline과 4배 이상 다르면 3A 수렴 지표(H.6, H.7, H.8)는 `CONDITION_MISMATCH`가 됩니다. 장면이 달라서 비교할 수 없다는 뜻입니다.
4. v0.2에서 실행하지 않는 지표(2.1, 2.4, 2.6, 2.7, 3.x)는 항상 `NOT_RUN`이고, 1.5는 `NOT_MEASURABLE`입니다.
5. `ThresholdEngine`이 상태를, `DiagnosisRules`가 진단을, `HealthComposer`가 건강 수준을 만듭니다. 기기 전체 수준은 엔드포인트 중 가장 나쁜 값입니다.

결과는 실행 파일로 저장되고 화면은 그 파일을 읽습니다.

### 결과가 예상과 다를 때 확인하는 순서

1. `EndpointResult.hardFailure`와 `failedStep` — 어느 단계에서 타임아웃이 났는지 먼저 봅니다. 이후 지표는 그 영향을 받습니다.
2. 이벤트의 `session`과 관측 창 — 지표는 같은 세션 ID의 이벤트만, 그것도 창 안의 것만 씁니다. 기대한 이벤트가 다른 세션에 붙어 있거나 창 밖에 있으면 지표에 반영되지 않습니다.
3. `unknownReason` — `NOT_RUN`, `INSUFFICIENT_SAMPLES`, `CONDITION_MISMATCH`, `NOT_MEASURABLE`은 각각 원인이 다릅니다. 값이 null인 이유가 여기 적혀 있습니다.
4. 임계값 — 상태 판정이 이상하면 `ThresholdTable`을 봅니다. v0.2의 임계값은 이 한 곳에만 있습니다.

### 벤치마크 갈래

`BenchmarkRunner`는 v0.3의 상태 기계입니다. warm reopen을 `launchIterations`만큼 반복한 뒤 관측 세션을 하나 더 열어 WARMUP, OBSERVE, STILL, CLOSE를 진행합니다. 실패한 사이클은 `failed = true`로 기록하고 다음 사이클로 넘어가며, 연속 실패가 `maxConsecutiveFailures`(기본 3회)에 이르거나 관측 세션이 실패할 때만 실행이 중단됩니다. 이 러너의 출력이 `BenchmarkEvaluator`와 실행 파일까지 연결되어 있는지는 이 원고에서 확인하지 않았으므로 확인 필요입니다.
