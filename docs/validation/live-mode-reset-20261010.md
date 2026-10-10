# 모드 초기화와 Live 선택 UI 검증 — 2026-10-10

SM-S936N, Android 16에서 `93385443` 기반 서명 release APK를 업데이트 설치하고 Live 버튼으로 검증했습니다. 앞선 PIP 검증 기록의 모드 간 복원 동작은 이번 요구 변경으로 대체됩니다.

| 시나리오 | 관찰 결과 |
|---|---|
| Camera2 Photo에서 YUV Off와 Physical PIP를 설정한 뒤 Video·Photo 왕복 | PIP가 꺼지고 Preview 1280×720, YUV 640×480, JPEG 1920×1080 기본 스트림으로 돌아왔습니다. |
| Camera2 PIP → CameraX, CameraX PIP → Camera2 | PIP 선택을 이어받지 않고 새 엔진의 기본 스트림을 열었습니다. |
| CameraX Photo·Video 양방향 전환 | 활성 PIP가 꺼지고 일반 CameraX 프리뷰가 열렸습니다. |
| PIP 드래그 후 모드 전환·PIP 다시 켜기 | 이동한 위치 대신 기본 위치에서 시작했습니다. |
| 같은 모드의 Live Streams 방문·복귀 | 선택한 PIP가 유지되었습니다. |
| Single PIP에서 Multi 진입·Live 복귀 | Multi에 Single PIP를 전달하지 않았고 Live 복귀 후에도 PIP가 꺼져 있었습니다. |
| PIP·Callback 강조 | 체크 문자 없이 활성 색상·굵기로 표시됐습니다. PIP 재탭은 선택창을 열었고 Off 선택 시 강조가 해제됐습니다. Callback 재탭도 강조를 해제했습니다. |
| Camera 목록 | Service · ID가 큰 첫 줄, 방향·렌즈가 작은 둘째 줄에 표시됐으며 0·1·2·3 순서였습니다. ID 1 선택 후 실제 메인 ID가 1로 바뀌었습니다. |
| 메인 1의 PIP 목록 | Service 0·2·3 순서였으며 Service 0 선택으로 PIP가 열렸습니다. |

JVM 테스트 727개, lint, release 빌드와 문서 회귀 테스트 33개가 통과했습니다. 숫자 ID 2가 10보다 먼저 오고 비숫자 ID를 뒤에 정렬하는 회귀 테스트를 추가했습니다. 스크린샷은 실제 카메라 영상을 포함하므로 비공개 로컬 자료로 보관했습니다. 다른 기기와 강제 카메라 실패 시나리오는 이번 변경에서 검증하지 않았습니다.
