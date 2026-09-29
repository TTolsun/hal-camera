# Launch 시스템 추적 결과 (2026-09-30, #204)

느린 모드의 대기 구간을 카메라 서비스의 재설정과 HAL의 첫 촬영 알림 이전 구간으로 좁혔다. **빠른 모드는 이번에 수집되지 않아 모드 전환의 근본 원인은 여전히 미확정이다. 성능 수정이나 공통 규칙 변경은 하지 않았다.**

아래에서 `[SQL]`은 추적·보고서로 확인한 값, `[추정]`은 그 값에 대한 해석, `[미확인]`은 현재 자료로 확인하지 못한 부분을 뜻한다.

## 수집 조건과 결과

- Samsung SM-S936N(S25+), Android 16/API 36, `BP4A.251205.006.S936NKSSCCZH2`에서 측정했다. 앱은 이전 조사와 같은 `ca2c709` release APK이며 SHA-256은 `397210aa48d828e21d980c6bbc29ad0a6dd91c78825df220ce6a42644a88f07d`이다.
- camera 0, `camera2-standard-v2`의 크기·FPS·반복 횟수를 유지했다. 00:12:55–00:22:33 KST에 화면으로 실행했다. 추적 4회 뒤 무추적 대조 1회를 수행했다. 각 실행에서 warmup 1개를 뺀 9개 사이클을 분석했다.
- USB 충전, 배터리 49–52%, 절전 모드 꺼짐, thermal start/max/end 0이었다. 모두 측정·비교 적격이며 `CHARGING` 때문에 점수 부적격이다. 화면의 장면은 매우 어두웠고 조도를 측정하지 않았다. 이전 직물 장면 실측과 같은 조건으로 합산하지 않는다.
- 수집은 [공식 record_android_trace](https://github.com/google/perfetto/blob/main/tools/record_android_trace)와 [launch.pftxt](../../tools/perfetto/launch.pftxt)를 사용했다. 기기 Perfetto는 v51.2, PC Trace Processor는 v58.2였다. 실행 상태는 약 15초 간격으로 확인했다.
- 모든 수집이 끝난 뒤 보고서를 회수하고 설치 전 APK로 복원했다. 설치 전 APK SHA-256은 `d23d08c0877ee0660d3a16cf61968206dfb07d59d79592b1ea2225309d3b9f61`이며 데이터와 baseline은 유지했다.

| run ID | 추적 | 빠름 / 느림 | open 중앙값(ms) | first started 중앙값(ms) | reconfigureCamera 중앙값(ms) |
| --- | --- | ---: | ---: | ---: | ---: |
| 20260930-001255-599 | 켰다. | 0 / 9 | 12.82 | 264.88 | 155.02 |
| 20260930-001516-028 | 켰다. | 0 / 9 | 12.40 | 268.22 | 155.06 |
| 20260930-001715-071 | 켰다. | 0 / 9 | 12.31 | 265.38 | 156.05 |
| 20260930-001914-085 | 켰다. | 0 / 9 | 12.48 | 264.63 | 145.62 |
| 20260930-002109-024 | 껐다. | 0 / 9 | 10.74 | 258.43 | 수집하지 않았다. |

`[SQL]` 추적을 켠 36개와 끈 9개가 모두 탐색 경계 220ms 이상의 느린 모드였다. `[미확인]` 순서를 무작위화하지 않았고 대조는 한 번뿐이므로 중앙값 차이를 추적 비용으로 확정하지 않는다. 다만 추적을 끈 상태에서도 느린 모드가 유지되었다.

`[SQL]` 추적 4개에서 BOOTTIME(clock ID 6)의 `ts-clock_value`는 모두 0이었다. 보고서의 `elapsedRealtimeNanos`와 직접 대조했고, 유효 사이클의 open/configure/first_started 108개 구간 모두 추적 범위 안에 있었다. 비영 데이터 유실·overrun 지표는 없었다. `config_write_into_file_no_flush=1` 경고가 있어 분석기가 추적 전체를 메모리에 올렸다. 이 경고 자체를 이벤트 유실로 해석하지 않는다.

## 대표 사이클의 지연 경계

`[SQL]` 첫 run의 iteration 1은 `repeating_call=54691883270612`, `first_started=54692133570924`이고 차이는 **250.300312ms**이다. 같은 BOOTTIME 시계에서 다음 경계로 분해했다.

| 구간 | 시간(ms) | 근거 |
| --- | ---: | --- |
| 앱 repeating_call → cameraserver AIDL 요청 | 1.365520 | 서비스 요청은 54691884636132ns에 시작했다. |
| 서비스 요청 → 재설정 시작 | 0.687188 | `reconfigureCamera`는 54691885323320ns에 시작했다. |
| 카메라 재설정 | 145.152031 | cameraserver `C3Dev-0-ReqQueu`, UTID 11171의 slice 11651이다. |
| 재설정 종료 → 첫 HAL 요청 | 0.494375 | `AidlCameraDeviceSession::FirstRequest` 진입 전 구간이다. |
| 첫 HAL 요청 처리 | 3.926823 | vendor provider UTID 1799의 slice 12751이다. |
| 첫 HAL 요청 반환 → vendor 촬영 알림 호출 | 98.300052 | 반환 54692034896549ns → UTID 1801의 알림 54692133196601ns이다. |
| vendor 알림 → 앱 Binder 콜백 | 0.171250 | 앱 UTID 7552의 콜백은 54692133367851ns에 시작했다. |
| 앱 Binder 콜백 → first_started 기록 | 0.203073 | 보고서의 이벤트 기록까지의 시간이다. |

`[SQL]` 의존 경로는 앱 요청 → cameraserver `updateSessionParameters` → `reconfigureCamera` → configureStreams Binder → Samsung vendor provider의 `[CAMKPI] camera3->configure_streams`이다. HAL configure는 139.775782ms이며 위 재설정 145.152031ms 안에 포함된다. 두 시간을 합산하지 않는다.

`[SQL]` 그 HAL configure 스레드의 상태는 Running 77.794793ms, Sleeping 54.809742ms, uninterruptible sleep(D) 5.298694ms, Runnable 1.872553ms였다. `[미확인]` 세부 vendor 함수·stack과 충분한 wake 의존 정보가 없어 실제 CPU 실행 코드와 각 대기의 대상을 특정할 수 없다.

`[SQL]` 첫 요청 반환 뒤 vendor 알림까지 98.300052ms가 있고, 알림을 앱으로 전달하는 시간은 0.171250ms이다. `[추정]` 따라서 이 표본의 긴 대기는 주로 앱 콜백이 오기 전 카메라 서비스·HAL 측에 있다. `[미확인]` 센서 stream-on·SOF 등의 세부 이벤트를 확보하지 못해 98.3ms를 특정 센서 동작이나 내부 파이프라인 단계로 확정하지 않는다.

`[SQL]` 다섯 번째 HAL 요청에는 110.720469ms의 긴 대기가 있지만 첫 촬영 알림 뒤까지 이어진다. `[추정]` 같은 파이프라인 진행을 기다리는 동시 관측일 수 있으며 첫 콜백 지연의 독립 원인으로 합산하면 안 된다.

## CPU 및 앱 대기 가설

`[SQL]` 추적 36개 사이클의 first_started 구간에서 기록된 CPU frequency counter는 CPU 0–5가 3072000kHz, CPU 6–7이 3513600kHz로 일정했다. 앱 main과 `CD.Camera2`의 Runnable 시간을 합해도 사이클당 0.328–3.509ms였다. 이 합계는 두 스레드의 시간이므로 wall time과 같지는 않다.

`[추정]` 관측한 약 250ms를 앱 main/camera 스레드가 CPU를 약 100ms 기다렸다는 가설로 설명하기 어렵다. `[미확인]` 이 결과는 다른 앱 스레드나 HAL 스레드의 스케줄링 영향 전체를 배제하지 않으며, 보고된 CPU frequency는 실리콘의 실제 순간 성능을 보장하지 않는다.

## 후속 실험의 구체적인 후보

현재 `Camera2Engine`은 `createCaptureSessionByOutputConfigurations`로 세션을 만들고, 첫 repeating 요청에서 profile의 `[30,30]` FPS를 설정한다. 초기 session parameters는 전달하지 않는다. Android의 [CONTROL_AE_TARGET_FPS_RANGE 설명](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE)은 template 기본값과 다른 FPS로 시작할 때 `SessionConfiguration.setSessionParameters`를 권장한다.

`[추정]` **초기 session parameters에 같은 FPS를 넣는 A/B 실험**은 관측한 재설정을 줄일 수 있는 구체적인 후보이다. `[미확인]` 이번 자료로 변경된 session key가 FPS인지, 빠른 모드에서도 재설정이 발생하는지, 이 변경이 총 시작 시간을 줄이는지는 검증하지 않았다. 재설정 시간이 초기 configure로 이동할 수도 있다.

이 실험에서는 first_started뿐 아니라 configure와 전체 preview 시작 시간, profile 충족 여부를 함께 비교해야 한다. 벤치마크 호출 순서가 달라지면 비교 계약과 기존 baseline의 호환성도 검토해야 한다. 따라서 이번 진단 PR에 성능 수정으로 섞지 않는다. #204는 배제한 설명과 관측 한계를 기록하는 조사 범위로 완료하며, 원인 규명이나 성능 개선 완료와 구분한다.

## 자료와 재현

[보고서 발췌와 추적 집계](launch-trace-20260930.json)에 원본 report SHA-256, 각 trace SHA-256, 환경, launch raw, 사이클별 재설정 시간과 앱 Runnable 시간, CPU counter 범위를 보존했다. 전체 run의 import용 파일은 아니다. 추적 파일 4개(각 약 101–108MB)는 다른 프로세스의 실행 이력을 포함하므로 저장소 밖 `reviews/issue204/perfetto/`에 보관했다.

```sh
node tools/analyze-launch-diagnostics.mjs docs/validation/launch-trace-20260930.json
node tools/perfetto/launch-windows.mjs docs/validation/launch-trace-20260930.json > windows.sql
```

추적을 로드한 같은 Trace Processor 세션에서 `windows.sql`, [공통 분석 SQL](../../tools/perfetto/launch-analysis.sql)을 순서대로 실행한다. 대표 사이클의 상세 경계는 [고정 자료용 SQL](launch-trace-20260930.sql)로 재조회한다. 상세 수집·분석 순서는 [도구 안내](../../tools/perfetto/README.md)를 따른다.

이번 추적 단계의 변경은 PC 수집·분석 도구와 실측 기록에 한정하며 수집 APK는 이전 실측과 같다. 분석기 테스트 4개와 실제 trace SQL 실행을 확인했다. 최종 문서 검토와 조사 완료 범위는 [launch 진단 기록](launch-diagnostics.md#코드-검증과-조사-완료-범위)에 정리했다.
