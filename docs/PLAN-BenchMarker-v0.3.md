# Camera BenchMarker 전환 계획 v0.3

- 작성일: 2026-09-09
- 상태: **2026-09-09 검토 3회 반영(13장). Data contract(3 · 5 · 6장)는 freeze. 이후 변경은 13장에 기록하고 schema_version 또는 계약 버전을 올린다.** 11장의 결정 항목이 확정되면 이 문서를 `PRODUCT-v0.3.md`로 승격한다.
- 대체하는 문서: `PRODUCT-v0.2.md`(Camera Doctor). 지표 정의는 `METRICS.md`를 그대로 상위 문서로 둔다. 시작점과 끝점, 시계, 통계 규칙은 바뀌지 않는다.
- 코드 기준: GitHub `TTolsun/camera-doctor` main `0163730` (2026-09-09). 단위 테스트 96개, lint 오류 0.

## 0. 제품 한 문장

> Camera BenchMarker는 카메라가 건강한지 판정하는 앱이 아니라, 카메라가 얼마나 빠르고 안정적인지, 그리고 SW 변경 후 어떻게 달라졌는지를 측정하는 앱이다.

목표는 두 개다.

1. 기기별 Camera Benchmark. raw metric을 먼저 쌓고 Score는 M5에서 만든다.
2. 개발자가 SW 형상(빌드, commit)별 성능 regression을 추적한다.

계산 순서는 다음과 같다. 점수는 마지막이며 M5 전에는 계산하지 않는다.

```text
measurement (raw samples, n회 반복)
  → statistics (p50 / p95 / min / max / n)
    → validity (측정이 유효한가 → 비교에 써도 되는가 → 점수에 써도 되는가, 세 단계)
      → comparison (baseline 대비 delta %, reference 대비 delta %)
        → regression state (IMPROVED / STABLE / REGRESSED / UNKNOWN, baseline 대비만)
          → score (M5 이후, 선택)
```

---

## 1. v0.2에서 무엇이 바뀌는가

| 항목 | Camera Doctor v0.2 | Camera BenchMarker v0.3 |
|---|---|---|
| 질문 | 카메라가 정상인가 | 얼마나 빠른가, 이전 빌드보다 어떤가 |
| 판정 | PASS / WARN / FAIL, NORMAL / WARNING / ISSUE | 없음. 수치와 IMPROVED / STABLE / REGRESSED |
| 기준 | CDD 500 / 1000 ms, 경험 임계값 | baseline run과의 차이만. 지표별 rule |
| 반복 | endpoint당 open 1회, still 3회 | open 10회, still 10회 (warm-up 1회 제외) |
| 대상 | 4개 endpoint 순회, 약 60초 | 선택한 카메라 1개, 약 45초 |
| 첫 화면 | Consumer Home(`HomeActivity`) | 지금의 Expert 화면(`MainActivity`) |
| Baseline | 첫 run 자동 생성, fingerprint가 바뀌면 무효 | 사용자가 명시적으로 지정. 자동 생성 없음. 빌드가 달라도 유지한다 |
| 점수 | 계산 후 비노출 | M5까지 계산하지 않음 |
| 조건 기록 | conditions 문자열 | 버전이 붙은 Benchmark Profile + 시작 전 compatibility 검사 |
| run 품질 | thermal · 배터리 기록만 | `validity` 블록. measurement / comparison / scoring 세 단계 eligibility |

유지되는 것은 측정 엔진 전체다. `Camera2Engine`, `Telemetry`, `FlightRecorder`, `MetricExtractor`, `CameraEndpointResolver`, 3A Oscilloscope, Frame Timeline, run JSON 원칙(이벤트 원본 보존, nearest-rank 통계, `not_measurable`)은 그대로 쓴다.

### 1.1 v0.3에서 하지 않는 것

- Score와 scoring curve (M5. S25+ 분포를 쌓은 뒤). M5의 첫 점수도 Camera Endpoint Score이며, 기기 전체를 대표하는 Device Score는 표준 endpoint 집합이 갖춰진 v0.4 이후다
- Process cold launch 측정. v1의 launch는 warm reopen이고, cold는 별도 profile(`camera2-cold-launch-v1`)로 v0.4 이후에 정의한다
- Resource 카테고리의 CPU / memory 지표. thermal과 배터리는 기록만 한다
- Switch 카테고리(렌즈 전환 지연). v0.4
- CameraX 벤치마크 프로필. 엔진은 LIVE 비교용으로 유지하고, `camerax-standard-v1`은 METRICS.md 7절의 대리 지표로 v0.4에서 정의한다
- 녹화 지표 3.x
- Git / CI 메타데이터 자동 주입. v0.3은 수동 입력 필드만 둔다
- 기기 간 결과를 모으는 서버

---

## 2. 코드 자산 대응

### 2.1 유지

| 파일 | 상태 |
|---|---|
| `camera/Camera2Engine.kt`, `CameraEngine.kt`, `CameraXEngine.kt` | 유지. Profile의 스트림 크기를 인자로 받도록 생성자만 확장(M2) |
| `telemetry/Telemetry.kt`, `FlightRecorder.kt`, `IncidentExporter.kt` | 유지 |
| `diagnosis/MetricExtractor.kt` | 유지. 패키지를 `metrics`로 옮기고 jitter(H.10) 계산을 추가(M1) |
| `diagnosis/MetricCatalog.kt` | 유지. `consumer` 열과 `stateText`류를 지우고 `category`와 영문 짧은 이름을 추가(M1) |
| `check/CameraEndpoint.kt`, `CameraEndpointResolver.kt` | 유지. 패키지를 `camera`로 이동(M3) |
| `ui/ScopeView.kt`, `TimelineView.kt`, `Look.kt` | 유지 |
| `ui/StripView.kt` | 유지하되 판정 색 제거(M3) |
| `MainActivity.kt` | 유지. 버튼 세 개로 정리하고 런처가 된다(M3) |

### 2.2 이름과 역할이 바뀌는 것, 새로 생기는 것

| 기존 | 신규 | 무엇이 달라지는가 |
|---|---|---|
| `check/AutoCheckRunner` | `benchmark/BenchmarkRunner` | endpoint 순회 대신 1개 카메라에 대해 launch 반복 → warm-up → observe → still 반복 → close. Driver / Scheduler / Listener 인터페이스는 유지 |
| `diagnosis/ThresholdEngine` | `benchmark/BenchmarkEvaluator` | 절대 임계값 판정을 모두 제거. 표본 → 통계 → `BenchmarkMetric`. `judgeRelative`의 delta 계산과 noise floor만 `RegressionDetector`로 옮긴다 |
| `diagnosis/ThresholdTable` | `benchmark/RegressionRules` | 지표별 `RegressionRule` 표(7.2). 기존 `Relative`의 rule 단위 `noiseFloorMs` 구조를 이어받고 CDD / Bounds / Count / Cadence는 삭제 |
| `diagnosis/DiagnosisRules` | (삭제) → `benchmark/RegressionDetector`(신규) | 역할이 다르다. DiagnosisRules는 상태를 원인 규칙에 대응시켰고, RegressionDetector는 delta를 IMPROVED / STABLE / REGRESSED로 바꾼다 |
| `diagnosis/HealthComposer` | (삭제) → `benchmark/ScoreComposer`(M5 신규) | M5 전에는 파일이 없다 |
| `diagnosis/Model.kt` | `benchmark/BenchmarkModel.kt` | `MetricSample`, `UnknownReason`, `jsonName` 유지. `State`, `ThresholdBasis`, `CddApplicability`, `ConditionEquivalence`, `HealthLevelV2`, `CauseLayer`, `MetricState`, `Diagnosis`, `Health` 삭제 |
| `report/HealthReport` | `benchmark/BenchmarkReport` | run JSON schema 3. 6장 |
| `baseline/BaselineStore` | `benchmark/BaselineStore` | 값 사본 대신 run_id 포인터. 사용자가 명시적으로만 지정하고 자동 생성은 없음. fingerprint 불일치로 무효화하지 않음 |
| (신규) | `benchmark/ProfileCompatibility` | 3.6 preflight. static 판정 + API 35 `CameraDeviceSetup` 정확 질의 |
| (신규) | `benchmark/RunValidity` | 5.3 flag 표에서 세 단계 eligibility 유도 |
| (신규) | `benchmark/ThermalTracker` | run 동안 `addThermalStatusListener`로 thermal 최고값 추적(M2) |
| (신규) | `benchmark/BuildIdentity` | 6축 빌드 동일성 비교(7.4) |
| (신규) | `benchmark/ReferenceResolver` | 같은 profile · endpoint의 직전 comparison-eligible run 선택(7.1) |
| `check/CheckEvaluator` | `BenchmarkEvaluator`에 흡수 | |
| `check/CheckResult` | `benchmark/BenchmarkRun` | 저장 JSON을 읽어 화면에 주는 모델 |
| `check/CheckActivity` | `benchmark/BenchmarkActivity` | 프로필 카드, 진행, 결과 |
| `home/RunSummary` | `benchmark/RunIndex`(M6) | 이력 목록용 |
| `diagnosis/HealthMonitor` | `ui/LiveStats` | 실시간 strip. 숫자만 내고 OK / WATCH / WARNING 판정은 제거 |

### 2.3 삭제

M3에서 한 번에 지운다. M1 · M2 동안은 기존 코드가 계속 컴파일되고 동작한다.

```text
home/HomeActivity.kt              Consumer 홈. 런처를 MainActivity로 옮긴다
check/CheckActivity.kt            BenchmarkActivity로 대체
check/AutoCheckRunner.kt          BenchmarkRunner로 대체
check/CheckEvaluator.kt
check/CheckResult.kt
diagnosis/ThresholdEngine.kt
diagnosis/ThresholdTable.kt
diagnosis/HealthComposer.kt
diagnosis/DiagnosisRules.kt
report/HealthReport.kt
baseline/BaselineStore.kt         새 BaselineStore로 대체
MainActivity의 DIAGNOSIS 카드      rule id, cause_layer, Expected/Observed 문구
MainActivity의 "검사" 버튼
Consumer 모드 분기(EXTRA_CONSUMER, "방금 이상했어요")
```

### 2.4 테스트

| 테스트 | 처리 |
|---|---|
| `FlightRecorderTest`, `MetricExtractorTest`, `MetricCatalogTest` | 유지 |
| `AutoCheckRunnerTest` | `BenchmarkRunnerTest`로 개작. 반복 루프와 warm-up 전이 추가 |
| `CheckEvaluatorTest` | `BenchmarkEvaluatorTest`로 개작. 판정 대신 통계 값 검증 |
| `ThresholdEngineTest`의 relative 부분 | `RegressionDetectorTest`로 이동 |
| `ThresholdEngineTest` 나머지, `HealthComposerTest`, `DiagnosisRulesTest`, `HealthMonitorTest` | 삭제 |
| 신규 | `BenchmarkProfileTest`, `BenchmarkReportTest`(JSON round-trip), `BaselineStoreTest`, `RegressionRulesTest`(지표별 경계값), `RunValidityTest`(flag 표 → 세 boolean 유도, thermal_max 경계), `ProfileCompatibilityTest`(static 판정은 fake 출력 크기 목록, 정확 질의 경로는 fake `CameraDeviceSetup` 결과), `BuildIdentityTest` |

테스트 수는 96개에서 한 번 줄었다가 M4 끝에 90개 내외로 돌아온다. 숫자보다 6장 JSON round-trip과 7장 경계값이 빠짐없이 있는지가 기준이다.

---

## 3. Benchmark Profile `camera2-standard-v1`

Profile은 측정 조건 전체를 고정한 불변 객체이고, 모든 run JSON에 id와 내용이 같이 저장된다. 규칙을 바꾸면 `v2`를 만들고 `v1` 결과와 섞지 않는다.

### 3.1 조건

| 항목 | 값 | 비고 |
|---|---|---:|
| `id` | `camera2-standard-v1` | M2 실기기 확인 전까지는 `camera2-standard-v1-draft`로 기록 |
| engine | Camera2 | |
| preview | 1920x1080, TextureView | 현재 코드는 1280x720. METRICS.md 미결 항목의 제안값을 채택 |
| yuv (1.8 대리 스트림, H.4) | 1920x1080, `YUV_420_888`, acquireLatest 후 즉시 close | 현재 코드는 640x480. CTS `testCameraLaunch`와 같은 크기 정책 |
| still | JPEG 1920x1080 | 현재 코드와 동일 |
| fps | `[30, 30]` 고정 | H.1 판정 기준이 아니라 cadence 기록용 |
| zsl / trigger | off / off | |
| AE / AF / AWB | ON / CONTINUOUS_PICTURE / AUTO | AF 모드 목록에 CONTINUOUS_PICTURE가 없는 고정 초점 카메라는 OFF로 실행하고 `conditions.effective.af_mode`에 기록하며 H.7은 `unsupported`. 3.6 compatibility 판정의 유일한 예외 |
| `launchMode` | `WARM_REOPEN` | 같은 프로세스에서 open → close를 반복하는 warm 재열기. METRICS.md 0.2절의 `warm_sequence`. 앱 첫 실행부터 첫 프리뷰까지(process cold)가 아니다 |
| launch 반복 | 10회 (open → configure → first frame → close) | 첫 회 warm-up 제외, n = 9 |
| warm-up | 마지막 open 후 3초 | 관측 창에서 제외 |
| observe | 10초 | H.1 – H.10 |
| still 반복 | 10장, `next_shot_policy = image_received` | 첫 장 warm-up 제외, n = 9. 간격은 8개 |
| 대상 | 카메라 1개 | LIVE 화면에서 선택한 카메라. 기본은 후면 MAIN |

### 3.2 실행 순서와 화면 단계

Runner 내부 상태와 사용자에게 보이는 6단계는 다르다. 화면은 사용자 제안대로 6단계를 유지한다.

```text
runner                                    화면
PREFLIGHT (3.6, 카메라를 열지 않음)          (카드에서 START 가능 여부로 표시)
LAUNCH_CYCLE ×10                          1 / 6  Camera Open
  OPEN → CONFIGURE → FIRST_FRAME → CLOSE
OPEN (11번째, 유지)                        2 / 6  First Preview
WARMUP 3 s
OBSERVE 10 s                              3 / 6  Preview Stability
  (같은 창에서 3A 수렴 계산)                 4 / 6  3A Response
STILL ×10                                 5 / 6  Still Capture
CLOSE                                     6 / 6  Camera Close
```

3A 수렴은 별도 단계가 아니라 11번째 open의 첫 result부터 잰다. 화면의 4단계는 관측 창이 끝난 뒤 값이 확정되는 시점을 표시하는 것이다. thermal listener는 PREFLIGHT 직후 등록하고 CLOSE 뒤 해제한다.

### 3.3 시간 예산 (S25+ 실측 기준 추정)

| 구간 | 예산 |
|---|---:|
| launch 10회 (preview_total 약 420 ms + close 약 100 ms) | 약 7초 |
| 마지막 open + warm-up | 약 4초 |
| observe | 10초 |
| still 10장 (약 300 ms 간격) | 약 4초 |
| close | 0.5초 |
| 합계 | 약 25 – 30초. 카드에는 "약 45초"로 표시하고 M2 실측 후 갱신 |

### 3.4 코드

```kotlin
enum class LaunchMode { WARM_REOPEN, PROCESS_COLD }   // PROCESS_COLD는 예약. v0.3 profile에는 쓰지 않는다

data class BenchmarkProfile(
    val id: String,
    val engine: String,                 // "camera2"
    val previewSize: String,            // "1920x1080"
    val yuvSize: String,
    val stillFormat: String,            // "jpeg"
    val stillSize: String,
    val fpsRange: String,               // "[30,30]"
    val zsl: Boolean,
    val trigger: Boolean,
    val afMode: String,                 // "CONTINUOUS_PICTURE". 고정 초점이면 실행 시 OFF, effective에 기록
    val launchMode: LaunchMode,         // WARM_REOPEN
    val launchIterations: Int,          // 10
    val warmupMs: Long,                 // 3000
    val observeMs: Long,                // 10000
    val stillCount: Int,                // 10
    val excludeFirst: Boolean           // true
) {
    val conditionsKey: String            // 안정된 키 순서의 문자열. BaselineStore 키와 JSON에 사용
    fun toJsonMap(): Map<String, Any?>
    companion object { val CAMERA2_STANDARD_V1: BenchmarkProfile }
}
```

### 3.5 불변 규칙

- `camera2-standard-v1-draft` 기간에만 조건을 바꿀 수 있다. M2 실기기 확인 뒤 `camera2-standard-v1`로 확정하면 이후에는 어떤 값도 바꾸지 않는다. 바꿔야 하면 `v2`다.
- draft로 기록된 run은 `PROFILE_DRAFT` flag가 붙어 scoring 부적격이다. profile id가 다르므로 v1 결과와는 애초에 비교되지 않는다.

### 3.6 ProfileCompatibility preflight

카메라를 열지 않고 판정한다. preflight를 위해 카메라를 열면 측정하지 않은 open이 하나 생기고 warm reopen이라는 조건 이름이 흐려지므로 하지 않는다. 결과는 SUPPORTED / UNSUPPORTED 둘뿐이며 DEGRADED는 두지 않는다. 지원하지 않는 조건을 앱이 낮춰서 같은 profile id로 저장하는 일은 없다(METRICS.md 0.4절 "자동 fallback 없음").

경로는 API 레벨에 따라 둘이다.

```text
1. Static preflight (항상, CameraCharacteristics만 사용)
   출력 크기 · fps range · hardware level

2-a. API ≥ 35 이고 CameraManager.isCameraDeviceSetupSupported(cameraId) == true
     CameraManager.getCameraDeviceSetup(cameraId)
       .isSessionConfigurationSupported(sessionConfiguration)   ← 열지 않는 정확 질의
     sessionConfiguration:
       OutputConfiguration(SurfaceTexture::class.java, 1920x1080)   // PRIV
       OutputConfiguration(YUV_420_888, 1920x1080)                   // API 35의 format+size 생성자
       OutputConfiguration(JPEG, 1920x1080)
       sessionParameters = createCaptureRequest(TEMPLATE_PREVIEW) + AE_TARGET_FPS_RANGE [30,30]
     method = "device_setup"

2-b. 그 외
     hardware level별 보장 조합 표로 static 판정
     method = "static_table"

3. 실제 configure 성공이 최종 runtime validation
```

| 검사 | 근거 | 실패 사유 코드 |
|---|---|---|
| preview 1920x1080 | `StreamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)`에 포함 | `PREVIEW_SIZE` |
| YUV 1920x1080 | `getOutputSizes(YUV_420_888)`에 포함 | `YUV_SIZE` |
| JPEG 1920x1080 | `getOutputSizes(JPEG)`에 포함 | `JPEG_SIZE` |
| fps [30,30] | `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`에 포함 | `FPS_RANGE` |
| 스트림 조합 PRIV + YUV + JPEG | 2-a면 정확 질의 결과. 2-b면 보장 표: LEGACY도 PRIV(PREVIEW) + YUV(PREVIEW) + JPEG(MAXIMUM)은 보장되며, PREVIEW 등급은 display 해상도와 1920x1080 중 작은 쪽이므로 display가 1080p 미만이면 UNSUPPORTED | `STREAM_COMBINATION` |
| 프레임 예산 | `getOutputMinFrameDuration(PRIV, 1080p)`와 `(YUV, 1080p)`를 읽어 33.33 ms 이하인지 `compatibility.frame_budget_ok`에 **기록만** 한다. 판정에는 쓰지 않는다. 실제 30 fps 유지 여부는 runtime의 `CADENCE_NOT_FIXED`가 판단한다. 30 fps가 안 나오는 것이 성능 차이라면 benchmark가 잡아야지 compatibility에서 숨기면 안 된다 | (없음) |
| AF | 판정 대상 아님. CONTINUOUS_PICTURE가 없으면 OFF로 실행하고 기록 | (없음) |

- `CameraDevice.isSessionConfigurationSupported()`(API 29)는 열린 `CameraDevice` 인스턴스가 필요하므로 preflight에 쓰지 않는다.
- 판정 결과는 run JSON의 `compatibility { method, supported, reasons }`에 남긴다. 어떤 경로로 SUPPORTED가 되었는지가 나중에 기기 간 비교에서 중요하다.
- UNSUPPORTED이면 run 파일을 만들지 않고 카드에 사유 코드를 보여 준다. 그 기기를 재기 위한 720p 조건은 `camera2-standard-720p-v1`처럼 별도 profile id로만 정의하며 v0.3 범위 밖이다.
- SUPPORTED였는데 실제 configure가 실패하면 run은 저장하되 `HARD_FAILURE`와 `PREFLIGHT_MISMATCH` flag가 붙는다. 2-b 경로에서 이 조합이 반복되면 보장 표 판정이 틀린 것이므로 STATUS에 기록한다.
- S25+(API 36)에서 `isCameraDeviceSetupSupported()`가 true인지는 M2에서 확인한다.

---

## 4. 지표와 카테고리

기존 METRICS.md id를 그대로 쓴다. 신규는 H.10 하나다. 카테고리는 `MetricCatalog`에 열로 추가한다.

| Category | id | 이름 | 통계 | sampleCount (v1) | samples 저장 | 나오는 단계 |
|---|---|---|---|---:|---|---|
| Launch | 1.1 | `open_latency` | p50 / p95 / min / max | 9 | 저장 | LAUNCH_CYCLE |
| Launch | 1.2 | `configure_latency` | 〃 | 9 | 저장 | 〃 |
| Launch | 1.3 | `first_frame_started_callback` | 〃 | 9 | 저장 | 〃 |
| Launch | 1.8 | `launch_yuv_proxy` | 〃 | 9 | 저장 | 〃 |
| Launch | 1.6 | `preview_total[endpoint=yuv_proxy]` | 〃 | 9 | 저장 | 〃 |
| Launch | 1.7 | `close_latency` | 〃 | 9 | 저장 | 〃 (Switch 카테고리는 v0.4에 예약) |
| Preview | H.1 | `frame_interval_p50` | p50 | 약 297 (간격 수) | null, events에서 재계산 | OBSERVE |
| Preview | H.2 | `frame_interval_p95` | p95 | 약 297 | null | 〃 |
| Preview | H.3 | `partial_latency` | p50 / p95 | 약 298 (프레임 수) | null | 〃 |
| Preview | H.4 | `buffer_latency` | p50 / p95 | 약 298 | null | 〃 |
| Preview | H.10 | `frame_interval_jitter` | 표준편차(ddof=0) ms | 약 297 | null | 〃. 3.7 정의를 preview 창에 적용. **METRICS.md에 추가 필요** |
| Capture | 2.2 | `capture_latency[jpeg,1920x1080,zsl=off,trigger=off]` | p50 / p95 | 9 | 저장 | STILL |
| Capture | 2.3 | `capture_result_latency` | p50 / p95 | 9 | 저장 | 〃 |
| Capture | 2.5 | `shot_to_shot` | p50 / p95 | 8 | 저장 | 〃 |
| Stability | H.5 | `stall_count` | 창 합 | 약 297 (판정한 간격 수) | null | OBSERVE |
| Stability | H.9 | `callback_failure_count` | 합 | 관측 프레임 수 + still 10 | null | OBSERVE + STILL 전체 |
| Stability | 2.7 | `preview_stall_during_capture` | 10개 창 합 | 10개 창의 간격 수 | null | STILL. M2에서 여유가 있으면 포함 |
| 3A | H.6 / H.7 / H.8 | AE / AF / AWB convergence | 단일 | 1 | 저장 (값 1개) | 11번째 open의 첫 result부터. 장면 의존이 커서 score-v1에서는 weight 0(informational). 같은 기기 · 같은 장소의 regression 비교에는 쓴다 |
| Resource | (없음) | thermal start / max / end, 배터리 start / end, 절전 모드 | 기록만 | | | run 전후와 도중 |

**sampleCount와 samples의 뜻.** `sampleCount`는 언제나 그 metric을 계산하는 데 쓴 raw 표본 수다. Launch의 9와 Preview의 297이 같은 뜻이어야 CSV 집계가 사람을 괴롭히지 않는다. `samples`는 표본 수가 profile로 유한하게 정해진 metric(launch, still, 3A)에만 저장하고, 관측 창 metric은 `null`로 두고 `events`에서 재계산한다. H.1과 H.2가 같은 300개 간격을 두 번 저장하지 않기 위해서다.

모든 Launch 값은 `launchMode = WARM_REOPEN` 조건의 값이다. 화면과 export에서 "Open 142 ms"는 항상 profile id와 함께 나오므로 cold 값과 섞이지 않는다.

**p95의 의미.** nearest-rank로 n = 9의 p95는 ceil(0.95 × 9) = 9번째, 곧 max다. JSON에는 p50 / p95 / min / max / n을 모두 저장하고, 화면 표시는 8.4의 규칙(n < 20이면 `max`, n ≥ 20이면 `p95`)을 따른다. 7.2 regression 규칙은 p50만 쓰므로 영향이 없다.

기존 `MetricExtractor.observe`가 H.1 – H.9를 내고 있으므로 H.10만 추가하면 된다. launch와 still의 표본은 `BenchmarkRunner`가 `LinkedHashMap` 시각 표시에서 계산한다. 현재 `AutoCheckRunner.endpointDone()`의 `ms(a, b)` 계산을 cycle 단위로 반복하면 된다.

---

## 5. 데이터 모델

### 5.1 지표와 run

```kotlin
enum class RegressionState { IMPROVED, STABLE, REGRESSED, UNKNOWN }

enum class Category { LAUNCH, PREVIEW, CAPTURE, STABILITY, THREE_A, RESOURCE, SWITCH }

/**
 * 비교 호환성 계약. profile은 "어떻게 촬영했는가", metricDefinitionVersion은 "그 데이터를 어떻게 숫자로 만들었는가"다.
 * 둘 중 하나라도 다르면 숫자를 나란히 놓지 않는다. baseline과 reference의 키는 (comparisonContractId, endpoint.key)다.
 */
data class MeasurementContract(
    val profileId: String,              // "camera2-standard-v1"
    val metricDefinitionVersion: String,// "metrics-0.3" (H.10 추가 시점부터)
    val statsMethod: String,            // "nearest_rank"
    val clock: String                   // "elapsedRealtimeNanos"
) {
    val comparisonContractId: String    // "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos"
}

/** run JSON의 metrics[] 한 항목. 통계는 BenchmarkEvaluator가, 비교는 RegressionDetector가 채운다. */
data class BenchmarkMetric(
    val id: String,                     // "1.1", "H.10"
    val category: Category,
    val unit: String,                   // "ms", "count", "fps"
    val value: Double?,                 // 대표값. latency는 p50, count는 합
    val p50: Double?,
    val p95: Double?,
    val min: Double?,
    val max: Double?,
    val sampleCount: Int,               // 계산에 쓴 raw 표본 수. JSON 이름은 "n"
    val samples: List<Double>?,         // 유한 표본 metric(launch, still, 3A)만. 관측 창 metric은 null
    val excludedWarmup: List<Double>?,  // 제외한 warm-up 표본. samples와 같은 규칙

    val baselineValue: Double?,         // 명시적 baseline 대비
    val deltaPct: Double?,
    val regression: RegressionState,    // baseline 대비로만 계산. reference 대비로는 상태를 내지 않는다

    val referenceValue: Double?,        // 직전 comparison-eligible run 대비. 참고 표시용
    val referenceDeltaPct: Double?,

    val score: Double?,                 // M5 전에는 항상 null
    val unknownReason: UnknownReason?   // NOT_MEASURABLE, NOT_RUN, INSUFFICIENT_SAMPLES, NO_BASELINE, UNSUPPORTED, CONDITION_MISMATCH
)

/**
 * 측정 대상 형상. 벤치마크 앱 자체의 버전은 AppInfo에 따로 있으므로, 여기의 commit은
 * 항상 "측정 대상(subject)"의 것이다. Camera HAL commit일 수도, 플랫폼 manifest revision일 수도 있다.
 */
data class SubjectLabel(
    val subjectBuildLabel: String?,     // "SW42_release_20260909"
    val subjectCommit: String?,         // "a8f29c1"
    val subjectBranch: String?,
    val note: String?
)

data class RunRef(                      // baseline_ref, reference_ref 공용
    val runId: String,
    val sameProfile: Boolean,
    val identity: BuildIdentityComparison
)

data class BenchmarkRun(
    val runId: String,
    val profile: BenchmarkProfile,
    val contract: MeasurementContract,  // comparisonContractId의 출처
    val compatibility: Compatibility,   // method, supported, reasons, frameBudgetOk
    val endpoint: CameraEndpoint,
    val device: DeviceInfo,             // 6장 device 블록
    val app: AppInfo,                   // versionName, versionCode
    val subject: SubjectLabel,
    val env: Map<String, Any?>,
    val validity: RunValidity,
    val baselineRef: RunRef?,
    val referenceRef: RunRef?,
    val metrics: List<BenchmarkMetric>,
    val aborted: String?,
    val file: File?
) {
    val regressedCount: Int get() = metrics.count { it.regression == RegressionState.REGRESSED }
    val improvedCount: Int get() = metrics.count { it.regression == RegressionState.IMPROVED }
}
```

`MetricState`의 `absolute / relative / final / thresholdBasis / cddApplicability / conditionEquivalence`는 모두 사라진다. `UnknownReason`은 유지하되 `CADENCE_CHANGED`는 H.1 판정이 없어졌으므로 삭제한다.

### 5.2 Regression rule

```kotlin
enum class RuleKind { LATENCY, COUNT }
enum class Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER }

data class RegressionRule(
    val metricId: String,
    val kind: RuleKind,
    val direction: Direction,
    val deltaPct: Double?,              // LATENCY만. COUNT는 null
    val noiseFloor: Double              // LATENCY는 ms, COUNT는 개수
)

object RegressionRules {
    const val VERSION = "regression-rule-v1"
    val rules: Map<String, RegressionRule>   // 7.2 표
}
```

### 5.3 Validity

"측정이 유효하다", "regression 비교에 써도 된다", "기기 간 점수에 써도 된다"는 서로 다른 질문이다. boolean 하나로 합치지 않고 세 단계로 나눈다. 세 boolean은 따로 정하지 않고 아래 flag 표에서 유도한다. 규칙의 출처가 표 하나이고 테스트도 표 기준으로 끝난다.

```kotlin
data class ValidityFlag(
    val code: String,
    val blocksMeasurement: Boolean,
    val blocksComparison: Boolean,
    val blocksScoring: Boolean
)

data class RunValidity(
    val measurementValid: Boolean,      // 어떤 flag도 blocksMeasurement가 아님
    val comparisonEligible: Boolean,    // measurementValid 이고 어떤 flag도 blocksComparison이 아님
    val scoringEligible: Boolean,       // comparisonEligible 이고 어떤 flag도 blocksScoring이 아님
    val flags: List<String>             // 발생한 flag code 전부
)
```

| flag | 조건 | measurement | comparison | scoring |
|---|---|:-:|:-:|:-:|
| `ABORTED` | 사용자 중단, 백그라운드 전환 | ✗ | ✗ | ✗ |
| `HARD_FAILURE` | timeout이나 error로 cycle이나 still이 빠짐 | ✗ | ✗ | ✗ |
| `PROFILE_UNSUPPORTED` | preflight 실패. 실제로는 run을 만들지 않으므로 방어용 | ✗ | ✗ | ✗ |
| `INSUFFICIENT_SAMPLES` | launch n < 9, still n < 9, 또는 관측 프레임 수가 `MetricExtractor` 최소 표본(15) 미만 | ✗ | ✗ | ✗ |
| `CADENCE_NOT_FIXED` | 관측 창의 effective fps range가 [30,30]이 아님. 측정은 맞지만 다른 cadence와 비교할 수 없다 | ○ | ✗ | ✗ |
| `THERMAL_HIGH` | **run 동안의 thermal 최고값** `thermal_max ≥ MODERATE`. 시작값이 아니다 | ○ | ✗ | ✗ |
| `POWER_SAVE_MODE` | `PowerManager.isPowerSaveMode()` == true. 절전 모드는 governor를 바꾼다 | ○ | ✗ | ✗ |
| `PREFLIGHT_MISMATCH` | SUPPORTED 판정 뒤 configure 실패. `HARD_FAILURE`와 함께 붙는다 | ○ | ○ | ○ |
| `CHARGING` | 충전 중. 사내 regression은 허용, 기기 간 점수는 제외 | ○ | ○ | ✗ |
| `BATTERY_LOW` | 시작 배터리 < 20 %. 성능을 실제로 바꾸는 것은 절전 모드이므로 그 자체는 비교 허용 | ○ | ○ | ✗ |
| `PROFILE_DRAFT` | profile id가 `-draft` | ○ | ○ | ✗ |
| `THERMAL_CHANGED` | `thermal_start ≠ thermal_end`. 참고 | ○ | ○ | ○ |
| `LABEL_MISSING` | subject label과 commit이 모두 비어 있음. 참고 | ○ | ○ | ○ |

소비자별 사용 규칙은 다음과 같다.

| 소비자 | 요구 단계 |
|---|---|
| 이력 표시, EXPORT | 없음. 모든 run |
| REFERENCE 자동 선택 | `comparisonEligible` |
| `SET AS BASELINE` | `comparisonEligible`. 아니면 버튼 비활성 |
| M5 dataset, PC 집계 기본 필터 | `scoringEligible` |

세 단계는 run 하나의 성질이다. 두 run 사이의 조건 차이(충전 상태가 다름, thermal_max 차이, 노출 부하 비율)는 7.5의 비교 시점 규칙이 따로 다룬다.

### 5.4 Build identity

```kotlin
data class BuildIdentityComparison(
    val sameSystemFingerprint: Boolean,      // Build.FINGERPRINT
    val sameVendorFingerprint: Boolean?,     // ro.vendor.build.fingerprint. 한쪽이라도 못 읽었으면 null
    val sameCameraInfoVersion: Boolean?,     // CameraCharacteristics.INFO_VERSION. 미제공이면 null
    val sameAppVersion: Boolean,             // 벤치마크 앱
    val sameSubjectLabel: Boolean?,          // 둘 다 입력했을 때만
    val sameSubjectCommit: Boolean?
)
```

---

## 6. Run JSON schema 3

파일 위치는 `files/benchmarks/<runId>.json`이다. `file_paths.xml`에 `benchmarks/` 경로를 추가한다. 기존 `checks/`는 v0.2 결과 읽기 호환을 위해 M6까지 남긴다.

```json
{
  "schema_version": 3,
  "kind": "benchmark",
  "run_id": "20260909-101422",
  "exported_at_utc": "2026-09-09T01:14:22.000Z",
  "aborted": null,

  "profile": { "id": "camera2-standard-v1", "engine": "camera2", "preview_size": "1920x1080", "yuv_size": "1920x1080",
               "still_format": "jpeg", "still_size": "1920x1080", "fps_range": "[30,30]", "zsl": false, "trigger": false,
               "af_mode": "CONTINUOUS_PICTURE", "launch_mode": "warm_reopen", "launch_iterations": 10, "warmup_ms": 3000,
               "observe_ms": 10000, "still_count": 10, "exclude_first": true },
  "compatibility": { "method": "device_setup", "supported": true, "reasons": [], "frame_budget_ok": true },
  "conditions": { "effective": { "af_mode": "CONTINUOUS_PICTURE", "fps_range": "[30,30]", "preview_size": "1920x1080", "yuv_size": "1920x1080" } },
  "metric_definition_version": "metrics-0.3",
  "stats_method": "nearest_rank",
  "clock": "elapsedRealtimeNanos",
  "comparison_contract_id": "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos",
  "regression_rule_version": "regression-rule-v1",
  "scoring_rule_version": null,

  "device": { "manufacturer": "samsung", "model": "SM-S936N", "build_display": "BP4A.251205.006", "build_incremental": "...",
              "fingerprint": "samsung/...", "vendor_fingerprint": "...", "sdk": 36, "security_patch": "2025-12-01",
              "camera_info_version": "..." },
  "app": { "version_name": "0.3.0", "version_code": 3 },
  "subject": { "build_label": "SW42_release_20260909", "commit": "a8f29c1", "branch": null, "note": "SAT 변경 적용" },
  "endpoint": { "logicalCameraId": "0", "physicalCameraId": null, "role": "MAIN", "timestampSource": 1, "hardwareLevel": 3 },
  "env": { "thermal_start": 0, "thermal_max": 1, "thermal_end": 1, "battery_start": 82, "battery_end": 80,
           "charging": false, "power_save_mode": false, "rotation": 0 },

  "validity": { "measurement_valid": true, "comparison_eligible": true, "scoring_eligible": true, "flags": ["THERMAL_CHANGED"] },

  "baseline_ref": { "run_id": "20260909-095100", "same_profile": true,
                    "identity": { "same_system_fingerprint": true, "same_vendor_fingerprint": false, "same_camera_info_version": null,
                                  "same_app_version": true, "same_subject_label": false, "same_subject_commit": false } },
  "reference_ref": { "run_id": "20260909-100302", "same_profile": true,
                     "identity": { "same_system_fingerprint": true, "same_vendor_fingerprint": true, "same_camera_info_version": null,
                                   "same_app_version": true, "same_subject_label": true, "same_subject_commit": true } },

  "metrics": [
    { "id": "1.1", "category": "launch", "unit": "ms", "value": 142.3, "p50": 142.3, "p95": 161.0, "min": 131.2, "max": 161.0, "n": 9,
      "samples": [142.3, 139.8, 161.0, 131.2, 144.0, 140.1, 150.2, 138.7, 143.9], "excluded_warmup": [201.4],
      "baseline_value": 151.2, "delta_pct": -5.9, "regression": "stable",
      "reference_value": 140.9, "reference_delta_pct": 1.0,
      "score": null, "unknown_reason": null }
  ],

  "summary": { "regressed": 1, "improved": 0, "stable": 14, "unknown": 2, "endpoint_score": null },

  "raw": {
    "launch_cycles": [ { "iteration": 0, "warmup": true, "open_ms": 201.4, "configure_ms": 41.0, "first_started_ms": 96.2,
                         "yuv_proxy_ms": 120.5, "preview_total_ms": 412.0, "close_ms": 88.1, "timestamps_ns": { "open_call": "..." } } ],
    "stills": [ { "index": 0, "warmup": true, "submit_ns": "...", "image_ns": "...", "result_ns": "...", "latency_ms": 180.2 } ],
    "observation": { "frames": 298, "interval_p50_ms": 33.3, "stall_count": 0, "three_a_stable": true, "af_supported": true,
                     "exposure_load_p50": 1.2e6 }
  },
  "events": []
}
```

위 수치는 구조 설명용이며 실측이 아니다. `events`에는 v0.2와 같이 전체 telemetry 이벤트를 넣어 PC에서 통계를 다시 계산할 수 있게 한다.

- `env.thermal_max`는 run 동안 `PowerManager.addThermalStatusListener`(API 29)로 받은 최고 상태다. thermal 변화는 시각과 함께 `events`에도 `thermal_status` 이벤트로 넣어 PC에서 throttling 시점을 볼 수 있게 한다. run 중간에 올라갔다가 끝날 때 내려오는 경우가 있으므로 시작값과 끝값만으로는 부족하다.
- `device.camera_info_version`은 `CameraCharacteristics.INFO_VERSION`(API 28 이상, vendor가 제공하면 문자열)이다. `vendor_fingerprint`는 `getprop ro.vendor.build.fingerprint` 결과이며 읽지 못하면 null이다. 둘 다 "가능하면 HAL 버전" 요구에 대한 현재 공개 API의 최대치다.
- `subject` 블록의 commit과 branch는 측정 대상 형상의 것이다. 벤치마크 앱의 형상은 `app`에 있다.
- `metrics[].regression`은 run 시점의 `regression_rule_version`으로 계산해 저장한다. COMPARE 화면은 앱의 현재 rule version으로 다시 계산하고 어느 버전을 썼는지 표시한다. rule을 조정해도 과거 JSON을 다시 쓰지 않는다.
- PC 집계 스크립트의 기본 필터는 `validity.scoring_eligible == true`다. regression 분석용으로는 `comparison_eligible`을 옵션으로 선택한다.
- `raw.observation.exposure_load_p50`은 `SENSOR_SENSITIVITY × SENSOR_EXPOSURE_TIME`의 관측 창 p50이며 7.5의 조건 비교에 쓴다.

---

## 7. Baseline, Reference, Regression

### 7.1 Baseline과 Reference

두 기준점을 분리한다. 단어의 뜻을 지키기 위해서다.

| | BASELINE | REFERENCE |
|---|---|---|
| 뜻 | 개발자가 의도적으로 고른 기준 형상 | 직전에 잰 값 |
| 정하는 방법 | 결과 화면이나 이력에서 `SET AS BASELINE`. **자동 생성 없음** | 같은 `(comparisonContractId, endpoint.key)`의 가장 최근 comparison-eligible run을 자동 선택. 자기 자신 제외 |
| 해제하는 방법 | 이미 baseline인 run에서는 같은 버튼이 `CLEAR BASELINE`이다. 포인터만 지우고 run 파일은 남긴다 | 해당 없음 |
| 저장 | `files/benchmarks/index.json`에 `(comparisonContractId, endpoint.key)` → run_id 포인터 | 저장하지 않고 조회 시 계산 |
| regression 상태 | 이것 대비로만 IMPROVED / STABLE / REGRESSED | 상태 없음. delta %만 참고 표시 |
| 없을 때 | `UNKNOWN(no_baseline)`. 화면에는 reference delta만 | 첫 run이면 표시 없음 |

- 자동 baseline을 없앤 이유: v0.2 checkpoint-007에서 첫 검사가 스트림 시작 artefact(stall 1회)를 안은 채 baseline이 되었다. 첫 run은 설치 직후, 발열 상태, 잘못된 label 등 우연에 가장 많이 노출된다.
- **fingerprint가 달라도 baseline은 무효화하지 않는다.** 빌드 간 regression 추적이 이 제품의 목적이다. 대신 7.4의 identity 비교를 함께 저장하고 화면에 보여 준다.
- profile.id가 다르면 비교하지 않는다. 모든 지표가 `UNKNOWN(condition_mismatch)`이다.
- comparison 부적격 run(5.3)은 baseline으로 지정할 수 없고 reference로도 선택되지 않는다.
- baseline run 파일이 삭제되면 포인터를 지우고 `NO_BASELINE`으로 돌아간다.
- **baseline 해제는 run 삭제와 별개다.** 기준으로 삼았던 형상이 더 이상 기준이 아니게 되는 일과, 그 측정 결과가 필요 없어지는 일은 다르다. 지정을 무르려고 run 파일을 지워야 한다면 측정 데이터를 잃게 되므로, 이미 baseline인 run의 결과 화면에서는 `SET AS BASELINE`이 `CLEAR BASELINE`으로 바뀌어 포인터만 지운다. 다른 run에 `SET AS BASELINE`을 누르면 포인터는 그대로 덮어써지므로 갱신에는 별도 동작이 필요 없다.

### 7.2 Regression rule `regression-rule-v1`

전역 noise floor는 쓰지 않는다. 10 ms 하나로는 Jitter(수 ms)와 Interval p95(약 33 ms)의 변화를 놓치기 때문이다. 지표별 표이며, 값 하나라도 바꾸면 `regression-rule-v2`다. 아래 값은 초기 제안이고 M5에서 S25+ 반복 분포를 보고 조정한다.

```text
LATENCY (lower is better)
  delta_pct = (current − baseline) / baseline × 100
  REGRESSED : delta_pct ≥ +deltaPct 이고 current − baseline ≥ noiseFloor
  IMPROVED  : delta_pct ≤ −deltaPct 이고 baseline − current ≥ noiseFloor
  STABLE    : 그 외

COUNT (lower is better)
  REGRESSED : current − baseline ≥ noiseFloor
  IMPROVED  : baseline − current ≥ noiseFloor
  STABLE    : 그 외

HIGHER_IS_BETTER 지표는 부호 반전 (v0.3에는 없음)
UNKNOWN   : baseline 없음 / 둘 중 하나가 값 없음 / profile 불일치 / 7.5의 조건 불일치
```

| 지표 | kind | deltaPct | noiseFloor |
|---|---|---:|---:|
| 1.1 open, 1.3 first started, 1.8 yuv proxy, 1.6 preview_total | LATENCY | 15 % | 10 ms |
| 1.2 configure, 1.7 close | LATENCY | 15 % | 5 ms |
| 2.2 capture, 2.3 result, 2.5 shot_to_shot | LATENCY | 15 % | 10 ms |
| H.1 interval p50, H.2 interval p95 | LATENCY | 10 % | 2 ms |
| H.3 partial, H.4 buffer | LATENCY | 15 % | 5 ms |
| H.10 jitter | LATENCY | 20 % | 1 ms |
| H.6 / H.7 / H.8 3A | LATENCY | 30 % | 200 ms |
| H.5 stall, 2.7 preview stall during capture | COUNT | — | 2 |
| H.9 callback failure | COUNT | — | 1 |

v1의 비교와 regression은 대표값(`value`, latency는 p50)만 쓴다. p95 / min / max는 raw statistics로만 저장하고 비교 필드를 따로 두지 않는다. run 전체의 `REGRESSION DETECTED` 배너는 `regressed ≥ 1`일 때다.

비교는 `comparisonContractId`가 같은 run 사이에서만 한다. profile이 같아도 `metric_definition_version`이 다르면(예: H.10 계산법 변경) `UNKNOWN(condition_mismatch)`이다.

### 7.3 Compare

두 run JSON을 읽어 지표별로 나란히 놓는다. 기본은 baseline 대 최신 run이고, M6의 이력 화면에서 임의의 두 run을 고를 수 있다. 비교 자체는 `RegressionDetector.compare(base, current, rules)` 순수 함수 하나이고 화면은 그 결과를 표로 그린다. 아래 예시는 7.2 표로 검산한 것이다. Open +13 %는 15 % 미만이라 STABLE이고, Jitter +122 %는 절대 차이 3.9 ms가 floor 1 ms를 넘으므로 REGRESSED다.

```text
COMPARE                                   rule regression-rule-v1
baseline  20260909-095100  SW41          BP4A.251205.006   thermal max 1
current   20260909-101422  SW42_release  BP4A.251205.006   thermal max 1
          Android 동일 · Camera build 다름 · 앱 동일 · subject 다름

                    BASELINE    CURRENT
Open                 142 ms      161 ms    +13%
Configure             38 ms       41 ms     +8%
First frame          381 ms      419 ms    +10%
Capture              164 ms      221 ms    +35%  ▲ REGRESSED
Interval p50        33.3 ms     33.4 ms      0%
Jitter               3.2 ms      7.1 ms   +122%  ▲ REGRESSED
Stalls                   0           0
```

### 7.4 Build identity

`same_build` 같은 boolean 하나로 줄이지 않는다. Camera HAL 개발에서는 Android fingerprint가 같아도 vendor binary나 실험 형상이 다를 수 있다. 5.4의 여섯 축을 각각 비교해 저장하고, 화면에는 세 줄에서 네 줄로 요약한다.

| 화면 문구 | 근거 |
|---|---|
| Android 동일 / 다름 | `sameSystemFingerprint` |
| Camera build 동일 / 다름 / 알 수 없음 | `sameVendorFingerprint`와 `sameCameraInfoVersion` 중 읽을 수 있는 것. 둘 다 null이면 "알 수 없음" |
| 앱 동일 / 다름 | `sameAppVersion` |
| subject 동일 / 다름 | `sameSubjectLabel`, `sameSubjectCommit`. 둘 다 null이면 줄을 생략 |

### 7.5 비교 시점의 조건 차이

5.3의 eligibility는 run 하나의 성질이다. 두 run이 각각 적격이어도 서로 조건이 다르면 비교가 왜곡된다. `RegressionDetector.compare`는 아래를 확인하고 결과에 `condition_mismatch[]`를 붙인다.

| 조건 | 판정 | 영향 |
|---|---|---|
| `thermal_max` 차이 ≥ 2 단계 | `THERMAL_MAX_DIFFERS` | 모든 지표 `UNKNOWN(condition_mismatch)`. delta는 표시 |
| `power_save_mode` 다름 | `POWER_SAVE_DIFFERS` | 모든 지표 `UNKNOWN(condition_mismatch)` |
| `charging` 다름 | `CHARGING_DIFFERS` | 상태는 유지하고 배너로만 표시 |
| `exposure_load_p50` 비율 > 4 또는 < 0.25 | `EXPOSURE_DIFFERS` | H.6 / H.7 / H.8만 `UNKNOWN(condition_mismatch)` (v0.2 6장 규칙 계승) |
| (미결) 같은 비율과 Capture 지표 | — | 저조도에서는 노출 시간, readout, vendor 처리 경로가 바뀌어 2.2 / 2.3 / 2.5도 장면 영향을 받을 수 있다. **M2에서 밝음 · 어두움 각각 반복해 상관을 실측한 뒤** 포함 여부를 정한다. 지금 추측으로 규칙을 넣지 않는다 |

---

## 8. 화면 계약

### 8.1 LIVE (`MainActivity`, 런처)

지금 화면을 그대로 두고 아래만 바꾼다.

| 기존 | 변경 |
|---|---|
| `MARK INCIDENT` / `방금 이상했어요` | `MARK`. 기능은 `FlightRecorder.trigger` 그대로. zip 저장 후 요약 대화상자는 raw 값만 남긴다 |
| 셔터(원형) | 유지. 접근성 이름 `SHUTTER` |
| `검사` | 삭제 |
| `진단` (패널 토글) | `BENCHMARK` 버튼으로 교체. 패널 토글은 작은 아이콘 버튼으로 우측 상단에 남긴다 |
| DIAGNOSIS 카드 | 삭제. 패널에는 raw 지표 표만 남는다 |
| health strip | 숫자만 표시. 정상 / 주의 / 이상 색과 문구 제거 |
| CameraX / Camera2 / 카메라 선택 | 유지. 여기서 고른 카메라가 BENCHMARK 대상이다 |

### 8.2 BENCHMARK 시작 카드 (`BenchmarkActivity`)

```text
STANDARD CAMERA BENCHMARK

Camera2 · 후면 메인 · 1080p30 · warm reopen
Profile  camera2-standard-v1        ✓ 이 카메라에서 실행 가능 (device_setup)
약 45초. 밝은 곳에서 글자나 물건을 향해 폰을 고정하세요.

Subject build   [                    ]   (선택, 직전 run 값 미리 채움)
Subject commit  [          ]             (선택)
Note            [                    ]   (선택)

[ START BENCHMARK ]
```

- 카드를 열 때 3.6 preflight를 먼저 돌린다. 카메라는 열지 않는다. UNSUPPORTED이면 START 대신 사유 코드(`YUV_SIZE` 등)와 "이 profile은 이 카메라에서 실행할 수 없습니다"를 보여 준다.
- CameraX가 선택된 상태에서 들어오면 "Benchmark profile v1은 Camera2 전용입니다. Camera2로 전환합니다"를 표시하고 Camera2로 바꾼다.
- thermal이 SEVERE 이상이면 v0.2와 같이 시작하지 않는다. MODERATE이면 시작은 하되 "이 run은 비교와 점수에 쓸 수 없습니다(THERMAL_HIGH)"를 카드에 띄운다. 절전 모드가 켜져 있으면 같은 형식으로 `POWER_SAVE_MODE`를 알린다.

### 8.3 진행

```text
BENCHMARKING

3 / 6  Preview stability
██████████████░░░░░░  72%

interval p50   33.3 ms
stalls         0
frames         214
thermal        1
```

프리뷰는 계속 보인다. `FLAG_KEEP_SCREEN_ON`을 켜고, 백그라운드로 가면 `aborted = background`로 저장한다.

### 8.4 결과

Score 자리는 M5까지 없다. 숫자와 delta만 보인다.

- 비교 열은 baseline이 있으면 "vs baseline", 없으면 "vs previous"(reference)이며 후자에는 ▲ 표시가 붙지 않는다.
- 두 번째 통계 열은 **n < 20이면 `max`, n ≥ 20이면 `p95`**다. n = 9의 nearest-rank p95는 max이므로 p95라고 쓰면 사람이 안정된 percentile로 오해한다. JSON에는 둘 다 있다.

```text
CAMERA BENCHMARK
Galaxy S25+ · 후면 메인 · camera2-standard-v1 · warm reopen
2026-09-09 10:14 · BP4A.251205.006 · SW42_release
비교 가능 · 점수 가능 · thermal 0 → 1 → 1

▲ 1 REGRESSED   baseline 20260909-095100
                Android 동일 · Camera build 다름 · 앱 동일

LAUNCH                p50       max    vs baseline
  Open              142 ms    161 ms       +3%
  Configure          38 ms     44 ms       −1%
  First frame       381 ms    419 ms      +10%
  Close              91 ms    104 ms       +2%
PREVIEW
  Interval p50     33.3 ms                  0%
  Interval p95     34.1 ms                 +1%
  Jitter            3.8 ms                +12%
CAPTURE
  Capture          164 ms    203 ms      +35%  ▲
  Result           158 ms    190 ms      +30%  ▲
  Shot-to-shot     412 ms    455 ms       +8%
STABILITY
  Stalls               0                    0
  Callback fail        0                    0
3A (informational)
  AE / AF / AWB   420 / 610 / 380 ms

[ SET AS BASELINE ]   [ COMPARE ]   [ EXPORT ]
```

이 run이 이미 baseline이면 첫 버튼이 `[ CLEAR BASELINE ]`이 된다(7.1).

baseline이 없는 첫 run의 머리글은 다음과 같다.

```text
baseline 없음 · 이전 run 20260909-100302 대비 표시
[ SET AS BASELINE ]을 누르면 이 run이 기준이 됩니다
```

eligibility에 따라 머리글이 달라진다.

```text
비교 가능 · 점수 제외 (CHARGING)
비교 불가 (THERMAL_HIGH, max 3) · SET AS BASELINE 비활성
측정 무효 (HARD_FAILURE)      · SET AS BASELINE 비활성
```

지표 표시 이름은 영문 짧은 이름(`Open`, `First frame`, `Capture`)을 쓴다. 사용자 제안의 화면 예시와 맞추고, 개발자가 CTS 이름과 대응하기 쉽기 때문이다. 안내 문구는 한국어다.

### 8.5 RESULTS 이력 (M6)

```text
RESULTS                                 camera2-standard-v1 · 후면 메인   [비교 가능만]

09-09 10:14  SW42_release  a8f29c1   Capture 221 ms   ▲ 1
09-09 10:03  SW42_release  a8f29c1   Capture 219 ms
09-09 09:51  SW41          9c01d2e   Capture 164 ms   ★ baseline
09-08 22:40  (subject 없음)           Capture 158 ms   비교 불가 · THERMAL_HIGH
```

행을 누르면 결과 화면, 길게 누르면 `SET AS BASELINE` / `COMPARE` / `EXPORT` / `DELETE`다.

---

## 9. 마일스톤

사용자 제안 순서를 그대로 따르고, 검토에서 제안된 이름을 붙였다. Score는 M5다. 예상 소요는 이전 M1 – M5(v0.2) 속도와 같은 방식(브랜치, cavecrew 리뷰, checkpoint)을 전제로 한다.

| 단계 | 이름 | 내용 | 완료 기준 | 예상 |
|---|---|---|---|---:|
| **M1** | Data contract | `BenchmarkProfile`(launchMode 포함), `BenchmarkModel`(`SubjectLabel`, `RunRef`), `RegressionRules` 표(값은 잠정), `RunValidity`(flag 표 → 세 단계), `BuildIdentity`, `BenchmarkEvaluator`(통계만), `BenchmarkReport`(JSON 3 쓰기 · 읽기, `compatibility` · `env.thermal_max` · `power_save_mode` 포함), `BenchmarkStore`(파일 · index). `MetricExtractor`에 H.10 | 단위 테스트: profile 직렬화, JSON round-trip, 통계 경계(n=9 p95는 max, n=8 간격), 7.2 표의 지표별 경계값, validity flag 표에서 세 boolean 유도(thermal_max 경계 포함), identity 비교의 null 처리. 기존 앱 동작 변화 없음. `assembleDebug testDebugUnitTest lintDebug` 통과 | 약 4시간 |
| **M2** | Measurement correctness | `ProfileCompatibility`(static + API 35 `CameraDeviceSetup` 경로), `ThermalTracker`, `BenchmarkRunner` 순수 Kotlin(launch 반복 → warm-up → observe → still 반복 → close, timeout과 abort), `Camera2Engine`이 profile 스트림 크기를 받음, 최소 `BenchmarkActivity`(preflight 결과와 method, START, 진행 텍스트, 완료 후 JSON 경로) | **S25+에서 후면 메인 3회, 전면 1회(preflight 통과 확인), 초광각 1회(AF OFF 경로) 완주하고 JSON을 Drive에 저장.** `isCameraDeviceSetupSupported()` 값과 정확 질의 결과 기록. YUV 1080p가 stall을 만들지 확인한 뒤 profile id를 `-draft`에서 확정. warm reopen 값이 v0.2 단일 open 값과 어떻게 다른지, thermal_max가 run 중 어떻게 움직였는지 STATUS에 기록. **밝은 곳과 어두운 곳에서 각각 반복해 `exposure_load_p50`과 2.2 / 2.3 / 2.5의 상관을 확인**하고 7.5에 Capture를 넣을지 결정 | 약 7시간 + 실기기 1시간 |
| **M3** | Product conversion | 8.1 LIVE 버튼 정리, 런처를 `MainActivity`로, 8.2 – 8.4 화면(p50 / max 표시 규칙, eligibility 머리글), `EXPORT`(JSON 공유). 2.3의 삭제 목록 전부 제거. 앱 label "Camera BenchMarker", versionName 0.3.x | Doctor 클래스 0개. 결과 화면이 eligibility 세 단계와 reference delta를 표시 | 약 5시간 |
| **M4** | Developer workflow | 명시적 `BaselineStore`(포인터, comparison-eligible만 허용), `ReferenceResolver`, `RegressionDetector`(7.2 표 적용, 7.5 조건 차이, 표시 시점 재계산), 결과 화면의 vs baseline / vs previous 열, 7.4 identity 요약, `SET AS BASELINE`과 `CLEAR BASELINE`, 7.3 COMPARE 화면 | 테스트: 7.2 표 각 행의 경계값, profile 불일치, 7.5 네 조건, 부적격 run 배제, baseline 파일 삭제, baseline 해제 후 `NO_BASELINE` 복귀, identity null 조합 | 약 6시간 |
| **M5a** | Internal score | `ScoreComposer`, 지표별 normalization curve 시제품, 카테고리 점수, **Camera Endpoint Score** 0 – 1000, 3A 카테고리 weight 0, `scoring_rule_version = score-v1-draft`. curve 학습 dataset은 정상 조건의 `scoring_eligible` run만 | **시작 조건: S25+ profile v1 scoring-eligible run이 정상 조건 10회 이상.** 발열 · 저조도 · 카메라 점유 경쟁 run(각 3회 이상)은 curve에 넣지 않고 점수가 실제로 내려가는지 확인하는 sensitivity 검증에만 쓴다 | 데이터 수집 후 약 3시간 |
| **M5b** | Public endpoint score | 여러 제조사 · 성능군 단말의 scoring-eligible 분포로 curve 확정, `scoring_rule_version = score-v1`. 이름은 Camera Endpoint Score. Device Score는 표준 endpoint 집합 이후 | **release gate: 최소 5 – 10개 기기의 분포.** S25+ 하나로 만든 curve는 S25+ regression score이지 기기 간 benchmark score가 아니다 | 기기 확보 후 약 3시간 |
| **M6** | History / export / configuration tracking | 8.5 RESULTS 화면(eligibility 필터), `RunIndex`, 임의 두 run COMPARE, run 삭제, CSV export, PC 집계 스크립트(`tools/aggregate.py`: JSON 폴더 → CSV, 기본 `scoring_eligible`, 옵션 `comparison_eligible`), subject 자동 채움(직전 run 값 재사용) | | 약 5시간 |

M1과 M2는 기존 Doctor 화면과 나란히 존재한다. M3에서 Doctor 코드를 지우기 전까지 앱은 두 흐름을 모두 가지며, 이 상태로 배포하지 않는다.

M2가 끝나면 사용자는 결과 UI 없이도 S25+에서 run을 반복해 raw 분포를 쌓기 시작할 수 있다. M5의 시작 조건이 이 데이터다.

---

## 10. 작업 방식

1. 마일스톤마다 브랜치 하나: `feature/bm-m1-model`, `feature/bm-m2-runner`, … . 커밋과 merge에는 `-c user.name=TTolsun -c user.email=112919054+TTolsun@users.noreply.github.com`을 붙인다.
2. 빌드는 Codex toolchain으로 `gradle.bat -p <project> --no-daemon --offline assembleDebug testDebugUnitTest lintDebug`.
3. merge 전에 cavecrew-reviewer 리뷰. 심각 지적은 같은 브랜치에서 수정 후 merge.
4. Drive `checkpoints/checkpoint-008-bm-m1-model/`부터 이어서 APK, diff, STATUS, 실기기 JSON을 저장한다.
5. 실기기 검증은 사용자가 잠금을 풀고 `START BENCHMARK`를 누른다. 결과는 `adb exec-out run-as dev.cameradoctor cat files/benchmarks/<runId>.json`으로 꺼낸다.
6. 문서 순서: 이 계획 → 결정 확정 → `PRODUCT-v0.3.md` 승격 → 코드. 규칙(profile, regression rule, validity flag 표)을 바꿀 때는 문서를 먼저 고친다.

---

## 11. 결정 필요 사항

각 항목에 제안값을 두었다. 다른 답이 없으면 제안값으로 진행한다.

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 앱 이름과 패키지 | label은 "Camera BenchMarker", `applicationId`는 `dev.cameradoctor` 유지 | 패키지를 바꾸면 기존 설치본, 저장된 run, adb 스크립트가 모두 끊긴다. 저장소 이름(`camera-doctor` → `camera-benchmarker`)은 GitHub가 redirect를 유지하므로 언제든 바꿔도 된다. 사용자 선택 |
| 2 | Profile v1 스트림 크기 | preview 1920x1080, YUV 1920x1080, JPEG 1920x1080 | METRICS.md 미결 항목의 제안값과 사용자 화면 예시("1080p30")와 일치. M2에서 YUV 1080p가 H.5 stall을 만들면 720p로 낮추고 그때 v1을 확정. 확정 후에는 불변(3.5) |
| 3 | 반복 수 | launch 10회(n=9), still 10회(n=9, 간격 8) | METRICS.md 0.2절 기본 반복 10회, 첫 회 제외와 일치 |
| 4 | run 범위 | 1 run = 카메라 1개 | 사용자 카드 예시("Rear Main · ~45 sec")와 일치. 전면 · 초광각은 카메라를 바꿔 다시 실행 |
| 5 | regression 규칙 | 전역 값 대신 지표별 표(7.2). 초기값은 open / capture 15 % + 10 ms, jitter 20 % + 1 ms, stall +2, callback failure +1 | 전역 10 ms floor는 문서 안에서 이미 예시와 모순되었다(Jitter 3.2 → 7.1 ms). M5에서 분포를 보고 조정 |
| 6 | baseline 정책 | 명시적 지정만. 자동 baseline 없음. 직전 comparison-eligible run은 REFERENCE로 자동 비교하되 상태는 내지 않음 | v0.2에서 artefact가 든 첫 run이 baseline이 된 사례. "baseline"은 의도한 기준점이라는 뜻을 지킨다 |
| 7 | Score | M5까지 계산도 표시도 없음. JSON `score: null` | 사용자 원칙: raw 분포 먼저 |
| 8 | 버전 | M1부터 versionName 0.3.0, 마일스톤마다 patch 증가 | v0.2 = Doctor, v0.3 = BenchMarker 첫 라인 |
| 9 | 지표 표시 이름 | 결과 · 비교 화면은 영문 짧은 이름, 안내 문구는 한국어 | 8.4 |
| 10 | 첫 score의 이름 | Camera Endpoint Score. Device Score는 표준 endpoint 집합 이후 | 실제로 잰 것은 "S25+ 후면 메인 camera2-standard-v1"이다 |
| 11 | 3A와 score | score-v1에서 weight 0(informational). regression 비교에는 포함 | 장면 · 거리 · 조도 의존이 커서 기기 간 비교에 쓰면 환경 차이가 성능처럼 보인다 |
| 12 | compatibility 판정의 AF 예외 | 스트림 · fps는 엄격, AF 모드만 예외(고정 초점은 OFF로 실행하고 기록) | 엄격히 요구하면 S25+ 초광각 같은 고정 초점 카메라를 영원히 잴 수 없다. endpoint별 baseline이라 섞이지 않는다 |
| 13 | validity 모델 | boolean 하나 대신 measurement / comparison / scoring 세 단계. flag 표(5.3)에서 유도. thermal은 시작값이 아니라 run 최고값 | "측정이 유효함"과 "점수에 적합함"은 다른 질문이다. LIGHT로 시작해 SEVERE로 끝난 run이 유효로 남는 구멍을 막는다 |
| 14 | 통계 열 표시 | n < 20이면 `max`, n ≥ 20이면 `p95`. JSON은 둘 다 저장 | n = 9의 nearest-rank p95는 max다 |
| 15 | 형상 label 이름 | `subject*` 접두어. JSON 블록 이름은 `subject` | 벤치마크 앱의 commit과 측정 대상의 commit이 장기적으로 섞이지 않게 한다. `cameraSw*`보다 일반적이어서 플랫폼 빌드에도 쓸 수 있다 |
| 16 | 비교 호환성 키 | `(profile.id, endpoint.key)` 대신 `(comparisonContractId, endpoint.key)`. contract = profile id + metric definition version + stats method + clock | profile은 촬영 방법이고 metric definition은 숫자를 만드는 방법이다. H.10 계산법이 바뀌면 profile이 같아도 비교하면 안 된다 |
| 17 | `sampleCount`와 `samples` 정책 | `sampleCount`는 언제나 raw 표본 수(JSON `n`). `samples`는 launch · still · 3A만 저장, 관측 창 metric은 null | Launch의 n=9와 Preview의 n=1이 다른 뜻이면 CSV 집계가 깨진다. 같은 300개 간격을 H.1과 H.2에 중복 저장하지 않는다 |
| 18 | M5 분리 | M5a internal(S25+ 분포, `score-v1-draft`), M5b public(5 – 10개 기기, `score-v1`). stress run은 curve가 아니라 sensitivity 검증용 | S25+ 하나로 만든 curve는 regression score이지 기기 간 benchmark score가 아니다 |

---

## 12. v0.2 산출물 처리

| 대상 | 처리 |
|---|---|
| `docs/PRODUCT-v0.2.md` (Drive, repo) | `docs/archive/PRODUCT-v0.2.md`로 이동. 삭제하지 않는다. 열거 절차(9장), Auto Check 시간 예산(10장), 디자인 토큰(11.6), exposure_load 규칙(6장)은 v0.3 문서가 참조한다 |
| `docs/METRICS.md`, `METRICS-REVIEW.md` | 유지. H.10 추가, 0.2절에 `launch_mode = warm_reopen` 대응 명시, 0.3절 환경값에 `thermal_max`와 절전 모드 추가, 0.5절에 schema 3 언급 |
| `docs/design/DESIGN.md` | 유지. Expert 어두운 배경 토큰이 LIVE와 BENCHMARK 화면의 기준 |
| `README.md` | M3에서 제품 정의와 빌드 방법을 BenchMarker 기준으로 다시 쓴다 |
| Drive `checkpoints/001 – 007` | 그대로 보존 |
| 기기의 `files/checks/*.json`, `files/incidents/*` | M3 설치 전에 `adb exec-out run-as`로 백업. schema 2 파일은 v0.3 앱이 읽지 않는다 |
| 삭제하는 개념 | Camera Health, NORMAL / WARNING / ISSUE, Health Score, 중고폰 검사, Consumer Health Check, Diagnosis rules, CDD 기반 판정, "방금 이상했어요", 고장 · 이상 진단 |

---

## 13. 변경 기록

### 2026-09-09 검토 1 반영

외부 검토(9개 지적)를 받아 아래를 고쳤다. 코드는 바꾸지 않았다.

| # | 지적 | 반영 |
|---|---|---|
| 1 | 7.2 전역 10 ms noise floor와 7.3 Jitter 예시(3.2 → 7.1 ms, REGRESSED)가 모순 | 지표별 `RegressionRule` 표로 교체(5.2, 7.2). 예시를 표로 검산 |
| 2 | Profile 미지원 기기에서 조건을 낮춰 같은 id로 저장할 위험 | `ProfileCompatibility` preflight 신설(3.6). SUPPORTED / UNSUPPORTED만, fallback은 별도 profile id. v1 확정 후 불변 규칙(3.5) |
| 3 | launch 10회 반복은 warm reopen인데 이름에 드러나지 않음 | Profile에 `launchMode = WARM_REOPEN` 필드. cold는 별도 profile로 예약 |
| 4 | 3A는 장면 의존이 커서 기기 간 score에 부적합 | score-v1에서 3A weight 0. regression 비교에는 유지 |
| 5 | 1 run = 1 camera인데 "기기 Score"라 부르면 논리가 뛴다 | 첫 점수를 Camera Endpoint Score로 명명. `summary.endpoint_score` |
| 6 | 첫 run 자동 baseline은 우연에 취약 | 자동 baseline 삭제. BASELINE(명시적)과 REFERENCE(직전 적격 run) 분리(7.1) |
| 7 | `same_build` boolean은 HAL 개발 형상 차이를 못 잡는다 | `BuildIdentityComparison` 6축(5.4, 7.4) |
| 8 | 점수 dataset용 품질 gate가 없다 | `validity` 블록을 M1 schema에 포함(5.3) |
| 9 | 마일스톤 이름과 배치 | Data contract / Measurement correctness / Product conversion / Developer workflow / Score / History로 재명명. RegressionRule 표와 validity를 M1로, preflight를 M2로, reference와 identity를 M4로 이동 |

조건을 붙여 반영한 두 가지:

- AF 모드는 compatibility 판정의 예외다(11장 12번). 고정 초점 카메라를 벤치마크 대상에서 제외하지 않기 위해서다.
- REFERENCE 대비로는 delta %만 보여 주고 regression 상태를 내지 않는다(11장 6번). 직전 run 하나와의 비교에 상태를 붙이면 run 간 잡음이 그대로 경고가 되기 때문이다.

### 2026-09-09 검토 2 반영

두 번째 외부 검토(5개 지적)를 받아 아래를 고쳤다. 코드는 바꾸지 않았다.

| # | 지적 | 반영 |
|---|---|---|
| 1 | `validity.valid` boolean 하나로 측정 유효성과 점수 적합성을 합쳤다. CHARGING이 dataset에 들어가고 thermal MODERATE 시작은 측정이 끝나도 무효가 된다 | measurement / comparison / scoring 세 단계로 분리(5.3). flag 표에 세 열을 두고 boolean은 표에서 유도. `CADENCE_NOT_FIXED`처럼 "측정은 맞지만 비교는 불가"인 경우를 표현할 수 있게 됨. `POWER_SAVE_MODE` flag 추가. 소비자별 요구 단계 표 추가. PC 집계 기본 필터를 `scoring_eligible`로 |
| 2 | thermal을 시작값만 본다. LIGHT → SEVERE run이 유효로 남는다 | `env.thermal_start / thermal_max / thermal_end` 세 값. `THERMAL_HIGH`는 `thermal_max ≥ MODERATE`. `ThermalTracker`가 `addThermalStatusListener`로 추적하고 변화를 `events`에 기록(2.2, 3.2, 6장) |
| 3 | 3.6이 "열지 않고 판정"이라면서 열린 `CameraDevice`가 필요한 `isSessionConfigurationSupported()`를 인용했다 | API 35 `CameraManager.getCameraDeviceSetup()` 정확 질의 경로와 그 외 보장 표 경로로 재작성(3.6). 실제 configure 성공을 최종 runtime validation으로. `compatibility { method, supported, reasons }`를 JSON에 추가. `PREFLIGHT_MISMATCH` flag |
| 4 | n = 9의 p95는 max인데 화면이 p95라고 쓴다 | 표시 규칙: n < 20이면 `max`, n ≥ 20이면 `p95`(4장, 8.4). JSON은 모두 유지 |
| 5 | `RunLabel.commit`이 벤치마크 앱 commit인지 측정 대상 commit인지 애매하다 | `SubjectLabel(subjectBuildLabel, subjectCommit, subjectBranch, note)`, JSON `subject` 블록, identity 필드 `sameSubjectLabel / sameSubjectCommit`(5.1, 5.4, 6장, 8.2) |

추가로 정리한 것: 두 run 사이의 조건 차이(thermal_max 차이, 절전 모드, 충전, 노출 부하)는 run 단위 eligibility와 별개 층이므로 7.5 "비교 시점의 조건 차이"로 분리했다.

### 2026-09-09 검토 3 반영 · Data contract freeze

세 번째 외부 검토(6개 지적)를 받아 아래를 고쳤다. 이 시점에 3 · 5 · 6장을 freeze하고 M1 구현을 시작했다.

| # | 지적 | 반영 |
|---|---|---|
| 1 | `profile.id`만으로 비교 호환성을 판단하면 metric 계산법이 바뀐 run이 섞인다 | `MeasurementContract(profileId, metricDefinitionVersion, statsMethod, clock)`과 `comparisonContractId` 신설(5.1). baseline · reference 키를 `(comparisonContractId, endpoint.key)`로(7.1). JSON `comparison_contract_id`, `metric_definition_version = metrics-0.3` |
| 2 | `n`이 Launch에서는 raw 표본 수, Preview에서는 집계 결과 수로 뜻이 다르다 | `sampleCount` = 언제나 raw 표본 수(JSON `n`). `samples`는 launch · still · 3A만 저장하고 관측 창 metric은 null(4장, 5.1, 11장 17번) |
| 3 | "p95 delta도 JSON 저장" 문장과 모델의 불일치 | 문장 삭제. v1 비교는 대표값만 쓰고 p95 / min / max는 raw statistics로만 저장(7.2) |
| 4 | 1080p30 "지원"과 "30 fps 유지 가능"은 다르다 | 프레임 예산(`getOutputMinFrameDuration`)을 `compatibility.frame_budget_ok`에 기록만 하고 판정에는 쓰지 않음. runtime `CADENCE_NOT_FIXED`가 판단(3.6) |
| 5 | 저조도에서는 Capture 지표도 장면 영향을 받는다 | 7.5에 미결 행 추가. M2에서 밝음 · 어두움 반복으로 상관을 실측한 뒤 결정(9장 M2 완료 기준) |
| 6 | S25+ 하나로 만든 curve는 기기 간 benchmark score가 아니다 | M5를 M5a internal(`score-v1-draft`)과 M5b public(5 – 10개 기기, `score-v1`)으로 분리. stress run은 curve가 아니라 sensitivity 검증용(9장, 11장 18번) |

M1 구현 중 계약에 추가한 것: `BenchmarkMetric.timeout`(3A 수렴이 관측 창 안에 끝나지 않음. METRICS.md 2.4의 timeout 기록 원칙), `Compatibility.NOT_CHECKED`(preflight가 없던 run의 기본값), `BenchmarkRun.raw`(6장의 `raw` 블록을 모델에서도 보존). `UnknownReason`은 M3까지 `diagnosis` 패키지의 것을 그대로 쓴다.

### 2026-09-10 PR #11 후속 검토 반영

M1은 PR #11(다른 세션, `80fa1ff`)로 main에 들어갔고, 그 PR의 후속 검토 코멘트(P1 3건, P2 2건, 패키지 순환)를 후속 PR에서 처리했다. 계약에 더해진 것:

| # | 지적 | 반영 |
|---|---|---|
| P1 | 알 수 없는 validity flag가 fail-open | `RunValidity.fromJsonMap`은 모르는 flag가 하나라도 있으면 comparison · scoring 부적격(measurement는 표대로). `unknownFlags` 필드와 `validity_rule_version`(`validity-v1`) 추가(5.3) |
| P1 | comparison contract 무결성 미검증 | 읽을 때 `kind == "benchmark"`, contract 구성 필드 전부 필수, 저장된 `comparison_contract_id`와 재계산값 일치, 확정(non-draft) canonical profile은 저장된 정의가 앱 정의와 동일해야 함. 어긋나면 읽을 수 없는 run(6장) |
| P1 | decode 시 events 유실 | `BenchmarkRun.events`로 보존. 읽고 다시 내보내도 관측 창 metric의 원본이 남는다(5.1, 6장) |
| P2 | `sameCameraBuild`가 형상 차이를 숨김 | 알려진 축 중 하나라도 다르면 false, 모두 같을 때만 true, 둘 다 없을 때만 null(5.4) |
| P2 | 파일 저장 원자성, run id 충돌, index 손상 은닉 | temp → fsync → atomic rename. run id에 millisecond suffix(`yyyyMMdd-HHmmss-SSS`). 손상된 index와 읽기 실패는 `lastIndexError` / `lastReadError`와 로그로 노출 |
| 순환 | `diagnosis.MetricCatalog` ↔ `benchmark` | category · 영문 이름 · 단위를 `benchmark.BenchmarkMetricCatalog`(`MetricInfo.kt`)로 분리. `diagnosis`는 benchmark를 참조하지 않는다 |
| 리뷰 | JSON 필수 필드의 `as String` 캐스트 | `JsonMaps.reqString / reqBoolean / reqInt / reqLong`. 누락 · 타입 오류는 키 이름이 든 `IllegalArgumentException` |

### 2026-09-10 M2 실기기 측정 반영

M2 코드는 PR #14로 main에 들어갔고(리뷰 P1 2건 · P2 2건 반영), Galaxy S25+에서 run 5개(전면 2회, 후면 메인 3회)를 완주했다. 측정 결과는 Drive `checkpoints/checkpoint-010-bm-m2-runner/DEVICE-RESULTS.md`에 있다.

| 항목 | 결과 |
|---|---|
| profile 확정 | 후면 메인 3회 모두 관측 창 H.5 stall 0개. YUV 1080p를 유지하고 `camera2-standard-v1`으로 확정(3.5, PR #17). 이후 조건은 불변이며 바꾸려면 `v2`다 |
| preflight 경로 | S25+(API 36)에서 `isCameraDeviceSetupSupported()` = true. `device_setup` 정확 질의로 SUPPORTED, `frame_budget_ok` = true. static 보장 표로 물러설 필요가 없었다(3.6) |
| 조도 상관 | 노출 부하 5.6배 차이에서 1.6 preview total 35 %, 1.3 first started 57 %, 2.2 capture 6 – 12 % 차이. 13장 검토 3의 5번 미결 항목에 대한 실측 근거이며, 7.5에 Capture를 넣을지와 함께 **Launch 쪽 영향이 더 크다는 점**을 같이 다루어야 한다 |
| 실제 카덴스 | H.1이 조도에 따라 33.34 ms(30.0 fps)와 33.51 ms(29.84 fps) 사이에서 움직인다. 요청 fps range는 `[30,30]` 그대로이므로 `CADENCE_NOT_FIXED`로는 잡히지 않는다. 요청 카덴스와 실제 카덴스는 다른 질문이다 |
| 2.7의 근거 | still 촬영 구간에서 100.02 ms 간격 프레임이 10개 나왔다(33.36 ms의 3배). 관측 창 밖이라 H.5에 잡히지 않으며, 이를 재는 지표가 2.7 stall during capture다. 잡을 현상이 실재함이 확인되었다 |
| 3A timeout | 저조도에서 H.7이 약 13초로 `timeout = true`, 밝은 곳에서는 467 ms. timeout일 때 관측 창 길이가 metric value로 저장되므로 M4의 delta 계산이 이 값을 그대로 쓰면 안 된다 |
| thermal | 다섯 run 모두 `thermal_start = thermal_max = thermal_end = 0`. 45초 run으로는 발열이 없어 `THERMAL_HIGH`와 `THERMAL_CHANGED`는 실측으로 검증되지 않았다 |

계약에 추가한 것: baseline 해제(`CLEAR BASELINE`, 7.1과 8.4). run을 지우지 않고 기준 지정만 무르는 방법이 없었다. M4 범위이며 이슈 #8에 반영했다.
