# Launch 모드 전환 진단 (#204)

S25+ 정상 반복 6회에서 빠른 사이클 10개와 느린 사이클 44개, 실행 안의 느림→빠름 전환 1회를 확인했다. 열기 전 CPU 보고 주파수가 상승한 직후 전환한 사례는 있었지만, 같은 높은 주파수에서도 느린 사이클이 36개였다. thermal 상태는 모두 0이었다. 따라서 이 두 스냅샷 값만으로 모드를 구분하는 7.5 조건을 추가하지 않는다. CPU 스케줄링이나 HAL 내부 동작을 포함한 근본 원인은 미확정이다.

## 계측 계약

후속 [2026-09-30 시스템 추적](launch-trace-20260930.md)에서는 느린 모드의 대기를 HAL 재설정과 첫 촬영 알림 이전 구간으로 좁혔다. 빠른 추적은 확보하지 못해 모드 전환의 원인은 여전히 미확정이다.

각 `raw.launch_cycles[]`의 `diagnostics`에 `before_open`, `after_close`, `close_completed`를 기록한다. 전자는 이전 카메라의 닫기가 완료된 뒤 다음 `open_call` 직전에 수집하고, 후자는 사이클의 닫기 처리 이후에 수집한다. 닫기 타임아웃이면 `close_completed`가 `false`이므로 닫기 완료 표본으로 해석하지 않는다. warmup과 실패한 사이클에도 진단 정보를 보존한다. 관측 세션은 launch 표본에 포함하지 않는다.

각 스냅샷은 다음 필드를 가진다.

| 필드 | 의미 |
| --- | --- |
| `start_ns`, `end_ns` | `elapsedRealtimeNanos` 기준 수집 시작·종료 시각이다. JSON 정밀도를 보존하기 위해 문자열로 쓴다. |
| `thermal_status` | `PowerManager.currentThermalStatus` 값이며 API 미지원·조회 실패는 `null`이다. |
| `cpu_status` | 주파수를 모두 읽으면 `available`, 일부만 읽으면 `partial`, 하나도 읽지 못하면 `unavailable`이다. |
| `cpu_policies` | `/sys/devices/system/cpu/cpufreq/policy*`별 관측이다. |
| `policy`, `related_cpus` | 정책 이름과 소속 CPU 목록이다. 코어 번호만으로 big/little을 추정하지 않는다. |
| `scaling_cur_freq_khz` | 해당 정책의 sysfs 보고 주파수이며 단위는 kHz이다. 실패·비정상 값은 `null`이다. 실제 하드웨어 클럭의 연속 측정값을 뜻하지 않는다. |

진단 정보는 schema 5의 선택적인 raw 확장이다. 지표, validity, baseline 판정, comparison contract의 입력에는 추가하지 않는다. 진단 조회 예외 때문에 카메라 측정이 실패하지 않으며, 지원되지 않는 클럭을 0으로 대체하지 않는다. 기존 보고서의 진단 필드가 없어도 그대로 읽는다.

스냅샷 조회 시간은 launch 지연의 측정 구간 밖에 있지만 사이클 간 간격과 CPU 상태 자체에 영향을 줄 수 있다. `end_ns - start_ns`로 조회 비용을 확인해야 한다. 두 시점의 표본으로 open/configure/repeating 중간의 순간적인 클럭 변화나 HAL 내부 상태를 배제할 수 없다.

`scaling_cur_freq`는 드라이버가 마지막으로 요청한 성능 상태의 주파수일 수 있으며 실제 실행 클럭과 항상 같지는 않다. [Linux CPUFreq 문서](https://cdn.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html#policy-interface-in-sysfs)의 정의에 따라 결과에서는 sysfs 보고 주파수로 해석한다.

## 재현 및 분석

1. 다른 기기 작업을 중지하고 기기 모델, Android 빌드, 앱 커밋, 전원·충전 상태, 장면·조도와 기기 자세를 기록한다. 기존 측정이 삭제되지 않도록 저장 한도를 확인한다.
2. 앱의 Lab → Benchmark에서 camera 0과 같은 profile로 정상 반복 측정을 실행한다. 벤치마크는 화면에서 시작한다. CLI 우회나 profile의 크기·반복 횟수 변경은 사용하지 않는다.
3. 각 실행의 JSON을 Export로 회수한다. 개발 검증에서는 동일 서명의 `CliStoreInstrumentation`을 `-e export_reports true`로 실행해 최근 10개 보고서의 `raw`·기기·조건·원본 SHA-256을 포함한 요약을 얻을 수도 있다. `-e probe_launch_diagnostics true`는 앱 UID의 읽기 권한과 스냅샷을 미리 확인한다. Instrumentation은 앱 프로세스를 재시작할 수 있으므로 반복 측정 도중에는 실행하지 않는다.
4. 별도 ADB 셸에서 `dumpsys media.camera`를 측정 전·후에 저장하고, 필요한 시간 구간의 `logcat -b main -b system -v monotonic`을 수집한다. 실행 중 반복적인 dumpsys는 부하를 만들므로 피한다. logcat의 시간 기준은 앱의 elapsedRealtimeNanos와 무조건 같다고 가정하지 말고, 수집 시각과 기기 suspend 여부를 함께 확인한다.
5. `node tools/analyze-launch-diagnostics.mjs run1.json run2.json`으로 분석한다. 이 도구는 단일 run JSON, JSON 배열, instrumentation의 `HALCAM_REPORTS=` 출력을 읽는다. warmup·실패·누락 표본은 제외하고, 연속한 사이클에서만 전환 위치를 보고한다.

빠른 모드는 #162 진단 1에서 사용한 `first_started_ms < 220`으로 분류한다. 이 경계는 탐색용이며 제품의 판정 기준이 아니다. 결과에는 모드별 CPU 정책·수집 시점의 주파수 범위와 중앙값, thermal 상태, 조회 비용, 누락 개수와 전환 양쪽의 원본 진단 정보를 포함한다. 서로 다른 기기·profile·조건의 자료는 한 집합으로 합치지 않는다. 전환과 동반 변화가 관찰되더라도 인과관계는 추가 통제 측정으로 확인해야 한다.

## 2026-09-29 사전 확인

- 기기는 Samsung SM-S936N(S25+), Android 16/API 36이다.
- ADB shell에서 `policy0`의 `related_cpus=0 1 2 3 4 5`, `policy6`의 `related_cpus=6 7`과 `scaling_cur_freq`를 읽었다. 이 권한은 앱 UID에서의 읽기 가능성을 보장하지 않는다.
- `dumpsys media.camera`에서 CONNECT/DISCONNECT/EVICT/DENIED 이벤트와 현재 클라이언트가 보였다. logcat에서 CamX/ChiX 공급업체 로그가 보였다. kernel 로그 접근은 거부되었다.
- 첫 측정 도중 앱이 Live 화면으로 바뀌어 반복 실측을 중지했다. 화면 표기도 달라져 외부 기기 작업과 충돌했을 가능성이 있다. 이 실행은 정상 표본으로 사용하지 않는다.

이 사전 확인 뒤 기기를 사용할 수 있을 때 아래 반복 측정을 수행했다. 중단된 첫 시도는 아래 집계에서 제외했다.

기존 기록을 보호하기 위해 기기의 Benchmark 저장 한도를 10개에서 무제한으로 늘렸다. 기존 자료를 자동 삭제할 수 있으므로 실측 뒤에도 이 상한을 유지했다. 측정 자료를 회수한 뒤 검증 전 설치 APK를 `adb install -r`로 복원했으며 앱 데이터와 baseline은 유지했다.

## 2026-09-29 정상 반복 측정

### 조건과 자료

- SM-S936N, Android 16/API 36, 빌드 `BP4A.251205.006.S936NKSSCCZH2`, 빌드 증분 `S936NKSSCCZH2`에서 측정했다.
- 앱은 `ca2c709`의 release 0.16.0(593)이며 APK SHA-256은 `397210aa48d828e21d980c6bbc29ad0a6dd91c78825df220ce6a42644a88f07d`이다. 이후 변경은 instrumentation 보고서 회수·문서에 한정했다.
- camera 0, `camera2-standard-v2`, 1080p30 warm reopen을 사용했다. profile과 스트림 크기를 바꾸지 않고 화면에서 6회를 실행했다. 각 실행은 launch 10회 중 첫 사이클을 warmup으로 제외하므로 분석 표본은 54개다.
- USB 충전, 배터리 39–41%, 절전 모드 꺼짐, thermal 0 상태였다. 화면에서 보이는 고정된 직물 장면을 유지했으며 조도계 측정은 하지 않았다. 카메라 경쟁이나 부하를 의도적으로 주입하지 않았다. 모든 run은 measurement/comparison 적격이고 `CHARGING` 때문에 scoring 부적격이다.
- 23:45:53–23:54:47 KST에 수집했다. UI 완료 여부를 약 15초 간격으로 확인했고 실행 중 반복적인 dumpsys는 하지 않았다. logcat은 `CameraService`, `Camera3-Device`, `CameraProviderManager`, `CamX`, `ChiX` 태그를 수집했다. 로그 수집 자체의 영향은 별도 대조 실험으로 분리하지 않았다.

[사이클 자료](launch-diagnostics-20260929.json)는 각 run의 조건과 launch raw를 보존한 분석용 발췌이며, 원본 run 파일의 SHA-256을 포함한다. 전체 이벤트와 설치 식별자는 포함하지 않으므로 앱에 가져올 전체 보고서는 아니다. 분석은 다음 명령으로 재현한다.

```sh
node tools/analyze-launch-diagnostics.mjs docs/validation/launch-diagnostics-20260929.json
```

### 실행별 결과

| run ID | 빠른 / 느린 사이클 | open 중앙값(ms) | first started 중앙값(ms) | 실행 안 전환 |
| --- | ---: | ---: | ---: | --- |
| 20260929-234553-098 | 9 / 0 | 5.12 | 168.33 | 없었다. |
| 20260929-234745-906 | 0 / 9 | 11.22 | 266.71 | 없었다. |
| 20260929-234911-080 | 0 / 9 | 11.92 | 264.94 | 없었다. |
| 20260929-235038-859 | 0 / 9 | 11.18 | 268.62 | 없었다. |
| 20260929-235206-481 | 1 / 8 | 10.57 | 259.00 | iteration 8→9에서 느림→빠름으로 바뀌었다. |
| 20260929-235334-587 | 0 / 9 | 11.58 | 267.11 | 없었다. |

첫 실행은 빠르고 두 번째 실행은 느리므로 실행 사이에는 반대 방향 변화도 있다. 실행 안에서 관찰한 전환 1회와 구분한다. 이 표의 iteration은 JSON과 같은 0 기반 번호다.

### CPU·thermal과의 관계

| 모드 | 열기 전 policy0/policy6 주파수(MHz) | 사이클 수 |
| --- | --- | ---: |
| 빠름 | 3072 / 3513.6 | 10 |
| 느림 | 3072 / 3513.6 | 36 |
| 느림 | 2918.4 / 3283.2 | 8 |

두 모드에서 열기 전·닫기 후 주파수 중앙값은 각각 3072/3513.6 MHz로 같았다. 닫기 후에도 높은 주파수 조합에서 빠른 표본 10개와 느린 표본 37개가 함께 나왔다. 따라서 이 스냅샷의 높은 주파수 조합은 빠른 모드의 충분조건이 아니다. 낮은 조합의 빠른 표본은 없지만, 빠른 표본 10개로 필요조건이나 일반 임계값을 확정하지 않는다.

전환한 run의 iteration 8은 열기 전 2918.4/3283.2 MHz였고, 닫기 후에는 3072/3513.6 MHz였다. iteration 9는 두 시점 모두 높은 조합이었다. open은 11.334→4.873 ms, first started는 262.921→192.396 ms로 짧아졌다. 주파수 상승과 전환의 동반 관측은 있지만, 다른 느린 표본들이 같은 높은 값을 보이므로 이것만으로 인과관계를 확정할 수 없다.

모든 run의 thermal start/max/end와 120개 스냅샷의 thermal 값은 0이었다. 따라서 이번 전환은 framework thermal 단계 변화 없이 발생했다. 실제 온도·전력 제약이 같았다는 뜻은 아니다.

CPU 조회 누락은 0/120이었다. 스냅샷 수집 시간은 0.137–1.706 ms였고, warmup 제외 중앙값은 빠른 모드 0.351 ms, 느린 모드 0.661 ms였다. 수집 비용도 모드별 차이가 있지만 main thread 배치·스케줄링·실행 이력 등을 계측하지 않았으므로 원인을 HAL 또는 CPU 어느 쪽으로도 확정하지 않는다.

### HAL 접근과 한계

카메라 서비스의 connect/disconnect, CamX/ChiX 세션·센서 캐시 관련 로그를 읽을 수 있었다. 측정 앱 PID는 28416으로 유지됐고, 측정 후 `dumpsys media.camera`의 active client는 비어 있었다. 로그의 `FastAECRealtime`·`SATOfflineReprocess0` 세션 이름은 빠른 표본과 느린 표본 모두에서 보였으므로 이름의 유무만으로 모드를 식별하지 않는다. 공급업체 로그에 표시된 ERROR 문자열 자체도 앱의 측정 실패를 뜻하지 않는다.

logcat은 epoch 시간으로 수집했고 시작·종료 시 기기의 wall clock과 `/proc/uptime`을 함께 읽었다. 두 시각 차이의 앵커는 1790640084.7974초와 1790640084.8014초로 약 4 ms 차이가 있었다. 이는 조회 지연·uptime 출력 정밀도를 포함하므로 정밀 동기화로 취급하지 않는다. 전환 양쪽 사이클은 대략 epoch 1790693532.987–1790693534.357 구간에서 대조했다. 이번 수집 태그에서는 단일한 HAL 성능 상태 flag나 CPU power hint를 얻지 못했다.

전체 로그와 회수 원본은 작업 폴더의 `reviews/issue204/resumed/`에 보관했다. `hal-logcat.txt`의 SHA-256은 `b368354e1da5bf0f84e79a20f58ea2babfe4709ce9f79a0f35b4537494e64765`이며, `reports-export.txt`는 `7b3954f2d9ea9edc97ab13d3b877e92a3adc060b0ec93549609007bd1f591898`이다. 이미지 픽셀은 이 보고서와 JSON 발췌에 포함하지 않았다.

### 판정과 후속 범위

이번 조사에서는 **관측한 CPU 주파수 한 쌍으로 빠름/느림을 나누는 설명**과 **framework thermal 단계 변화가 이 전환을 설명한다는 설명**을 배제한다. 순간적인 실제 클럭, CPU core 배치·스케줄링, HAL 내부 캐시·파이프라인·성능 hint의 영향은 배제하지 못했다. 관찰된 주파수 상승은 후속 가설이며 원인 규명 결과는 아니다.

7.5의 새 비교 조건과 기기 공통 임계값을 추가하지 않는다. #165의 정상 baseline 집합 비교를 유지한다. 별도의 인과 조사에서는 slow/fast 전환 구간의 scheduler·CPU frequency·camera trace를 함께 수집하고 계측을 켠 경우와 끈 경우를 대조해야 한다. 이 결론의 범위는 위 기기·펌웨어·충전 조건이며 다른 기기에 일반화하지 않는다.

## 코드 검증과 조사 완료 범위

앱 JVM 테스트 594개, `lintDebug`, `assembleRelease`, 분석기 Node 테스트 2개가 통과했다. 기존 release 서명으로 `assembleReleaseAndroidTest`도 빌드했고, 앱 UID의 sysfs 접근·120개 스냅샷 저장·6개 run 회수를 실제 기기에서 확인했다. 공개 JSON 발췌를 다시 분석해 원래 집계와 같은 두 모드의 수치가 나오는지도 확인했다.

초기 문서 검사에서는 관련 소스 변경으로 최신성 13개 항목이 재검토 대상이었다. 변경 근거를 코드와 대조하고 runtime-flow 원고와 담당 요소 바인딩을 갱신했다. 이후 사용자가 조사 결론의 검토·종료 진행을 승인하여, 승인 주체 `TTolsun`과 코드 대조 주체 `Codex`를 구분한 reviewer로 검토 기록을 남겼다. 최신 main을 통합하고 생성 페이지·사이트를 다시 만든 뒤 `docflow check --build` 6단계와 문서 회귀 테스트 23개가 통과했다. 이 승인 기록은 원인 규명이나 추가 기기 실측을 뜻하지 않는다.

#204는 원인을 찾지 못했을 때 무엇을 배제했는지 기록하는 것도 완료 조건으로 둔다. 사이클별 진단, HAL 관측 가능 범위, 정상 반복에서 모드 전환과 관측값의 관계, 배제한 설명과 남은 한계를 기록했으므로 조사 범위를 완료했다. 원인 미확정 상태에서 새 7.5 조건은 추가하지 않는다. FPS session parameters를 바꾸는 성능 실험은 별도 후속 범위이며 이번 이슈의 종료 조건이 아니다.

관련 기준은 [#204](https://github.com/TTolsun/hal-camera/issues/204)와 [벤치마크 설계](../PLAN-BenchMarker-v0.3.md)에서 확인한다.
