# Live AWB 표시 검증

2026-10-11, Galaxy S25+ (SM-S936N), Android 16 / API 36에서 확인했습니다.

실시간 정보는 `FPS · EXP · AE · AF · AWB` 순서입니다. AWB는 요청한 WB 모드가 아니라 capture result의 상태를 표시합니다. 값이 없으면 `—`로 남깁니다.

## 기기 확인

| 시나리오 | 결과 |
| --- | --- |
| Camera2 Photo | `AWB Search`가 표시됐으며 다섯 항목이 한 줄에서 겹치지 않았습니다. |
| AE·AF 잠금 및 상태 변화 | 각 열의 가로 위치가 유지됐습니다. 활성 설정은 기존처럼 아래에 표시됐습니다. |
| CameraX Photo·Video | `AWB OK`가 표시됐습니다. |
| Callback 열기·닫기 | 실시간 정보가 숨겨지고 다시 나타났습니다. |
| Camera2 Physical ID 6 PIP | 메인 결과의 AWB 상태가 계속 표시됐습니다. |
| Camera2 Video → Photo | AWB가 새 모드의 결과로 갱신됐습니다. |

![AWB를 포함한 Live 정보](../../guide/assets/screenshots/live.png)

기기에서는 Search와 OK를 관찰했습니다. Idle·Locked·미지원 값과 기존 Dual 경로는 코드 및 JVM 검사 범위이며 이번 기기 검증에서 별도로 재현하지 않았습니다.

## 로컬 검사와 리뷰

- JVM 테스트 746개, lintDebug, assembleRelease를 통과했습니다.
- 문서 회귀 검사 33개를 통과했습니다.
- 공통 formatter와 view를 확장했습니다. 화면마다 상태 변환을 복제하지 않았으며, 열 수와 간격 계산은 예약된 항목 수를 따릅니다.
- AWB 네 상태, 누락·알 수 없는 값, AE·AF·AWB 조합의 고정 열 순서를 검사했습니다.
