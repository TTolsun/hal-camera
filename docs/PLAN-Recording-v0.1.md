# 녹화(3.x) 지표 설계 v0.1

- 작성일: 2026-09-24
- 상태: **설계 확정. 3절의 결정 세 가지는 2026-09-24에 사용자 확인을 받았다. 구현을 시작할 수 있다.**
- 대상 이슈: [#122 녹화(3.x) 지표 구현](https://github.com/TTolsun/hal-camera/issues/122)
- 근거 문서: [METRICS.md 3장](METRICS.md), [PLAN-BenchMarker-v0.3.md 3장·5장·7장](PLAN-BenchMarker-v0.3.md)
- 범위: 벤치마크 시퀀스에 RECORD 단계를 추가하고 3.1·3.2·3.4·3.6·3.7 다섯 지표를 산출하는 설계. 3.3(encoder drop)과 3.5(장시간 drift)는 제외한다.

## 1. 한 문단 요약

벤치마크의 관측 세션이 STILL을 끝낸 뒤 같은 `CameraDevice` 위에서 CaptureSession을 프리뷰와 `MediaRecorder` 표면으로 다시 구성하고, 정해진 횟수만큼 짧은 녹화를 반복한 다음 카메라를 닫는다. 녹화 구간의 `capture_started`와 `capture_result`는 `record-<n>` 태그로 구분하므로 기존 1.x·2.x·H.x의 관측 창과 섞이지 않는다. 지표 계산은 `metrics` 패키지에 순수 Kotlin으로 추가하고, 결과는 새 `RECORD` 카테고리로 보고한다.

## 2. 현재 코드가 녹화를 막고 있는 지점

| 위치 | 현재 동작 | 필요한 변경 |
|---|---|---|
| `Camera2Engine.startRecording` | `spec != null`이면 즉시 거부한다. 즉 profile이 주어진 벤치마크 경로에서는 녹화가 불가능하다 | 벤치마크용 녹화 진입점을 따로 두고, profile이 지정한 크기·코덱·비트레이트로만 구성한다 |
| `BenchmarkRunner.Driver` | `open` · `still` · `close` 세 개뿐이다 | `prepareRecord` · `startRecord` · `stopRecord`를 추가한다 |
| `BenchmarkRunner.Step` · `Phase` | STILL 다음이 바로 CLOSE이고 화면은 6단계이다 | RECORD 단계와 7번째 화면 단계를 추가한다 |
| `BenchmarkProfile` | 녹화 조건을 담는 필드가 없다 | 녹화 크기·코덱·비트레이트·fps·길이·반복 필드를 추가한다 |
| `RegressionDetector` | `Direction.HIGHER_IS_BETTER`를 판정 함수는 이미 처리하지만 규칙 표에 그 방향을 쓰는 지표가 하나도 없다 | 3.4 `steady_fps`가 첫 사용처이므로 규칙을 더하고 경계값을 테스트로 고정한다 |
| `ResultPresenter` | 카테고리 순서가 LAUNCH · PREVIEW · CAPTURE · STABILITY · 3A로 고정되어 있고, 소수점 표기는 PREVIEW만 예외이다 | RECORD 카테고리를 순서에 넣고 `fps` 단위의 표기 규칙을 정한다 |

## 3. 확정된 결정 사항

아래 세 가지는 구현 범위를 바꾸므로 먼저 확정했다. 2026-09-24에 사용자가 세 항목 모두 권고안을 선택했으며, 이후 변경하려면 이 절을 먼저 고친다.

### 3.1 profile을 어떻게 나눌 것인가 (확정: A안)

**A안 — `camera2-standard-v2` 하나로 합친다.** 기존 시퀀스에 RECORD 단계를 붙이고 profile id를 올린다.

- 장점: 한 번의 run이 모든 카테고리를 담으므로 `ScoreComposer`가 녹화 지표까지 포함한 하나의 endpoint 점수를 계산할 수 있다. 이슈 [#123](https://github.com/TTolsun/hal-camera/issues/123)이 요구하는 "녹화 지표가 점수 체계에 반영된 상태"가 이 안에서만 성립한다.
- 비용: `camera2-standard-v1`으로 저장된 기존 run과 baseline은 v2와 비교되지 않는다. 기기마다 baseline을 다시 측정해야 한다. 과거 run 파일은 그대로 읽히고 v1끼리는 계속 비교된다.

**B안 — `camera2-record-v1`을 따로 만든다.** 녹화 전용 run을 별도로 돌린다.

- 장점: 기존 baseline을 그대로 쓴다.
- 비용: 점수는 run 하나 안의 지표로 계산되므로 녹화 지표가 점수에 들어가지 못한다. 회귀 판정도 두 run을 따로 봐야 한다.

점수 체계와의 연결이 이 기능의 목적 중 하나이므로 A안으로 확정했다. 기존 `camera2-standard-v1` run 파일은 계속 읽히고 v1끼리는 계속 비교되지만, v2로 전환한 뒤에는 기기마다 baseline을 다시 측정해야 한다.

### 3.2 녹화 반복 횟수와 길이 (확정: 5회 × 9초)

METRICS.md 0.2절의 기본 반복은 10회이지만, 녹화는 한 회마다 세션 재구성과 파일 종료가 필요하므로 시간 예산이 크게 늘어난다.

| 안 | 녹화 구간 소요 | 3.1·3.6의 유효 표본 | 판단 |
|---|---:|---:|---|
| 10회 × 9초 | 약 100초 | 9 | 0.2절의 기본값을 지키지만 run 전체가 2분을 넘는다 |
| 5회 × 9초 | 약 50초 | 4 | 확정. run 전체는 약 80초이다 |
| 3회 × 9초 | 약 30초 | 2 | p50과 p95가 사실상 최소·최대여서 통계로 쓰기 어렵다 |

5회 × 9초로 확정했으며, 유효 표본이 4개라는 사실과 그때의 p95가 최대값이라는 사실을 결과 화면과 JSON에 그대로 표시한다.

녹화 길이는 3.4가 요구하는 3초의 건너뛰기 구간 뒤에 완전한 1초 창 5개를 남기도록 정했다. 처음에는 8초로 적었으나 5단계 구현에서 8초로는 창이 4개뿐임을 확인했다. 30 fps로 8초를 녹화하면 결과는 240개이고 첫 결과부터 마지막 결과까지의 센서 시간이 7.967초이므로, 3초를 건너뛰면 4.967초만 남아 창이 4개가 된다. 9초로 올리면 결과 270개에 센서 시간 8.967초이므로 창 5개가 남는다. 사용자 확인을 거쳐 2026-09-24에 9초로 확정했고, 그 대가로 녹화 구간이 약 45초에서 약 50초로, run 전체가 약 75초에서 약 80초로 늘었다.

### 3.3 녹화 지표를 점수에 언제 넣을 것인가 (확정: 처음에는 가중치 0)

`ScoreComposer`는 현재 16개 지표, 네 카테고리를 같은 가중치로 묶고 3A의 가중치는 0이다. RECORD 카테고리를 추가하되 calibration이 끝나기 전까지는 3A와 같이 가중치 0으로 두고, 이슈 #123의 민감도 검증에서 기준을 확정한 뒤 가중치를 부여한다. 이렇게 하면 v2 전환 직후에도 점수의 의미가 v1과 같은 축 위에 남는다.

## 4. Profile `camera2-standard-v2`

v1의 모든 값을 그대로 유지하고 녹화 필드 일곱 개를 더한다.

| 항목 | 값 | 근거 |
|---|---|---|
| `recordSize` | `1920x1080` | preview · still과 같은 크기. `getOutputSizes(MediaRecorder::class.java)`에 포함되는지 preflight에서 확인한다 |
| `recordCodec` | `h264` | `MediaRecorder.VideoEncoder.H264`. 기기 간 지원 폭이 가장 넓다 |
| `recordBitrate` | `10000000` | Live 녹화와 같은 값 |
| `recordFps` | `30` | profile의 `fpsRange`와 같다. 3.2의 `T_ref`는 여기에서 나온다 |
| `recordDurationMs` | `9000` | 3.2절 참고 |
| `recordIterations` | `5` | 3.2절 참고 |
| `recordAudio` | `false` | 오디오는 3장 어느 지표에도 쓰이지 않는다. 마이크 권한이 거부되면 run 전체가 실패하므로 조건에서 제외한다 |

`conditionsKey`에도 `record=h264@1920x1080/30fps/9000ms x5` 형태로 포함한다. profile 필드는 비교 계약의 일부이므로 `BenchmarkProfile.fromJsonMap`에서 누락 시 예외를 던지는 기존 규칙을 그대로 따른다.

preflight(`ProfileCompatibility`)는 카메라를 열지 않고 `recordSize`가 `MediaRecorder` 출력 크기 목록에 있는지만 확인한다. 실제 스트림 조합 지원 여부는 열어 보기 전에는 알 수 없으므로, 구성 실패는 preflight가 아니라 실행 중 실패로 처리한다(9절).

## 5. 실행 순서와 화면 단계

```text
runner                                       화면
PREFLIGHT (카메라를 열지 않음)                 (카드의 Start 가능 여부)
LAUNCH_CYCLE ×10                             1 / 7  Camera Open
  OPEN -> CONFIGURE -> FIRST_FRAME -> CLOSE
OPEN (11번째, 유지)                           2 / 7  First Preview
WARMUP 3 s
OBSERVE 10 s                                 3 / 7  Preview Stability
  (같은 창에서 3A 수렴 계산)                    4 / 7  3A Response
STILL ×10                                    5 / 7  Still Capture
RECORD_CYCLE ×5                              6 / 7  Recording
  PREPARE -> START -> RUN 9 s -> STOP
CLOSE                                        7 / 7  Camera Close
```

RECORD를 STILL 뒤에 두는 이유는 세 가지이다.

1. 녹화는 CaptureSession 재구성을 요구하므로, 관측 창과 still 구간이 끝난 뒤에 실행해야 H.1–H.10과 2.x가 재구성의 영향을 받지 않는다.
2. 녹화가 실패해도 이미 확보한 1.x·2.x·H.x 표본이 남는다. 반대 순서였다면 녹화 실패가 run 전체를 버리게 만든다.
3. `CameraDevice`를 다시 열지 않으므로 warm reopen이라는 조건 이름이 흐려지지 않는다.

시간 예산에는 숫자가 두 가지 있다. PLAN-BenchMarker 3.3의 계산상 예산은 약 25–30초이지만, Galaxy S25+ 실측은 45초였고 시작 카드는 실측값을 쓴다. 여기에 녹화 약 50초가 더해지므로 카드의 안내는 약 95초가 된다. 계산상 예산으로는 약 80초이다. 시작 카드의 안내 문구와 `ProgressPresenter`의 단계별 가중치를 함께 고친다.

## 6. 녹화 사이클 하나의 절차와 이벤트

한 사이클은 아래 순서로 진행하고, 각 단계의 앞뒤에 telemetry 이벤트를 남긴다. `MediaRecorder`는 사이클마다 새로 만들고 사이클이 끝나면 `reset()`·`release()`한다.

```text
1. record_prepare_call     프리뷰 전용 repeating은 유지한 채 MediaRecorder를 구성하고 prepare()
2. (세션 재구성)            createCaptureSession(preview, recorder.surface)
   record_configured       onConfigured 진입. 여기까지는 어떤 지표에도 들어가지 않는다
   record_configure_failed onConfigureFailed. 이 사이클은 실패로 기록한다
3. (프리뷰만 repeating)     TEMPLATE_RECORD, target = preview, tag = "record-prep-<n>"
4. record_start_call       MediaRecorder.start() 호출 직전                     <- 3.1 시작점
   record_started          start()가 정상 반환한 직후
5. (녹화 대상 repeating)    TEMPLATE_RECORD, target = preview + recorder, tag = "record-<n>"
   capture_started         tag가 "record-<n>"인 첫 진입                        <- 3.1 끝점
6. (9초 유지)              이 구간의 capture_result가 3.2 · 3.4 · 3.7의 모집단이다
7. (repeating 중단)        stopRepeating(). CTS RecordingTest와 같은 순서이다
   record_stop_call        MediaRecorder.stop() 호출 직전                      <- 3.6 시작점
   record_stopped          stop()이 정상 반환한 직후                            <- 3.6 끝점
   record_failed           stop()이 예외를 던졌거나 OnErrorListener가 울린 경우
8. (세션 복구)              다음 사이클이 있으면 2번으로, 없으면 CLOSE로 간다
```

4번과 5번의 순서는 METRICS.md 3.1의 주석이 제안한 정책을 그대로 따른 것이다. 즉 `start()`가 정상 반환한 뒤에 녹화 대상 request를 제출하므로, 준비 중에 이미 도착한 프리뷰 callback은 3.1의 끝점 후보가 되지 않는다. 7번에서 repeating을 먼저 멈추는 순서는 AOSP `RecordingTest.stopRecording`과 같으며, 이 순서 자체를 측정 조건으로 고정한다.

`record-<n>` 태그는 `CaptureRequest.setTag`로 붙이고, 기존 `Telemetry.callback`이 이미 `requestTag`를 모든 `capture_started`·`capture_result`에 기록하므로 telemetry 쪽 변경은 새 `event()` 호출을 더하는 것뿐이다.

## 7. 지표 계산 규칙

계산은 `metrics` 패키지에 순수 Kotlin으로 추가한다. Android와 `org.json` import를 넣지 않으며 `LayerIsolationTest`가 이를 검사한다. 녹화 cadence는 `MetricExtractor`를 더 키우는 대신 같은 패키지의 `RecordMetrics`에 두었다. 프리뷰 관측 창과 녹화 사이클은 모집단을 고르는 방식이 다르기 때문이다. 프리뷰는 시각 구간으로 자르고 녹화는 request 태그로 고른다. 두 곳에서 같은 통계를 쓰는 표준편차는 `MetricExtractor.stdDev` 하나로 합쳤다.

### 7.1 입력

3.1과 3.6은 runner가 기록한 `RecordCycle`의 마크에서 나오고, 나머지 세 개는 이벤트에서 다시 계산한다. `RecordCadence`에는 사이클의 성공·실패나 warm-up 여부가 들어가지 않는다. `metrics` 패키지는 패키지 그래프의 leaf이므로 `benchmark` 패키지의 개념을 알지 못하며, 두 쪽을 합치는 일은 6단계의 evaluator가 맡는다.

```kotlin
/** 한 녹화 사이클의 cadence. frames는 tag가 "record-<n>"인 capture_result만 담는다. */
data class RecordCadence(
    val frames: Int,
    val intervals: List<Double>,      // 유효 인접 간격, ms
    val comparedIntervals: Int,       // 고정 cadence 구간에서 비교한 개수
    val excludedIntervals: Int,       // 결과 누락 등으로 제외한 쌍
    val anomalyCount: Int?,           // 3.2. 고정 cadence 구간이 없으면 null
    val anomalyUnknownReason: UnknownReason?,
    val windowFpsP50: Double?,        // 3.4
    val windowFpsMin: Double?,        // 3.4
    val windows: Int,
    val jitterStdDevMs: Double?,      // 3.7
    val jitterP95Ms: Double?          // 3.7
)
```

### 7.2 3.1 `record_start_to_camera_callback`

`record_start_call`부터, 그 시각 이후에 도착한 `record-<n>` 태그의 첫 `capture_started`까지의 밀리초이다. 해당 `capture_started`가 없으면 `not_measurable`이며 사이클은 실패로 기록한다. 사이클 간 집계는 nearest-rank p50이고 warm-up 사이클 한 회는 제외한다.

### 7.3 3.2 `camera_frame_interval_anomaly`

1. `record-<n>` 결과를 센서 timestamp 순으로 정렬하고, 프레임 번호가 연속인 쌍만 유효 비교로 센다.
2. 각 결과의 `SENSOR_FRAME_DURATION`으로 구간을 나눈다. `T_ref = 1e9 / recordFps`와 일치하는 구간에서만 고정 기준을 적용한다.
3. 그 구간에서 간격이 `1.5 × T_ref`를 넘은 횟수를 센다.
4. `T_ref`와 일치하는 구간이 하나도 없으면 `anomalyCount = null`이고 `unknown_reason = cadence_changed`이다. 0으로 적지 않는다.

이 값은 실제 손실 개수가 아니므로 원인층은 `unattributed`이다.

화면 이름은 `Record stalls`이다. 처음에는 손실로 읽히지 않도록 `Interval anomalies`로 적었으나, 프리뷰에서 똑같은 계산을 하는 H.5가 이미 `Stalls`라는 이름을 쓰고 있어서 같은 개념에 두 이름이 생기는 문제가 더 컸다. 두 지표 모두 손실 개수가 아니라는 주의는 이름이 아니라 가이드 문서가 진다.

### 7.4 3.4 `steady_fps`

1. `record-<n>` 결과 중 첫 센서 timestamp에 3,000 ms를 더한 시각을 창의 시작점으로 삼는다.
2. 센서 시계에서 겹치지 않는 1초 창마다 결과 개수를 센다. 마지막 불완전한 창은 버린다.
3. 사이클마다 창의 p50과 최솟값을 남긴다. 사이클 간 집계는 p50들의 p50, 최솟값들의 최솟값이다.
4. 완전한 창이 하나도 없으면 `not_measurable`이다.

이 값은 camera result의 fps이며 인코딩되거나 화면에 그려진 fps가 아니다. 단위는 `fps`이고 **클수록 좋은** 유일한 지표이다.

### 7.5 3.6 `record_stop_latency`

`record_stop_call`부터 `record_stopped`까지의 밀리초이다. `stop()`이 예외를 던지면 값을 내지 않고 사이클을 실패로 기록한다. 파일 크기나 재생 가능 여부를 종료 조건으로 쓰지 않는다.

### 7.6 3.7 `frame_interval_jitter`

유효 인접 간격의 모집단 표준편차(ddof = 0)와 nearest-rank p95(ms)이다. 계산식은 `Stats.stdDev`와 같으므로 재사용한다. 사이클마다 계산하고 사이클 간에는 p50으로 집계하며, 원시 간격과 n을 `raw.record`에 보존한다.

## 8. 데이터 모델과 저장에 미치는 영향

| 대상 | 변경 |
|---|---|
| `Category` | `RECORD` 추가 |
| `BenchmarkMetricCatalog` | `3.1 Record start / ms`, `3.4 Record fps / fps`, `3.6 Record stop / ms`, `3.7 Record jitter / ms`, `3.2 Record stalls / count` |
| `BenchmarkMetrics` | `RECORD = listOf("3.1", "3.4", "3.6", "3.7", "3.2")`를 `ALL`에 추가 |
| `BenchmarkRunner.Result` | `records: List<RecordCycle>` 추가 |
| `BenchmarkEvaluator.Input` | `records`와 `recordUnsupported` 추가. 사이클이 없으면 다섯 지표 모두 `NOT_RUN`, 조합이 거부되었으면 `UNSUPPORTED` |
| `MeasurementContract` | **바꾸지 않는다.** 아래 설명 참고 |
| `BenchmarkReportCodec` | `SCHEMA_VERSION` 4에서 5로, `READABLE_SCHEMA_VERSIONS`를 `3..5`로 |
| run JSON | `raw.record`에 사이클별 원시값을 담는다. `profile`에는 4절의 녹화 필드가 들어간다 |
| `ValidityFlags` | `RECORD_NOT_MEASURED` 추가(측정 무효 아님 · 사내 비교 가능 · 교차 기기 점수 제외). `VERSION`을 `validity-v3`으로 올린다 |
| `ScoreComposer` | 카테고리는 네 개 그대로 두고 v1·v2 두 profile을 모두 채점 대상으로 인정한다. 아래 설명 참고 |
| `BenchmarkCsv` | **바꾸지 않는다.** CSV는 run과 지표의 조합마다 한 행이므로 새 지표가 행으로 저장된다 |

**`METRIC_DEFINITION_VERSION`을 올리지 않는 이유.** 처음에는 `metrics-0.4`로 올리기로 적었으나 구현하면서 그 판단을 뒤집었다. 이 값은 비교 계약 id의 구성 요소이고, 규약은 "어떤 지표의 계산이 바뀔 때" 올리도록 정해져 있다. 녹화 지표는 id를 새로 더할 뿐 기존 스무 개의 계산을 하나도 바꾸지 않는다. 그런데도 버전을 올리면 새 앱이 저장한 v1 run의 계약 id가 예전 v1 run과 달라져서, v1끼리의 비교와 기존 baseline이 전부 끊긴다. 3.1절에서 감수하기로 한 비용은 v2로 전환할 때 baseline을 다시 잡는 것이지 v1 이력을 잃는 것이 아니므로, 버전은 `metrics-0.3`으로 둔다.

같은 이유로 evaluator는 녹화하는 profile에서만 3.x 항목을 내보낸다. v1 run의 지표 목록이 예전과 정확히 같아야 하기 때문이다.

**점수의 가중치 0을 구현하는 방법.** `ScoreComposer.floors`와 `categories`에 RECORD를 넣지 않는 것이 곧 가중치 0이다. 3A·RESOURCE·SWITCH가 이미 그런 방식으로 제외되어 있다. 계산이 전혀 바뀌지 않으므로 `VERSION`도 `score-v1-draft` 그대로 둔다. 다만 v1 profile만 채점하도록 못박혀 있던 검사는 canonical profile 전체로 넓혔다. calibration은 계약 id로 묶여 있으므로, v1에서 학습한 calibration이 v2 run을 채점하는 일은 여전히 일어나지 않고 기기마다 calibration을 다시 만들어야 한다.

## 9. 실패 처리

| 상황 | 처리 |
|---|---|
| `onConfigureFailed` (녹화 스트림 조합 미지원) | 재시도하지 않고 남은 사이클을 모두 건너뛴 뒤 CLOSE로 간다. 다섯 지표는 `unsupported`, run에는 `RECORD_NOT_MEASURED`를 붙인다. 같은 조합이 다음 사이클에서 성공할 이유가 없으므로 5회를 반복하지 않는다 |
| `prepare()` · `start()` 예외 | 해당 사이클만 실패로 기록하고 다음 사이클을 시작한다 |
| 3.1의 `capture_started`가 오지 않음 | 사이클을 `record_first_frame_missing`으로 실패 처리하되 녹화 길이는 끝까지 채우고 `stop()`을 정상 호출한다. 중간에 끊으면 `MediaRecorder`와 파일이 다음 사이클로 새기 때문이며, 어차피 중단하려면 같은 `stop()`을 불러야 한다 |
| `stop()` 예외 또는 10초 초과 | 해당 사이클 실패. `MediaRecorder`를 `reset()`·`release()`하고 세션을 복구한 뒤 다음 사이클로 간다 |
| 사이클 연속 실패가 `maxConsecutiveFailures`에 도달 | 남은 사이클을 건너뛰고 CLOSE로 간다. run 자체는 중단하지 않는다 |
| 녹화 중 `abort` | 진행 중인 사이클을 실패로 닫고 CLOSE로 간다. 이미 끝난 사이클의 값은 보존한다 |
| 유효 사이클이 `MIN_RECORD_CYCLES`(3) 미만 | 다섯 지표에 `insufficient_samples`를 붙인다 |

녹화 단계의 실패는 어느 경우에도 run의 `hard_failure`나 `aborted`를 설정하지 않는다. 이 단계가 시작되는 시점에는 1.x·2.x·H.x의 표본이 모두 확보되어 있으므로, 녹화기가 열리지 않았다는 이유로 run 전체를 무효로 만들면 멀쩡한 측정을 함께 버리게 된다. 실패는 `RecordCycle.failure_reason`과 run 수준의 `RECORD_NOT_MEASURED` flag로만 남긴다. 사용자가 직접 누른 `abort`는 예외이며, 기존 규칙대로 run을 중단 상태로 표시한다.

파일은 `cacheDir`에 만들고 사이클이 끝나면 삭제한다. 갤러리에 저장하지 않는다. 측정 중 저장소가 부족해지면 사이클 실패로 기록한다.

## 10. 회귀 규칙

`RegressionRules.VERSION`을 `regression-rule-v2`로 올리고 아래 다섯 줄을 더한다.

| id | kind | direction | deltaPct | noiseFloor |
|---|---|---|---:|---:|
| 3.1 | LATENCY | LOWER_IS_BETTER | 15 % | 10 ms |
| 3.6 | LATENCY | LOWER_IS_BETTER | 15 % | 20 ms |
| 3.7 | LATENCY | LOWER_IS_BETTER | 20 % | 1 ms |
| 3.4 | LATENCY | **HIGHER_IS_BETTER** | 5 % | 1 fps |
| 3.2 | COUNT | LOWER_IS_BETTER | — | 2 |

3.4는 `HIGHER_IS_BETTER`의 첫 사용처이다. `RegressionDetector.state`는 이미 두 방향을 모두 처리하고 있었으므로 규칙만 더했고, 값이 내려갈 때 REGRESSED가 되는지를 경계값과 함께 단위 테스트로 고정했다.

## 11. 테스트 계획

JVM 테스트만으로 아래를 덮는다. 카메라 동작은 fake `Driver`와 fake `Scheduler`로 대신한다.

1. `BenchmarkRunnerRecordTest`: 정상 5회, 구성 실패 시 즉시 종료, 3.1 timeout, stop timeout, 녹화 중 abort, 연속 실패 상한.
2. `MetricExtractorRecordTest`: 33.3 ms 고정 cadence에서 이상 0회·창 30 fps·jitter가 0에 가까움, 80 ms 간격 하나를 넣으면 이상 1회, `SENSOR_FRAME_DURATION`이 바뀌면 3.2가 `cadence_changed`, 녹화가 3초보다 짧으면 3.4가 `not_measurable`, 프레임 번호가 끊기면 제외 개수가 늘어남.
3. `BenchmarkEvaluatorRecordTest`: 사이클이 없을 때 `NOT_RUN`, 유효 사이클이 2회일 때 `INSUFFICIENT_SAMPLES`, warm-up 사이클 제외.
4. `RegressionDetectorTest`: 3.4의 `HIGHER_IS_BETTER` 판정.
5. `BenchmarkReportCodecTest`: schema 5 왕복, schema 3·4 파일이 계속 읽히는지 확인.
6. `LayerIsolationTest`: 기존 규칙 그대로 통과.

실기기 확인은 Galaxy S25+에서 카메라 4대를 대상으로 run을 한 번씩 돌려 3.1·3.6의 값 범위, 3.4가 30 fps 부근인지, 3.2가 0인지를 기록한다. 결과는 `docs/STATUS.md`에 기기·Android 버전·versionCode와 함께 남긴다.

## 12. 이 설계에서 하지 않는 것

- 3.3 `encoder_frame_drop`: `MediaRecorder`로는 인코더 쪽 프레임 수를 얻을 수 없다. `not_measurable`을 유지한다.
- 3.5 `long_run_drift`: 10분 녹화는 기본 반복과 성격이 다르므로 별도 시나리오로 남긴다.
- `MediaCodec.createPersistentInputSurface()`를 쓴 세션 유지: 세션을 한 번만 구성할 수 있어 매력적이지만 기기별 동작 차이를 확인하지 못했고, 지원 여부를 `CameraCharacteristics`로 미리 알 수 없어 "자동 fallback 없음" 원칙과 충돌한다. 사이클마다 세션을 재구성하는 쪽이 CTS `RecordingTest`와 `Camera2Engine.startRecording`의 기존 구조를 그대로 따른다.
- 오디오 트랙: 3장의 어떤 지표도 오디오를 쓰지 않는다.
- CameraX 엔진: profile이 `engine = camera2`로 고정되어 있다.

## 13. 작업 순서

| 단계 | 내용 | 예상 |
|---|---|---:|
| 1 | 이 문서 3절의 결정 확정 | 완료 (2026-09-24) |
| 2 | `BenchmarkProfile` 녹화 필드와 `camera2-standard-v2`, preflight 확장 | 1시간 |
| 3 | `BenchmarkRunner`에 RECORD 단계·Driver 세 메서드·Result 추가와 단위 테스트 | 3시간 |
| 4 | `Camera2Engine`의 벤치마크 녹화 경로와 telemetry 이벤트 | 3시간 |
| 5 | `MetricExtractor`의 다섯 지표와 단위 테스트 | 3시간 |
| 6 | 카탈로그·evaluator·회귀 규칙·validity·schema·CSV·점수 | 3시간 |
| 7 | 화면 7단계, 진행률 가중치, 결과 화면의 RECORD 블록 | 2시간 |
| 8 | 문서 갱신(METRICS.md 구현 상태, guide/benchmark.md, docgen coverage)과 실기기 확인 | 2시간 |
