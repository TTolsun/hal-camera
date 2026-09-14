---
based_on: ["overall-architecture"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkController.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkCsv.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RunIndex.kt","app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/camera/LiveController.kt","app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt"]
decisions: []
verifications: []
---
각 패키지는 명확한 책임을 지며 변경 시 해당 클래스의 의존성을 확인해야 합니다. `cli/`와 `tools/halcam/`는 shell 호출자 검사, 영속 요청 상태, artifact 등록을 담당하며 `CommandCoordinator`와 `CommandStore`를 통해 요청 ID 와 진행 상태를 관리합니다. `camera/`는 엔진 계약과 구현 (`Camera2Engine`, `CameraXEngine`) 을 제공하며 `LiveController` 는 CLI 명령을 동기화하고 `RecentMediaThumbnail` 은 앨범 썸네일을 별도 스레드에서 읽습니다. `metrics/`는 `MetricExtractor` 가 이벤트를 관측 표본으로 재구성하는 순수 계산 로직만 담당합니다. `benchmark/`는 프로파일, 러너, 지표 계산, 저장 (`BenchmarkReport`, `BenchmarkStore`), 비교 (`RegressionDetector`) 와 이력 화면을 제공하며 `ScoreComposer` 는 최소 10 개 실행을 기반으로 스케일링 곡선을 계산합니다.

실행과 평가 영역은 카메라 하드웨어 제어와 실시간 측정을 담당합니다. `MainActivity` 의 `openCamera` 와 `tick` Runnable 이 센서 이벤트를 필터링하여 프레임 간격, ISO, 노출 등을 계산하며 `LiveReadout` 과 UI 컴포넌트에서 시각화합니다. 기록과 저장 영역은 벤치마크 결과를 영구적으로 관리합니다. `FlightRecorder` 는 30 초 순환 버퍼와 incident ZIP 을 생성하고 `Telemetry` 가 이벤트 로그를 기록합니다. `BenchmarkReport` 는 schema 4 JSON 파일을 쓰고 읽으며, `HistoryActivity` 와 `RunIndex` 는 실행 이력을 필터링하고 `BenchmarkCsv` 는 지표별 CSV 를 작성합니다. 각 영역은 별도 스레드에서 처리되며 파일 작업과 비교는 원본 JSON 을 다시 읽는 별도의 실행기에서 수행됩니다.
