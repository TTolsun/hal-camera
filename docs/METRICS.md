# Camera Doctor — 1차 MVP 지표 정의서 v0.1

상태: **중간 산출물 / 검토용 정의안** · 2026-09-08  
대상: Camera2 단일 엔진 · First preview / Shot-to-shot / Recording performance  
범위: 조건을 고정한 반복 측정 → run JSON → PC 분석. 앱 내 그래프·CameraX A/B·Flight Recorder·AI는 후속 범위.

## 공통 측정 계약

- 앱 시각은 `SystemClock.elapsedRealtimeNanos()`로 수집한다. JSON에서는 나노초 값을 10진 문자열로 저장한다. 벽시계는 파일 식별과 기록 날짜에만 쓴다.
- API 시작점은 **호출 직전**, 콜백 종료점은 **콜백 진입 직후**로 찍는다. UI 갱신·JSON 변환·이미지 복사보다 먼저 기록한다.
- `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`일 때만 센서 시각과 앱 시각을 빼서 계산한다. UNKNOWN이면 해당 값은 `null`, 상태는 `unavailable`, 사유는 `sensor_clock_unknown`이다. 같은 센서 시계 안에서 프레임 간격을 구하는 것은 별개다.
- `cameraId + sessionId + requestId + frameNumber`로 요청/결과를 식별하고, 이미지에는 세션 내 `Image.timestamp == SENSOR_TIMESTAMP` 대응을 확인한다. 일치하지 않는 이미지를 FIFO 순서만으로 같은 촬영에 붙이지 않는다.
- 콜백 관측 시간에는 프레임워크 처리·스케줄링·앱 콜백 전달 시간이 포함된다. `HAL open`, `JPEG processing` 같은 내부 구간 이름으로 보고하지 않는다.
- 미지원·타임아웃·취소는 0 ms가 아니다. `status`, `reason`과 원시 타임스탬프를 남기고 통계에서 제외한다. 제외 횟수도 보고한다.

## 1. First preview

| JSON 지표 | 시작 | 종료 | 해석 / 조건 |
|---|---|---|---|
| `camera_open_callback_ms` | `openCamera()` 직전 | 해당 `onOpened()` 진입 | 앱이 관측한 camera open 지연 |
| `session_configure_callback_ms` | `createCaptureSession()` 직전 | 해당 `onConfigured()` 진입 | stream 구성 + 콜백 전달 |
| `repeat_to_first_started_callback_ms` | 첫 `setRepeatingRequest()` 직전 | 그 요청의 첫 `onCaptureStarted()` 진입 | 콜백 기준이며 센서 시각 기준과 별도 |
| `repeat_to_first_sensor_ms` | 첫 repeating 호출 직전 | 첫 프레임의 센서 timestamp | REALTIME인 경우만 유효 |
| `repeat_to_first_preview_surface_ms` | 첫 repeating 호출 직전 | preview SurfaceTexture의 첫 `OnFrameAvailable` 진입 | 실제 listener를 소유할 때만 제공. TextureView 업데이트 시각으로 대체하지 않음 |
| `launch_to_first_yuv_ms` | `openCamera()` 직전 | preview 동반 YUV ImageReader의 첫 이미지 콜백 진입 | **MVP의 기본 launch 수치**. CTS와 같은 YUV 대리 관측 방식 |
| `launch_to_first_preview_surface_ms` | `openCamera()` 직전 | preview의 첫 `OnFrameAvailable` 진입 | YUV 대리값과 다른 지표. 구현되지 않으면 unavailable |

`OnFrameAvailable`과 YUV 이미지 콜백 모두 화면에 실제 표시된 시각은 아니다. 화면 표시 시점은 향후 SurfaceFlinger/Perfetto에서 분석한다. 앱 프로세스 시작 시간은 이 표에 포함하지 않는다.

독립 구간 사이의 앱 실행 시간이 있으므로 위 지표를 합쳐 launch 값을 재구성하지 않는다. 전체 시작·종료를 직접 뺀다. 기본 출력은 preview + YUV이며 두 스트림의 실제 크기를 기록한다.

## 2. Shot-to-shot

| JSON 지표 | 시작 | 종료 | 해석 / 조건 |
|---|---|---|---|
| `shutter_lag_ms` | 수동 버튼 핸들러 진입 또는 자동 촬영 의도 생성 시각 | 해당 still의 `SENSOR_TIMESTAMP` | REALTIME 전용. `trigger_origin`을 manual/automatic으로 구분. ZSL에서는 음수 가능 |
| `capture_to_image_ms` | 해당 `capture()` 호출 직전 | 대응 still ImageReader 콜백 진입 | JPEG/YUV를 구분. 획득·소비 완료 시각도 따로 저장 |
| `capture_to_result_ms` | 해당 capture 직전 | 대응 `onCaptureCompleted()` 진입 | 이미지 지연과 별개 |
| `submit_to_queue_empty_ms` | capture N 직전 | 해당 제출 이후 capture queue empty 알림 | 다음 제출 정책의 관측 신호. HAL의 유일한 '촬영 가능' 시점이라는 뜻은 아님 |
| `shot_to_shot_submit_ms` | capture N 직전 | capture N+1 직전 | 실제 연속 제출 간격. `next_shot_policy` 필수 |
| `shot_to_shot_sensor_ms` | still N 센서 시각 | still N+1 센서 시각 | 같은 센서 시계의 연속 still 간격. CTS multiple capture 비교용 보조 지표 |
| `preview_recovery_ms` | capture N 직전 | 정상 preview 간격 5개가 연속 확인된 마지막 결과 콜백 진입 | 아래의 복귀 규칙 적용. 노출 변경과 프레임 손실을 혼동하지 않음 |

**조건 키:** camera id, format, 실제 capture/preview/YUV 크기, 요청/관측 FPS 범위, ZSL 요청값·확인 가능한 유효값, AE/AF precapture 수행 여부, AF mode, AE lock, flash, next-shot policy, warm/cold provenance.

**기본 조건 제안:** ZSL off 요청, AE/AF precapture trigger 없음, JPEG, preview + YUV + still. YUV still은 별도 run. 지원되지 않는 키는 `unsupported`, 결과로 확인할 수 없는 유효값은 `unknown`으로 남긴다. 미지원 stream 조합을 몰래 다른 크기로 바꾸지 않는다.

**다음 촬영 정책:** `queue_empty`는 큐 비움 콜백 후 다음 capture를 제출한다. `image_consumed`는 대응 이미지를 획득하고 닫은 다음 제출하는 직렬 모드다. 두 정책의 통계를 섞지 않는다. 콜백과 submit 시각을 모두 남겨 앱 지연을 분리한다.

**Preview 복귀 규칙:** 촬영 전 유효 preview 센서 간격 30개의 중앙값을 기준으로 고정하고, 이후 preview 간격이 그 기준의 ±20% 안에 5개 연속 들어오면 복귀 확인으로 기록한다. 3초 내 미복귀는 timeout. 기준 30개 미확보, 센서 시각 역행, 노출/FPS 정책 변경은 unavailable 또는 조건 변경으로 기록한다. 이 임계값은 제품 규칙이며 CTS 정의가 아니다.

## 3. Recording performance

| JSON 지표 | 시작 | 종료 / 집계 | 해석 / 조건 |
|---|---|---|---|
| `record_start_to_first_encoded_frame_ms` | 앱 녹화 시작 명령 진입 | 첫 유효 encoded media frame output 콜백 진입 | 인코더 생성·구성 여부를 `encoder_prepared`로 기록. codec config/EOS-only buffer 제외 |
| `codec_start_to_first_encoded_frame_ms` | `MediaCodec.start()` 직전 | 첫 유효 encoded frame 콜백 진입 | 위 지표와 별개. 카메라 입력 시작도 원시 이벤트로 기록 |
| `sensor_interval_anomaly_count` | 녹화 대상으로 제출한 요청의 결과열 | 센서 Δt가 직전 유효 `SENSOR_FRAME_DURATION`의 1.5배를 초과한 횟수 | **드롭 확정 개수가 아님**. frame duration 누락·0·역행이면 판정 제외 |
| `camera_record_result_count` | 녹화용 request 최초 제출 | 해당 요청의 마지막 완료 결과까지 | preview 전용 결과 제외. session/request 범위로 집계 |
| `encoded_frame_count` | 인코더 출력 시작 | EOS drain 완료 | codec config 제외, 정상 media frame 기준. partial frame은 조립 후 1회 집계 |
| `record_stop_to_finalize_ms` | 앱 stop 명령 진입 | EOS drain, muxer stop/release, 파일 닫기가 모두 끝난 시각 | 디스크 영속 flush 완료를 보장하는 지표는 아님 |
| `stability_1s_bins` | 녹화 시작 | 매 1초 구간 | 카메라 결과 수·인코딩 수·센서 간격 분포·thermal·앱 CPU를 각각 저장 |

카메라 결과 수에서 인코더 출력 수를 바로 빼 HAL/encoder drop으로 확정하지 않는다. 시작/종료 경계, timestamp 매핑, EOS drain, 중복·부분 출력부터 검증한다. 확인되지 않은 차이는 `pipeline_count_difference`로 기록하며 원인층은 `unattributed`다.

기본 녹화는 AVC, 영상만, 10초. bitrate, profile/level, codec name, 크기, 요청 FPS와 실제 output format을 저장한다. 600초 안정성은 별도 시나리오 옵션으로 한 번 실행하며, 1초 bin들을 반복 run인 것처럼 p50/p95 표본으로 사용하지 않는다.

## 반복·통계·환경

- 짧은 시나리오는 기본 **총 10회**, 첫 번째를 warm-up으로 표시하고 집계 제외 → 성공 시 **9개 표본**. 첫 회도 원시 JSON에서 보존한다. 실패 시 자동 재시도로 표본을 채우지 않는다.
- p50/p95는 nearest-rank `sorted[ceil(p*n)-1]`. 유효 n=9일 때 p95는 최대값이다. n, 원시 반복값, warm-up 제외, 실패·취소 횟수를 함께 내보낸다. n=0은 null.
- cold는 단순 앱 재시작으로 판정하지 않는다. 부팅 후 다른 앱의 카메라 사용 여부를 일반 앱이 보증할 수 없으므로 `cold_requested_unverified`와 검증 가능한 외부 실험 기록을 구분한다. 기본은 `warm_sequence`다.
- 매 반복 전후 thermal을 수집하고 장시간 녹화는 1초마다 기록한다. API 29 미만은 unavailable. 발열 조건이 다른 run을 같은 기준선으로 묶지 않는다.
- governor는 접근 가능한 경우에만 CPU policy별 값과 수집 경로를 기록한다. 권한 부족은 `unavailable: permission_denied`; 값을 추정하지 않는다. Engineering mode에서 ADB로 보완한다.
- 카메라 close 완료를 기다린 후 다음 open. timeout은 open/configure/first-image 5초, still 5초, finalize 10초의 제안 기본값이며 JSON에 저장한다.
- 표본을 임의 제거하는 outlier 필터는 기본 비활성. 예열 시간·반복 사이 휴지 시간·지원 크기 선정 규칙도 조건에 포함한다.

## run JSON 계약

한 번의 사용자 실행을 `run`이라고 부르고, 그 안에 반복 10개를 `iterations[]`로 저장한다. 장시간 녹화는 iteration 1개다.

```json
{
  "schema_version": "0.1-draft",
  "artifact_kind": "schema_example_not_measurement",
  "run_id": null,
  "metric_definition_version": "0.1",
  "source_commit": null,
  "scenario": "first_preview",
  "device": {"manufacturer": null, "model": null, "build_fingerprint": null, "sdk": null},
  "camera": {"id": null, "timestamp_source": null},
  "conditions": {"requested": {}, "effective": {}, "cache_state": "warm_sequence"},
  "iterations": [],
  "summary": {"requested_n": 10, "warmup_excluded_n": 1, "valid_n": 0, "failed_n": 0},
  "cts_reference": {"revision": null, "comparison_status": "not_validated"}
}
```

각 iteration은 status/reason, 환경 시작·끝, 단계별 원시 이벤트, 지표별 `{value_ms, status, reason}`, recording의 1초 bin과 원시 count를 가진다. 요청 조건과 실측 적용 조건이 다르면 명시하며, 서로 다른 조건을 합산하지 않는다.

각 단계는 `android.os.Trace`에도 run/iteration/request id와 함께 기록한다. 단일 thread의 begin/end와 비동기 구간을 구분한다. trace marker를 넣는 것과 Perfetto 세션을 실제 수집하는 것은 별개이며, 후자는 외부 collector가 수행한다.

## CTS 대조 결과와 비교 제한

2026-09-08에 확인한 AOSP CTS `PerformanceTest.java`는 launch에 preview 동반 YUV를 대리 관측하고, 반복 수는 10이다. single capture는 이미지·결과 도착 지연을 별도로 다루며, multiple capture는 큐 비움에 따른 제출과 센서 간격을 사용한다. 본 앱은 이를 참고하되 콜백 진입 시각과 CTS blocking helper 반환 시각, 준비 비용, 크기·3A 조건, warm-up 제외와 요약 통계가 달라 **그대로 동등하다고 주장하지 않는다**. 실제 대조 run과 고정 CTS revision 확보 전에는 `comparison_status=not_validated`다.

원본 파일을 통째로 재배포하지 않고, 대조에 사용한 로컬 사본의 SHA-256만 기록한다: `2f4059a0aeb5b81dfc22e25400ae15866e27ee194b8ad8c0e676d8dab7991de0` (UTF-8 저장 사본).

참고 자료:

- [AOSP CTS PerformanceTest](https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/camera/src/android/hardware/camera2/cts/PerformanceTest.java)
- [CameraCharacteristics timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)
- [SurfaceTexture callback](https://developer.android.com/reference/android/graphics/SurfaceTexture#setOnFrameAvailableListener(android.graphics.SurfaceTexture.OnFrameAvailableListener,android.os.Handler))
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)

## 다음 검토점

1. 기본 launch를 CTS와 같은 YUV 대리 관측으로 우선 구현하고, preview Surface 도착은 확장 지표로 둘지.
2. Shot-to-shot 기본 정책을 `queue_empty`로 둘지, `image_consumed`로 둘지.
3. 10회 중 첫 회 제외(9개 집계), preview 복귀 ±20%/5개, timeout 기본값을 제품 기준으로 채택할지.

이 문서가 이번 체크포인트의 우선 검토 대상이다. 새 계획의 세 시나리오 구현은 아직 시작하지 않았다.
