# Multi UI 일관성 검증 — 2026-10-17

최신 main의 #289 (`552b057d`)를 기준으로 Photo와 배경 및 선택 목록의 표현을 맞췄습니다. 드래그 배치·카메라별 크기·FPS/EXP·P/V 스트림 재구성은 기존 구현을 유지합니다.

## 코드 변경

- Photo와 동일하게 프리뷰 바깥 배경을 검정으로 표시합니다.
- 카메라 ID를 드래그 표시가 있는 작은 손잡이로 표시하고, 선택한 카메라는 흰 테두리·체크와 Live 크기의 밝은 글씨로 구분합니다. 기존 48dp 터치 영역을 유지합니다.
- Live의 크기는 3개 이상에서도 카메라마다 한 줄씩 표시합니다. Sizes는 카메라별 Preview/JPEG 버튼을 나란히 배치합니다.
- Split은 지원하는 조합과 화면 순서를 먼저 보여 주고, 지원하지 않는 조합은 펼쳐서 확인하도록 정리합니다. 레이아웃 선택은 추가하지 않습니다.
- 카메라 수·화면 순서·Preview/JPEG 크기는 Photo의 `LiveChoiceSheet` 선택 목록으로 고릅니다. 부모 설정창의 Apply와 취소 동작을 유지합니다.
- 상단 Split은 위에서, 하단 카메라 버튼은 아래에서 같은 설정창을 엽니다.
- P/V를 전환하면 펼친 줌을 접고 기존 `close(done)` 경로로 스트림을 재구성합니다.

## 자동 검사

`assembleDebug testDebugUnitTest lintDebug`가 통과했습니다. app JVM 테스트는 826개입니다. 기존 SplitArrangement와 크기 선택 테스트도 포함합니다.
문서 리뷰에서는 카메라가 셋 이상일 때의 크기 표시와 Split 설정창의 열림 방향에 관한 오래된 설명을 수정했습니다. 사용자의 리뷰·머지 요청에 따라 Codex가 코드와 문서를 대조한 기록이며, 별도 사람의 코드 리뷰를 뜻하지 않습니다.

## 기기 관찰

Galaxy S25+ (SM-S936N), Android 16에서 별도 검증 패키지 `dev.halcamera.uiverify`로 확인했습니다. 기존 `dev.halcamera` 앱은 교체하지 않았습니다.

1. Multi P 기본 배치는 Side by side이며 각 카메라의 크기와 FPS/EXP가 표시됩니다.
2. Rear ID를 250ms 이상 누르고 오른쪽으로 끌면 Front/Rear 순서가 바뀌고 프리뷰가 계속 실행됩니다. 상단 크기와 Sizes도 같은 순서를 따릅니다.
3. Sizes에서 Front의 Preview만 640×480으로 선택하고 Apply하면 Front만 변경되며 Rear는 1280×720을 유지합니다. 두 JPEG는 1920×1440입니다.
4. Multi P→V→P에서 배치·순서·카메라별 크기가 유지되고 프리뷰가 다시 실행됩니다.
5. Multi V 녹화 종료 후 `Videos saved · Details`, P 복귀 후 촬영에서 `Photos saved · Details`를 확인했습니다. 파일의 픽셀·코덱 분석은 이번 검증 범위에 포함하지 않았습니다.

최종 APK에서도 Android의 연속 `input touchscreen draganddrop` 입력으로 Rear를 오른쪽으로 옮겨 Front/Rear 순서와 Live 크기 순서가 함께 바뀌는 것을 확인했습니다. Front Preview만 640×480으로 변경한 뒤 P→V→P를 전환해 순서·크기·선택 상태 유지와 카메라별 FPS/EXP 갱신을 확인했습니다. ID 손잡이, Sizes의 두 열 배치, Split의 접힌 미지원 조합도 실기기 화면으로 확인했습니다. 앞선 개별 `motionevent` 주입으로는 드래그 결과가 확정되지 않았으므로 성공 근거로 사용하지 않습니다. 사진·영상 저장 검증은 최종 간격 정리 전 빌드에서 수행했습니다.

3개 이상 카메라 및 다른 기기는 실측하지 않았습니다. 배치의 순수 계산은 기존 JVM 테스트로 검증합니다.

## 화면

- [Multi P — 선택 손잡이와 배경 개선](multi-photo-ui-20261017/multi-p.png)
- [크기와 순서를 변경한 Multi V](multi-photo-ui-20261017/multi-v.png)
- [카메라별 Sizes](multi-photo-ui-20261017/sizes.png)
- [Split 설정](multi-photo-ui-20261017/split.png)
