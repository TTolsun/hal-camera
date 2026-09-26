# Live 상단 배치 검증

중앙의 카메라 이름·Live 상태를 제거하고, API 아래의 작은 Live 표시등과 중앙의 촬영 제어 화살표로 교체했습니다. 앱 실행 시 프리뷰로 진입하는 흐름과 Flash·AF·AE·EV 동작은 유지합니다.

## 자동 검사

`assembleRelease testDebugUnitTest lintDebug`가 통과했습니다. 기기 설치용 APK에는 저장소 밖의 Gradle init script로 versionCode 512를 적용했습니다. 기존 앱 데이터를 유지한 채 업데이트했습니다.

## 기기 관찰

- Galaxy S25+ SM-S936N, Android 16/API 36, Snapdragon SM8750에서 확인했습니다. Exynos 기기 검증은 수행하지 않았습니다.
- Camera2와 CameraX 모두 프리뷰가 시작되면 원과 Live 글씨가 함께 빨간색으로 깜빡였습니다. 서로 다른 시점의 화면에서도 두 요소의 밝기가 함께 변했습니다.
- 진단 패널에서 일시정지하면 원과 글씨가 회색으로 바뀌었으며 재개하면 다시 깜빡였습니다.
- 중앙 화살표로 제어 줄을 펼치고 접었습니다. Flash의 Off·Auto·On·Torch 선택지, AF·AE 잠금 선택 표시와 EV 다이얼이 열리는 것을 확인했습니다. 이번 변경에서 모든 플래시 모드의 실제 발광을 재검증하지는 않았습니다.
- 홈 화면으로 나갔다가 복귀한 뒤 프리뷰와 Live 표시가 재개되었습니다. 검증 후 Camera2, 제어 기본값, 접힌 상태로 돌려놓았습니다.

프레임 중단 감지는 1.5초 유효 기간을 사용합니다. Camera2는 TextureView 갱신을, CameraX는 STREAMING 상태와 최근 capture result를 함께 확인합니다. 강제 HAL 정지와 애니메이션 비활성화 설정은 이번 기기 검증에 포함하지 않았습니다.

[Live UI 설계](../design/APP-UI.md)에서 배치 규칙을 확인할 수 있습니다.
