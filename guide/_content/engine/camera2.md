---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/Camera2Engine.kt
  - app/src/main/java/dev/halcamera/camera/Camera2StillCapture.kt
  - app/src/main/java/dev/halcamera/camera/Camera2LiveRecorder.kt
  - app/src/main/java/dev/halcamera/camera/BenchmarkRecorder.kt
  - app/src/main/java/dev/halcamera/camera/PreviewBufferRelay.kt
  - app/src/main/java/dev/halcamera/camera/RecordingBufferRelay.kt
  - app/src/main/java/dev/halcamera/camera/StreamConfiguration.kt
  - app/src/main/java/dev/halcamera/camera/StillPair.kt
  - app/src/main/java/dev/halcamera/camera/YuvPacking.kt
  - app/src/main/java/dev/halcamera/camera/StillEncoding.kt
  - app/src/main/java/dev/halcamera/camera/MediaLibrary.kt
  - app/src/main/java/dev/halcamera/camera/LiveControls.kt
  - app/src/main/java/dev/halcamera/camera/LiveControlRequests.kt
  - app/src/main/java/dev/halcamera/camera/TouchMeter.kt
  - app/src/main/java/dev/halcamera/camera/TouchMeterRequests.kt
decisions: []
verifications: []
---

**Camera2Engine은 요청 키를 앱이 모두 정합니다.** 그래서 `request_observed`에 기록된 요청과 HAL 로그를 그대로 대조할 수 있습니다. 엔진 자체는 세션, 요청 구성, 줌, Live 제어를 담당하고, 사진은 `Camera2StillCapture`, Live 녹화는 `Camera2LiveRecorder`, 터치 측광은 `Camera2TouchFocus`, 벤치마크 녹화는 `BenchmarkRecorder`가 맡습니다. 카메라 요청과 capture 콜백은 엔진이 만든 카메라 스레드 하나에서 처리하고, 버퍼 relay와 파일 저장은 각자의 스레드에서 처리합니다.

### 세션 구성

Live 세션은 프리뷰, YUV_420_888, JPEG 세 스트림으로 구성합니다.

| 스트림 | Live에서 고르는 크기 | 용도 |
| --- | --- | --- |
| 프리뷰 | 1280×720 이하에서 가장 큰 크기 | TextureView 표시 |
| YUV (`analysis_acquire_latest`) | 640×480 이하에서 가장 큰 크기 | 프레임 도착 기록, 사진 쌍의 YUV |
| JPEG (`still`) | 1920×1080 이하에서 가장 큰 크기 | 사진 쌍의 JPEG, 벤치마크 still |

벤치마크 세션은 profile의 `StreamSpec` 크기를 그대로 사용합니다. 지원하지 않는 크기이면 작은 크기로 대체하지 않고 구성 단계에서 실패합니다. 같은 profile ID로 다른 크기를 측정하면 비교가 무의미해지기 때문입니다.

`StreamConfiguration`은 세션을 만드는 출력, 요청의 대상, Callback 그래프가 표시하는 스트림을 같은 목록에서 만듭니다. Android 13 이상의 Live에서는 `PreviewBufferRelay`가 PRIVATE 프리뷰 버퍼를 먼저 받아 도착 시각을 기록한 뒤 TextureView로 넘깁니다. 픽셀은 복사하지 않습니다. 그보다 낮은 버전에서는 TextureView에 직접 연결하고, 프리뷰 출력을 관측할 수 없다고 표시합니다.

### 사진

1. `capture()`나 `capturePhoto()`를 받으면, 플래시가 Auto·On이고 AE가 잠겨 있지 않은 경우에만 AE precapture를 먼저 실행합니다. trigger를 프리뷰 capture 하나로 보내고, AE 상태가 PRECAPTURE를 지나 벗어날 때까지 기다립니다. PRECAPTURE 없이 안정 상태가 결과 3개 연속으로 이어지면 이 순서를 건너뛰는 기기로 보고, 3초 안에 끝나지 않으면 `precapture_timeout`을 남기고 그대로 촬영합니다.
2. still 요청 하나에 YUV와 JPEG를 모두 대상으로 지정하고, 화면 방향을 `JPEG_ORIENTATION`으로, 품질을 95로 설정합니다.
3. `StillPair`가 센서 타임스탬프로 두 버퍼를 연결합니다. 짝을 기다리는 동안에는 reader가 `acquireLatestImage` 대신 `acquireNextImage`로 이미지를 순서대로 꺼냅니다. 최신 이미지만 꺼내면 짝이 될 YUV 프레임을 버릴 수 있기 때문입니다.
4. 별도 작업 스레드에서 `YuvPacking`이 stride와 crop을 고려해 NV21을 만들고, `encodeYuvStill`이 JPEG로 압축한 뒤 화면 방향만큼 회전합니다.
5. `MediaLibrary.savePair`가 두 파일을 `DCIM/HALCamera`에 쓰고 둘 다 성공했을 때만 공개합니다. 하나라도 실패하면 둘 다 지웁니다.
6. 5초 안에 짝이 완성되지 않으면 `capture_timeout`으로 끝냅니다. 녹화 중에는 촬영 요청을 거절합니다.

벤치마크 still은 JPEG만 대상으로 하고, JPEG 도착이 측정값이며 저장하지 않습니다.

### 녹화

`Camera2LiveRecorder`는 MediaRecorder로 H.264 MPEG-4 파일을 기록합니다. 크기는 MediaRecorder가 지원하는 가로 크기 중 1920×1080 이하에서 가장 큰 것이고, 30fps, 10Mbps입니다. 소리를 포함하면 AAC 128kbps, 44.1kHz를 사용합니다.

1. 녹화를 시작하면 프리뷰와 인코더 두 스트림으로 새 세션을 만듭니다. YUV와 JPEG는 이 세션에 없으므로 녹화 중에는 사진을 찍지 않습니다.
2. Android 13 이상에서는 `RecordingBufferRelay`가 인코더로 가는 PRIVATE 버퍼를 먼저 받아 도착 시각을 기록합니다. 그보다 낮은 버전에서는 인코더에 직접 연결하고, 녹화 출력을 관측할 수 없다고 표시합니다.
3. 녹화 요청은 `TEMPLATE_RECORD`이며, 연속 동영상 AF(`CONTINUOUS_VIDEO`)를 지원하면 사용합니다. 줌과 Live 제어는 녹화 중에도 같은 요청을 다시 만들어 적용합니다.
4. 정지하면 세션을 닫고, 파일을 `MediaLibrary.saveVideo`로 앨범에 공개한 뒤 프리뷰 세션을 다시 만듭니다. 파일이 재생할 수 없을 만큼 짧으면 저장하지 않고 알립니다.

벤치마크의 RECORD 단계는 `BenchmarkRecorder`가 따로 처리합니다. 측정은 크기를 스스로 고르거나 소리를 녹음하거나 앨범에 저장하면 안 되기 때문입니다.

### Live 제어

`applyLiveControls`가 프리뷰·still·녹화 요청에 같은 값을 넣으므로, 요청 종류가 바뀌어도 잠금이나 EV가 빠지지 않습니다.

| 제어 | 요청 키 |
| --- | --- |
| EV | `CONTROL_AE_EXPOSURE_COMPENSATION`. AE 잠금 중에도 적용됩니다. |
| AE 잠금 | `CONTROL_AE_LOCK` |
| 플래시 Auto·On | `CONTROL_AE_MODE`의 `ON_AUTO_FLASH`·`ON_ALWAYS_FLASH` |
| 토치 | `FLASH_MODE_TORCH` |
| AF 잠금 | 키가 아니라 연속 AF 모드에서 보내는 `CONTROL_AF_TRIGGER_START` 한 번입니다. 잠금을 풀 때는 `CANCEL`을 보냅니다. |

녹화 시작·정지로 세션이 바뀌면 `AeRelock`이 새 세션을 잠금 없이 시작합니다. AE 상태가 결과 2개 연속으로 안정되거나 2초가 지나면 다시 잠급니다. 새 세션의 첫 요청부터 잠그면 아직 수렴하지 않은 노출이 고정되기 때문입니다. 다시 잠근 노출이 이전보다 1/3 EV 넘게 다르면 "노출을 다시 잠갔습니다 · 이전보다 0.8 EV 어둡습니다" 같은 안내를 표시합니다. AF 잠금도 새 세션에서 trigger를 다시 보냅니다.

줌과 제어는 입력마다 요청을 보내지 않습니다. 값을 먼저 저장하고 카메라 스레드에 요청 하나만 예약하므로, 빠른 드래그는 스레드 한 차례에 요청 하나로 합쳐집니다.

### 터치 측광

`Camera2TouchFocus`는 탭한 AF 지점과 길게 누른 AE 지점을 따로 가집니다.

1. TextureView의 변환 행렬을 거꾸로 적용해 기기 기본 방향의 정규화 좌표를 구합니다. `TouchMeter.toSensor`가 전면 카메라의 좌우 반전을 되돌리고 `SENSOR_ORIENTATION`만큼 돌려 센서 좌표로 바꿉니다.
2. `TouchMeter.region`이 보이는 영역의 짧은 변의 1/6 크기 정사각형을 만듭니다. 16:9 프리뷰는 4:3 active array의 가운데만 보여 주므로, 영역도 보이는 범위 안으로 제한합니다.
3. 짧은 터치는 AF 영역과 AUTO AF 모드를 repeating 요청에 넣고 `AF_TRIGGER_START`를 한 번 보냅니다. trigger capture가 끝난 뒤의 결과만 읽으며, FOCUSED_LOCKED는 성공, NOT_FOCUSED_LOCKED는 실패입니다. 3초 안에 결과가 없으면 실패로 처리하고, 결과가 나온 뒤 5초가 지나면 연속 AF로 돌아갑니다. AF 잠금 중이면 지점을 유지합니다.
4. 길게 누르면 AE 영역을 넣고, AE 상태가 결과 2개 연속으로 안정되거나 2초가 지나면 측광이 끝난 것으로 봅니다. 그다음 화면이 AE 잠금을 겁니다.
