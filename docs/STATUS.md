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
- Lint: 5 errors, 24 warnings. 따라서 전체 검증 명령은 실패했습니다.

오류 분류:

1. API 26에서 API 27의 `windowLightNavigationBar`를 참조하는 style 1건.
2. 이 PC의 `local.properties` SDK 경로 escape 1건. 이 파일은 저장소에서 제외됩니다.
3. Camera2Interop 실험 API opt-in 표기 3건.

추가 구현 중단 요청 이후 이 오류들을 고치거나 재빌드하지 않았습니다. 현재 상태 그대로 검토할 수 있도록 보고서를 보존했습니다.

## 아직 하지 않은 것

- 새 계획 기준 Camera2 3개 시나리오와 반복 측정 루프.
- 녹화 인코더·muxer·frame count 계측.
- 실기기 권한 거부/중단/회전/전후면 전환/카메라 점유 경쟁 검증.
- CTS와 동일 기기·동일 조건 대조 측정.
- GitHub Actions, Google Drive 자동 동기화, Perfetto/AI 연동.

## 다음 한 단계

`METRICS.md`의 기본 launch 관측 대상, shot-to-shot 제출 정책, 반복/복귀 규칙을 검토해 정의를 고정합니다. 그 다음 First preview 시나리오 하나부터 구현·실기기 검증합니다.
