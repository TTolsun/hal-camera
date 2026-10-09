---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/Camera2Engine.kt
  - app/src/main/java/dev/halcamera/camera/CameraOpenRetry.kt
  - app/src/main/java/dev/halcamera/camera/CameraReleaseWait.kt
  - app/src/main/java/dev/halcamera/camera/LiveStreamSettings.kt
  - app/src/main/java/dev/halcamera/camera/LiveStabilization.kt
  - app/src/main/java/dev/halcamera/camera/LiveStreamCapabilities.kt
  - app/src/main/java/dev/halcamera/camera/LiveSessionCheck.kt
  - app/src/main/java/dev/halcamera/camera/Camera2StillCapture.kt
  - app/src/main/java/dev/halcamera/camera/Camera2LiveRecorder.kt
  - app/src/main/java/dev/halcamera/camera/Camera2VideoSnapshot.kt
  - app/src/main/java/dev/halcamera/camera/VideoSnapshot.kt
  - app/src/main/java/dev/halcamera/camera/BenchmarkRecorder.kt
  - app/src/main/java/dev/halcamera/camera/PreviewBufferRelay.kt
  - app/src/main/java/dev/halcamera/camera/RecordingBufferRelay.kt
  - app/src/main/java/dev/halcamera/camera/StreamConfiguration.kt
  - app/src/main/java/dev/halcamera/camera/StillPair.kt
  - app/src/main/java/dev/halcamera/camera/YuvPacking.kt
  - app/src/main/java/dev/halcamera/camera/OriginalYuv.kt
  - app/src/main/java/dev/halcamera/camera/RawFrame.kt
  - app/src/main/java/dev/halcamera/camera/DngOutput.kt
  - app/src/main/java/dev/halcamera/camera/StillEncoding.kt
  - app/src/main/java/dev/halcamera/camera/MediaLibrary.kt
  - app/src/main/java/dev/halcamera/camera/LiveControls.kt
  - app/src/main/java/dev/halcamera/camera/LiveControlRequests.kt
  - app/src/main/java/dev/halcamera/camera/ManualControls.kt
  - app/src/main/java/dev/halcamera/camera/ManualControlRequests.kt
  - app/src/main/java/dev/halcamera/camera/TouchMeter.kt
  - app/src/main/java/dev/halcamera/camera/TouchMeterRequests.kt
decisions: []
verifications: []
---

**Camera2Engine은 앱에서 CaptureRequest를 직접 구성합니다.** `request_observed`에 기록한 키를 HAL 로그와 대조할 수 있습니다. 엔진 자체는 세션, 요청 구성, 줌, Live 제어를 담당하고, 사진은 `Camera2StillCapture`, Live 녹화는 `Camera2LiveRecorder`, 터치 측광은 `Camera2TouchFocus`, 벤치마크 녹화는 `BenchmarkRecorder`가 맡습니다. 카메라 요청과 capture 콜백은 엔진이 만든 카메라 스레드 하나에서 처리하고, 버퍼 relay와 파일 저장은 각자의 스레드에서 처리합니다.

### 수동 촬영

**Camera2에서 노출·초점·WB를 고정할 수 있습니다.** 요청한 값과 실제 적용값을 함께 확인하세요.

<details markdown="1" id="detail-611519fe59" data-search-section>
<summary>수동 촬영 구현</summary>

`ManualControls`는 ISO와 노출 시간을 한 쌍으로 저장하고 초점과 WB를 독립적으로 관리합니다. `manualSupport`는 capability와 요청 키, 센서 범위, 초점 이동 범위, 지원 AWB 모드를 확인합니다. 고정 초점에는 수동 초점을 제공하지 않습니다. `ManualControlPanel`은 요청값과 capture result의 실제 값을 구분해서 표시합니다.

Camera2 Live의 프리뷰·사진·녹화·녹화 중 사진 요청은 기존 제어와 터치 영역을 적용한 뒤 `applyManualControls`를 호출합니다. 수동 노출에서는 AE를 끄고 ISO·노출 시간·frame duration을 지정합니다. 수동 초점은 AF를 끄고 lens focus distance를 지정하므로 이전 터치가 모드를 덮어쓰지 못합니다. 자동으로 복귀하면 새 요청에는 수동 센서 키를 넣지 않습니다. Benchmark는 이 경로를 호출하지 않습니다.

수동 frame duration은 Live FPS의 상한으로 정하며 Auto FPS에서는 30fps를 사용합니다.

노출 시간은 센서 범위와 frame duration 중 작은 값으로 제한합니다.

영상 출력 자체의 최소 프레임 시간이나 HAL 양자화 때문에 실제 값이 다를 수 있으므로 `capture_result`의 ISO·노출·frame duration을 함께 확인합니다.

수동 WB는 MANUAL_POST_PROCESSING과 관련 요청 키가 있고 수동 노출이 켜져 있을 때 gains와 transform을 사용합니다.

색온도를 추정하지 않습니다.

</details>

### 손떨림 보정

**지원 모드가 있어도 모든 크기와 FPS에서 적용되지는 않습니다.** Live의 결과 표시로 실제 적용 상태를 확인하세요.

<details markdown="1" id="detail-c8f808cdf0" data-search-section>
<summary>손떨림 보정 구현</summary>

Live Streams의 Stabilization에서 Auto, Off와 기기가 지원하는 OIS·EIS (Video)·EIS (Preview + Video)를 선택합니다.

EIS (Preview + Video)는 Android 13 이상에서 지원 목록과 요청 키가 모두 있을 때 제공합니다.

OIS와 EIS (Video)는 동시에 요청하지 않으며, EIS (Preview + Video)에서는 플랫폼이 OIS를 제어합니다.

Auto는 새 요청 템플릿의 기본값을 유지합니다.

설정은 프리뷰·사진·녹화·녹화 중 사진 요청에 적용합니다.

촬영·녹화가 끝난 뒤 카메라를 닫고 재개하며 카메라와 엔진마다 값을 분리합니다.

지원 모드가 있어도 모든 크기·FPS에서 적용된다는 뜻은 아닙니다.

Live 상단에서 현재 프리뷰·녹화의 EIS 적용 상태를 확인하세요. 요청과 다른 결과가 1초 이상 이어지면 요청값과 결과값을 함께 표시합니다.

결과 키가 없거나 1.5초 이상 오래됐으면 확인 불가로 표시하며, 사진 요청의 결과를 프리뷰 상태에 섞지 않습니다.

`request_observed`와 `capture_result`에는 `opticalStabilization`, `videoStabilization`, `cropRegion`을 기록합니다.

crop metadata는 보정 변환 전체나 실제 화각을 나타내지 않습니다.

Benchmark의 요청은 이 설정을 읽지 않습니다.

</details>

### 세션 구성

**선택한 출력 조합을 기기가 받아들여야 프리뷰가 열립니다.** 개별 크기를 지원하는 것만으로 조합이 보장되지는 않습니다.

<details markdown="1" id="detail-1a87542f67" data-search-section>
<summary>세션 구성 구현</summary>

Live의 기본 세션은 프리뷰, YUV_420_888, JPEG 세 스트림으로 구성합니다. `Lab → Settings → Live Streams`에서 정확한 크기와 프리뷰 FPS 범위를 선택하고 YUV·JPEG를 각각 끌 수 있습니다. 프리뷰는 항상 켜집니다. 아래 표는 설정을 지정하지 않았을 때의 픽셀 예산입니다. 예산 이하 크기가 없으면 가장 작은 지원 크기를 고릅니다.

| 스트림 | Live에서 고르는 크기 | 용도 |
| --- | --- | --- |
| 프리뷰 | 1280×720 이하에서 가장 큰 크기 | TextureView 표시 |
| YUV (`analysis_acquire_latest`) | 640×480 이하에서 가장 큰 크기 | 프레임 도착 기록, 사진 쌍의 YUV |
| JPEG (`still`) | 1920×1080 이하에서 가장 큰 크기 | 사진 쌍의 JPEG, 벤치마크 still |

벤치마크 세션은 profile의 `StreamSpec` 크기를 그대로 사용합니다. 지원하지 않는 크기이면 작은 크기로 대체하지 않고 구성 단계에서 실패합니다. 같은 profile ID로 다른 크기를 측정하면 비교가 무의미해지기 때문입니다.

명시한 Live 설정도 작은 크기로 대체하지 않습니다.

`LiveStreamSupport`는 개별 크기·일반 FPS 범위·녹화 인코더 지원을 검사하고, 엔진은 출력의 최소 프레임 시간과 FPS 하한을 대조합니다.

Android 10 이상에서는 `isSessionConfigurationSupported`로 출력 조합을 조회합니다.

조회를 지원하지 않거나 Android 9 이하이면 지원 여부를 미확인으로 기록하고 실제 세션 구성으로 확인합니다.

조합 조회만으로 FPS 지원을 확정하지 않으며 `capture_result.fpsRange`와 `frameDurationNs`로 실제 결과를 확인합니다.

설정 적용은 기존 엔진의 `close(done)` 뒤 새 엔진을 여는 순서입니다.

실패 이유는 화면에 남고, 설정 적용에 실패했고 정상 구성 기록이 있을 때만 오류 아래에 나타나는 `이전 설정으로 복원` 버튼으로 해당 카메라에서 마지막으로 구성이 성공했던 값으로 돌아갑니다.

사진·녹화·저장 중에는 적용하지 않습니다.

카메라와 엔진마다 설정을 분리하고 Activity 재생성 시 복원합니다.

CameraX도 같은 설정 화면에서 출력과 크기를 선택합니다.

Live 옆의 크기 표시를 누르면 설정을 바로 열며 이 경로의 뒤로 가기와 저장은 Live로 돌아갑니다.

Benchmark는 Live 설정을 읽지 않습니다.

CLI는 기본값에 명시한 크기 옵션을 적용하며 이전 화면 설정을 이어받지 않습니다.

Live에서 첫 프레임 전에 카메라 열기가 `onDisconnected`나 `onError`(사용 중, 최대 개수 초과, 기기·서비스 오류) 또는 같은 원인의 `CameraAccessException`으로 실패하면, `CameraOpenRetry`에 따라 200·400·800·1600·2000ms 간격으로 최대 5번 다시 엽니다(#224). 다른 엔진이 카메라를 놓은 직후 카메라 서비스가 열기를 잠깐 거부할 수 있기 때문입니다.

다음 열기는 실패한 기기의 `onClosed` 뒤에 예약하고, 시도마다 `open_retry` 이벤트에 원인·회차·지연을 남깁니다.

카메라가 비활성화됐거나 재시도를 모두 쓰면 실패를 바로 알려 대기 중인 CLI 요청이 타임아웃까지 기다리지 않습니다.

벤치마크와 첫 프레임 이후의 오류는 다시 열지 않습니다.

엔진이나 카메라를 바꿀 때 Live의 첫 Camera2 열기는 이전 엔진이 쓰던 카메라가 실제로 해제될 때까지 기다립니다(#230). CameraX는 `CameraState.CLOSED` 뒤 `close(done)`을 부르지만 카메라 서비스는 그 카메라를 약 1초 더 잡고 있어, 그 사이의 열기가 거부되고 재시도 간격이 해제 시점을 늦게 맞혔기 때문입니다.

`CameraManager.AvailabilityCallback`이 그 카메라를 사용 가능으로 알리면 바로 열고, 2초가 지나면 기다리지 않고 엽니다.

이미 해제된 카메라는 콜백 등록 직후 사용 가능으로 알려지므로 기다리지 않습니다.

대기 시작과 끝은 `release_wait`·`release_waited` 이벤트에 카메라·결과·대기 시간으로 남고, 그 뒤의 열기 실패는 위 재시도가 그대로 맡습니다.

벤치마크는 기다리지 않습니다.

`StreamConfiguration`은 세션을 만드는 출력, 요청의 대상, Callback 그래프가 표시하는 스트림을 같은 목록에서 만듭니다. Android 13 이상의 Live에서는 `PreviewBufferRelay`가 PRIVATE 프리뷰 버퍼를 먼저 받아 도착 시각을 기록한 뒤 TextureView로 넘깁니다. 픽셀은 복사하지 않습니다. 그보다 낮은 버전에서는 TextureView에 직접 연결하고, 프리뷰 출력을 관측할 수 없다고 표시합니다.

</details>

### 사진

**요청한 사진 출력을 받은 뒤 파일로 저장합니다.** 두 이미지의 연결 기준은 엔진마다 다릅니다.

<details markdown="1" id="detail-902b2a5546" data-search-section>
<summary>사진 구현</summary>

1. `capture()`나 `capturePhoto()`를 받으면, 플래시가 Auto·On이고 AE가 잠겨 있지 않은 경우에만 AE precapture를 먼저 실행합니다.

trigger를 프리뷰 capture 하나로 보내고, AE 상태가 PRECAPTURE를 지나 벗어날 때까지 기다립니다.

PRECAPTURE 없이 안정 상태가 결과 3개 연속으로 이어지면 이 순서를 건너뛰는 기기로 보고, 3초 안에 끝나지 않으면 `precapture_timeout`을 남기고 그대로 촬영합니다.
2. still 요청 하나에 켜진 YUV·JPEG 출력을 대상으로 지정하고, 화면 방향을 `JPEG_ORIENTATION`으로, 품질을 95로 설정합니다.

둘 다 꺼져 있으면 셔터를 비활성화하고 엔진도 촬영을 거절합니다.
3. `StillPair`가 센서 타임스탬프로 요청한 버퍼만 기다립니다.

단일 출력도 capture의 센서 시각과 일치해야 하며 꺼진 출력은 기다리지 않습니다.

기다리는 동안에는 reader가 `acquireLatestImage` 대신 `acquireNextImage`로 이미지를 순서대로 꺼냅니다.

최신 이미지만 꺼내면 촬영 대상인 YUV 프레임을 버릴 수 있기 때문입니다.
4. 이미지 콜백에서 stride와 crop을 고려해 NV21으로 복사하고 Image를 닫습니다.

YUV Save Format이 JPEG이면 저장 스레드에서 `encodeYuvStill`로 압축하고 화면 방향만큼 회전합니다.

NV21이면 복사한 샘플을 그대로 저장합니다.
5. `MediaLibrary.saveCapture`가 선택한 이미지와 촬영 JSON을 쓰고 모두 성공한 뒤 공개합니다.

실패하면 이번 촬영에서 만든 항목의 삭제를 시도합니다.
6. 5초 안에 짝이 완성되지 않으면 `capture_timeout`으로 끝냅니다.

녹화 중에는 촬영 요청을 거절합니다.

벤치마크 still은 JPEG만 대상으로 하고, JPEG 도착이 측정값이며 저장하지 않습니다.

</details>

#### YUV 저장 포맷

**사진 파일을 열어 보려면 JPEG를, 변환 전 YUV 샘플을 분석하려면 NV21을 선택하세요.** YUV Save Format에서 둘 중 하나를 고릅니다. 두 포맷을 동시에 저장하지 않습니다.

| 선택 | 파일 | 폴더 | 지원 |
| --- | --- | --- | --- |
| JPEG | `_YUV.jpg` | `DCIM/HALCamera` | Camera2·CameraX |
| NV21 | `_YUV.nv21` | `Download/HALCamera` | Camera2만 지원하며, 짝수 크기·프레임당 16 MiB 이하여야 합니다. |
| 촬영 정보 (자동 저장) | `_metadata.json` | `Download/HALCamera` | 사진 모드에서 두 포맷 모두 함께 저장합니다. |

이 선택은 녹화 중 사진에는 적용하지 않습니다. 일반 CLI 촬영은 기본 JPEG 포맷을 사용합니다.

`_YUV.nv21`은 YUV_420_888의 crop 영역에 있는 8비트 샘플을 손실 없이 재배열한 파일입니다. Y를 행 순서로 쓰고 V·U를 교대로 쓰며 패딩·회전·압축·색 변환을 적용하지 않습니다. JSON의 outputs에는 실제 파일명·MIME·크기를, NV21에는 출력 plane의 offset/rowStride/pixelStride와 원본 크기·crop·stride를 함께 기록합니다. 저장소의 `docs/design/ORIGINAL-YUV.md`에 복원 규칙이 있습니다.

JPEG와 NV21 촬영 모두 이미지와 같은 SENSOR_TIMESTAMP의 최종 CaptureResult를 기다립니다. JSON의 capture에는 카메라 ID, 요청 ID·태그, 프레임 번호, 센서 시각과 시각 소스, 노출 시간·ISO·프레임 주기·AE 상태, JPEG 방향을 저장합니다. 결과가 없으면 5초 뒤 실패하며 프리뷰 결과로 대체하지 않습니다. 출력별 최대 두 프레임을 보관하고 시각 확정 뒤 다른 버퍼를 버립니다. 저장이 끝날 때까지 다음 촬영을 받지 않습니다.

#### RAW/DNG

Live Streams의 **RAW (DNG)**에서 RAW_SENSOR 크기를 고르면 사진마다 `<촬영명>_RAW.dng`를 DCIM/HALCamera에 함께 저장합니다. 기본값은 Off입니다. 카메라가 RAW capability를 알리지 않으면 선택할 수 없고, CameraX는 RAW를 거절합니다. RAW만 켜고 YUV·JPEG를 꺼도 촬영할 수 있습니다. 세션이 RAW 조합을 거부하면 기존 구성 거부 안내를 표시합니다.

RAW reader는 버퍼 두 개로 열고 still 요청에만 포함합니다. 이미지 콜백은 같은 센서 시각의 RAW 프레임을 행 패딩 없는 16비트 샘플로 직접 버퍼에 복사하고 Image를 닫습니다. 저장 스레드에서 `DngCreator`가 카메라 특성과 같은 SENSOR_TIMESTAMP의 최종 CaptureResult로 DNG를 씁니다. 화면 방향은 DNG 방향 태그에만 넣고 샘플은 회전하지 않습니다. JSON outputs에는 DNG의 크기·센서 시각·CFA 배열과 카메라 특성의 white level·black level을 기록하고, 카메라가 보고한 프레임별 값은 dynamicBlackLevel·dynamicWhiteLevel에 따로 기록합니다. DngCreator는 프레임별 값이 있으면 그 값을 DNG에 씁니다. Live 상단 스트림 표시에는 RAW 크기가 함께 나옵니다. 저장 뒤 상태에 파일 수와 합계 크기를 표시합니다. 벤치마크와 녹화 세션에는 RAW 출력을 넣지 않습니다.

### 녹화

**녹화용 출력으로 전환한 뒤 MP4를 저장합니다.** 크기·FPS·코덱의 지원 조건을 확인하세요.

<details markdown="1" id="detail-11a053d988" data-search-section>
<summary>녹화 구현</summary>

`Camera2LiveRecorder`의 기본값은 MediaRecorder가 지원하는 가로 크기 중 1920×1080 픽셀 예산으로 고른 크기, H.264, 30fps, 10Mbps입니다.

Live 스트림 설정에서는 카메라 크기·고정 AE FPS 범위·인코더의 surface 입력, 크기·프레임률·비트레이트 지원을 만족하는 H.264/HEVC 조합을 선택합니다.

후보 FPS는 24·25·30·60이며 고속 세션은 제공하지 않습니다.

명시한 크기·FPS·코덱으로 준비하고 실패하면 이유를 표시합니다.

소리를 포함하면 AAC 128kbps, 44.1kHz를 사용합니다.

1. 녹화를 시작하면 프리뷰와 인코더, 그리고 녹화 중 사진용 JPEG 스트림으로 새 세션을 만듭니다. YUV 스트림은 이 세션에 없으므로 녹화 중 사진은 JPEG만 저장합니다. 카메라가 이 조합을 거절하면 JPEG 없이 프리뷰와 인코더만으로 다시 구성하고, 이 경우 녹화 중 사진을 지원하지 않는다는 이유를 화면에 알립니다.
2. Android 13 이상에서는 `RecordingBufferRelay`가 인코더로 가는 PRIVATE 버퍼를 먼저 받아 도착 시각을 기록합니다. 그보다 낮은 버전에서는 인코더에 직접 연결하고, 녹화 출력을 관측할 수 없다고 표시합니다.
3. 녹화 요청은 `TEMPLATE_RECORD`이며, 연속 동영상 AF(`CONTINUOUS_VIDEO`)를 지원하면 사용합니다. 줌과 Live 제어는 녹화 중에도 같은 요청을 다시 만들어 적용합니다.
4. 정지하면 세션을 닫고, 파일을 `MediaLibrary.saveVideo`로 앨범에 공개한 뒤 프리뷰 세션을 다시 만듭니다. 파일이 재생할 수 없을 만큼 짧으면 저장하지 않고 알립니다.

**녹화 중 사진(`Camera2VideoSnapshot`)은 녹화를 멈추지 않고 JPEG 한 장을 저장합니다.** 녹화 세션의 JPEG 크기는 요청한 크기(없으면 1080p 이하 중 가장 큰 크기)를 먼저 시도하고, 세션 조합 조회(`isSessionConfigurationSupported`)가 거절하면 녹화 크기 안에 들어가는 가장 큰 크기로 내려갑니다.

실제 크기는 `recording_started`의 `snapshotSize`에 남아 요청값과 구분됩니다.

사진 요청은 `TEMPLATE_VIDEO_SNAPSHOT`이며 프리뷰·인코더·JPEG 세 출력을 모두 대상으로 합니다.

사진은 한 번에 한 장만 처리하고, 앞의 사진이 저장되는 중이거나 녹화가 멈추는 중이면 새 요청을 거절합니다.

5초 안에 JPEG이 오지 않거나 저장에 실패하면 `video_snapshot_failed`를 기록하고 알림만 표시하며, 녹화 파일과 세션은 그대로 유지합니다.

정지와 겹친 사진은 세션이 닫히기 전에 도착하면 저장하고, 그렇지 않으면 실패로 답합니다.

Live 스트림 설정에서 JPEG을 끄면 녹화 중 사진도 지원하지 않습니다.

`live_streams_changed`·`live_streams_requested`는 변경·요청값을, `live_stream_preflight`는 출력 조합 조회 결과를 기록합니다.

성공한 출력 크기는 `configured`와 `negotiatedStreams`에 남습니다.

녹화는 `live_recording_requested`·`live_recording_preflight`·`recording_started`로 구분하며, 요청한 인코더 설정과 결과 FPS를 혼동하지 않습니다.

벤치마크의 RECORD 단계는 `BenchmarkRecorder`가 따로 처리합니다. 측정은 크기를 스스로 고르거나 소리를 녹음하거나 앨범에 저장하면 안 되기 때문입니다.

</details>

### Live 제어

**줌·EV·잠금 값은 요청 종류가 바뀌어도 유지하도록 적용합니다.**

<details markdown="1" id="detail-c1f77c4a04" data-search-section>
<summary>Live 제어 구현</summary>

`applyLiveControls`가 프리뷰·still·녹화 요청에 같은 값을 넣으므로, 요청 종류가 바뀌어도 잠금이나 EV가 빠지지 않습니다.

| 제어 | 요청 키 |
| --- | --- |
| EV | `CONTROL_AE_EXPOSURE_COMPENSATION`. AE 잠금 중에도 적용됩니다. |
| AE 잠금 | `CONTROL_AE_LOCK` |
| 플래시 Auto·On | `CONTROL_AE_MODE`의 `ON_AUTO_FLASH`·`ON_ALWAYS_FLASH` |
| 토치 | `FLASH_MODE_TORCH` |
| AF 잠금 | 키가 아니라 연속 AF 모드에서 보내는 `CONTROL_AF_TRIGGER_START` 한 번입니다. 잠금을 풀 때는 `CANCEL`을 보냅니다. |

녹화 시작·정지로 세션이 바뀌면 `AeRelock`이 새 세션을 잠금 없이 시작합니다. AE 상태가 결과 2개 연속으로 안정되거나 2초가 지나면 다시 잠급니다. 새 세션의 첫 요청부터 잠그면 아직 수렴하지 않은 노출이 고정되기 때문입니다. 다시 잠근 노출이 이전보다 1/3 EV 넘게 다르면 "Exposure locked again · 0.8 EV darker" 같은 안내를 표시합니다. AF 잠금도 새 세션에서 trigger를 다시 보냅니다.

줌과 제어는 입력마다 요청을 보내지 않습니다. 값을 먼저 저장하고 카메라 스레드에 요청 하나만 예약하므로, 빠른 드래그는 스레드 한 차례에 요청 하나로 합쳐집니다.

</details>

### 터치 측광

**짧게 터치하면 초점을, 길게 누르면 노출을 조절합니다.** 두 지점은 따로 관리합니다.

<details markdown="1" id="detail-1a7dfc7f16" data-search-section>
<summary>터치 측광 구현</summary>

`Camera2TouchFocus`는 탭한 AF 지점과 길게 누른 AE 지점을 따로 가집니다.

1. TextureView의 변환 행렬을 거꾸로 적용해 기기 기본 방향의 정규화 좌표를 구합니다. `TouchMeter.toSensor`가 전면 카메라의 좌우 반전을 되돌리고 `SENSOR_ORIENTATION`만큼 돌려 센서 좌표로 바꿉니다.
2. `TouchMeter.region`이 보이는 영역의 짧은 변의 1/6 크기 정사각형을 만듭니다. 16:9 프리뷰는 4:3 active array의 가운데만 보여 주므로, 영역도 보이는 범위 안으로 제한합니다.
3. 짧은 터치는 AF 영역과 AUTO AF 모드를 repeating 요청에 넣고 `AF_TRIGGER_START`를 한 번 보냅니다. trigger capture가 끝난 뒤의 결과만 읽으며, FOCUSED_LOCKED는 성공, NOT_FOCUSED_LOCKED는 실패입니다. 3초 안에 결과가 없으면 실패로 처리하고, 결과가 나온 뒤 5초가 지나면 연속 AF로 돌아갑니다. AF 잠금 중이면 지점을 유지합니다.
4. 길게 누르면 AE 영역을 넣고, AE 상태가 결과 2개 연속으로 안정되거나 2초가 지나면 측광이 끝난 것으로 봅니다. 그다음 화면이 AE 잠금을 겁니다.

</details>
