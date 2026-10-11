# Photo·Video CLI 검토와 수정

Photo·Video에서 명시한 설정으로 실행하고, 요청 ID로 완료와 파일을 확인하는 경로를 검토했습니다. 기준은 0.25.0의 PR #281 머지 커밋 `623786ed00adf9fdb67122021ddac421621c15f2`이며, 이번 CLI 변경을 그 위에 적용했습니다. 이 기록은 기기 전체의 모든 카메라·크기 조합에 대한 지원 선언이 아닙니다.

## 수정한 동작

1. Python의 `run cts.run --cases KEY[,KEY...]`가 `params.cases`를 전달합니다. 기존 `cts run --case KEY`도 유지합니다.
2. 요청 조회가 캐시·디스크 모두에서 만료를 검사합니다. 새 요청이 없어도 만료 시각부터 해당 요청과 파일을 회수할 수 없습니다. 원본 MediaStore 촬영물은 이 정리 대상이 아닙니다.
3. 셸과 Python은 `record start/stop/snapshot`, `preview stop`, `status UUID`를 사용합니다. `status`의 기본값은 현재 앱 상태입니다. 셸의 이전 마지막 요청 조회는 `status --last`로 분리했습니다. Python의 기존 `status --request UUID`와 `control record.stop`은 유지합니다.
4. 전용 Python 촬영 명령과 공통 `run`은 같은 요청 생성기를 사용합니다. RAW·FPS·보정·수동 제어·PIP 옵션을 전달하며 중복 필드를 거부합니다.
5. Photo에는 RAW·프리뷰 FPS, Video에는 녹화 FPS·크기·코덱·보정을 적용합니다. 모드에 맞지 않는 명시적 설정은 조용히 제거하지 않고 거부합니다. Video 프리뷰는 `preview --mode Video`로 준비합니다.

PIP는 `pip sources`로 기존 엔진의 사전 검사 결과를 읽고 `--pip-type physical|service --pip-camera ID`로 선택합니다. 크기와 위치도 명시할 수 있습니다. PIP 구성이 끝난 뒤 촬영·녹화를 시작하며, 다음 카메라 요청은 이전 PIP 선택을 이어받지 않습니다. 미지원 조합과 CameraX Physical PIP는 기존 엔진의 지원 조건에 따라 거부합니다.

## 책임과 중복 정리

| 코드 | 책임 |
| --- | --- |
| `CliCommands` | 명령의 필수 인자·허용 옵션·기본 제한·실행 대상을 정의하고 `hello.command_specs`로 제공합니다. |
| `CommandCoordinator`, `CommandStore` | 요청 소유권·상태·취소·보존과 파일 등록을 관리합니다. 저장소는 Coordinator 내부에 둡니다. |
| `CliQueries`, `CliLibrary` | 장치 조회·Probe와 저장된 결과 조작을 각각 처리합니다. |
| `CliCompletion`, `CliExecution`, 기능별 host | Live·CTS·Benchmark 컨트롤러의 구체 Coordinator 의존성을 줄이고 녹화·스냅샷·제어 지원을 분리합니다. |
| `BracketCapture`, `BracketFusion.fuseSaved` | UI·CLI의 EV 순서·안정화 대기·제어 복원과 HDR 적합성·원본 선택을 공유합니다. |

`fetch`와 `results export`, 요청 `status`와 `live info`는 서로 다른 대상을 다루므로 유지했습니다.

## 자동 검사

- Python·APK 셸의 모의 검사 88개가 통과했습니다. CTS 전달, 전용·공통 명령의 동일 설정, Video 종료 대기, 상태 조회, 중복 옵션을 포함합니다.
- 전체 JVM 검사 804개와 Android lint, 앱·계측 APK 빌드가 통과했습니다.
- 기존 서명으로 기기에 업데이트했습니다. 요청 저장소의 만료·복구와 PIP 준비 중 취소·조기 녹화 종료·중복 준비 콜백·비동기 실행 오류·스냅샷 저장 후 종료를 계측 검사 32개로 확인했습니다.
- 문서 회귀 검사 33개, coverage와 사이트 일치 검사를 수행했습니다. 문서의 검토 승인 기록은 코드·기기 검사 결과와 구분합니다.

## 기기에서 확인한 범위

기기는 Galaxy S25+ `SM-S936N`, Android 16입니다. Photo·Video 기능 시나리오 36개와 최종 APK의 모드별 지원 목록·Video FPS 오용 거부 검사 3개가 통과했습니다. 회수한 파일의 크기와 SHA-256도 검증했습니다.

| 영역 | 확인한 시나리오와 결과 |
| --- | --- |
| Photo · Camera2 | 프리뷰, HAL JPEG, YUV 변환 JPEG, RAW 단독 DNG, 3장 연사, AEB·HDR 저장이 통과했습니다. |
| Photo · CameraX | JPEG 저장이 통과했고 RAW 요청은 `PREFLIGHT_FAILED`로 거부했습니다. |
| Video · Camera2 | HEVC 1280×720 준비, 무음 녹화, 녹화 중 줌, JPEG 스냅샷, 종료 후 MP4·사진 회수가 통과했습니다. |
| Video · CameraX | Auto 코덱의 1280×720 프리뷰 준비, 무음 녹화·종료·MP4 회수가 통과했습니다. |
| PIP · Camera2 | Service·Physical 각각의 사진과 영상을 저장했습니다. 사진 요청마다 합성 JPEG·주 카메라 원본·보조 카메라 원본과 메타데이터를 회수했습니다. 영상은 합성 MP4를 회수했습니다. 다음 일반 프리뷰에 PIP가 남지 않는 것도 확인했습니다. |
| 거부와 복구 | Photo 보정, Video RAW, CameraX Physical PIP, 주 카메라를 보조 PIP로 선택하는 요청을 거부했습니다. |
| CTS·셸 | 공통 `run`의 `custom:fast_on_off`는 passed 1·failed 0으로 끝났고 JSON·TXT를 회수했습니다. APK 셸의 Video 프리뷰와 기본 앱 상태 조회도 통과했습니다. |

수동 ISO·노출·초점·WB·AE/AF 잠금·측광·플래시, 사용자 지정 PIP 위치·크기는 코드와 입력·지원 조건 검사로 대조했습니다. 이번 기기 시나리오에서는 이 항목들의 센서 적용값 전체, 유음 녹화, CameraX Service PIP, 다른 기기의 조합을 실측하지 않았습니다.

## 콜백 식별 정보 보완 · 2026-10-12

Main·Dual의 `live info`가 공통 `CliEvents` 변환을 사용합니다. 기존 `kind`·`at_ns`·`values`에 `session_id`·`frame_number`·`sensor_timestamp_ns`를 추가했습니다. 시각은 각 도메인의 원래 값을 문자열로 보존하고, 없는 프레임·센서 시각은 `null`로 전달합니다. 최근 1초·최대 100개와 이벤트 순서는 유지합니다.

앱 JVM 808개, CTS vendor JVM 12개, Python·APK 셸 88개와 lint가 통과했습니다. 같은 서명으로 업데이트한 기기의 계측 검사 34개는 실제 JSON 변환의 큰 정수 시각·독립 세션·누락 값도 확인했습니다.

Galaxy S25+ `SM-S936N`·Android 16에서 아래 5개 시나리오가 통과했습니다. 모든 이벤트의 식별 필드·타입과 같은 세션·프레임의 시작/결과 연결을 검사했고, 확인 후 프리뷰를 종료했습니다.

| 시나리오 | 확인 결과 |
| --- | --- |
| Main Photo | 세션·프레임·센서 시각을 보존했습니다. |
| Main Video 프리뷰 준비 | 녹화 전 실제 콜백에서도 같은 필드를 보존했습니다. |
| Main Photo Service PIP | 메인·보조 세션을 구분했습니다. |
| Main Video Service PIP 프리뷰 준비 | 메인·보조 세션을 구분했습니다. |
| Camera2 Dual 물리 프리뷰 | Dual의 논리 요청 세션·프레임으로 콜백을 연결했습니다. |

이번 추가 검증은 프리뷰의 진단 응답을 대상으로 하며, 녹화·촬영 파일 검증은 앞의 2026-10-11 기록과 구분합니다.

현재 명령과 복구 절차는 [CLI 가이드](../../guide/cli.md)를 따릅니다.
