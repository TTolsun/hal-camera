# Camera Doctor 성능 지표 정의표

- 작성일: 2026-09-08
- 상태: 초안 (코드 작성 전에 이 표를 확정한다)
- 범위: Camera2 단일 엔진. CameraX, Perfetto, AI 진단은 범위 밖.
- 원칙
  1. 모든 지표는 시작점과 끝점이 코드에서 찍을 수 있는 시각이어야 한다.
  2. 측정 조건이 값을 바꾸면 조건을 지표 이름에 붙인다.
  3. 측정할 수 없는 조건이면 값을 내지 않고 `not_measurable`로 기록한다. 0이나 추정값을 쓰지 않는다.
  4. 1회 측정은 값이 아니다. 반복 통계(p50/p95)만 보고한다.

## 0. 공통 규약

### 0.1 시계

| 시계 | 용도 | 비고 |
|---|---|---|
| `SystemClock.elapsedRealtimeNanos()` | 앱 쪽 모든 시각 | CLOCK_BOOTTIME |
| `CaptureResult.SENSOR_TIMESTAMP` | 센서 노출 시작 시각 | `SENSOR_INFO_TIMESTAMP_SOURCE`가 `REALTIME`일 때만 앱 시계와 비교 가능 |

`SENSOR_INFO_TIMESTAMP_SOURCE == UNKNOWN`이면 센서 시각을 앱 시각과 비교하는 지표(셔터 lag)는 `not_measurable`이다. 센서 시각끼리의 차이(프레임 간격)는 계속 유효하다.

### 0.2 반복과 통계

| 항목 | 값 |
|---|---|
| 기본 반복 | 10회 |
| 제외 | 첫 1회 (warm-up) |
| 보고 | p50, p95, min, max, 유효 표본 수 |
| cold/warm | 부팅 후 첫 open은 `cold`, 이후는 `warm`으로 표기. 섞어서 통계 내지 않는다 |

### 0.3 매 run에 함께 기록하는 환경값

| 항목 | 출처 |
|---|---|
| 기기 모델, Android 빌드 번호 | `Build.MODEL`, `Build.DISPLAY` |
| camera id, 논리/물리 여부 | `CameraCharacteristics` |
| thermal status (run 시작, 종료) | `PowerManager.getCurrentThermalStatus()` |
| 배터리 잔량, 충전 여부 | `BatteryManager` |
| 화면 회전 | `Display.getRotation()` |
| 앱 프로세스 cold/warm | 프로세스 기동 여부 |

### 0.4 시나리오 조건 축

지표 이름은 `지표명[조건1,조건2,...]` 형태로 쓴다. 예: `capture_latency[zsl=on,trigger=off,fmt=jpeg,res=12mp]`.

| 축 | 값 |
|---|---|
| `zsl` | on / off (`CONTROL_ENABLE_ZSL`) |
| `trigger` | on / off (AE precapture + AF trigger 수렴 포함 여부) |
| `fmt` | jpeg / yuv / raw |
| `res` | 출력 해상도 |
| `fps` | 30 / 60 (녹화) |
| `stab` | on / off (녹화 stabilization) |

### 0.5 산출물

run 하나당 JSON 파일 하나. 그래프와 비교는 PC에서 한다. 각 시각은 `android.os.Trace`의 async section/counter로도 찍어서 나중에 Perfetto와 같은 타임라인에 올릴 수 있게 한다.

## 1. First preview time

시작점은 앱 기동이 아니라 `openCamera()` 호출이다. 앱 프로세스 기동 시간은 1.5로 분리한다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 1.1 | `open_latency` | `openCamera()` 호출 직전 | `StateCallback.onOpened()` 진입 | cold/warm |
| 1.2 | `configure_latency` | `createCaptureSession()` 호출 직전 | `StateCallback.onConfigured()` 진입 | 스트림 구성(preview 단독 / preview+jpeg / preview+video) |
| 1.3 | `first_frame_sensor` | `setRepeatingRequest()` 호출 직전 | 첫 `onCaptureStarted()` 진입 | |
| 1.4 | `first_frame_surface` | `setRepeatingRequest()` 호출 직전 | preview `SurfaceTexture.OnFrameAvailableListener` 첫 호출 | |
| 1.5 | `app_launch_to_open` | Activity `onCreate()` 진입 | `openCamera()` 호출 직전 | 앱 cold/warm. 앱 오버헤드 분리용 |
| 1.6 | `preview_total` | `openCamera()` 호출 직전 | 1.4의 끝점 | 1.1+1.2+1.4의 합과 다르면 앱 쪽 갭이 있다는 뜻 |
| 1.7 | `close_latency` | `CameraDevice.close()` 호출 직전 | `StateCallback.onClosed()` 진입 | 카메라 전환 시나리오에서 필요 |

주의

- "화면에 실제로 표시된 시각"은 앱에서 알 수 없다. 1.4는 Surface 도착 기준이며, 표시 기준은 나중에 Perfetto(SurfaceFlinger)로 붙인다.
- 1.3과 1.4 사이의 차이가 크면 HAL 내부 파이프라인 깊이(버퍼 큐 깊이)를 의심한다.

## 2. Shot-to-shot

`capture()`는 단발 촬영 요청이다. 조건 축 `zsl`, `trigger`, `fmt`, `res`를 반드시 붙인다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 2.1 | `shutter_lag` | 셔터 버튼 `onClick` 진입 | 해당 요청의 `SENSOR_TIMESTAMP` | `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`일 때만. 아니면 `not_measurable` |
| 2.2 | `capture_latency` | `capture()` 호출 직전 | 해당 요청의 `ImageReader.onImageAvailable()` 진입 | zsl, trigger, fmt, res |
| 2.3 | `capture_result_latency` | `capture()` 호출 직전 | 해당 요청의 `onCaptureCompleted()` 진입 | 2.2와의 차이가 JPEG 인코딩 + 버퍼 전달 시간 |
| 2.4 | `precapture_convergence` | AE precapture trigger를 담은 요청의 `capture()` 호출 직전 | `CONTROL_AE_STATE`가 `CONVERGED` 또는 `FLASH_REQUIRED`가 되고 `CONTROL_AF_STATE`가 `FOCUSED_LOCKED` 또는 `NOT_FOCUSED_LOCKED`가 된 첫 result | trigger=on일 때만 |
| 2.5 | `shot_to_shot` | N번째 `capture()` 호출 직전 | N+1번째 `capture()` 호출 직전 | 연속 촬영 모드: 이전 촬영의 `onImageAvailable`을 받은 즉시 다음 `capture()` 발행 |
| 2.6 | `preview_recovery` | `capture()` 호출 직전 | preview 프레임 간격이 정상(2.7 기준)으로 돌아온 첫 `onCaptureStarted` | zsl에 따라 크게 달라짐 |
| 2.7 | `preview_stall_during_capture` | 촬영 요청 발행 후 preview 프레임 간격 중 최대값 | | 정상 간격 = 설정 fps의 frame duration × 1.5 초과를 stall로 본다 |

주의

- 2.1과 2.2를 합쳐서 하나의 숫자로 내지 않는다. 사용자가 느끼는 지연(2.1)과 HAL 처리 지연(2.2)은 다른 층이다.
- trigger=on이면 2.2는 2.4를 포함한다. trigger=off 값과 나란히 보고한다.
- 연속 촬영(2.5)은 10장을 찍고 앞 1장을 제외한 9개 간격을 표본으로 쓴다.

## 3. Recording performance

`MediaRecorder` 사용을 기본으로 한다. 인코더 쪽 프레임 수는 `MediaRecorder`로는 얻을 수 없으므로, 3.3의 인코더 쪽 drop은 `MediaCodec` 직접 사용 시에만 측정한다. MVP에서는 카메라 쪽 drop만 측정하고 인코더 쪽은 `not_measurable`로 둔다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 3.1 | `record_start_latency` | `MediaRecorder.start()` 호출 직전 | 녹화 스트림을 포함한 첫 `onCaptureStarted()` 진입 | fps, res, stab |
| 3.2 | `camera_frame_drop` | 녹화 구간 전체 | 연속 `SENSOR_TIMESTAMP` 간격이 설정 frame duration × 1.5를 초과한 횟수와, 초과분을 frame duration으로 나눈 추정 drop 프레임 수 | fps, res, stab |
| 3.3 | `encoder_frame_drop` | 녹화 구간 전체 | 카메라 쪽 프레임 수와 인코더 출력 프레임 수의 차이 | `MediaCodec` 직접 사용 시만. MVP는 `not_measurable` |
| 3.4 | `steady_fps` | 녹화 시작 후 3초 이후 | 1초 창마다 프레임 수, 그 창들의 p50/min | fps, res, stab |
| 3.5 | `long_run_drift` | 10분 녹화 | 1분 단위 `steady_fps`와 thermal status 추이 | 발열에 따른 fps 하락 시각을 기록 |
| 3.6 | `record_stop_latency` | `MediaRecorder.stop()` 호출 직전 | `stop()` 반환 후 파일 크기가 더 이상 변하지 않는 시각 | |
| 3.7 | `frame_interval_jitter` | 녹화 구간 전체 | `SENSOR_TIMESTAMP` 간격의 표준편차와 p95 | fps |

주의

- 3.2는 HAL이 프레임을 못 낸 것이고, 3.3은 프레임은 왔는데 인코딩을 못한 것이다. 둘을 합치면 원인 층을 잃는다.
- 3.5는 기본 반복(10회)에서 제외한다. 1회를 별도 시나리오로 돌린다.
- stabilization은 `CONTROL_VIDEO_STABILIZATION_MODE`(EIS)와 `LENS_OPTICAL_STABILIZATION_MODE`(OIS)를 구분해서 기록한다.

## 4. JSON 스키마 초안

```json
{
  "schema_version": 1,
  "run_id": "20260908-224355",
  "device": { "model": "", "build": "", "camera_id": "0", "logical": true },
  "env": {
    "thermal_start": "NONE",
    "thermal_end": "LIGHT",
    "battery_pct": 82,
    "charging": false,
    "app_cold": true,
    "camera_cold": false
  },
  "scenario": "capture_latency",
  "conditions": { "zsl": "on", "trigger": "off", "fmt": "jpeg", "res": "4000x3000" },
  "timestamp_source": "REALTIME",
  "samples_ms": [612, 598, 640, 587, 601, 655, 593, 610, 599],
  "excluded_warmup_ms": [812],
  "stats_ms": { "p50": 601, "p95": 655, "min": 587, "max": 655, "n": 9 },
  "not_measurable": []
}
```

- `not_measurable`에는 측정하지 않은 지표 id와 사유를 넣는다. 예: `[{"id": "2.1", "reason": "SENSOR_INFO_TIMESTAMP_SOURCE=UNKNOWN"}]`.
- 시각 원본(각 콜백의 elapsedRealtimeNanos)은 별도 `events` 배열로 같은 파일에 넣는다. 통계는 원본에서 PC가 다시 계산할 수 있어야 한다.

## 5. CTS PerformanceTest와의 대응

`android.hardware.camera2.cts.PerformanceTest`와 정의가 겹치는 항목은 그쪽 정의를 따른다. 비교 가능성이 우선이다.

| CTS | 이 표 |
|---|---|
| `testCameraLaunch` (open + configure + first frame) | 1.1, 1.2, 1.3, 1.6 |
| `testSingleCapture` | 2.2, 2.3 |
| `testMultipleCapture` | 2.5 |
| 없음 | 2.1, 2.4, 2.6, 2.7, 3.x 전부 |

CTS에 없는 항목이 이 앱의 차별점이다.

## 6. MVP 포함 여부

| 포함 | 지표 |
|---|---|
| MVP | 1.1, 1.2, 1.3, 1.4, 1.6, 2.2, 2.3, 2.5, 3.1, 3.2, 3.4 |
| 다음 | 1.5, 1.7, 2.1, 2.4, 2.6, 2.7, 3.5, 3.6, 3.7 |
| 이후 | 3.3 (`MediaCodec` 전환 필요) |

## 미결 사항

- [ ] preview 해상도를 고정할지(1080p) 기기 기본값을 따를지 결정
- [ ] 2.5 연속 촬영에서 preview를 유지할지 끌지 결정 (둘 다 조건 축으로 둘 수도 있음)
- [ ] `res` 조건 값의 표기 규칙(픽셀 수 vs 가로x세로) 통일
