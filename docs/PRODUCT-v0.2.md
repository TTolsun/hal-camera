# Camera Doctor 제품 정의 · 측정 정책 · 구현 계약 v0.2

- 작성일: 2026-09-09
- 상태: **검토용 초안.** 임계값과 가중치는 제안값이며 사용자 확정 전까지 코드에 상수로 고정하지 않는다.
- 상위 문서: [지표 정의표 v0.2](METRICS.md), [검토 기록](METRICS-REVIEW.md)
- 이 문서의 역할: 제품 포지셔닝, 지표에서 점수까지의 계산 규칙, UI 계약을 한 곳에 고정한다. UI 구현자가 점수 계산 규칙을 임의로 바꾸지 못하도록, 규칙 변경은 이 문서를 먼저 고친 뒤 코드에 반영한다.

## 계산 순서 원칙

```text
metric (측정값)
  → threshold (절대 · 상대 기준)
    → metric state (PASS / WARN / FAIL / UNKNOWN)
      → diagnosis (규칙 기반 판정 문장)
        → composite health (NORMAL / WARNING / ISSUE / INSUFFICIENT)
          → score (선택, 마지막 산출물)
```

점수는 마지막 산출물이다. 첫 화면 디자인에서 점수를 역산하지 않는다. v0.2에서는 점수를 **optional**로 두고 기본값을 비노출로 한다(7장).

---

## 1. Product Positioning

### 1.1 한 줄 정의

Camera Doctor는 스마트폰 카메라의 건강 상태를 검사하고, 문제가 생긴 순간을 기록하고 분석하는 앱이다.

### 1.2 두 모드

| 모드 | 대상 | 약속 |
|---|---|---|
| Consumer Health Check | 일반 사용자, 중고폰 구매자 | 약 60초 안에 카메라가 정상인지 알려준다 |
| Expert Diagnostics | 카메라 개발자, QA, 리뷰어 | 카메라 파이프라인을 실시간으로 관측하고 incident를 분석한다 |

### 1.3 두 모드의 데이터 소스는 동일하다

두 모드는 같은 Telemetry 이벤트, 같은 FlightRecorder, 같은 ThresholdEngine을 사용한다. Consumer 모드는 결과를 요약하고, Expert 모드는 원본을 노출한다. Consumer 모드 전용 계산 경로를 만들지 않는다. 같은 run에 대해 두 모드가 다른 판정을 내면 버그다.

```text
Camera2Engine / CameraXEngine
        │  Telemetry events (capture_started, capture_result, image_available, ...)
        ▼
FlightRecorder (ring buffer, incident window)
        │
        ▼
MetricExtractor ──► ThresholdEngine ──► DiagnosisRules ──► HealthComposer
        │                                      │                 │
        ▼                                      ▼                 ▼
 Expert UI (raw)                     Diagnosis card       Consumer Home
```

### 1.4 제품의 얼굴 세 가지

1. 60초 Camera Health Check (v0.2)
2. Incident Capture: "방금 이상했어요" (v0.2, 기존 FlightRecorder 재사용)
3. 중고폰 검사 (v0.3, 이미지 품질 분석이 필요하므로 이번 범위 밖)

---

## 2. Scope v0.2

| 항목 | 종류 | 설명 |
|---|---|---|
| Auto Check Runner | 신규 | 카메라 endpoint마다 open → 프리뷰 관측 → 정지 촬영 → close를 자동 수행하는 state machine (10장) |
| Threshold Engine | 신규 | 측정값을 PASS/WARN/FAIL/UNKNOWN으로 바꾸는 규칙 엔진 (5장, 6장) |
| Diagnosis Card | 신규 | 판정 한 줄 + 증거 + 원본의 3계층 카드 (8장, 11장) |
| Consumer Home | 신규 | 첫 화면. 검사 버튼, 최근 결과, "방금 이상했어요" 버튼 (11장) |
| Expert Mode 분리 | 이동 | 현재 화면(3A scope, frame timeline, health strip)을 Expert 탭으로 옮기고 상단에 Diagnosis Summary 추가 (12장) |
| Flight Recorder | 기존 재사용 | 이전 10초 + 이후 5초 incident 저장. 신규 기능으로 세지 않는다 |
| ZIP Share | 기존 재사용 | IncidentExporter의 zip 공유. v0.2에서는 Health Report JSON을 같은 zip에 추가한다 |
| Baseline Store | 신규 | 기기별 기준 run을 저장하고 상대 기준에 사용 (5.3절) |

v0.2가 끝났을 때 사용자가 할 수 있는 일은 세 가지다. 60초 검사를 실행해 NORMAL/WARNING/ISSUE 판정과 근거를 본다. 이상한 순간에 버튼을 눌러 incident를 저장하고 요약을 본다. Expert 탭에서 같은 데이터의 원본을 본다.

---

## 3. Non-Goals (v0.2에서 하지 않는 것)

| 항목 | 이유 | 재검토 시점 |
|---|---|---|
| 렌즈 먼지 · 스크래치 검출 | flat-field 촬영과 이미지 분석 모듈이 필요. 시간 지표로는 검출 불가 | v0.3 |
| OIS 소음 · 진동 진단 | 마이크와 가속도계 분석이 필요. 카메라 API 밖 | v0.4 이후 |
| AI 진단 | 측정 체계가 안정된 뒤 해석 계층으로 붙인다. v0.2에서 AI가 판정을 만들면 규칙이 검증 불가능해진다 | v0.4 |
| 기기 간 전역 순위 | 익명 통계 수집과 동의 절차가 필요. Health Check가 본체이고 benchmark는 유입 기능 | v0.4 |
| HAL 내부 원인 단정 | 앱은 framework callback만 관측한다. Perfetto 없이 HAL/CPU 스케줄링을 구분할 수 없다 | Engineering Edition |
| 녹화 지표(3.x)의 Auto Check 포함 | MediaRecorder 준비와 10분 drift는 60초 예산에 들어가지 않는다 | Expert 시나리오 runner (v0.3) |
| 숨겨진 physical camera ID 탐색 | 공개 API가 열거하지 않는 ID를 추측해서 여는 것은 지원되지 않는 동작 | 하지 않음 |
| 점수 기본 노출 | 임계값과 가중치가 실측으로 검증되기 전에는 점수가 권위만 갖고 근거는 없다 | 7.5절 조건 충족 후 |

---

## 4. Metrics Model

### 4.1 두 그룹

| 그룹 | ID | 출처 | 성격 |
|---|---|---|---|
| A. 성능 지표 | 1.1–1.8, 2.1–2.7, 3.1–3.7 (22개, 1.4 제외 번호 유지) | METRICS.md | 시작점과 끝점이 API 호출 시각인 시나리오 지표 |
| B. 관측 지표 | H.1–H.9 (9개, 이 문서에서 신설) | Telemetry가 이미 기록하는 `capture_started`, `capture_result`, `image_available`, `capture_failed`, `buffer_lost` | 프리뷰가 도는 동안의 연속 관측 창에서 계산하는 지표. 현재 HealthMonitor가 계산하는 값을 지표 ID로 고정한 것 |

그룹 A의 정의는 METRICS.md를 따르고 이 문서에서 다시 정의하지 않는다. 이 문서는 그룹 A 지표의 **판정 규칙**만 추가한다.

### 4.2 그룹 B 정의

관측 창은 기본 10초다. 시작점은 해당 endpoint의 첫 `capture_result` 도착, 끝점은 시작점 + 10초다. 관측 창 안에서 결과가 15개 미만이면 모든 H 지표는 `UNKNOWN(insufficient_samples)`다.

| id | 지표 | 계산 | 단위 | 집계 | 비고 |
|---|---|---|---:|---|---|
| H.1 | `frame_interval_p50` | 인접 `capture_result`의 `SENSOR_TIMESTAMP` 차이 | ms | nearest-rank p50 / 10 s | 요청 FPS 범위와 `SENSOR_FRAME_DURATION`을 같이 기록 |
| H.2 | `frame_interval_p95` | 같음 | ms | p95 / 10 s | |
| H.3 | `partial_latency` | 같은 frame의 `onCaptureStarted` 진입 → `onCaptureCompleted` 진입 | ms | p50, p95 / 10 s | HAL 처리 시간이 아니라 콜백 도착 차이 |
| H.4 | `buffer_latency` | 같은 frame의 `onCaptureStarted` 진입 → YUV `onImageAvailable` 진입 | ms | p50, p95 / 10 s | YUV stream이 있을 때만. 없으면 `UNKNOWN(not_measurable)` |
| H.5 | `stall_count` | interval > 1.5 × 해당 frame의 자체 `SENSOR_FRAME_DURATION`인 횟수. baseline이 있으면 interval > 1.5 × baseline p50 조건도 함께 요구 | count | 합 / 10 s | 실제 drop 개수가 아니다. 자체 duration과 비교하므로 AE 가변 FPS에서도 판정 가능. duration이 없는 frame은 baseline p50 × 1.5 단독 기준으로 대체(cadence-aware fallback) |
| H.6 | `ae_convergence` | 첫 `capture_result` → AE 상태가 CONVERGED/FLASH_REQUIRED/LOCKED인 첫 result | ms | 단일값 | 창 끝까지 미도달이면 `timeout` |
| H.7 | `af_convergence` | 첫 `capture_result` → AF 상태가 PASSIVE_FOCUSED/FOCUSED_LOCKED인 첫 result | ms | 단일값 | AF 모드 OFF 또는 고정 초점은 `UNKNOWN(unsupported)`. NOT_FOCUSED_LOCKED는 수렴이 아니다 |
| H.8 | `awb_convergence` | 첫 `capture_result` → AWB 상태가 CONVERGED/LOCKED인 첫 result | ms | 단일값 | |
| H.9 | `callback_failure_count` | `capture_failed` + `buffer_lost` 이벤트 수 | count | 합 / 10 s | |

### 4.3 v0.2 Auto Check에서 계산하는 지표

| 단계 | 지표 | 조건 |
|---|---|---|
| Open | 1.1, 1.5 | warm_sequence (앱 첫 실행을 cold로 간주하지 않음) |
| Configure | 1.2 | 스트림 구성 preview+yuv (1920x1080 제안) |
| First frame | 1.3, 1.8, 1.6 | endpoint=yuv_proxy |
| Observe 10 s | H.1–H.9 | AE/AF/AWB 자동 모드, 요청 FPS 범위 기록 |
| Still ×3 | 2.2, 2.3, 2.5 | `zsl=off, trigger=off, fmt=jpeg, res=1920x1080` (CDD 비교 조건). 3장이므로 2.5 유효 간격은 1개. 통계가 아니라 원본만 보고 |
| Close | 1.7 | |
| 실행 안 함 | 2.1, 2.4, 2.6, 2.7, 3.1–3.7 | `UNKNOWN(not_run)`. Expert 시나리오 runner 전용 |

still 3장은 60초 예산 때문이다. 통계가 필요한 2.5는 Expert 시나리오 runner에서 10장 반복으로 측정한다.

### 4.4 엔진별 가용성

측정 엔진은 Camera2다. CameraX run은 METRICS.md 7절에 따라 별도 이름으로 기록하고 Health 판정에 쓰지 않는다. Consumer Health Check는 항상 Camera2로 실행한다.

---

## 5. Threshold Policy

### 5.1 기준의 세 종류

| 종류 | 출처 | 적용 |
|---|---|---|
| Absolute · 물리 기준 | 요청 FPS 범위, `SENSOR_FRAME_DURATION`, timeout, 실패 이벤트 | 기기와 무관하게 성립하는 사실. 항상 적용 |
| Absolute · CDD 기준 | Android 16 CDD 2.2.7.2 Media Performance Class 카메라 요구사항 [S1] | 조건이 일치할 때만 FAIL 근거. 조건 불일치 시 WARN 근거 |
| Relative · 기기 baseline | 같은 기기, 같은 endpoint, 같은 조건의 저장된 기준 run | baseline이 있을 때만 적용 |

### 5.2 CDD 기준 원문과 적용 범위

CDD 2.2.7.2는 `Build.VERSION.MEDIA_PERFORMANCE_CLASS`를 선언한 handheld 기기에 적용되며, 요구사항은 performance class 버전별로 분기된다. 아래 500 ms / 1000 ms 값은 Android 14(U) 이상 class의 요구사항이다. 그 이전 class(R/S/T)에는 launch 600 ms 같은 다른 값이 쓰였으므로, 이 문서의 값은 **U 이상 class를 선언한 기기에만** 적용한다. 확인한 원문 [S1]:

> [7.5/H-1-5] MUST have camera2 JPEG capture latency < 1000 ms for 1080p resolution as measured by the CTS camera PerformanceTest under ITS lighting conditions (3000K) for both primary cameras.
>
> [7.5/H-1-6] MUST have camera2 startup latency (open camera to first preview frame) < 500 ms as measured by the CTS camera PerformanceTest under ITS lighting conditions (3000K) for both primary cameras.

주의할 점 두 가지가 있다.

1. CTS PerformanceTest 자체는 launch/capture 지연에 대해 assert하지 않고 ReportLog로 보고만 한다 [S2]. 한계값의 출처는 CTS가 아니라 CDD다. 문서와 UI에서 "CTS 기준"이라고 쓰지 않고 "CDD 기준"이라고 쓴다.
2. Camera Doctor는 ITS 조명 조건을 재현하지 않고, 프리뷰 첫 프레임 정의는 CTS의 YUV 대리 관측과 같지만 해상도, warm-up, 반복 정책은 다를 수 있다. 조건이 같지 않은데 "CDD FAIL"이라고 말하면 사용자는 CDD 위반으로 받아들인다. 따라서 CDD 값은 조건 일치 등급에 따라 다르게 쓴다.

**적용 게이트 (코드 계약)**

```kotlin
val mpc = Build.VERSION.MEDIA_PERFORMANCE_CLASS          // 0이면 미선언
val cddCameraLatencyApplicable = mpc >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE   // U = 34
```

**두 축을 분리한다.** CDD가 이 기기·이 endpoint에 적용되는가(`cdd_applicability`)와, 측정 조건이 CDD 측정 조건과 얼마나 같은가(`condition_equivalence`)는 다른 질문이다. 한 필드에 섞으면 "MPC 미선언"과 "조명 통제 안 함"이 같은 값이 되어 경계가 흐려진다.

| `cdd_applicability` | 조건 |
|---|---|
| `applicable` | MPC ≥ U이고 primary camera(후면 메인 또는 전면) |
| `not_applicable` | MPC 미선언, U 미만, 또는 primary camera가 아님 |

| `condition_equivalence` | 조건 | 값 초과 시 상태 | `threshold_basis` |
|---|---|---|---|
| `equivalent` | CTS PerformanceTest와 같은 stream 구성, 같은 warm-up/반복 정책, ITS 조명(3000 K) 검증 | FAIL | `absolute_validated` |
| `similar` | 관측 방식은 같지만 조명 통제 없음 (v0.2 Auto Check의 기본 상태) | WARN | `absolute_reference` |
| `non_equivalent` | stream 구성, 해상도, 측정 방식이 실질적으로 다름 | WARN | `absolute_reference` |

`condition_equivalence`는 CDD 참조 지표(1.6, 2.2)에만 있고 그 외 지표에서는 null이다. `cdd_applicability = not_applicable`이면 CDD 값을 제품 경험 기준(`heuristic`)으로만 쓴다. v0.2 Auto Check는 조명을 통제하지 않으므로 `equivalent`가 나오지 않는다. `equivalent`는 v0.3 Expert 시나리오 runner에서 조명 검증 절차가 생긴 뒤에만 나온다. 즉 **v0.2에서는 CDD 값 초과가 FAIL을 만들지 않는다.**

| 지표 | CDD 값 | `applicable` + `similar`/`non_equivalent` | `not_applicable` |
|---|---:|---|---|
| 1.6 `preview_total[endpoint=yuv_proxy]` p50 | 500 ms | ≥ 500 ms → WARN(`absolute_reference`) | ≥ 500 ms → WARN(`heuristic`), ≥ 1000 ms → FAIL(`heuristic`) |
| 2.2 `capture_latency[zsl=off,trigger=off,fmt=jpeg,res=1920x1080]` p50 | 1000 ms | ≥ 1000 ms → WARN(`absolute_reference`) | ≥ 1000 ms → WARN(`heuristic`), ≥ 2000 ms → FAIL(`heuristic`) |

문구는 Consumer에서 "권장 성능 기준보다 느립니다", Expert에서 `CDD_REFERENCE_EXCEEDED · environment_not_equivalent`다. CDD는 primary camera(후면 메인, 전면)에만 적용된다. Ultra Wide, Telephoto endpoint에는 relative 기준과 product heuristic만 쓴다.

### 5.3 Baseline 정책

| 항목 | 규칙 |
|---|---|
| 키 | `Build.FINGERPRINT` + endpoint id + conditions.effective 문자열 |
| 생성 조건 | 하드 실패 없음, thermal status ≤ LIGHT, 배터리 ≥ 20 %, 모든 H 지표가 `insufficient_samples`가 아님 |
| 생성 시점 | 위 조건을 만족하는 첫 run. 자동으로 갱신하지 않는다 |
| 무효화 | `Build.FINGERPRINT` 변경(OS 업데이트) 시 자동 무효화. 사용자가 Expert 모드에서 수동 초기화 가능 |
| 값 | 각 지표의 p50과 p95를 저장. 원본 run JSON 경로도 저장 |
| 없을 때 | relative 기준이 필요한 지표는 `UNKNOWN(no_baseline)`. PASS로 간주하지 않는다 |

첫 검사에서는 relative 지표가 전부 UNKNOWN이므로 Consumer 화면은 "기준을 기록했습니다. 다음 검사부터 변화를 비교합니다"라고 말한다. 이것이 정직한 첫 결과다.

### 5.4 충돌 규칙: 더 나쁜 쪽을 채택한다

absolute와 relative가 다른 상태를 내면 더 나쁜 상태를 채택하고, 진단 문구에 두 사실을 함께 쓴다.

| absolute | relative | 최종 | 문구 |
|---|---|---|---|
| PASS | WARN (+48 %) | WARN | "규격 범위 안이지만 평소보다 느립니다." |
| FAIL | PASS (변화 없음) | FAIL | "평소와 같지만 성능 기준을 만족하지 않습니다." |
| PASS | UNKNOWN | PASS | "규격 범위 안입니다. 기준과의 비교는 다음 검사부터 가능합니다." |
| UNKNOWN | UNKNOWN | UNKNOWN | 판정하지 않음 |

상태 순서는 FAIL > WARN > PASS > UNKNOWN이다. UNKNOWN은 가장 낮지만, UNKNOWN과 PASS가 만나면 PASS다. 판정 근거가 하나라도 있으면 그 근거를 쓴다.

**판정 근거 `threshold_basis`.** 상태(State)는 "얼마나 나쁜가"이고, `threshold_basis`는 "왜 그렇게 판정했는가"다. 두 축은 직교한다. `state=WARN, basis=absolute_reference`와 `state=FAIL, basis=relative`가 모두 정상 조합이다. final 상태를 결정한 기준의 basis를 기록하며, PASS이면 null이다. composite health로 승격되는 규칙은 7.1절에 있다.

| `threshold_basis` | 근거 | FAIL일 때 composite 승격 |
|---|---|---|
| `hard` | 5.6절의 오류, timeout | ISSUE |
| `absolute_validated` | 조건이 `equivalent`인 CDD 기준 초과 | ISSUE |
| `absolute_reference` | 조건이 `similar`/`non_equivalent`인 CDD 참조값 초과. v0.2에서는 WARN까지만 낸다 | WARNING |
| `relative` | baseline 대비 편차 | WARNING |
| `heuristic` | 제품이 정한 경험 기준 (stall 횟수, fps 비율, 3A 시간 등) | WARNING |

"평소보다 2배 느려짐"은 WARNING이고, `openCamera()` 실패는 ISSUE다. relative 기준 하나만으로 사용자에게 "문제가 있습니다"라고 말하지 않는다.

### 5.5 UNKNOWN의 사유 코드

| 코드 | 의미 |
|---|---|
| `not_measurable` | METRICS.md 원칙 3. 시계 불일치, 스트림 없음 등 |
| `not_run` | 이 run의 시나리오에 포함되지 않음 |
| `insufficient_samples` | 관측 창 표본 부족 (H 지표 15개 미만, 반복 지표 유효 표본 0) |
| `no_baseline` | relative 전용 지표인데 baseline 없음 |
| `unsupported` | 기기가 기능을 지원하지 않음 (고정 초점 등) |
| `cadence_changed` | AE 가변 FPS로 frame duration이 바뀌었고, 자체 `SENSOR_FRAME_DURATION`도 없어 관측 간격을 비교할 대상이 없음. duration이 있으면 이 사유를 쓰지 않고 duration 기준으로 판정한다 |
| `condition_mismatch` | baseline과 conditions.effective가 다름 |

UNKNOWN에는 항상 사유 코드가 붙는다. 사유 없는 UNKNOWN은 허용하지 않는다.

### 5.6 하드 실패

아래는 임계값과 무관하게 FAIL이며, composite health를 ISSUE로 만든다.

| 이벤트 | 지표 |
|---|---|
| `openCamera` 오류, `onError`, `CameraAccessException`, 3 s timeout | 1.1 |
| `onConfigureFailed`, 3 s timeout | 1.2 |
| 첫 frame 3 s timeout | 1.3 / 1.8 |
| still 이미지 5 s timeout | 2.2 |
| `capture_failed` 또는 `buffer_lost` 1건 이상 | H.9 |
| close 3 s timeout | 1.7 |

timeout 값은 production watchdog 값이며 카메라 성능 규격이 아니다. CTS의 `WAIT_FOR_RESULT_TIMEOUT_MS = 3000`도 test harness 안전용 timeout이므로 "참고"로만 적고 "CTS 기준"이라고 부르지 않는다 [S2]. `absoluteSource`에는 `watchdog_v0.2`를 쓴다.

---

## 6. PASS / WARN / FAIL Table

relative 열의 백분율은 baseline p50 대비 run p50의 증가율이다. p95는 같은 규칙을 baseline p95에 적용한다. 작은 값의 잡음을 막기 위해 latency 지표는 절대 증가량 10 ms 미만이면 relative WARN을 내지 않는다.

모든 임계값은 **제안값**이며 Galaxy S25+ 실측 후 확정한다. Absolute 열의 출처는 `absoluteSource`에 기록하며 값은 `cdd_2.2.7.2_H-1-5`, `cdd_2.2.7.2_H-1-6`, `physics`(자체 frame duration), `watchdog_v0.2`, `product_stability_v0.2`, `product_recording_v0.2` 중 하나다. `product_*`는 공식 규격이 아닌 제품 경험 기준이다. FAIL 열의 상태는 5.4절 `threshold_basis`에 따라 composite 승격 여부가 다르며, 이 표에서 ISSUE로 승격되는 FAIL은 basis가 `hard`인 것뿐이다(v0.2에는 `absolute_validated`가 나오지 않는다).

### 6.1 그룹 A · Launch

| Metric | Source | Unit | Aggregation | Absolute | Relative | PASS | WARN | FAIL | Notes |
|---|---|---:|---|---|---|---|---|---|---|
| 1.1 `open_latency` | API 시각 | ms | p50 / n≥1 | 하드 실패만 | +30 % / +100 % | 둘 다 아님 | relative +30 % | 하드 실패(`hard`) 또는 +100 %(`relative`) | cold/warm 분리 |
| 1.2 `configure_latency` | API 시각 | ms | p50 | 하드 실패만 | +30 % / +100 % | | | | 스트림 구성 조건 키 |
| 1.3 `first_frame_started_callback` | callback | ms | p50 | 3 s timeout | +30 % / +100 % | | | | |
| 1.5 `activity_create_to_open` | 앱 시각 | ms | 단일 | 없음 | 없음 | 항상 UNKNOWN(not_measurable_for_health) | | | 권한 대기 포함. 기록만 하고 판정하지 않음 |
| 1.6 `preview_total[endpoint=yuv_proxy]` | 차감 | ms | p50 | CDD 500 ms 참조 (5.2절) | +30 % / +100 % | < 500 ms이고 relative 이내 | ≥ 500 ms (`absolute_reference`) | v0.2에서는 `hard` 또는 `relative`만 | primary camera만 CDD 참조 |
| 1.7 `close_latency` | API 시각 | ms | p50 | 3 s timeout | +50 % / +200 % | | | | 전환 시나리오 |
| 1.8 `launch_yuv_proxy` | callback | ms | p50 | 3 s timeout | +30 % / +100 % | | | | 1.6의 끝점 |

### 6.2 그룹 A · Still

| Metric | Source | Unit | Aggregation | Absolute | Relative | PASS | WARN | FAIL | Notes |
|---|---|---:|---|---|---|---|---|---|---|
| 2.1 `shutter_lag` | 센서 시각 | ms | p50 | 없음 | +30 % / +100 % | | | | REALTIME 시계일 때만. Auto Check v0.2는 not_run |
| 2.2 `capture_latency[jpeg,1080p]` | callback | ms | p50 (n=3) | CDD 1000 ms 참조 (5.2절) | +30 % / +100 % | < 1000 ms이고 relative 이내 | ≥ 1000 ms (`absolute_reference`) | v0.2에서는 `hard` 또는 `relative`만 | 2.4 미포함 |
| 2.3 `capture_result_latency` | callback | ms | p50 | 5 s timeout | +30 % / +100 % | | | | |
| 2.4 `precapture_convergence` | metadata | ms | p50 | 5 s timeout | +50 % | | | | not_run in v0.2 |
| 2.5 `shot_to_shot` | API 시각 | ms | 원본 (n=1 in v0.2) | 없음 | +30 % / +100 % | | | | 표본 1개는 UNKNOWN(insufficient_samples). Expert runner에서만 판정 |
| 2.6 `preview_recovery` | callback | ms | p50 | 3 s timeout | +50 % | | | | not_run |
| 2.7 `preview_stall_during_capture` | 센서 간격 | count | 창 합 | 0 / 1–2 / ≥3 | 없음 | 0 | 1–2 | ≥ 3 | not_run in v0.2 |

### 6.3 그룹 A · Recording (v0.2 Auto Check에서 not_run)

| Metric | Source | Unit | Aggregation | Absolute | Relative | PASS | WARN | FAIL | Notes |
|---|---|---:|---|---|---|---|---|---|---|
| 3.1 `record_start_to_camera_callback` | callback | ms | p50 | 3 s timeout | +30 % / +100 % | | | | |
| 3.2 `camera_frame_interval_anomaly` | 센서 간격 | count | 10 s 창 | 0 / 1–2 / ≥3 | 없음 | 0 | 1–2 | ≥ 3 | 실제 drop 아님 |
| 3.3 `encoder_frame_drop` | MediaCodec | count | | | | UNKNOWN(not_measurable) | | | MVP 밖 |
| 3.4 `steady_fps` | 센서 시각 | fps | 1 s 창 p50 | 요청 FPS 대비 −5 % / −15 % (`product_recording_v0.2`) | 없음 | ≥ 95 % | 85–95 % | < 85 % (`heuristic`) | 규격 아님. v0.3 runner에서는 expected/observed frame 수와 drop event로 대체 검토 |
| 3.5 `long_run_drift` | 센서 시각 | fps | 1 min 창 | 첫 분 대비 −10 % / −25 % | 없음 | | | | 10분 별도 시나리오 |
| 3.6 `record_stop_latency` | API 시각 | ms | p50 | 예외는 FAIL | +50 % / +200 % | | | | |
| 3.7 `frame_interval_jitter` | 센서 간격 | ms | 표준편차, p95 | 없음 | +50 % / +150 % | | | | |

### 6.4 그룹 B · 관측

| Metric | Source | Unit | Aggregation | Absolute | Relative | PASS | WARN | FAIL | Notes |
|---|---|---:|---|---|---|---|---|---|---|
| H.1 `frame_interval_p50` | 센서 간격 | ms | p50 / 10 s | 고정 FPS([30,30])일 때만: p50 > 1e9/fps × 1.2 → WARN. 가변 FPS([15,30] 등)는 target range로 WARN을 내지 않고 frame별 자체 `SENSOR_FRAME_DURATION` × 1.2와 비교 (`physics`) | +20 % / +50 % | 유효 cadence 이내 | > 1.2 × expected | `relative`만 | target FPS 상한을 기준으로 쓰지 않는다. [15,30]에서 66.7 ms는 정상이다. duration도 없고 cadence가 바뀌면 그때만 UNKNOWN(cadence_changed) |
| H.2 `frame_interval_p95` | 센서 간격 | ms | p95 / 10 s | 없음 | +30 % / +100 % | | | | |
| H.3 `partial_latency` | callback | ms | p50, p95 / 10 s | 없음 | +30 % / +100 % | | | | 절대 증가 10 ms 미만은 WARN 안 냄 |
| H.4 `buffer_latency` | callback | ms | p50, p95 / 10 s | 없음 | +30 % / +100 % | | | | YUV stream 필요 |
| H.5 `stall_count` | 센서 간격 | count | 합 / 10 s | 0 / 1–2 / ≥3 (`product_stability_v0.2`) | 없음 | 0 | 1–2 | ≥ 3 (`heuristic`) | 자체 `SENSOR_FRAME_DURATION`이 있으면 가변 FPS에서도 판정. 없을 때만 baseline p50 × 1.5 단독 기준. 공식 규격 아님 |
| H.6 `ae_convergence` | metadata | ms | 단일 | ≤ 1500 → PASS, 그 외 WARN (`product_stability_v0.2`) | +50 % → WARN | ≤ 1500 | > 1500 또는 timeout | v0.2 없음 | 장면 의존. 성능 신호이지 결함 신호가 아님 |
| H.7 `af_convergence` | metadata | ms | 단일 | ≤ 1500 → PASS, 그 외 WARN | +50 % → WARN | ≤ 1500 | > 1500 또는 timeout | v0.2 없음 | 고정 초점은 UNKNOWN(unsupported). FAIL은 v0.3 AF robustness(통제 대상, 10회 반복, 7/10 timeout)에서만 |
| H.8 `awb_convergence` | metadata | ms | 단일 | ≤ 1500 → PASS, 그 외 WARN | +50 % → WARN | ≤ 1500 | > 1500 또는 timeout | v0.2 없음 | |
| H.9 `callback_failure_count` | callback | count | 합 / 10 s | 0 / — / ≥1 | 없음 | 0 | 없음 | ≥ 1 | 하드 실패 |

3A 수렴 시간은 장면(대비, 거리, AF 영역, 움직임)에 크게 좌우되고, Android API는 "몇 초 안에 수렴해야 정상"이라는 요구사항을 제공하지 않는다. 그래서 v0.2의 3A는 **성능 신호**이며 카메라 결함 신호가 아니고, FAIL을 내지 않는다. 10장의 사용자 안내에서 "밝은 곳에서 글자나 물체를 향해" 검사하도록 요구한다.

환경 불일치 판정은 ISO 단독이 아니라 노출 부하 대리값을 쓴다.

```text
exposure_load = SENSOR_SENSITIVITY × SENSOR_EXPOSURE_TIME   (관측 창 p50)
exposure_load_ratio = exposure_load(run) / exposure_load(baseline)

exposure_load_ratio > 4 또는 < 0.25 → H.6–H.8을 UNKNOWN(condition_mismatch)
```

ISO 100 · 1/120 s와 ISO 400 · 1/500 s는 ISO만 보면 4배 차이지만 노출 부하는 거의 같으므로 같은 환경으로 본다. 조리개가 바뀌는 기기는 `LENS_APERTURE`도 곱에 넣는다. 이 값도 lux 측정이 아니라 대리값이며, run JSON에 원본을 남긴다.

---

## 7. Health Score Algorithm

### 7.1 Composite health (v0.2 기본 노출)

| 수준 | 조건 |
|---|---|
| ISSUE | `threshold_basis`가 `hard` 또는 `absolute_validated`인 FAIL 1건 이상 |
| WARNING | ISSUE가 아니고, WARN 상태 지표 1개 이상 또는 `threshold_basis`가 `relative`/`heuristic`인 FAIL 1개 이상 |
| NORMAL | ISSUE도 WARNING도 아니고, 판정된(PASS) 지표의 가중치 합이 전체 가중치의 50 % 이상 |
| INSUFFICIENT | 위 어느 것도 아님. UNKNOWN이 너무 많아 판정할 수 없음 |

endpoint별로 먼저 계산하고, 기기 전체 수준은 endpoint 중 가장 나쁜 수준이다. 3A timeout 하나로는 ISSUE가 되지 않고 WARNING이다. "평소보다 2배 느림"도 WARNING이다. ISSUE는 카메라를 열지 못했거나, 촬영이 실패했거나, 조건이 검증된 규격을 넘었을 때만이다.

Consumer 화면의 기본 표시는 다음 형태다.

```text
CAMERA HEALTH
● NORMAL

21개 항목 검사
18 PASS · 2 WARN · 1 UNKNOWN
```

### 7.2 가중치 (제안)

| 그룹 | 지표 | 가중치 |
|---|---|---:|
| Launch | 1.1, 1.2, 1.3, 1.6, 1.8 | 25 |
| Still | 2.2, 2.3 | 25 |
| Stability | H.1, H.2, H.3, H.4, H.5, H.9 | 30 |
| 3A | H.6, H.7, H.8 | 20 |

그룹 안에서는 지표별 동일 분배다. 1.5, 1.7, 2.5(v0.2), 3.x는 가중치 0이며 판정만 기록한다.

### 7.3 점수 계산식

```text
p(PASS) = 1.0,  p(WARN) = 0.5,  p(FAIL) = 0.0,  UNKNOWN은 분모와 분자에서 제외

coverage = Σ w_i (i ∈ 판정된 지표) / Σ w_i (전체)

score = round(100 × Σ w_i · p_i / Σ w_i (판정된 지표))

하드 실패가 있으면 score = min(score, 59)
coverage < 0.7 이면 score = null
baseline == null 이면 score = null   (첫 검사 점수 금지)
```

### 7.4 UNKNOWN 처리와 첫 검사

UNKNOWN은 0점이 아니다. 분모에서 빠진다. 대신 coverage가 낮아지고, coverage가 70 % 미만이면 점수를 내지 않는다.

첫 검사(baseline 없음)에서는 absolute 판정만으로도 coverage 70 %를 넘길 수 있다(1.1, 1.2, 1.3, 1.6, 1.8, 2.2, 2.3, H.1, H.5, H.6–H.9가 모두 absolute 판정 가능). 그래서 coverage 규칙에 기대지 않고 **baseline이 없으면 점수를 내지 않는다**고 명시한다. composite level(NORMAL/WARNING/ISSUE)은 첫 검사에서도 낸다. Consumer 문구는 다음과 같다.

```text
첫 검사가 완료되었습니다.
이 결과를 기준으로 저장했습니다.
다음 검사부터 변화도 함께 확인합니다.
```

### 7.5 점수 노출 조건

점수는 설정 플래그 `score.enabled`가 켜졌을 때만 화면에 나온다. 기본값은 꺼짐이다. 켜는 조건은 다음 세 가지를 모두 만족했을 때다.

1. Galaxy S25+에서 baseline 생성 후 연속 10회 검사에서 NORMAL 판정과 점수 편차가 ±5 이내다.
2. 의도적으로 만든 이상 상황(카메라 점유 경쟁, 발열 상태, 렌즈 가림) 3종에서 점수가 하락한다.
3. 6장의 임계값을 실측으로 확정해 이 문서를 갱신했다.

점수가 꺼져 있어도 run JSON에는 `score`와 `coverage`를 기록해 검증에 쓴다.

---

## 8. Diagnosis Rules

진단은 metric state 조합에서 문장을 고르는 규칙표다. 규칙 ID는 run JSON에 기록되고, Consumer와 Expert가 같은 규칙 ID를 다른 문구로 보여준다. 현재 HealthMonitor의 다섯 가지 case를 그대로 규칙 ID로 승격한다.

| 규칙 ID | 조건 | Consumer 문구 | Expert 문구 |
|---|---|---|---|
| `normal` | WARN/FAIL 없음 | 현재 프레임 흐름은 정상입니다. | NORMAL |
| `slower_than_baseline` | latency 지표 relative WARN/FAIL, absolute PASS | 규격 범위 안이지만 평소보다 느립니다. | RELATIVE DEGRADATION · {metric} +{pct}% vs baseline |
| `below_spec` | `threshold_basis=absolute_validated` (조건 `equivalent`일 때만. v0.2에서는 발생하지 않음) | 평소와 같지만 성능 기준을 만족하지 않습니다. | ABSOLUTE FAIL · {metric} {value} ≥ {bound} |
| `cdd_reference_exceeded` | CDD 참조값 초과, 조건 `similar` 또는 `non_equivalent`, 또는 `cdd_applicability=not_applicable` | 권장 성능 기준보다 느립니다. | CDD_REFERENCE_EXCEEDED · environment_not_equivalent · {metric} {value} ≥ {bound} |
| `sensor_stall` | H.5 WARN/FAIL, H.3 PASS | 프레임이 예상보다 늦게 도착합니다. | SENSOR STALL · interval > own duration · 앞단 |
| `callback_delay` | H.5 PASS, H.3 WARN/FAIL | 프레임 응답이 지연됩니다. 촬영 파이프라인 후반부의 지연 가능성이 있습니다. | PARTIAL DELAY · cadence 정상 · 뒷단 |
| `pipeline_stall` | H.5와 H.3 모두 WARN/FAIL | 프레임 흐름 전체가 정체됩니다. | PIPELINE STALL |
| `cadence_change` | H.1 UNKNOWN(cadence_changed) | 어두운 환경에서 프레임 속도가 낮아졌습니다. 문제가 아닙니다. | CADENCE CHANGE · AE variable FPS · not a stall |
| `three_a_unstable` | H.6–H.8 중 WARN/FAIL | 초점 또는 노출 맞추기가 평소보다 오래 걸립니다. | 3A UNSTABLE · {axis} {ms} ms |
| `three_a_searching` | 관측 창 끝에서 3A 미안정, 지표는 PASS | 초점을 맞추는 중입니다. | 3A SEARCHING |
| `hard_failure` | 5.6절 이벤트 | 카메라를 열거나 촬영하지 못했습니다. | HARD FAIL · {event} |
| `insufficient_evidence` | INSUFFICIENT | 검사할 데이터가 부족합니다. 밝은 곳에서 다시 검사해 주세요. | INSUFFICIENT · coverage {pct}% |

### 8.1 원인 단정 금지

모든 진단에는 `cause_layer` 필드가 붙고 값은 `unattributed`, `app`, `framework_callback`, `sensor_front` 네 가지뿐이다. `hal`이라는 값은 없다. 앱은 콜백 도착만 관측하므로 HAL 내부를 지목할 수 없다. `sensor_front`는 "센서 timestamp 간격이 자체 frame duration보다 늦었다"는 관측 사실을 뜻하고, 원인이 센서라는 뜻이 아니다. Consumer 문구는 "가능성이 있습니다"까지만 쓴다.

### 8.2 우선순위

여러 규칙이 동시에 맞으면 `hard_failure` > `below_spec` > `pipeline_stall` > `sensor_stall` > `callback_delay` > `cdd_reference_exceeded` > `slower_than_baseline` > `three_a_unstable` > `cadence_change` > `three_a_searching` > `normal` 순서로 헤드라인을 정하고, 나머지는 증거 목록에 남긴다.

---

## 9. Camera / Lens Enumeration

### 9.1 원칙

UI에 Main / Ultra Wide / Tele / Front를 먼저 그려 놓고 backend를 끼워 맞추지 않는다. 열거 결과가 화면을 만든다. 열거되지 않은 렌즈는 화면에 없다.

### 9.2 Capability model

```kotlin
enum class LensRole { MAIN, ULTRA_WIDE, TELE, FRONT, EXTERNAL, UNKNOWN }

data class CameraEndpoint(
    val logicalCameraId: String,
    val physicalCameraId: String?,       // null이면 논리 카메라 자체
    val role: LensRole,
    val facing: Int,                     // CameraCharacteristics.LENS_FACING
    val independentlyOpenable: Boolean,  // cameraIdList에 직접 포함
    val selectableByZoom: Boolean,       // 논리 카메라의 zoom ratio 범위로 도달 가능
    val exposedToCameraX: Boolean,       // CameraX CameraInfo에서 같은 id 확인
    val equivalentFocalMm: Double?,      // 역할 추정 근거
    val timestampSource: Int,            // SENSOR_INFO_TIMESTAMP_SOURCE
    val hardwareLevel: Int
)
```

### 9.3 열거 절차

1. `CameraManager.cameraIdList`의 각 id를 `independentlyOpenable = true`인 endpoint로 만든다.
2. API 28 이상에서 `CameraCharacteristics.physicalCameraIds`가 비어 있지 않으면 각 physical id를 `independentlyOpenable = false` endpoint로 추가한다. 이 endpoint는 논리 카메라를 연 뒤 `setPhysicalCameraKey`가 아니라 `OutputConfiguration.setPhysicalCameraId`로 스트림을 붙여 관측한다. v0.2에서는 physical endpoint의 Auto Check를 **실행하지 않고 열거만** 한다.
3. 역할 추정: `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`와 `SENSOR_INFO_PHYSICAL_SIZE`로 35 mm 환산 초점거리를 계산해 `< 20 mm`이면 ULTRA_WIDE, `20–35 mm`이면 MAIN, `> 35 mm`이면 TELE로 둔다. 후면에 MAIN이 둘 이상이면 초점거리가 가장 짧지 않은 것 중 첫 번째만 MAIN이고 나머지는 UNKNOWN이다. 전면은 FRONT다.
4. `selectableByZoom`: 논리 카메라의 `CONTROL_ZOOM_RATIO_RANGE` 하한이 1.0 미만이면 초광각이 zoom으로 도달 가능하다고 표시한다. 도달 가능하다는 표시일 뿐 어느 physical camera로 전환되는지는 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`로 확인한다.
5. 열거 결과 전체를 run JSON의 `endpoints` 배열에 기록한다.

### 9.4 검증이 필요한 단말

Galaxy S25+(SM-S936N)에서 다음을 실측으로 확인하기 전까지 Ultra Wide / Tele 행의 표시를 약속하지 않는다.

- `cameraIdList`에 초광각과 망원이 별도 id로 나오는지
- 논리 카메라 0의 `physicalCameraIds`가 비어 있는지
- zoom ratio 하한이 1.0 미만인지
- CameraX `CameraInfo`가 같은 id 집합을 보는지

공개 API가 열거하지 않는 id를 숫자로 추측해서 여는 시도는 하지 않는다(3장).

---

## 10. Auto Check Flow

### 10.1 State machine

```text
IDLE
 └─► PERMISSION ──denied──► ABORTED(permission)
      └─► ENUMERATE ──0 endpoints──► ABORTED(no_camera)
           └─► [endpoint 반복, independentlyOpenable만]
                OPEN(3 s) ──► CONFIGURE(3 s) ──► FIRST_FRAME(3 s)
                  └─► OBSERVE(10 s 고정)
                       └─► STILL ×3 (각 5 s)
                            └─► CLOSE(3 s) ──► 다음 endpoint
           └─► EVALUATE (ThresholdEngine, DiagnosisRules, HealthComposer)
                └─► REPORT (run JSON 저장, Consumer 결과 화면)
```

단계별 timeout에 걸리면 그 endpoint는 하드 실패로 기록하고 `CLOSE`로 진행한다. 다음 endpoint는 계속 검사한다. 한 endpoint의 실패가 전체 검사를 중단시키지 않는다.

### 10.2 시간 예산

| 단계 | 예산 |
|---|---:|
| open + configure + first frame | ≤ 2 s (정상 시) |
| observe | 10 s |
| still ×3 | ≤ 3 s |
| close | ≤ 0.5 s |
| endpoint당 합 | 약 15 s |
| endpoint 4개 | 약 60 s |

endpoint가 4개를 넘으면 후면 MAIN, FRONT, ULTRA_WIDE, TELE 순으로 4개만 검사하고 나머지는 `not_run`으로 둔다.

### 10.3 재시도

자동 재시도는 없다(METRICS.md 0.2절 원칙). 실패는 사유와 함께 기록한다. 사용자가 결과 화면에서 "다시 검사"를 누르면 새 run이 된다.

### 10.4 사용자 상호작용

검사 중 사용자 입력은 받지 않는다. 시작 전에 한 화면으로 안내한다.

```text
검사 준비

밝은 곳에서
글자나 물건을 향해 폰을 들고
60초 동안 움직이지 마세요.

[ 검사 시작 ]
```

검사 중 화면은 현재 endpoint의 프리뷰와 진행률만 보여준다. 화면 꺼짐을 막기 위해 `FLAG_KEEP_SCREEN_ON`을 켠다. 앱이 백그라운드로 가면 run을 `ABORTED(background)`로 끝낸다.

### 10.5 환경 조건 기록

METRICS.md 0.3절의 환경값(thermal, 배터리, 회전, 프로세스 cold/warm)을 run 시작과 끝, 각 endpoint 전후에 기록한다. thermal status가 SEVERE 이상이면 검사를 시작하지 않고 "기기가 뜨겁습니다. 식힌 뒤 검사하세요"를 보여준다.

---

## 11. Consumer UI Contract

### 11.1 세 계층

| 계층 | 내용 | 기본 표시 |
|---|---|---|
| L1 판정 | NORMAL / WARNING / ISSUE / INSUFFICIENT + 한 문장 | 항상 |
| L2 증거 | 사람이 읽는 3–5개 항목. 예: "프레임 응답 +69 %", "AF 수렴 중", "frame stall 0회" | 항상 |
| L3 원본 | baseline p50, interval, duration, partial, frame number, threshold | "상세 분석 보기"를 누른 뒤 |

L1–L3를 한 박스에 넣지 않는다. 현재 진단 패널의 전체 텍스트는 L3로 옮긴다.

### 11.2 첫 화면

```text
CAMERA DOCTOR

내 카메라는 건강할까요?

  ● NORMAL
  마지막 검사 2026.09.09 14:22

  후면 메인    ✓
  전면         ✓
  (열거된 endpoint만 표시)

[ 60초 카메라 검사 ]

[ 방금 이상했어요 ]
직전 10초 + 이후 5초를 저장하고 분석합니다.

Expert Diagnostics ›
```

검사 이력이 없으면 판정 자리에 "아직 검사하지 않았습니다"를 쓴다.

### 11.3 결과 화면

```text
CAMERA HEALTH
● WARNING
규격 범위 안이지만 평소보다 느립니다.

21개 항목 검사
18 PASS · 2 WARN · 1 UNKNOWN

후면 메인
  프레임 응답      +69 %   평소 56 ms → 지금 95 ms
  초점 맞추기      1.8 s   평소 0.9 s
전면
  정상

권장
렌즈를 닦고 밝은 곳에서 다시 검사하세요.

[ 상세 분석 보기 ]   [ 결과 공유 ]
```

"권장" 문구는 규칙 ID별 고정 문구다. `hard_failure`는 "다른 앱이 카메라를 사용 중인지 확인한 뒤 다시 검사하세요"다. 문구에 원인 단정을 넣지 않는다.

### 11.4 Incident 버튼

| 모드 | 버튼 이름 | 저장 후 화면 |
|---|---|---|
| Consumer | 방금 이상했어요 | L1 판정 + L2 증거 + "상세 데이터" 버튼 |
| Expert | MARK INCIDENT | 현재 진단 패널(L3) 그대로 |

현재 `WRONG` 버튼은 위 두 이름으로 바꾼다. 기능은 FlightRecorder.trigger 그대로다.

### 11.5 문구 규칙

- 숫자는 단위와 함께 쓰고 소수점은 한 자리까지만 쓴다.
- "느립니다"에는 항상 비교 대상(평소, 규격)을 붙인다.
- ISO, AE, AF, AWB, partial 같은 용어는 L1과 L2에 쓰지 않는다. L2에서는 "노출", "초점", "색", "프레임 응답"으로 바꾼다.
- "고장"이라는 단어는 쓰지 않는다. 하드 실패도 "카메라를 열지 못했습니다"까지만 말한다.


### 11.6 시각 방향 (2026-09-09 사용자 지정)

기준 문서는 [docs/design/DESIGN.md](design/DESIGN.md)다. 아래는 그 토큰을 Camera Doctor의 두 모드에 대응시킨 것이다.

**원칙.** 장식 그라데이션과 chrome 그림자를 쓰지 않는다. 상호작용 색은 Action Blue 하나뿐이다. 판정 색(PASS/WARN/FAIL)은 기준 문서에 없으므로 별도로 정의하되, 판정 색은 상태 표시에만 쓰고 버튼에는 쓰지 않는다.

| 역할 | 토큰 | 값 | Camera Doctor 용도 |
|---|---|---:|---|
| 기본 배경 | `canvas-parchment` | #f5f5f7 | Consumer 화면 배경 |
| 카드 | `canvas` | #ffffff | 결과 카드, 항목 카드. 1 px `hairline` #e0e0e0 테두리, 모서리 18 px |
| 본문 글자 | `ink` | #1d1d1f | 모든 헤드라인과 본문 |
| 보조 글자 | `ink-muted-48` | #7a7a7a | 검사 시각, 단위, 각주 |
| 상호작용 | `primary` | #0066cc | "60초 카메라 검사", "상세 분석 보기", 링크. 유일한 버튼 색 |
| 포커스 | `primary-focus` | #0071e3 | 포커스 링 |
| Expert 배경 | `surface-tile-1` | #272729 | Expert 탭 전체 배경. 프리뷰와 scope가 있는 화면은 어두운 tile |
| Expert 카드 | `surface-tile-2` / `surface-tile-3` | #2a2a2c / #252527 | 인접 패널의 미세 구분 |
| Expert 글자 | `body-on-dark` / `body-muted` | #ffffff / #cccccc | Diagnosis Summary / 보조 값 |
| Expert 링크 | `primary-on-dark` | #2997ff | 어두운 배경 위 상호작용 |
| 프리뷰 배경 | `surface-black` | #000000 | 카메라 프리뷰 뒤 |
| PASS | `status-pass` | #34c759 | ● NORMAL, ✓ 표시 |
| WARN | `status-warn` | #ff9500 | ● WARNING, △ 표시 |
| FAIL | `status-fail` | #ff3b30 | ● ISSUE |
| UNKNOWN | `ink-muted-48` | #7a7a7a | ○ 표시. 색으로 판정을 암시하지 않음 |

**타이포.** 본문 서체는 Inter(variable)를 번들하고, 굵기 사다리는 300 / 400 / 600만 쓴다(500과 700은 쓰지 않는다). 표시 크기에서는 자간을 −0.01 em 줄인다. 숫자는 `tabular-nums`로 고정폭 정렬한다.

| 용도 | 크기 | 굵기 | 자간 |
|---|---:|---:|---:|
| 판정 헤드라인 (● NORMAL) | 34 sp | 600 | −0.37 px |
| 결과 큰 숫자 (95 ms, 92) | 56 sp | 600 | −0.28 px |
| 카드 제목 | 21 sp | 600 | 0 |
| 본문 | 17 sp | 400 | −0.37 px, 행간 1.47 |
| 캡션, 버튼 | 14 sp | 400 | −0.22 px |
| 각주 | 12 sp | 400 | −0.12 px |

**현재 Expert 화면의 색.** 지금 코드의 mint(#6fe1c6), coral(#ff807e), muted(#99aec0), glass(반투명 #0c131a)는 위 표로 교체한다. scope 트랙 색은 판정 색과 겹치지 않도록 파랑 계열 명도 단계(#2997ff, #7fbfff, #cccccc)로 통일하고, 이상 프레임과 incident 커서만 systemOrange/systemRed를 쓴다.

---

## 12. Expert UI Contract

### 12.1 유지하는 것

현재 화면(CameraX/Camera2 전환, 카메라 선택, LIVE 프리뷰, health strip, frame timeline, 3A oscilloscope, MARK INCIDENT, ZIP 공유)은 그대로 Expert 탭이 된다. 3A oscilloscope는 Camera Doctor의 시그니처 화면으로 유지한다.

### 12.2 추가하는 것

1. **Diagnosis Summary 헤더.** 화면 상단에 규칙 ID, 기대값, 관측값, 계층별 상태를 고정 표시한다.

   ```text
   DIAGNOSIS
   ⚠ callback_delay

   Expected   +56.2 ms (baseline p50)
   Observed   +95.4 ms (+69 %)

   3A         NORMAL
   Cadence    NORMAL
   Callback   ABNORMAL

   cause_layer  unattributed
   ```

2. **Incident vertical marker.** incident trigger 시각과 각 이상 프레임 시각에 모든 scope 트랙(AE, AF, AWB, Exposure, ISO, Interval)에 같은 세로 커서를 긋는다. 커서 위에 frame number를 쓴다. 이렇게 하면 "ISO가 먼저 변했고 180 ms 뒤 interval이 튀었다" 같은 상관을 눈으로 확인할 수 있다.

3. **Frame callback timeline 그래프.** 현재 텍스트(`START +0 · PARTIAL +55.0 · BUFFER +60.2 ms`) 위에 두 줄의 점선 그래프를 그린다. 위 줄은 현재 프레임, 아래 줄은 baseline p50이다.

   ```text
   Now       ●────────────●────●
             0          55.0  60.2 ms
   Typical   ●────────●──●
             0       38   43 ms
   ```

4. **Raw metric 노출.** 모든 metric state를 ID, 값, 기준, 상태, UNKNOWN 사유와 함께 표로 보여준다. 이 표가 6장의 구현 검증 수단이다.

### 12.3 Expert에서만 가능한 조작

- baseline 수동 초기화와 baseline run 선택
- 시나리오 runner 실행(2.5 반복 10장, 3.x 녹화 시나리오) — v0.3
- `score.enabled` 플래그 토글과 점수 검증 기록 보기

---

## 13. Implementation Contract

### 13.1 패키지와 책임

| 패키지 | 클래스 | 책임 | 상태 |
|---|---|---|---|
| `telemetry` | `FlightRecorder`, `Telemetry`, `IncidentExporter` | 이벤트 기록, incident 창, zip | 기존 |
| `diagnosis` | `MetricExtractor` | 이벤트 목록에서 그룹 A/B 측정값 계산. 순수 함수 | 신규 |
| `diagnosis` | `ThresholdEngine` | 측정값 + baseline + 기기 정보 → `MetricState` 목록. 임계값은 `ThresholdTable` 하나에만 존재 | 신규 |
| `diagnosis` | `DiagnosisRules` | `MetricState` 목록 → 규칙 ID와 증거 | 신규. HealthMonitor의 case 로직을 옮김 |
| `diagnosis` | `HealthComposer` | 규칙과 상태 → composite level, coverage, score(nullable) | 신규 |
| `diagnosis` | `HealthMonitor` | 실시간 strip용. 내부에서 위 세 클래스를 1.5 s 창으로 호출하도록 교체 | 변경 |
| `baseline` | `BaselineStore` | 키별 baseline 저장, 무효화 | 신규 |
| `check` | `AutoCheckRunner` | 10장 state machine. CameraEngine과 Telemetry만 의존 | 신규 |
| `check` | `CameraEndpointResolver` | 9장 열거 | 신규 |
| `report` | `HealthReport` | run JSON 직렬화, zip 첨부 | 신규 |
| `ui` | `ConsumerHomeActivity`, `CheckResultView` | 11장 | 신규 |
| `ui` | 현재 `MainActivity` | Expert 탭으로 이동 | 변경 |

`MetricExtractor`, `ThresholdEngine`, `DiagnosisRules`, `HealthComposer`는 Android 의존성 없는 순수 Kotlin이어야 하고 JVM 단위 테스트로 6장 표의 모든 행을 검증한다.

### 13.2 MetricState

세 축은 직교한다. State는 결과가 얼마나 나쁜가, ThresholdBasis는 왜 그렇게 판정했는가, ConditionEquivalence는 외부 기준과 조건이 얼마나 같은가다. 모든 enum은 JSON에서 snake_case 소문자로 직렬화한다(`NO_BASELINE` → `"no_baseline"`). 문자열 필드로 두지 않는다.

```kotlin
enum class State { PASS, WARN, FAIL, UNKNOWN }

enum class ThresholdBasis { HARD, ABSOLUTE_VALIDATED, ABSOLUTE_REFERENCE, RELATIVE, HEURISTIC }

enum class ConditionEquivalence { EQUIVALENT, SIMILAR, NON_EQUIVALENT }

enum class CddApplicability { APPLICABLE, NOT_APPLICABLE }

enum class UnknownReason {
    NOT_MEASURABLE, NOT_RUN, INSUFFICIENT_SAMPLES, NO_BASELINE,
    UNSUPPORTED, CADENCE_CHANGED, CONDITION_MISMATCH
}

data class MetricState(
    val id: String,                  // "1.6", "H.3" ...
    val value: Double?,              // 대표값 (p50 등)
    val p95: Double? = null,
    val n: Int,

    val absolute: State,
    val absoluteBound: Double?,
    val absoluteSource: String?,     // "cdd_2.2.7.2_H-1-6", "physics", "watchdog_v0.2", "product_stability_v0.2" ...

    val relative: State,
    val baselineValue: Double?,
    val deltaPct: Double?,

    val final: State,                // 5.4절 worst-of

    val thresholdBasis: ThresholdBasis?,          // final을 결정한 기준. PASS/UNKNOWN이면 null
    val cddApplicability: CddApplicability?,      // CDD 참조 지표(1.6, 2.2)만. 그 외 null
    val conditionEquivalence: ConditionEquivalence?, // CDD 참조 지표만. 그 외 null

    val unknownReason: UnknownReason?              // final == UNKNOWN일 때 필수
) {
    val isHardFailure: Boolean
        get() = final == State.FAIL && thresholdBasis == ThresholdBasis.HARD
}
```

`hardFailure` 필드는 두지 않는다. `thresholdBasis == HARD`에서 유도된다.

### 13.3 Health Report JSON (run JSON 확장)

METRICS.md 4장의 run JSON에 아래 필드를 추가한다. 기존 필드는 바꾸지 않는다.

```json
{
  "schema_version": 2,
  "product_definition_version": "0.2-draft",
  "threshold_table_version": "0.2-draft",
  "endpoints": [ { "logicalCameraId": "0", "physicalCameraId": null, "role": "MAIN", "independentlyOpenable": true } ],
  "baseline_ref": { "run_id": "20260909-101500", "fingerprint": "...", "valid": true },
  "metric_states": [ { "id": "1.6", "value": 412.0, "n": 1, "absolute": "pass", "absolute_source": "cdd_2.2.7.2_H-1-6", "relative": "unknown", "final": "pass", "threshold_basis": null, "cdd_applicability": "applicable", "condition_equivalence": "similar", "unknown_reason": null } ],
  "diagnosis": { "rule": "normal", "cause_layer": "unattributed", "evidence": [] },
  "health": { "level": "NORMAL", "coverage": 0.62, "score": null, "score_visible": false },
  "recommendation_id": "none"
}
```

### 13.4 테스트 계약

| 테스트 | 검증 내용 |
|---|---|
| `ThresholdEngineTest` | 6장 표의 각 행에 대해 PASS/WARN/FAIL 경계값, baseline 없음, 하드 실패, 5.4절 충돌 4가지, `threshold_basis` 부여, MPC 게이트(U 미만·미선언·U 이상), 조건 등급별 CDD 초과 상태, 가변 FPS [15,30]에서 66.7 ms가 WARN이 아님, 3A timeout이 FAIL이 아님, exposure_load 비율 4배 경계 |
| `HealthComposerTest` | 7.1절 수준 결정, relative/heuristic FAIL은 WARNING이고 hard/absolute_validated FAIL만 ISSUE, coverage 70 % 경계, 하드 실패 cap 59, UNKNOWN 분모 제외, baseline 없으면 score null |
| `DiagnosisRulesTest` | 8.2절 우선순위, HealthMonitor 기존 5개 case와 동일 판정 |
| `MetricExtractorTest` | 고정 이벤트 목록에서 H.1–H.9 값, insufficient_samples 경계(14개/15개) |
| `AutoCheckRunnerTest` | fake engine으로 timeout 전이, 한 endpoint 실패 후 다음 endpoint 계속 |

---

## 14. Milestones

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| M1 | Metric threshold engine | `MetricExtractor`, `ThresholdEngine`, `HealthComposer`, `DiagnosisRules`와 단위 테스트. 기존 HealthMonitor가 새 엔진 위에서 같은 판정을 냄 |
| M2 | Auto Check runner | Galaxy S25+에서 60초 검사 완주, run JSON 저장, endpoint 열거 결과 기록 |
| M3 | Diagnosis summary | L1/L2/L3 카드, Expert 상단 Diagnosis Summary, incident 저장 후 요약 |
| M4 | Consumer Home | 첫 화면, 검사 준비/진행/결과, "방금 이상했어요", 결과 공유(zip에 Health Report 포함) |
| M5 | Expert Mode migration | 현재 화면을 Expert 탭으로 이동, incident vertical marker, timeline 그래프, raw metric 표 |

M1이 끝나기 전에 M4의 결과 화면을 만들지 않는다. 화면이 먼저 생기면 점수와 판정이 화면에 맞춰 역산되기 때문이다.

---

## 15. v0.3 이후로 미룬 것

| 항목 | 필요한 것 |
|---|---|
| 중고폰 정밀 검사 모드 | flat-field 촬영(흰 면), 먼지/얼룩 검출, vignetting 편차, 결과 공유 이미지/PDF/QR |
| AF robustness | 근거리/원거리 반복 AF, 수렴 실패율, AF 모터 이상 대리 지표 |
| OIS | `LENS_OPTICAL_STABILIZATION_MODE` 관측과 가속도계 상관, 소음 분석은 v0.4 |
| 이미지 품질 분석 | 선명도(MTF 대리), 노이즈, 색 편차 |
| Expert 시나리오 runner | 2.5 10장 반복, 2.4 trigger, 3.x 녹화, 10분 drift |
| Before/After 비교와 검사 이력 | run JSON 목록, baseline 재설정 흐름 |
| CameraX 비교 run | METRICS.md 7절 이름으로 별도 기록 |

---

## 미결 사항 — 사용자 확정 필요

- [ ] 6장 임계값 전부 (실측 후 확정). 2026-09-09 검토로 반영한 변경: CDD 게이트를 MPC ≥ U로, 조건 비동일 시 CDD 초과는 WARN, H.1의 target FPS 상한 기준 제거, 3A는 FAIL 없음, relative/heuristic FAIL은 ISSUE로 승격하지 않음, 첫 검사 점수 금지, 환경 불일치는 exposure_load 비율
- [ ] latency 지표별 noise floor: v0.2는 10 ms 전역 값 유지, 이후 `noiseFloorMs(metric)`로 분리
- [ ] 7.2절 가중치
- [ ] 점수 노출 시점: 이 문서는 7.5절 조건 충족 전까지 비노출을 제안한다. 2026-09-09 UI 의견에서 Health Score를 다음 기능 4개에 포함했는데, 계산과 JSON 기록은 M1에서 하되 화면 노출은 7.5절 조건 이후로 두는 안으로 정리했다.
- [ ] Auto Check still 해상도: CDD 비교를 위해 1920x1080 JPEG 제안. 최대 해상도는 Expert runner
- [ ] endpoint 4개 초과 시 검사 순서
- [ ] baseline 생성 조건의 배터리/thermal 기준값

## 출처

- [S1 · Android 16 CDD 2.2.7.2 Media Performance Class, [7.5/H-1-5], [7.5/H-1-6]](https://source.android.com/docs/compatibility/16/android-16-cdd) — 2026-09-09 원문 확인
- [S2 · AOSP CTS PerformanceTest.java](https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/camera/src/android/hardware/camera2/cts/PerformanceTest.java) — launch/capture 지연은 ReportLog 보고만 하며 assert 없음. `WAIT_FOR_RESULT_TIMEOUT_MS = 3000`, `NUM_TEST_LOOPS = 10`
- [S3 · CameraCharacteristics — physicalCameraIds, LENS_INFO_AVAILABLE_FOCAL_LENGTHS, CONTROL_ZOOM_RATIO_RANGE](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [S4 · Build.VERSION.MEDIA_PERFORMANCE_CLASS](https://developer.android.com/reference/android/os/Build.VERSION#MEDIA_PERFORMANCE_CLASS)
