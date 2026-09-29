# Launch 모드 전환 진단 (#204)

사이클별 계측과 오프라인 분석을 구현했다. 정상 반복 실측은 기기 사용 일정 때문에 보류했으며, launch 모드 전환의 원인은 아직 규명하지 않았다.

## 계측 계약

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

## 재현 및 분석

1. 다른 기기 작업을 중지하고 기기 모델, Android 빌드, 앱 커밋, 전원·충전 상태, 장면·조도와 기기 자세를 기록한다. 기존 측정이 삭제되지 않도록 저장 한도를 확인한다.
2. 앱의 Lab → Benchmark에서 camera 0과 같은 profile로 정상 반복 측정을 실행한다. 벤치마크는 화면에서 시작한다. CLI 우회나 profile의 크기·반복 횟수 변경은 사용하지 않는다.
3. 각 실행의 JSON을 Export로 회수한다. 개발 검증에서는 동일 서명의 `CliStoreInstrumentation`을 `-e export_reports true`로 실행해 최근 10개 보고서의 `raw`를 포함한 요약을 얻을 수도 있다. Instrumentation은 앱 프로세스를 재시작할 수 있으므로 모든 반복 측정이 끝난 후 한 번 실행한다.
4. 별도 ADB 셸에서 `dumpsys media.camera`를 측정 전·후에 저장하고, 필요한 시간 구간의 `logcat -b main -b system -v monotonic`을 수집한다. 실행 중 반복적인 dumpsys는 부하를 만들므로 피한다. logcat의 시간 기준은 앱의 elapsedRealtimeNanos와 무조건 같다고 가정하지 말고, 수집 시각과 기기 suspend 여부를 함께 확인한다.
5. `node tools/analyze-launch-diagnostics.mjs run1.json run2.json`으로 분석한다. 이 도구는 단일 run JSON, JSON 배열, instrumentation의 `HALCAM_REPORTS=` 출력을 읽는다. warmup·실패·누락 표본은 제외하고, 연속한 사이클에서만 전환 위치를 보고한다.

빠른 모드는 #162 진단 1에서 사용한 `first_started_ms < 220`으로 분류한다. 이 경계는 탐색용이며 제품의 판정 기준이 아니다. 결과에는 모드별 CPU 정책·수집 시점의 주파수 범위와 중앙값, thermal 상태, 조회 비용, 누락 개수와 전환 양쪽의 원본 진단 정보를 포함한다. 서로 다른 기기·profile·조건의 자료는 한 집합으로 합치지 않는다. 전환과 동반 변화가 관찰되더라도 인과관계는 추가 통제 측정으로 확인해야 한다.

## 2026-09-29 사전 확인

- 기기는 Samsung SM-S936N(S25+), Android 16/API 36이다.
- ADB shell에서 `policy0`의 `related_cpus=0 1 2 3 4 5`, `policy6`의 `related_cpus=6 7`과 `scaling_cur_freq`를 읽었다. 이 권한은 앱 UID에서의 읽기 가능성을 보장하지 않는다.
- `dumpsys media.camera`에서 CONNECT/DISCONNECT/EVICT/DENIED 이벤트와 현재 클라이언트가 보였다. logcat에서 CamX/ChiX 공급업체 로그가 보였다. kernel 로그 접근은 거부되었다.
- 첫 측정 도중 앱이 Live 화면으로 바뀌어 반복 실측을 중지했다. 화면 표기도 달라져 외부 기기 작업과 충돌했을 가능성이 있다. 이 실행은 정상 표본으로 사용하지 않는다.

아직 전환과 함께 움직이는 값을 확정하거나 CPU/HAL 가설을 배제할 수 없다. 7.5의 비교 조건 추가도 보류한다. 계측 코드가 준비되었다는 사실과 원인 규명이 완료되었다는 판단을 구분하며, 정상 반복 실측과 결론이 기록되기 전에는 #204를 완료로 처리하지 않는다.

기존 기록을 보호하기 위해 기기의 Benchmark 저장 한도를 10개에서 무제한으로 늘렸다. 후속 실측과 자료 백업이 끝나기 전에는 원래 상한으로 되돌리지 않는다.

## 코드 검증과 남은 검토

앱 JVM 테스트 594개, `lintDebug`, `assembleRelease`, 분석기 Node 테스트 2개가 통과했다. 기존 release 서명으로 `assembleReleaseAndroidTest`도 빌드했다. 이 결과는 앱 UID의 sysfs 접근이나 정상 반복 실측 성공을 증명하지 않는다.

`docflow check --build`는 관련 소스 변경으로 원본 최신성 13개 항목에서 실패했다. 담당 요소 검사와 기존 사이트 일치 검사는 통과했으나, 최신성 상태가 바뀌므로 `generate --check`도 재생성이 필요하다고 보고했다. `tools/docgen/README.md`는 accepted 기록을 사람이 `--accept --reviewer=이름`으로만 남기도록 규정한다. 자동으로 승인 기록을 덮어쓰지 않았으며, 문서 원본의 코드 대조·사람 검토·생성물 갱신이 남아 있다.

관련 기준은 [#204](https://github.com/TTolsun/hal-camera/issues/204)와 [벤치마크 설계](../PLAN-BenchMarker-v0.3.md)에서 확인한다.
