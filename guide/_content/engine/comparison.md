---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/Camera2Engine.kt
  - app/src/main/java/dev/halcamera/camera/Camera2StillCapture.kt
  - app/src/main/java/dev/halcamera/camera/Camera2LiveRecorder.kt
  - app/src/main/java/dev/halcamera/camera/TouchMeterRequests.kt
  - app/src/main/java/dev/halcamera/camera/CameraXEngine.kt
  - app/src/main/java/dev/halcamera/camera/CameraXStillCapture.kt
  - app/src/main/java/dev/halcamera/camera/CameraXLiveRecorder.kt
  - app/src/main/java/dev/halcamera/camera/CameraXControls.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
decisions: []
verifications: []
---

**두 엔진의 측정값을 비교하기 전에 아래 차이가 결과에 영향을 주는지 확인하세요.** 같은 조작이라도 엔진이 카메라에 보내는 요청과 앱이 기록하는 시각이 다를 수 있습니다.

| 항목 | Camera2 | CameraX |
| --- | --- | --- |
| 요청 키 | 앱이 CaptureRequest를 직접 구성하며 `request_observed`에 기록합니다. | 3A 모드와 영역 일부를 CameraX가 정합니다. 앱이 직접 넣는 키는 `CONTROL_AE_LOCK`과 AF cancel trigger뿐입니다. |
| 사진 쌍의 YUV | 같은 capture의 버퍼입니다. 센서 시각이 JPEG와 같습니다. | JPEG와 센서 시각이 가장 가까운 analysis 프레임입니다. 차이는 `yuvOffsetNs`에 기록됩니다. |
| 스트림 선택 | Preview 크기와 YUV·JPEG 활성화·크기, FPS 범위를 선택합니다. | Preview·YUV·JPEG 크기와 출력 활성화를 선택합니다. 요청한 해상도만 필터에 남기며 조합은 bind 성공 여부로 확인합니다. |
| 녹화 코덱 | 기본 H.264이며 지원 조합에서 HEVC도 선택합니다. 오디오는 AAC 128kbps 44.1kHz입니다. | 기기의 encoder profile을 따릅니다. |
| 녹화 중 사진 | 녹화 세션에 JPEG 스트림을 넣고 `TEMPLATE_VIDEO_SNAPSHOT`으로 요청합니다. 조합을 거절하면 JPEG 없이 녹화합니다. | 녹화와 ImageCapture를 함께 bind하고 `takePicture`를 호출합니다. 거절하면 VideoCapture만 bind합니다. 두 엔진 모두 JPEG만 저장합니다. Galaxy S25+에서 Camera2는 영상 프레임 누락이 없었고, CameraX는 사진마다 프레임 1개가 빠졌습니다. |
| AE 잠금 중 플래시 사진 | precapture를 건너뛰고 잠긴 노출로 촬영합니다(`Camera2StillCapture`). | ImageCapture가 자기 순서대로 precapture를 수행합니다. |
| AF 잠금 중 길게 누르기 | 탭한 AF 지점이 없으면 AF trigger를 보내지 않습니다. 탭한 지점이 있으면 그 지점을 끝내면서 `AF_TRIGGER_CANCEL`을 보냅니다(`Camera2TouchFocus`). | AF 잠금도 FocusMeteringAction이므로, 합친 action을 다시 보내면서 AF가 한 번 더 스캔합니다. action에서 AF를 빼면 CameraX가 AF 잠금을 풀기 때문에 피할 수 없습니다(`CameraXControls`). |
| 버퍼 도착 기록 (Android 13 이상) | 프리뷰와 녹화 버퍼의 도착 시각을 relay로 기록합니다. | 프리뷰와 녹화 버퍼는 직접 관측하지 못합니다. ImageAnalysis와 ImageCapture의 이미지 수신은 기록합니다. |
| Live 표시의 프리뷰 판정 | TextureView의 화면 갱신 시각을 씁니다. | PreviewView가 STREAMING 상태이고 최근 capture 결과가 있는지로 판정합니다. |
| Benchmark | 지원합니다. | 지원하지 않으며 Camera2로 엽니다. |
| CLI | 기본 엔진입니다. 크기와 녹화 H264·HEVC를 지정합니다. | `--engine CameraX`로 선택합니다. 크기를 지정하며 녹화 코덱은 Auto입니다. |
