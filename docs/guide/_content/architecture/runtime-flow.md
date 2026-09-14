---
based_on: ["data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/GalleryActivity.kt","app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BaselineManager.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt","app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt","app/src/main/java/dev/halcamera/cli/CliProvider.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","app/src/main/java/dev/halcamera/ui/ExpandingZoomControl.kt","app/src/main/java/dev/halcamera/ui/GalleryImageView.kt","app/src/main/java/dev/halcamera/ui/IconButton.kt","app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt","app/src/main/java/dev/halcamera/ui/SelectionPopup.kt","app/src/main/java/dev/halcamera/ui/ShutterButton.kt","tools/halcam/halcam/cli.py","tools/halcam/halcam/download.py"]
decisions: []
verifications: []
---
사용자 조작은 UI 계층에서 처리되어 HAL 요청으로 전달됩니다. LIVE 셔터 조작은 `MainActivity`의 LiveController를 통해 선택된 엔진의 촬영·녹화 동작으로 이어집니다. 벤치마크는 별도 화면인 `BenchmarkActivity`가 준비를 마친 뒤 `BenchmarkRunner.start()`를 호출하여 시작합니다. `Telemetry.callback(...)`이 반환한 Camera2 콜백의 `onCaptureStarted`는 프레임워크 신호를 기록하며, LIVE 셔터와 벤치마크 시작을 연결하는 메서드가 아닙니다.

이벤트 경로와 러너 경로는 UI 이벤트 처리 (클릭 등) 와 데이터 로드/처리 작업 (IO, 계산 등) 을 분리하기 위해 따로 존재합니다. UI 상태 변경은 메인 스레드 (`main = Handler(Looper.getMainLooper())`) 를 통해 즉시 반영되지만, 중대형 작업은 `queryIo`, `imageIo`, `thumbnailIo` 같은 별도의 스레드 풀에서 병렬 처리됩니다. 두 경로는 `main.post` 또는 `Runnable` 을 통해 합쳐져 최종 결과를 화면에 표시합니다.

결과가 예상과 다를 때는 먼저 데이터 로드 단계의 실패 메시지를 확인해야 합니다. `loadMedia` 함수는 `queryIo` 를 통해 미디어 목록을 조회하고, `showCurrent` 는 `imageIo` 를 통해 이미지 데이터를 로드하며, 각 단계에서 `result.fold` 로 성공/실패 처리가 이루어집니다. 실패 시 `empty.text` 나 `loading.text` 에 "불러오지 못했습니다" 등의 메시지가 표시됩니다.
