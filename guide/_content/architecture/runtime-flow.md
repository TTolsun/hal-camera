---
based_on: [data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CliProvider.kt
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - tools/halcam/halcam/cli.py
  - tools/halcam/halcam/download.py
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/GalleryActivity.kt
  - app/src/main/java/dev/halcamera/camera/MediaLibrary.kt
  - app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunValidity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunRetention.kt
  - app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkEvaluator.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt
decisions: []
verifications: []
---

**실행 실패는 러너 결과에서, 측정값의 차이는 이벤트와 계산 규칙에서 확인하세요.** 벤치마크의 입력은 카메라 콜백 이벤트와 러너가 기록한 실행 시각입니다.

이 절은 데이터가 지나가는 경로만 설명합니다. 각 화면을 어떻게 읽고 조작하는지는 해당 화면을 담당하는 문서에 있습니다. 결과 화면과 실행 기록은 [Benchmark](benchmark.md), 사양 표는 [Probe](probe.md), 케이스 실행과 판정은 [CTS](cts.md), ADB 명령의 사용법은 [CLI](cli.md)에서 확인하세요. 배치·간격·애니메이션·접근성 문구 같은 앱의 조작 규칙은 [APP-UI.md](https://github.com/TTolsun/hal-camera/blob/main/docs/design/APP-UI.md)가 관리합니다.

Live 셔터 조작은 `MainActivity`에서 선택한 엔진의 촬영·녹화 동작으로 이어집니다. 벤치마크는 별도 화면인 `BenchmarkActivity`가 준비를 마친 뒤 `BenchmarkRunner.start()`를 호출하여 시작합니다. `Telemetry.callback(...)`이 반환한 Camera2 콜백의 `onCaptureStarted`는 프레임워크 신호를 기록하며, Live 셔터와 벤치마크 시작을 연결하는 메서드가 아닙니다.

### 실행에서 저장까지

1. `BenchmarkActivity`가 선택한 카메라와 profile을 사전 확인합니다.
2. `BenchmarkRunner`가 열기·닫기 반복을 수행합니다. 각 사이클은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE로 진행합니다.
3. 별도 관측 세션에서 WARMUP → OBSERVE → STILL → CLOSE를 진행합니다. profile은 반복 횟수와 관측·촬영 조건을 정합니다.
4. `RunAssembler`가 러너 결과와 이벤트를 결합해 `BenchmarkEvaluator`와 `RunValidityEvaluator`를 호출합니다. `ScoreComposer`는 calibration의 적용 범위와 적격 조건에 맞는 run에만 내부 점수를 채웁니다.
5. `BenchmarkReport`가 실행 JSON을 저장합니다. Activity는 baseline 또는 이전 실행을 찾아 비교 결과를 별도로 계산합니다. 저장 직후 `RunRetention`이 설정된 `보관 개수`를 적용해 한도를 넘는 실행 파일을 오래된 것부터 삭제하며, baseline으로 지정된 실행은 삭제하지 않습니다(기본값은 Unlimited).

`Telemetry.callback()`은 `capture_started`, `request_observed`, `capture_result`, `capture_failed`, `buffer_lost`를 기록합니다. 콜백의 `alive()`가 거짓이면 이미 닫힌 세션의 늦은 이벤트를 버립니다. `request_observed`는 요청 제출 시각이 아니라 `onCaptureStarted`에서 관측한 요청 내용입니다. Live 제어를 확인할 수 있도록 `request_observed`에는 요청한 줌·EV·AE 잠금·플래시 모드·AF/precapture trigger를, `capture_result`에는 적용된 AE 잠금·EV·플래시 상태와 논리 카메라가 알려 주는 물리 카메라 ID(API 29 이상)를 함께 기록합니다.

러너는 API 호출과 완료 신호 사이의 시각 차이를 기록합니다. `RunAssembler`는 관측 세션의 프레임 중 관측 시작 이전에 도착한 프레임 수를 워밍업으로 계산합니다. `MetricExtractor`가 이벤트를 표본으로 연결하고, `BenchmarkEvaluator`가 profile에 따라 초기 반복을 제외하고 통계를 계산합니다. 3A 수렴은 관측 세션의 첫 결과부터 계산합니다.

`RunAssembler.ObservationInput.observedFrames`는 워밍업을 제외한 `steadyFrames`의 수입니다. 이 값은 `RunValidityEvaluator`의 표본 수 검사에 전달됩니다. `RunAssembler`가 구성한 `BenchmarkRun`은 `BenchmarkReportCodec`에서 schema 4로 직렬화하며, 파일 쓰기는 조립기 밖에서 처리합니다.

### 저장된 실행을 다시 계산하는 경로

`RegressionDetector`는 두 실행의 측정 계약·endpoint·validity·환경 조건을 확인합니다. baseline 비교에서만 회귀 판정을 표시하며, 이전 실행이나 임의 선택 실행과의 비교는 변화량과 비교 불가 사유를 표시합니다. 단위가 다르면 각 단위를 유지하고 백분율을 표시하지 않습니다.

측정값이 파일로 저장되는 단계와, 화면에서 비교 결과를 다시 계산하는 단계는 서로 다릅니다. 저장된 JSON에는 비교 결과가 들어 있지 않으며, 결과 화면과 실행 기록은 파일을 읽은 뒤 현재 기준으로 비교를 새로 계산합니다. 기준을 고르는 방법과 결과를 읽는 방법은 [Benchmark](benchmark.md)에 있습니다.

### Live에서 촬영과 저장

Live에서 사진을 촬영하면 Camera2의 같은 요청에 YUV와 JPEG 출력을 지정합니다. 센서 타임스탬프가 일치하는 버퍼를 연결해 별도 작업 스레드에서 사진 쌍을 저장합니다. 동영상은 프리뷰·인코더 세션으로 전환하고 종료 후 파일을 앨범에 공개합니다. CameraX 상태에서 미디어 작업을 요청하면 Camera2로 전환합니다.

`도구` 메뉴는 `Probe`·`CTS`·`Benchmark`를 이 순서로 엽니다. `CTS`와 `Benchmark`는 자기 카메라를 열기 때문에 Live 카메라의 `close(done)` 콜백을 받은 뒤에 화면을 열며, 그동안 메뉴를 비활성화합니다. `Probe`는 카메라를 열지 않으므로 닫기 완료를 기다리지 않고 바로 엽니다. Live 카메라는 화면이 가려질 때 평소처럼 닫히고 돌아오면 다시 열립니다. 녹화·저장·세션 종료·CLI 작업 중에는 도구 메뉴를 비활성화합니다.

### 갤러리 항목의 조회 경로

촬영 화면의 최근 썸네일은 MediaStore에서 HALCamera의 저장 완료 항목만 조회합니다. 파일 저장과 화면 복귀 시 백그라운드에서 갱신하며 항목이 없거나 읽기에 실패하면 갤러리 아이콘을 표시합니다. 촬영 화면을 벗어나면 변경 감시를 중단하므로 뒤늦게 도착한 조회 결과는 반영하지 않습니다.

`GalleryActivity`도 HALCamera 앨범만 조회합니다. 공유 Intent에는 선택한 URI와 읽기 권한만 전달하며, 삭제는 Android 11 이상에서 `MediaStore.createDeleteRequest()`의 시스템 확인을 거칩니다. 2,000개를 넘는 선택은 2,000개 이하로 나누어 순서대로 시스템 확인을 요청합니다. 격자 열 수, 필터, 상세 화면의 확대와 탐색 같은 조작 규칙은 `APP-UI.md`에 있습니다.

### CLI 요청과 결과 수집

CLI 명령은 ADB와 `CliProvider`를 거쳐 `CommandCoordinator`에 접수됩니다. 요청 ID와 내용을 먼저 저장하고 `LiveController`, `BenchmarkController`, `CtsController`가 화면의 카메라·벤치마크·CTS suite 동작을 실행합니다. 사진은 두 이미지의 저장 완료, 벤치마크는 report 파일 쓰기 완료, CTS suite는 보고서 JSON·텍스트 쓰기 완료 후 artifact를 등록합니다. `cameras`·`probe`·`cts.cases`는 화면을 거치지 않고 coordinator가 IO 스레드에서 바로 완료하며, probe 파일은 요청별 `files/cli/artifacts/<request_id>/`에 두었다가 기록 정리와 함께 지웁니다. 명령 접수와 실제 완료는 서로 다른 상태입니다.

PC는 요청 상태를 조회하고 완료된 artifact의 크기와 SHA-256을 확인합니다. 같은 요청 ID와 같은 내용은 기존 결과를 반환하며 새로운 촬영을 시작하지 않습니다. 원본 파일이 삭제되거나 전송이 끊긴 경우에는 요청 ID로 상태를 확인한 다음 파일 수집을 재시도합니다.

앱을 열 때는 투명한 `CliLaunchActivity`가 main thread에서 작업 상태를 다시 확인합니다. 실행 중인 작업이 있으면 Live로 전환하지 않습니다. 상태 조회는 `CommandStore`의 메모리 snapshot을 읽으며 파일 기록은 상태 전환 때만 수행합니다.
