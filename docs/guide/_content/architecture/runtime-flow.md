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
**실행 실패는 러너 결과에서, 측정값의 차이는 이벤트와 계산 규칙에서 확인하세요.** Auto Check와 벤치마크는 서로 다른 실행 순서와 워밍업 기준을 사용합니다.

### Auto Check의 실행 순서

`AutoCheckRunner`는 엔드포인트마다 `Driver`를 호출해 다음 순서로 검사합니다.

1. OPEN과 CONFIGURE 단계에서 카메라를 열고 세션을 구성합니다.
2. FIRST_FRAME을 확인한 뒤 OBSERVE 단계에서 고정된 10초 동안 관측합니다.
3. STILL 촬영을 3회 실행합니다.
4. CLOSE로 카메라를 닫고 다음 엔드포인트로 이동합니다.

어느 단계에서든 타임아웃이 발생하면 해당 엔드포인트에 `hardFailure`와 실패 단계를 기록하고 CLOSE로 이동합니다. 다음 엔드포인트는 계속 검사하며, 실패한 단계를 자동으로 재시도하지 않습니다.

### 이벤트 기록과 러너 기록

| 기록 | 담당 코드 | 담는 내용 |
| --- | --- | --- |
| 콜백 이벤트 | `Telemetry.callback(sessionId, alive)`와 `FlightRecorder.record()` | 프레임워크가 보낸 프레임·요청·상태 정보를 담습니다. |
| 실행 시각 | `AutoCheckRunner`의 `EndpointResult` | 단계 시작·완료 시각에서 구한 지연과 관측 창의 범위를 담습니다. |

`onCaptureStarted`는 `capture_started`와 `request_observed`를 기록합니다. `onCaptureCompleted`는 AE/AF/AWB 상태, 노출, ISO, 프레임 길이, 센서 타임스탬프를 `capture_result`로 기록합니다. `onCaptureFailed`와 `onCaptureBufferLost`는 각각 `capture_failed`와 `buffer_lost`를 남깁니다.

각 콜백은 먼저 `alive()`를 확인합니다. 이미 닫힌 세션에서 늦게 도착한 콜백은 기록하지 않습니다.

러너 결과의 `openMs`, `configureMs`, `firstStartedMs`, `closeMs`, `stillLatenciesMs`는 대응하는 시각의 차이입니다. 실행 지연과 촬영 지연인 1.x·2.x 지표의 입력이 됩니다. 러너는 `observeStartNs`와 `observeEndNs`로 관측 창을 지정하고, 이벤트는 그 창에서 일어난 일을 제공합니다.

### Auto Check 지표를 계산하는 순서

`CheckEvaluator.evaluate(result, events, baseline, mediaPerformanceClass)`가 두 기록을 결합합니다.

1. 관측 창이 있으면 `MetricExtractor.observe()`로 H.x 지표를 계산합니다.
2. `EndpointResult.launchSamples()`로 1.x·2.x 지표를 만듭니다. 실패한 단계는 hard failure로, 실행하지 않은 단계는 `NOT_RUN`으로 표시합니다.
3. baseline이 있으면 아래의 장면 조건을 대조해 3A 수렴 지표를 비교할 수 있는지 판단합니다.
4. v0.2에서 실행하지 않는 2.1·2.4·2.6·2.7·3.x는 `NOT_RUN`으로, 1.5는 `NOT_MEASURABLE`로 표시합니다.
5. `ThresholdEngine`, `DiagnosisRules`, `HealthComposer` 순서로 상태·진단·건강 수준을 만듭니다. 기기 전체의 건강 수준은 엔드포인트 중 가장 나쁜 수준입니다.

#### 워밍업과 표본 수

Auto Check는 관측 창의 첫 결과 프레임 5개(`WARMUP_FRAMES = 5`)를 H.1~H.5의 간격·partial·버퍼·stall 통계에서 제외합니다. 3A 수렴 계산에는 제외 전 프레임도 사용합니다.

첫 5개를 제외한 steady 프레임이 15개 미만이면 H.1~H.8에서 측정 가능한 지표 값은 `null`이 됩니다. 이때 `unknownReason`은 `INSUFFICIENT_SAMPLES`입니다.

#### 장면 조건이 다른 경우

현재 실행과 baseline의 **ISO × 노출 시간 p50**이 모두 양수여야 비율을 비교합니다. 비율이 4를 초과하거나 1/4 미만이면, 다른 `unknownReason`이 없는 H.6·H.7·H.8에 `CONDITION_MISMATCH`를 지정합니다. 장면 조건이 달라 3A 수렴을 비교하기 어렵다는 뜻입니다.

평가가 끝나면 결과를 실행 파일에 저장하고 화면에서 읽습니다.

### 결과가 예상과 다를 때 확인하는 순서

1. `EndpointResult.hardFailure`와 `failedStep`에서 타임아웃이 난 단계를 확인합니다.
2. 이벤트의 `session`과 관측 창을 확인합니다. 계산 대상은 같은 세션에서 관측 창 안에 기록된 `capture_result`입니다.
3. 값이 `null`이면 `unknownReason`을 확인합니다. `NOT_RUN`, `INSUFFICIENT_SAMPLES`, `CONDITION_MISMATCH`, `NOT_MEASURABLE`은 각각 다른 원인입니다.
4. 상태 판정이 예상과 다르면 v0.2 임계값을 정의한 `ThresholdTable`을 확인합니다.

프레임 연결에 필요한 `capture_started`, `request_observed`, `image_available`은 관측 창 밖에서도 찾습니다. 첫 간격을 계산할 때는 창 직전 결과의 센서 타임스탬프도 사용합니다. H.9의 실패 이벤트 개수는 관측 창 안에서만 셉니다.

### 벤치마크의 실행과 중단 조건

`BenchmarkRunner`는 카메라를 닫았다가 다시 여는 warm reopen을 `launchIterations`만큼 반복합니다. 이후 관측 세션을 하나 더 열어 WARMUP, OBSERVE, STILL, CLOSE 순서로 진행합니다.

실패한 사이클에는 `failed = true`를 기록하고 다음 사이클을 실행합니다. 연속 실패가 `maxConsecutiveFailures`에 도달하거나 관측 세션이 실패하면 중단합니다. 기본 연속 실패 한도는 3회이며, 명시적인 abort로도 실행을 종료할 수 있습니다.

LIVE에서 전달한 카메라 ID와 엔진 이름은 시작 카드에 사용합니다. 실제 벤치마크는 프로파일에 맞춰 Camera2로 전환합니다. 시작 조건과 6단계 진행률의 표시 모델은 각각 `StartCardPresenter`와 `ProgressPresenter`가 만듭니다.

### 벤치마크의 저장과 비교

1. `MainActivity`가 LIVE에서 `BenchmarkActivity`를 엽니다.
2. 실행이 끝나면 `BenchmarkActivity.finishRun()`이 러너 결과와 기록된 이벤트를 `RunAssembler.assemble()`에 전달합니다.
3. `RunAssembler`는 `BenchmarkEvaluator`와 `RunValidityEvaluator`로 측정값과 유효성을 계산합니다. Activity는 `BenchmarkReport.write()`로 schema 3 실행 파일과 이벤트를 저장합니다.
4. `BaselineManager`에서 지정한 baseline을 선택합니다. baseline이 없으면 이전의 적격 실행을 reference로 선택하고, `RegressionDetector.compare()`로 비교합니다.
5. 메인 Handler에서 RESULT 화면으로 전환합니다. `renderResult()`는 `ResultPresenter`, `renderCompare()`는 `ComparePresenter`로 결과를 표시합니다.

조립·파일 저장·기준 실행 읽기·비교는 `io` 실행기에서 처리합니다. `toggleBaseline()`으로 기준을 설정하거나 해제하면 기준 선택과 비교 계산을 다시 수행합니다.

벤치마크의 워밍업 제외 수는 고정된 5개가 아닙니다. `RunAssembler.observe()`는 관측 세션의 첫 결과부터 프레임을 모으고, 관측 시작 전에 도착한 프레임 수를 계산해 간격·버퍼 통계에서 제외합니다. 3A 수렴은 세션의 첫 결과부터 계산합니다.
