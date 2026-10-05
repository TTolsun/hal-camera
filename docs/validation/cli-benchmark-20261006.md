# 벤치마크 CLI 추가 검증

`benchmark run --camera 0`을 추가했습니다. 기존 Live 카메라의 `close(done)` 후 `BenchmarkActivity`로 이동하며, 화면과 같은 `camera2-standard-v2` 프로파일·preflight·Runner·판정·JSON 저장 경로를 사용합니다. CLI에서 측정 조건이나 baseline 규칙을 변경하지 않습니다.

## 계약

- adb 셸과 선택형 Python 클라이언트에서 `benchmark run`을 지원합니다. 기본 카메라는 `0`, 프로파일은 `camera2-standard-v2`, 앱 실행 제한은 600초입니다.
- `--profile`은 현재 v2만 받습니다. Live 엔진·스트림 크기·오디오 옵션은 거부합니다. JSON 직접 제출에서는 `params.profile_id`를 명시합니다.
- `status`, `cancel`, `fetch`는 같은 요청 ID를 사용합니다. 성공 시 원본 JSON 하나를 등록하며, 실패·취소·시간 초과 후에도 저장된 보고서가 있으면 회수할 수 있습니다.
- 완료 결과는 `run_id`, `camera_id`, `profile_id`, `report_schema_version`, `artifact_count`를 포함합니다. 명령 성공과 측정 validity·점수·회귀 판정은 별개입니다.

## 실행한 검사

| 검사 | 결과 |
| --- | --- |
| `testDebugUnitTest` | 통과했습니다. 앱 JVM 테스트 606개를 실행했습니다. 프로파일 기본값, 부적절한 인자 거부, 기존 측정·상태 전이 검사를 포함합니다. |
| `lintDebug` | 통과했습니다. |
| `assembleDebug`, `assembleDebugAndroidTest` | 통과했습니다. 테스트 APK 빌드는 기기 테스트 실행을 뜻하지 않습니다. |
| Python·셸 CLI 테스트 | 47개가 통과했습니다. 벤치마크 payload, 화면 실행 경로, 대기·비대기 실행, 기존 취소·재회수 검사를 포함합니다. |
| 문서 coverage·generate·site 검사 | 통과했습니다. |
| 문서 전체 `check --build` | 코드 근거 14개 항목의 재검토 필요 상태로 최신성 검사에서 실패했습니다. 사람의 승인 기록은 변경하지 않았습니다. |

기존 구조 스캔 `.omm`에는 벤치마크 제외 설명이 남아 있으므로 다음 구조 동기화와 재검토가 필요합니다. 사용자 가이드와 architecture 원고는 현재 구현에 맞게 갱신하고 생성기로 배포 문서를 재생성했습니다.

## 실기기 검증 범위

벤치마크 CLI의 실기기 실행·중단·보고서 회수는 아직 검증하지 않았습니다. 연결된 Galaxy S25+·Android 16에는 0.19.0이 설치되어 있고 작업 기준의 앱은 0.17.0이므로, 기존 앱을 교체하거나 다운그레이드하지 않았습니다. 이전 사용성 조사에서 수행한 `doctor` 검증을 벤치마크 실행 검증으로 간주하지 않습니다.

실기기에서는 기능이 포함된 정상 서명의 APK를 준비한 뒤 정상 완료, 준비 중 취소, 실행 중 취소, `--timeout` 초과, 중복 ID 제출, 앱 배경 전환, 실패 후 JSON 회수를 확인해야 합니다. 단말의 thermal·power-save·profile 지원 조건 때문에 preflight가 거부되면 그 결과와 실제 측정 성공을 구분해 기록합니다.

실행 예제는 [CLI 가이드](../../guide/cli.md)를 확인하세요.
