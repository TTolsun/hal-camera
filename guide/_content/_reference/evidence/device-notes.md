---
based_on: [overall-architecture]
confidence: device
sources:
  - app/src/main/java/dev/halcamera/camera/CameraXControls.kt
  - app/src/main/java/dev/halcamera/camera/CameraXStillCapture.kt
  - app/src/main/java/dev/halcamera/camera/CameraXLiveRecorder.kt
decisions: []
verifications: [V-002]
---

아래는 위 조건에서 수행한 V-002 기록의 관찰 결과입니다.

| 확인 항목 | 관찰 결과 |
| --- | --- |
| 사진 | 엔진 전환 없이 두 장이 저장됐습니다. JPEG는 4080×3060에 EXIF 방향 6, YUV는 480×640이었습니다. |
| 녹화 | H.264 1920×1080(90도 회전 정보), 약 10Mbps, AAC 48kHz 파일이 저장됐습니다. |
| AE 잠금 중 EV | EV +0.5를 적용하자 노출 시간×ISO가 1.42배가 됐고, 사진 EXIF는 1/59초, ISO 287, 노출 보정 +0.5였습니다. |
| AE 잠금 중 플래시 On 사진 | 플래시가 발광했고 1/1169초, ISO 25로 저장됐습니다. 잠금 직전 프리뷰는 ISO 161, 8.33ms였습니다. |
| 녹화 중 AF 잠금 | 첫 Status 이벤트에서 초점 요청을 다시 보내기 전에는 AF Idle, 다시 보낸 뒤에는 No focus(잠김)로 표시됐습니다. |
| `cancelFocusAndMetering` 뒤의 AE 잠금 | 이 호출을 쓰던 빌드에서는 탭 초점이 끝난 뒤 AE 잠금을 켜도 결과가 AE OK였고, 최신 프레임 메타데이터의 `android.control.aeLock`이 OFF였습니다. 호출을 없앤 빌드에서는 탭 초점 종료, 녹화 시작·종료, 플래시 사진 뒤에도 AE Locked가 유지됐습니다. |

`CameraXControls`의 `cancelFocusAndMetering` 우회와 녹화 중 초점 요청 재전송은 이 관찰에서 나온 수정입니다. 플래시 사진의 재측광은 CameraX ImageCapture의 precapture 순서에서 온 것으로 보지만, CameraX 내부 로그로 확인하지는 않았습니다.
