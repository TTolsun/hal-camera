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
| `testDebugUnitTest` | 통과했습니다. 최신 main 통합 후 앱 JVM 테스트 636개를 실행했습니다. 프로파일 기본값, 부적절한 인자 거부, 기존 측정·상태 전이 검사를 포함합니다. |
| `lintDebug` | 통과했습니다. |
| `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease` | 통과했습니다. 테스트 APK 빌드는 기기 테스트 실행을 뜻하지 않습니다. |
| Python·셸 CLI 테스트 | 47개가 통과했습니다. 벤치마크 payload, 화면 실행 경로, 대기·비대기 실행, 기존 취소·재회수 검사를 포함합니다. |
| 문서 coverage·generate·site 검사 | 통과했습니다. |
| 문서 전체 `check --build` | 코드 근거 14개 항목의 재검토 필요 상태로 최신성 검사에서 실패했습니다. 사람의 승인 기록은 변경하지 않았습니다. |

기존 구조 스캔 `.omm`에는 벤치마크 제외 설명이 남아 있으므로 다음 구조 동기화와 재검토가 필요합니다. 사용자 가이드와 architecture 원고는 현재 구현에 맞게 갱신하고 생성기로 배포 문서를 재생성했습니다.

## 실기기 업데이트와 검증

2026-10-06 Galaxy S25+ (`SM-S936N`)·Android 16에서 검증했습니다. 기존 앱 0.19.0(versionCode 628)의 APK를 저장한 뒤 최신 `origin/main`(`8d2c51f`)을 CLI 브랜치에 통합했습니다. 검증 소스는 `6c128c7`이며, 같은 버전 번호와 기존 릴리스 서명을 사용한 APK를 `adb install -r`로 설치했습니다. 서명 키는 원래 경로에서 참조했으며 복사하거나 이동하지 않았습니다. 앱 삭제·데이터 초기화·다운그레이드는 수행하지 않았습니다.

설치 전후 기존 촬영 요청 `7b0e9ab4-ccf8-4ec9-b210-984cc97de6af`의 JSON 내용이 일치했습니다. CLI 허용 설정과 카메라 권한도 유지됐습니다. 이는 확인한 요청과 설정의 보존 근거이며 앱의 모든 저장 자료를 전수 비교한 결과는 아닙니다.

| 시나리오 | 실기기 결과 |
| --- | --- |
| 정상 실행 | 요청 `e2fae080-dc40-4533-bd62-71bc0731253f`가 succeeded로 완료됐습니다. run `20261006-085110-324`, schema 5, JSON 6,073,520바이트를 회수했습니다. measurement_valid·comparison_eligible·scoring_eligible가 모두 true이고 validity flags는 비어 있습니다. |
| 동일 ID 재전송 | 실행 중 같은 ID·같은 인자를 제출하자 기존 running 요청을 반환했습니다. 새 측정은 생성하지 않았습니다. |
| 동일 ID·다른 인자 | camera를 1로 바꿔 재전송하자 REQUEST_CONFLICT로 거부했습니다. |
| 실행 중 새 요청 | BUSY로 거부했으며 기존 작업을 유지했습니다. |
| 실행 제한 5초 | `d2dea896-a4cc-4add-a05c-0001a4932299`가 failed/EXECUTION_TIMEOUT으로 끝났습니다. 셸 종료 코드는 1이었고, fetch로 중단 JSON 88,204바이트를 회수했습니다. |
| 실행 중 명시적 취소 | `ae06199e-feba-4404-b938-e207fd4c76c0`가 cancelled/CANCELLED로 끝났습니다. abort 원인은 cli이며 JSON 534,502바이트를 회수했습니다. |
| 실행 중 홈 화면 이동 | `29598c58-90d4-40f3-8264-49e116abad95`가 cancelled/CANCELLED로 끝났습니다. abort 원인은 background이며 JSON 606,009바이트를 회수했습니다. 직후 fetch는 아직 running이어서 거부됐고 종료 후 재시도에 성공했습니다. |
| 잘못된 카메라 | invalid-camera를 요청하자 UNSUPPORTED_CAMERA로 실패했고 파일은 생성하지 않았습니다. |
| 지원하지 않는 옵션 | camera2-standard-v1 프로파일과 CameraX 엔진 지정은 INVALID_ARGUMENT로 거부했습니다. |

회수한 네 JSON 모두 앱의 artifact SHA-256과 PC 파일 해시가 일치했습니다. 정상 요청의 파일 해시는 `52d4b5c36c19cd5162378fb10fcb22e85d2175f5e4de6c4ec248b6dc4c9b5c09`입니다. 상세 요청·validity·파일 크기·해시는 [검증 결과 JSON](assets/cli-benchmark-20261006/results.json)에 보관했습니다.

검증 후 앱은 Live 화면이며 `busy=false`, 활성 요청은 없습니다. 업데이트한 앱과 APK 내부에서 다시 추출한 `/data/local/tmp/halcam`을 그대로 두었습니다. 정상 측정은 후면 카메라 0에서 한 번 수행했으므로 다른 렌즈나 반복 재현성까지 검증한 것은 아닙니다. 준비 단계의 순간적인 취소, 프로세스 강제 종료, 실제 무선 단절은 이번 검증에 포함하지 않았습니다.

설치 APK의 SHA-256은 `4e8408b9544c894655db3e46dcc5cb09daa522030317fd68a0c24b4621231495`입니다. APK와 원본 보고서는 작업 폴더의 `releases/cli-benchmark-validation/`에 보관했습니다. 동일한 0.19.0 번호를 사용하는 검증 빌드이므로 버전 번호만으로 공식 배포본과 구분하지 않고 해시를 함께 사용합니다.

실행 예제는 [CLI 가이드](../../guide/cli.md)를 확인하세요.