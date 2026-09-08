# Checkpoint 001 — 작업 상태

2026-09-08 · 사용자 요청에 따라 추가 구현 중단 후 공유.

## 이번에 공유하는 것

- 1차 MVP 지표 정의서: 시작·종료 지점, 시계 구분, 조건, 통계, CTS 비교 범위.
- 초기 아이디어 기반 Android 코드 초안: Camera2/CameraX, metadata, 3A scope, Flight Recorder, ZIP 공유.
- 컴파일·단위 테스트·lint 결과 원문.

새 계획이 도착하기 전에 작성된 `app/` 코드는 기존 탐색안입니다. 지표 정의서에서 제안한 성능 시나리오가 이미 구현되었다는 뜻이 아닙니다.

## 실행한 검증

`assembleDebug testDebugUnitTest lintDebug` 실행 결과:

- APK 생성 성공. 초기 탐색용 APK이며 실기기 동작은 확인하지 않았습니다.
- FlightRecorderTest: tests=7, failures=0, errors=0.
- 최종 Lint: 오류 0개. 경고는 첨부 보고서에 보존했습니다.
- 최종 전체 검증: BUILD SUCCESSFUL (43초).

초기 검사에서 발견되어 이번 프리뷰 체크포인트를 위해 수정한 오류:

1. API 26에서 API 27의 `windowLightNavigationBar`를 참조하는 style 1건.
2. 이 PC의 `local.properties` SDK 경로 escape 1건. 이 파일은 저장소에서 제외됩니다.
3. Camera2Interop 실험 API opt-in 표기 3건.

사용자가 중간 산출물에 실제 카메라 프리뷰를 요구한 후, 위 빌드 검사 오류만 수정하고 재검증했습니다. 새 성능 시나리오 구현은 진행하지 않았습니다.

ADB 장치 목록이 비어 있어 실기기에서 프리뷰를 직접 관찰하거나 테스트하지 못했습니다. APK에 실제 CameraX/Camera2 preview 경로는 구현되어 있습니다. 사용자가 기기에 설치해 확인할 수 있도록 APK를 제공합니다.

## 아직 하지 않은 것

- 새 계획 기준 Camera2 3개 시나리오와 반복 측정 루프.
- 녹화 인코더·muxer·frame count 계측.
- 실기기 권한 거부/중단/회전/전후면 전환/카메라 점유 경쟁 검증.
- CTS와 동일 기기·동일 조건 대조 측정.
- GitHub Actions, Google Drive 자동 동기화, Perfetto/AI 연동.

## 다음 한 단계

`METRICS.md`의 기본 launch 관측 대상, shot-to-shot 제출 정책, 반복/복귀 규칙을 검토해 정의를 고정합니다. 그 다음 First preview 시나리오 하나부터 구현·실기기 검증합니다.


## 문서 갱신 — 지표 정의표 v0.2

사용자 원문을 별도 보존하고 METRICS.md를 Camera2/MediaRecorder 중심으로 갱신했다. callback/센서 시각, precapture 포함 여부, 간격 표본 수, drop 추정과 CTS 대응의 충돌은 검토안으로 정리했다. 앱 구현·APK·실기기 검증 상태는 checkpoint-001과 같다. 다음 단계는 정의서 검토이며, 새로운 성능 시나리오는 구현하지 않았다.
