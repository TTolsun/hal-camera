# Camera Doctor 성능 지표 정의표 v0.2

- 작성일: 2026-09-08
- 상태: **검토용 초안 — 사용자 원문 반영 + 정의 충돌 수정안. 확정 전 새 성능 계측 코드 작성 보류.**
- 입력 원문: [사용자 제공 정의표](inputs/user-metrics-2026-09-08.md). 번호와 MVP/다음/이후 구분을 유지하고 변경 사유는 [검토 기록](METRICS-REVIEW.md)에 남긴다.
- 현재 APK: `checkpoint-001`의 프리뷰 프로토타입. 이 문서의 성능 지표가 구현되었다는 뜻이 아니다.
- 범위: Camera2 단일 엔진. CameraX, Perfetto, AI 진단은 범위 밖.
- 원칙
  1. 모든 지표는 시작점과 끝점이 코드에서 찍을 수 있는 시각이어야 한다.
  2. 측정 조건이 값을 바꾸면 조건을 지표 이름에 붙인다.
  3. 측정할 수 없는 조건이면 값을 내지 않고 `not_measurable`로 기록한다. 0이나 추정값을 쓰지 않는다.
  4. 일반 시나리오의 대표값은 반복 통계(p50/p95)로 보고한다. 단일 표본·실패·warm-up 원본도 파일에 보존한다. 10분 녹화는 반복 통계와 구분한 시계열 예외다.

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
| cold/warm | 외부 실험 기록으로 확인된 부팅 후 첫 open만 `cold_verified`. 그 외 `warm_sequence` 또는 `unknown`. 서로 섞지 않는다 |
| 통계 방식 | nearest-rank: 정렬된 n개에서 `ceil(p × n)`번째. 일반 10회에서 warm-up 제외 후 최대 n=9. n=9의 p95는 최대값 |
| 실패 처리 | warm-up 외 timeout/취소/미지원은 유효 표본에서 제외하되 횟수와 이유를 저장. 자동 재시도로 보충하지 않음 |

일반 앱은 다른 프로세스의 카메라 사용 이력까지 보증할 수 없으므로 앱 첫 실행을 camera cold로 간주하지 않는다. 실제 cold 표본의 통계가 필요하면 부팅을 반복해 별도 수집한다. cold 단일 표본은 원본으로 남기고 반복 통계가 성립하지 않음을 표시한다.

### 0.3 매 run에 함께 기록하는 환경값

| 항목 | 출처 |
|---|---|
| 기기 모델, Android 빌드 번호 | `Build.MODEL`, `Build.DISPLAY` |
| camera id, 논리/물리 여부 | `CameraCharacteristics` |
| thermal status (run 시작, 종료) | `PowerManager.getCurrentThermalStatus()` |
| 배터리 잔량, 충전 여부 | `BatteryManager` |
| 화면 회전 | `Display.getRotation()` |
| 앱 프로세스 cold/warm | 프로세스 인스턴스 id와 Activity 재생성 여부를 별도 기록. `onCreate()`만으로 process cold를 판정하지 않음 |
| governor | 읽을 수 있는 CPU policy 값만 기록. 권한 제한·미노출은 `not_measurable`과 사유. 추정하지 않음 |

환경은 run 전체뿐 아니라 각 반복 전후에도 저장한다. thermal API는 29 이상에서 수집하며 그 미만은 `not_measurable`. 메타데이터 누락과 미지원은 0/false로 대체하지 않는다.

### 0.4 시나리오 조건 축

지표 이름은 `지표명[조건1,조건2,...]` 형태로 쓴다. 예: `capture_latency[zsl=on,trigger=off,fmt=jpeg,res=12mp]`.

| 축 | 값 |
|---|---|
| `zsl` | on / off (`CONTROL_ENABLE_ZSL`) |
| `trigger` | on / off (AE precapture + AF trigger 수렴 포함 여부) |
| `fmt` | jpeg / yuv / raw |
| `res` | 실제 출력 `가로x세로` (예: `4000x3000`). preview/capture/video 각각 별도 기록 |
| `fps` | 30 / 60 (녹화) |
| `stab` | EIS/OIS를 별도 enum으로 기록. 요청값과 관측 결과값을 구분 |
| `preview` | on / off (연속 촬영 중 preview 유지 여부) |
| `next_shot_policy` | MVP는 `image_received` — 대응 이미지 수신 콜백 후 다음 capture |

조건 문자열은 구조화된 `conditions.requested` / `conditions.effective`에서 안정된 키 순서로 생성한다. 지원되지 않는 요청을 자동으로 바꾸지 않으며 유효값을 확인할 수 없으면 `unknown`으로 기록한다. RAW·ZSL·60 fps의 존재는 기기 기능과 stream 조합 검증을 통과해야 한다. 축이 있다는 것 자체가 지원을 보장하지 않는다.

### 0.5 산출물

run 하나당 JSON 파일 하나. 그래프와 비교는 PC에서 한다. 각 이벤트에 `session_id`, `iteration_id`, `request_id`, `frame_number`, 앱 수신 시각과 센서 시각을 분리해 저장한다. 나노초 원본은 JSON 10진 문자열을 사용한다.

앱 수신 시각은 콜백 진입 직후, API 시각은 호출 직전에 찍고 이후 JSON 직렬화를 한다. request tag/프레임 번호와 timestamp를 확인해 이미지·결과를 연결한다. 출력 timestamp base나 readout 설정으로 대응이 확인되지 않으면 FIFO 순서만으로 연결하지 않고 `not_measurable`로 남긴다.

`android.os.Trace` marker도 병행한다. async 구간/counter는 API 지원 여부를 확인하고 API 26–28에서는 호환 trace marker를 사용한다. 원본 타임스탬프의 근거는 JSON이며 trace marker 시각이 완전히 같다고 가정하지 않는다. Perfetto 수집기 구현은 범위 밖이다.

## 1. First preview time

시작점은 앱 기동이 아니라 `openCamera()` 호출이다. Activity 생성 이후 앱 구간은 1.5로 분리한다. 실제 프로세스 전체 기동 시간은 별도다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 1.1 | `open_latency` | `openCamera()` 호출 직전 | `StateCallback.onOpened()` 진입 | cold/warm |
| 1.2 | `configure_latency` | `createCaptureSession()` 호출 직전 | `StateCallback.onConfigured()` 진입 | 스트림 구성(preview 단독 / preview+jpeg / preview+video) |
| 1.3 | `first_frame_started_callback` | `setRepeatingRequest()` 호출 직전 | 해당 repeating 요청의 첫 `onCaptureStarted()` 진입 | 원문 `first_frame_sensor`를 명칭 정정. 센서 timestamp 자체가 아니라 앱 콜백 지연 [S1] |
| 1.4 | `first_frame_surface` | `setRepeatingRequest()` 호출 직전 | 해당 세션 preview `SurfaceTexture.OnFrameAvailableListener` 첫 진입 | listener를 직접 소유하고 세션 시작 전 미수신 상태에서 arm해야 함 [S2] |
| 1.5 | `activity_create_to_open` | Activity `onCreate()` 진입 | `openCamera()` 호출 직전 | 프로세스 전체 기동 시간이 아님. 권한 대기·surface 대기도 포함될 수 있으므로 조건 기록 |
| 1.6 | `preview_total` | `openCamera()` 호출 직전 | 1.4의 끝점 | 직접 차감. 단계 합과의 차이는 open 완료→configure 호출, configure 완료→repeating 호출 사이의 앱 구간 |
| 1.7 | `close_latency` | `CameraDevice.close()` 호출 직전 | `StateCallback.onClosed()` 진입 | 카메라 전환 시나리오에서 필요 |

주의

- "화면에 실제로 표시된 시각"은 앱에서 알 수 없다. 1.4는 Surface 도착 기준이며, 표시 기준은 나중에 Perfetto(SurfaceFlinger)로 붙인다.
- 1.4−1.3은 콜백 관측 차이다. 센서 readout, 처리·buffer 전달, listener 스케줄링 등이 함께 포함될 수 있으며 HAL buffer queue 깊이를 단독으로 추정하지 않는다. 이는 관측 경계에 따른 해석 제한이다.
- 현재 프리뷰 APK는 TextureView를 사용하며 이 listener 계측이 없다. `onSurfaceTextureUpdated()`나 YUV 이미지 콜백을 1.4로 대신 기록하지 않는다.
- `preview_total = open_latency + open_to_configure_gap + configure_latency + configured_to_repeat_gap + first_frame_surface`를 같은 세션 원본으로 검산한다.
- CTS와 비교할 때는 별도 `launch_yuv_proxy`를 수집해야 한다. Surface endpoint와 같은 이름으로 섞지 않는다 [S3].

## 2. Shot-to-shot

`capture()`는 단발 촬영 요청이다. 조건 축 `zsl`, `trigger`, `fmt`, `res`를 반드시 붙인다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 2.1 | `shutter_lag` | 셔터 버튼 `onClick` 진입 | 해당 요청의 `SENSOR_TIMESTAMP` | `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`일 때만. 아니면 `not_measurable` |
| 2.2 | `capture_latency` | `capture()` 호출 직전 | 해당 요청의 `ImageReader.onImageAvailable()` 진입 | zsl, trigger, fmt, res |
| 2.3 | `capture_result_latency` | still `capture()` 호출 직전 | 해당 요청의 `onCaptureCompleted()` 진입 | 2.2−2.3은 이미지/결과 콜백 도착 차이. JPEG 인코딩 시간으로 분해 불가 |
| 2.4 | `precapture_convergence` | AE precapture + AF START trigger 요청의 `capture()` 호출 직전 | trigger가 적용된 프레임 이후 아래 AE/AF 완료 규칙을 만족하는 첫 result 콜백 진입 | trigger=on 전용. 미지원/고정 초점 및 timeout은 규칙 참조 |
| 2.5 | `shot_to_shot` | N번째 still `capture()` 호출 직전 | N+1번째 still `capture()` 호출 직전 | `next_shot_policy=image_received`. 대응 이미지 콜백 시각과 다음 submit 시각 모두 보존 |
| 2.6 | `preview_recovery` | still `capture()` 호출 직전 | 정상 preview 센서 간격 5개 연속을 확인한 마지막 `onCaptureStarted()` 진입 | 복귀 확인 지연. preview=on, 기준 FPS 고정. 자세한 규칙은 아래 |
| 2.7 | `preview_stall_during_capture` | still 호출부터 대응 이미지 수신 후 1초까지의 preview 센서 간격 | 위 관측창과 겹치는 간격의 최대값(ms), `interval > 1.5 × T_ref` 횟수 | 지연 구간의 시작/끝 차이가 아닌 창 내 집계. 촬영 전 마지막 프레임도 포함 |

주의

- 2.1은 버튼 입력→센서 노출, 2.2는 still 제출→앱 이미지 콜백이다. 둘 다 HAL 단독 처리 시간을 의미하지 않는다. ZSL의 shutter lag는 음수가 될 수 있으며 0으로 보정하지 않는다.
- **2.2는 2.4를 포함하지 않는다.** trigger 요청과 실제 still 요청을 분리해 저장한다. 수렴 포함 지연이 필요하면 `trigger_to_image`라는 별도 지표를 trigger 요청 직전→대응 still 이미지 수신으로 정의한다. trigger=on/off의 2.2도 따로 보고한다.
- 2.4: trigger request id 이후의 프레임만 판정한다. AE는 지원·설정된 모드에서 CONVERGED/FLASH_REQUIRED, AF는 FOCUSED_LOCKED/NOT_FOCUSED_LOCKED를 완료 상태로 본다. NOT_FOCUSED_LOCKED는 초점 성공이 아니다. 고정 초점(AF OFF)은 AF 조건을 면제하고 이유를 기록한다. 미지원 trigger·AE lock으로 규칙을 적용할 수 없으면 별도 조건으로 분리하거나 `not_measurable`. 5초 timeout과 판정에 사용한 상태열을 남긴다.
- **2.5 표본 수 정정안:** 10장→9개 제출 간격. 첫 warm-up 간격을 제외하면 **8개 유효 간격**이다. 9개 유효 간격이 필요하면 11장을 촬영해야 한다. 문서 기본은 원문의 10장을 유지해 최대 n=8로 두며, 11장 변경 여부는 미결로 남긴다.
- 이미지 콜백에서 즉시 수신 시각을 찍고 실제 대응 이미지를 획득·식별한 후 다음 요청을 제출한다. 픽셀 복사/파일 저장은 다음 submit의 전제조건이 아니다. 획득한 이미지는 즉시 닫아 버퍼 고갈을 막고 콜백→submit 앱 구간을 기록한다.
- 2.6/2.7: `T_ref`는 실측 요청·결과에서 확인된 고정 FPS의 `1e9/fps`다. 가변 FPS, 노출에 따른 정상 cadence 변경, 누락된 timestamp에는 고정 기준 판정을 하지 않는다. 촬영 의도 이후 프레임만으로 판정하고, 미리 관측된 정상 cadence가 있을 때 0.5–1.5 × T_ref 간격 5개 연속을 복귀 확인으로 삼는다. 3초 내 미확인은 timeout. 창 끝을 걸치는 interval을 확인하려면 다음 프레임 1개를 추가 수집하고 그 유무도 기록한다. 이 복귀 규칙은 검토용 제품 규칙이며 CTS 정의가 아니다.

## 3. Recording performance

`MediaRecorder` 사용을 기본으로 한다. 인코더 쪽 프레임 수는 `MediaRecorder`로는 얻을 수 없으므로, 3.3의 인코더 쪽 drop은 `MediaCodec` 직접 사용 시에만 측정한다. MVP에서는 **카메라 센서 간격 이상**만 집계하고 실제 camera/encoder drop 개수는 `not_measurable`로 둔다. 간격으로 추정한 값은 실제 손실 개수와 다르므로 기본 JSON에 drop 확정값으로 내보내지 않는다.

| id | 지표 | 시작점 | 끝점 | 조건 |
|---|---|---|---|---|
| 3.1 | `record_start_to_camera_callback` | `MediaRecorder.start()` 호출 직전 | 시작 이후 제출한 녹화 대상 request id의 첫 `onCaptureStarted()` 진입 | 인코딩 시작 시각이 아닌 카메라 콜백 대리 지연. submission 정책도 조건으로 고정 |
| 3.2 | `camera_frame_interval_anomaly` | 녹화 대상으로 제출된 request의 결과열 | 고정 FPS와 유효 frame duration을 확인한 구간에서 센서 간격이 `1.5 × T_ref`를 초과한 횟수 | 실제 drop 개수 아님. 누락 결과·노출 변화 등 교란 조건과 유효 비교 개수 저장 |
| 3.3 | `encoder_frame_drop` | 대응을 검증한 녹화 입력/출력 구간 | MediaCodec output과 입력의 timestamp·경계·drain 검증 후에만 산정 검토 | MVP는 `not_measurable`. 단순 count 차이는 `pipeline_count_difference`로만 기록 |
| 3.4 | `steady_fps` | 시작 이후 첫 녹화 대상 센서 timestamp + 3초 | 센서 시계의 겹치지 않는 완전한 1초 창에서 녹화 대상 result 개수; 창 p50/min | camera result FPS이며 encoded/rendered FPS 아님. 끝의 불완전한 창 제외 |
| 3.5 | `long_run_drift` | 10분 녹화 | 1분 단위 `steady_fps`와 thermal status 추이 | 발열에 따른 fps 하락 시각을 기록 |
| 3.6 | `record_stop_latency` | `MediaRecorder.stop()` 호출 직전 | `stop()`의 정상 반환 직후 | 호출 완료 지연. 파일 크기 안정성을 종료 조건으로 쓰지 않음. 예외는 실패 [S4] |
| 3.7 | `frame_interval_jitter` | 녹화 대상 센서 timestamp의 유효 인접 간격 | 모집단 표준편차(ddof=0)와 nearest-rank p95(ms) | fps/노출 정책 고정 구간별 집계. 원시 간격과 n 보존 |

주의

- 3.2만으로 HAL 손실이라고 단정할 수 없다. 요청 cadence, 노출, callback/result 누락과 실제 stream buffer 전달을 구분해야 한다. 3.3도 count 차이만으로 encoder 손실이라고 단정하지 않는다. 원인층은 추가 증거가 없으면 `unattributed`.
- 3.1은 세션 준비 및 recorder prepare를 마친 뒤 `start()` 직전 시각을 기록하고, 정상 반환 뒤 새 녹화 대상 repeating request를 제출하는 정책을 제안한다. 준비 중 이미 도착한 preview/video callback은 제외한다. 이 정책의 장치 호환성은 구현 단계에서 확인하며 다른 순서가 필요한 기기는 조건을 분리한다. MediaRecorder로 첫 encoded frame 시각은 측정하지 않는다 [S4].
- 3.2는 직전 결과의 frame duration과 설정 FPS가 일치하는지 확인한 구간에서만 고정 기준을 적용한다. AE로 정상 frame duration이 달라졌으면 별도 구간으로 나누고 고정 FPS 기준 판정을 `not_measurable`로 처리한다 [S5].
- 3.4의 1초 창 통계와 run 반복 통계를 분리한다. 센서 시계 UNKNOWN에서도 같은 센서 시계의 상대 창은 계산 가능하며, 앱/thermal과 시각 비교는 별도로 제한한다.
- 3.6의 파일 해석 가능 여부는 반환 후 별도 검증한다. `stop()` 예외를 성공 지연으로 기록하지 않는다. 파일 크기가 일정하다는 관찰은 완성·내구성의 증거가 아니다.
- 3.5는 기본 반복(10회)에서 제외한다. 1회를 별도 시나리오로 돌린다.
- stabilization은 `CONTROL_VIDEO_STABILIZATION_MODE`(EIS)와 `LENS_OPTICAL_STABILIZATION_MODE`(OIS)를 구분해서 기록한다.

## 4. JSON 스키마 초안

```json
{
  "schema_version": 1,
  "metric_definition_version": "0.2-draft",
  "example_only": true,
  "stats_method": "nearest_rank",
  "run_id": "20260908-224355",
  "device": { "model": "", "build": "", "camera_id": "0", "logical": true },
  "env": {
    "thermal_start": "NONE",
    "thermal_end": "LIGHT",
    "battery_pct": 82,
    "charging": false,
    "app_cold": true,
    "camera_cache_state": "warm_sequence",
    "camera_cache_evidence": null
  },
  "scenario": "capture_latency",
  "conditions": {
    "requested": { "zsl": "on", "trigger": "off", "fmt": "jpeg", "res": "4000x3000" },
    "effective": { "zsl": "unknown", "trigger": "off", "fmt": "jpeg", "res": "4000x3000" }
  },
  "timestamp_source": "REALTIME",
  "samples_ms": [612, 598, 640, 587, 601, 655, 593, 610, 599],
  "excluded_warmup_ms": [812],
  "stats_ms": { "p50": 601, "p95": 655, "min": 587, "max": 655, "n": 9 },
  "not_measurable": [],
  "events": []
}
```

위 수치는 **사용자 원문에 있던 설명용 값이며 실측 결과가 아니다**. events가 비어 있는 이 예시는 구조 발췌이며 실제 run 파일은 통계를 재계산할 수 있는 전체 이벤트를 담아야 한다. cold 여부와 환경 누락은 null + 사유로 나타낸다.

- `not_measurable`에는 측정하지 않은 지표 id와 사유를 넣는다. 예: `[{"id": "2.1", "reason": "SENSOR_INFO_TIMESTAMP_SOURCE=UNKNOWN"}]`.
- 시각 원본(각 콜백의 elapsedRealtimeNanos)은 별도 `events` 배열로 같은 파일에 넣는다. 통계는 원본에서 PC가 다시 계산할 수 있어야 한다.

## 5. CTS PerformanceTest와의 대응

`android.hardware.camera2.cts.PerformanceTest`와 비교할 때 이름만 맞추지 않고 endpoint, stream 구성, 3A, 반복·제출 정책을 일치시킨다. 아래는 검토한 CTS 원본에 대한 대응 수준이며, 동등성 인증이 아니다 [S3].

| CTS | 이 표 |
|---|---|
| `testCameraLaunch` | 1.1/1.2 개념 대응. CTS는 동반 YUV ImageReader를 preview 대리 관측하므로 Surface 기준 1.4/1.6 및 started callback 1.3과 직접 동일하지 않음 |
| `testSingleCapture` | 2.2/2.3에 근접. request build 포함 여부, 크기, 3A, 결과·이미지 callback 기준과 clock을 맞춰 비교 |
| `testMultipleCapture` | CTS는 queue-empty 기반 제출과 still 센서 간격. 원문 2.5의 image-received 제출 간격과 다름. 별도 CTS 비교 정책이 필요 |
| 없음 | 2.1, 2.4, 2.6, 2.7, 3.x 전부 |

원시 endpoint와 조건이 맞기 전 `cts_comparison_status=not_validated`로 기록한다. 단위, warm-up 제외, percentile 방식까지 일치시킨 비교 run만 직접 비교한다. 고정 CTS revision은 실기기 비교 전에 선정한다.

## 6. MVP 포함 여부

| 포함 | 지표 |
|---|---|
| MVP | 1.1, 1.2, 1.3, 1.4, 1.6, 2.2, 2.3, 2.5, 3.1, 3.2, 3.4 |
| 다음 | 1.5, 1.7, 2.1, 2.4, 2.6, 2.7, 3.5, 3.6, 3.7 |
| 이후 | 3.3 (`MediaCodec` 전환 필요) |

## 미결 사항 — 아래 값은 기본값 제안이며 사용자 확정 아님

- [ ] preview: `1920x1080` 고정 제안. 해당 stream 조합 미지원 시 명시적으로 중단하고 다른 크기 run을 별도 선택. 자동 fallback 없음
- [ ] 2.5: `preview=on` 기본 제안. off는 별도 조건 run
- [ ] `res`: `가로x세로` 통일 제안. MP 표기는 화면 보조값만
- [ ] 2.5: 10장/8개 유효 간격 유지 또는 11장/9개 유효 간격 선택
- [ ] 1.4: 실제 SurfaceTexture listener 소유 구조 적용. 현재 TextureView 프리뷰를 해당 계측으로 오인하지 않음
- [ ] 3.1: recorder start→새 녹화 repeating 제출 순서의 장치 호환성과 측정 이름 확정


## 출처와 해석

공식 문서는 API callback과 lifecycle의 근거다. 표본 수 계산, 이름 변경, 정상 간격·복귀 판정과 기본값은 이 문서의 설계 제안이다.

- [S1 · CameraCaptureSession.CaptureCallback — callback과 exposure timestamp](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.CaptureCallback#onCaptureStarted(android.hardware.camera2.CameraCaptureSession,android.hardware.camera2.CaptureRequest,long,long))
- [S2 · SurfaceTexture — frame available callback](https://developer.android.com/reference/android/graphics/SurfaceTexture)
- [S3 · AOSP CTS PerformanceTest](https://android.googlesource.com/platform/cts/+/master/tests/camera/src/android/hardware/camera2/cts/PerformanceTest.java)
- [S4 · MediaRecorder start/stop](https://developer.android.com/reference/android/media/MediaRecorder)
- [S5 · CaptureResult SENSOR_FRAME_DURATION](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_FRAME_DURATION)
- [시계 비교 조건 · SENSOR_INFO_TIMESTAMP_SOURCE](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)
