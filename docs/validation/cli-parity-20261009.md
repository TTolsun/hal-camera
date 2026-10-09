# 전체 CLI 연동 검토 — 2026-10-09

현재 구현된 기능을 CLI 명령에 연결했습니다. **실기기 촬영 검증은 아직 완료하지 않았습니다.** 아래 자동 검사는 코드·입력 계약·가짜 ADB 전송의 검증이며 하드웨어 동작을 증명하지 않습니다.

## 검토 기준

작업은 main `445e08c3`에서 시작했고 PR #252의 `7768ad86`을 작업 브랜치에 통합했습니다. 작업 중 #252가 main `acd7c432`에 병합된 사실도 확인했습니다. 최근 PR 목록과 현재 화면·엔진·CLI 구현을 대조했습니다. 주요 기능 근거는 #238·#245(Dual), #244(연사), #247(AEB), #249·#250(HDR와 생략 이유), #252(취소·진행·접근성)이며 #253의 가이드 구성도 유지합니다. 기존 PR을 원격에서 수정하거나 대신 승인하지 않았습니다.

## 기능과 명령 대응

| 앱 기능 | CLI 경로 | 구현 근거·제약 |
| --- | --- | --- |
| 엔진·카메라·출력 선택 | `cameras`, `streams`, `preview`, `capture`, `record start` | Camera2/CameraX, 명시적 크기·FPS·코덱·보정 모드를 검증합니다. |
| RAW·NV21 | `capture --raw-size … --yuv-format NV21` | 기존 MediaLibrary를 사용합니다. RAW만 켜는 구성도 허용합니다. |
| 줌·플래시·EV·잠금·수동 노출/초점/WB | 촬영 옵션, `live set`, `live reset` | 지원 범위 밖의 요청을 자동 보정하지 않습니다. 수동 노출 시간은 선택 FPS 제약을 따릅니다. |
| 터치 초점·노출 측광 | `meter --x … --y … --meter focus|exposure` | 정규화한 좌표를 현재 프리뷰 좌표로 바꿉니다. 결과는 접수 사실이며 초점 성공을 보장하지 않습니다. |
| 연사·AEB·HDR | `burst`, `bracket`, `cancel` | 기존 BurstRun·BracketFusion을 사용합니다. 부분 저장과 HDR 실패·생략을 구분합니다. |
| 녹화와 녹화 중 사진 | `record start`, `record snapshot`, `record stop` | 스냅샷은 원래 녹화 요청의 파일 목록에 등록합니다. 사진 저장 중 정지를 지연합니다. |
| 듀얼 프리뷰·사진·영상 | `dual cameras`, `dual preview|capture|record` | 두 물리 ID를 명시합니다. 두 프리뷰의 업데이트 후 실행하며 영상은 무음 MP4 두 개입니다. CameraX 듀얼 사진은 앱과 동일하게 미지원입니다. |
| 듀얼 제어·보고서·진단 | `live set/reset/info`, `meter`, `events` | 현재 Dual 화면을 유지합니다. 엔진별 지원 제약을 따릅니다. |
| 벤치마크·측정 라벨 | `benchmark run --build … --commit … --branch … --note …` | 고정 Camera2 표준 v2를 그대로 사용합니다. 생략한 라벨은 빈 값입니다. |
| 저장 결과·비교·baseline | `results list/show/compare/export/delete`, `baseline add/remove` | 기존 Presenter·RegressionDetector·BaselineManager를 재사용합니다. 비교용으로 고른 참조를 영구 baseline으로 자동 등록하지 않습니다. JSON/CSV는 실행별로 회수하고 여러 실행의 집계에는 기존 aggregate.py를 사용합니다. |
| 갤러리 파일 관리 | `gallery list/export/delete` | HALCamera 경로에 있는 파일 ID만 사용합니다. Android가 삭제 승인을 요구하면 앱에서 처리합니다. |
| 진단·카메라 사양·CTS | `live info`, `events`, `incidents list/export/delete`, `probe`, `cts cases/run` | 화면 보고서·이벤트·기존 CTS 실행 경로에 연결합니다. 진단 ZIP에 픽셀을 넣지 않습니다. |
| 결과 보관 한도 | `settings show/limit` | 먼저 삭제 예정 목록을 반환하고 `--confirm true`일 때 적용합니다. baseline을 보호합니다. |
| 작업 상태·파일 회수 | `status`, `cancel`, `fetch` | UUID 중복 방지·24시간/200개 기록·크기/SHA-256 검증을 유지합니다. Python 다운로드에 MP4/DNG/NV21/CSV를 추가했습니다. |

권한 허용·잠금 해제·ADB 접근 재허용은 Android 화면에서 수행합니다. 갤러리 확대·재생·공유 대상 선택은 회수한 파일을 PC 도구로 열어 수행합니다. PiP 위치·메뉴 펼침·내비게이션 같은 화면 배치 조작은 데이터/촬영 기능의 CLI 명령으로 대체하지 않습니다.

## 사용 흐름 개선

기본 `help`에는 시작 작업 다섯 개만 표시합니다. 상세 명령과 수동 옵션은 `help all`·`help controls`에 둡니다. CLI는 상태가 바뀔 때 진행 상황을 출력하고 마지막 UUID를 기억하며 저장 후 실행할 `adb pull` 한 줄을 제공합니다. 실패·취소 후에도 같은 요청의 `fetch`를 안내합니다. 삭제에는 대상 ID와 확인이 필요하고 보관 한도는 영향을 먼저 보여 줍니다. 이는 인지 부담을 줄이는 설계이며 ADHD 사용자를 대상으로 한 사용성 평가 결과는 아닙니다.

## 자동 검증

- JVM 테스트 693개: 실패·오류·건너뜀 0개입니다.
- `testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest --offline`: 통과했습니다. 계측 테스트 APK의 빌드는 실행 결과가 아닙니다.
- Python/셸 CLI 테스트 58개: 통과했습니다. 새 미디어 형식 다운로드, 명령 옵션 전달, 듀얼 화면 유지, 녹화 제한, 실패 후 회수 경로를 검사합니다.
- 문서 회귀 검사 33개와 coverage 검사는 통과했습니다. CLI 어댑터·파일 전달·요청 작업의 문서 근거를 별도 요소로 나눠 기존 60,000자 입력 한도를 유지했습니다.
- `CommandStore.update`의 취소 중 갱신·terminal 이후 갱신 금지 검사를 Android 계측 코드에 추가했습니다. 실기기 실행은 미완료입니다.

## 남은 검증

연결 대상 Galaxy S25+가 ADB 목록에서 사라졌고 재연결도 실패했습니다. 새 APK를 설치하거나 기존 앱을 삭제하지 않았습니다. 따라서 이번 변경의 기기 모델·Android 버전·시나리오별 통과 기록과 새 CLI 화면 스크린샷은 없습니다. PR #252에 포함된 기존 스크린샷을 이번 CLI 검증의 증거로 사용하지 않습니다.

기기 연결 후에는 단일 사진·RAW/NV21, 연사/AEB 취소와 HDR, 녹화 중 사진·정지·화면 이탈, 두 엔진의 Dual과 파일 회수, 결과/baseline/갤러리/진단/보관 한도의 조작을 확인해야 합니다. 삭제 검증은 별도로 생성한 테스트 데이터로 수행합니다.

문서의 최신성 검사에는 변경된 소스에 대한 재검토가 남아 있습니다. 사람의 검토가 필요한 `verify --accept --reviewer=…`는 실행하지 않았습니다. 원고와 사이트 생성은 수행하되 검토 사실을 자동 승인으로 기록하지 않습니다.
