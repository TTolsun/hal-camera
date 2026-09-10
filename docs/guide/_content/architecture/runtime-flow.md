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
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt#finishRun
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt#assemble
  - app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt#compare
  - app/src/main/java/dev/halcamera/benchmark/ResultPresenter.kt#present
  - app/src/main/java/dev/halcamera/benchmark/ComparePresenter.kt#present
  - app/src/main/java/dev/halcamera/benchmark/BaselineManager.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
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

1. 관측 창이 있으면 `MetricExtractor.observe()`가 그 창 안의 이벤트에서 H.x 지표를 계산합니다. Auto Check 관측 창의 첫 `WARMUP_FRAMES = 5` 결과 프레임은 H.1~H.5의 간격·partial·버퍼·stall 통계에서 제외됩니다. 3A 수렴은 제외 전 결과를 사용합니다. 관측 창의 결과 프레임에서 첫 5개를 제외한 steady 프레임이 15개 미만이면 H.1~H.8의 측정 가능 지표 값은 null이 되고 `unknownReason`이 `INSUFFICIENT_SAMPLES`로 채워집니다.
2. `EndpointResult.launchSamples()`가 1.x, 2.x 지표를 만듭니다. 실패한 단계의 지표는 hard failure로, 실행되지 않은 단계는 `NOT_RUN`으로 표시됩니다.
3. baseline이 있고 현재와 baseline의 ISO × 노출 시간 p50이 모두 양수이며 그 비율이 4 초과 또는 1/4 미만이면, 기존 unknownReason이 없는 3A 수렴 지표(H.6, H.7, H.8)는 `CONDITION_MISMATCH`가 됩니다. 장면이 달라서 비교할 수 없다는 뜻입니다.
4. v0.2에서 실행하지 않는 지표(2.1, 2.4, 2.6, 2.7, 3.x)는 항상 `NOT_RUN`이고, 1.5는 `NOT_MEASURABLE`입니다.
5. `ThresholdEngine`이 상태를, `DiagnosisRules`가 진단을, `HealthComposer`가 건강 수준을 만듭니다. 기기 전체 수준은 엔드포인트 중 가장 나쁜 값입니다.

결과는 실행 파일로 저장되고 화면은 그 파일을 읽습니다.

### 결과가 예상과 다를 때 확인하는 순서

1. `EndpointResult.hardFailure`와 `failedStep` — 어느 단계에서 타임아웃이 났는지 먼저 봅니다. 이후 지표는 그 영향을 받습니다.
2. 이벤트의 `session`과 관측 창 — 같은 세션 ID의 capture_result 중 관측 창 안의 결과 프레임을 대상으로 삼습니다. 연결할 capture_started·request_observed·image_available은 창 밖에서도 찾으며, 첫 간격 계산에는 창 직전 결과의 센서 타임스탬프도 사용합니다. H.9 실패 이벤트 개수는 관측 창 안에서만 셉니다.
3. `unknownReason` — `NOT_RUN`, `INSUFFICIENT_SAMPLES`, `CONDITION_MISMATCH`, `NOT_MEASURABLE`은 각각 원인이 다릅니다. 값이 null인 이유가 여기 적혀 있습니다.
4. 임계값 — 상태 판정이 이상하면 `ThresholdTable`을 봅니다. v0.2의 임계값은 이 한 곳에만 있습니다.

### 벤치마크 갈래

`BenchmarkRunner`는 v0.3의 상태 기계입니다. warm reopen을 `launchIterations`만큼 반복한 뒤 관측 세션을 하나 더 열어 WARMUP, OBSERVE, STILL, CLOSE를 진행합니다. 실패한 사이클은 `failed = true`로 기록하고 다음 사이클로 넘어가며, 연속 실패가 `maxConsecutiveFailures`(기본 3회)에 이르거나 관측 세션이 실패하면 중단되며, 명시적인 abort도 실행을 종료할 수 있습니다.

LIVE의 벤치마크 진입은 `MainActivity`에서 `BenchmarkActivity`를 엽니다. `BenchmarkActivity.finishRun()`은 러너 결과와 recorder의 이벤트를 `RunAssembler.assemble()`에 넘깁니다. 조립기는 `BenchmarkEvaluator`와 `RunValidityEvaluator`로 측정값과 유효성을 만들고, Activity가 `BenchmarkReport.write()`로 schema 3 실행 파일과 이벤트를 저장합니다. 따라서 이 경로는 이미 코드로 연결돼 있습니다.

Auto Check와 달리 벤치마크의 워밍업 제외 수는 고정 5개가 아닙니다. `RunAssembler.observe()`가 관측 세션의 첫 결과부터 프레임을 모아 관측 시작 이전의 프레임 수를 계산하고, 이를 간격·버퍼 통계에서 제외합니다. 3A 수렴은 세션 첫 결과부터 계산합니다. `BenchmarkActivity.finishRun()`은 io 실행기에서 조립·저장을 수행하고, `BaselineManager`로 설정된 baseline을 먼저 선택하며 없으면 이전 적격 실행을 reference로 선택합니다. `RegressionDetector.compare()`로 비교한 뒤 main Handler에서 RESULT 화면으로 전환합니다. `renderResult()`는 `ResultPresenter`, `renderCompare()`는 `ComparePresenter`를 사용합니다. `toggleBaseline()`으로 기준을 설정·해제하면 같은 선택·비교 절차를 다시 수행합니다.

LIVE에서 넘긴 카메라 ID와 엔진 이름은 시작 카드의 입력입니다. 벤치마크 실행은 프로파일에 맞춰 Camera2로 전환합니다. 시작 조건과 6단계 진행률은 각각 `StartCardPresenter`와 `ProgressPresenter`가 표시 모델로 만듭니다.
