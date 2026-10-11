# PIP 선택 사전 검사 기기 검증

**Galaxy S25+에서 지원 불가 항목의 선택 차단과 지원되는 PIP 프리뷰 구성을 확인했습니다.** 기기별 예외는 추가하지 않았습니다.

- 검증일: 2026-10-11 (KST).
- 기기: Samsung SM-S936N, Android 16, QTI SM8750.
- APK: 0.24.0, 변경 코드 `44517453`, 기존 앱과 같은 서명의 release 빌드로 업데이트했습니다.
- 도구: 무선 ADB, Android CLI의 UI 조회, HAL CAM CLI의 `live info`.

## 확인한 결과

| 조건 | 관찰 결과 |
| --- | --- |
| Camera2 VIDEO, 메인 Service 0 + Service 2 | 후보가 `Cannot run together`로 표시됐습니다. 탭해도 선택창이 유지됐고 해당 조합은 적용되지 않았습니다. |
| Camera2 VIDEO, 메인 Service 0 + Physical 2 | 선택이 허용됐습니다. PIP 선택 표시와 합성 TextureView가 나타났으며 VIDEO 모드가 유지됐습니다. `live info`의 `ready`가 true였고 프리뷰 1280×720, YUV 640×480, 합성 JPEG 590×1280을 확인했습니다. 화면 FPS는 약 30이었습니다. |
| Camera2 PHOTO, 메인 Service 0 + Physical 2 | PIP 선택 표시와 합성 프리뷰가 나타났으며 `ready`가 true였습니다. |
| CameraX PHOTO, 분석 3840×2160 | Service 1·3이 `Unsupported streams`로 표시됐습니다. Service 1을 탭해도 적용되지 않았습니다. Service 2는 `Cannot run together`, Physical 후보는 `Camera2 only`로 표시됐습니다. |
| CameraX PHOTO, 분석을 640×480으로 변경한 뒤 Service 1 선택 | Service 1·3이 다시 선택 가능해졌습니다. Service 1의 PIP 구성이 완료됐고 선택창을 다시 열어 선택 표시를 확인했습니다. `ready`가 true였으며 메인·보조 프리뷰는 각각 1280×720, 분석은 640×480이었습니다. |

이 기록은 선택 사전 검사와 프리뷰 구성의 검증입니다. 녹화·사진 파일 저장, Exynos를 포함한 다른 기기의 HAL 동작, 조회 API 미지원 기기의 실제 구성은 별도 검증이 필요합니다. 조회 불가 시 선택을 허용하는 분기와 작은 지원 프리뷰를 유지하는 규칙은 JVM 테스트로 확인했습니다.

## 화면 근거

Android CLI로 설정창의 후보·사유·선택 상태를 확인했습니다. `Cannot run together`, `Unsupported streams`, `Camera2 only`를 확인했으며, 차단 항목을 탭한 뒤에도 선택창이 유지됐습니다.

사용자가 게시를 승인한 기기 스크린샷입니다.

![Camera2 VIDEO PIP 선택 차단](assets/pip-availability-camera2.png)

![CameraX PIP 스트림 조합 차단](assets/pip-availability-camerax.png)

## 코드 검증

후속 원본 사진 저장·PIP 크기 설정 변경을 포함하여 앱 JVM 테스트 783개, Android lint, 서명 release APK 빌드가 통과했습니다. 독립 코드 리뷰에서 발견한 CameraX 단일·동시 모드 판정, Camera2 종료 중 촬영 대기, 보조 사진의 화면 회전 처리 문제를 수정했습니다. Physical 원본도 별도 요청에 해당 센서의 방향을 적용하며 이 수정의 재리뷰에서 추가 문제는 발견되지 않았습니다.

위 기기 표는 `44517453`의 사전 검사 결과입니다. 후속 변경의 JPEG On 세 장 저장, JPEG Off 합성 한 장 저장, Live Streams의 보조 크기 변경과 Callback ID 전환은 무선 ADB 연결 중단으로 아직 기기에서 검증하지 않았습니다. 다른 기기와 자연 가로 방향 기기의 회전 처리는 이 기록의 하드웨어 검증 범위에 포함하지 않습니다.

문서 변경은 사용자가 승인했으며 승인 기록 작성도 위임했습니다. 문서 검증기의 reviewer는 `TTolsun (approval delegated to Codex)`로 기록했습니다.

사용 방법은 [Live PIP 가이드](../../guide/live.md#pip로-보이는-구도를-저장하세요)에서 확인합니다.
