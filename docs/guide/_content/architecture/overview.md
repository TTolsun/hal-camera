---
based_on: ["overall-architecture","data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","tools/halcam/halcam/cli.py"]
decisions: []
verifications: []
---
앱은 CLI 명령을 받아 `CommandCoordinator`가 요청 ID 와 상태 관리를 수행하고, UI 를 통해 카메라 구동 (`CameraEngine`) 과 콜백 기록 (`Telemetry`, `FlightRecorder`) 을 제어합니다. `BenchmarkRunner` 는 실행 순서와 실패 처리를 담당하며, `RunAssembler` 가 러너 결과와 이벤트를 결합하여 JSON 형식의 실행 결과를 만듭니다. 내부 점수는 `ScoreComposer` 가 검토한 calibration 범위에 맞는 적격 release run 에 대해 계산하고 저장합니다. 최종적으로 `RegressionDetector` 는 기준선과 비교하여 회귀 여부를 판정하며, `BenchmarkReport` 는 schema 4 JSON 으로 저장합니다.

LIVE 관측은 `FlightRecorder` 의 실시간 리스너를 통해 즉시 이벤트 (`Event`) 를 처리하고 연속적인 흐름을 추적하는 반면, 벤치마크는 `ScoreComposer` 가 정의된 엄격한 조건 (배터리 상태, 열 관리 등) 을 충족하는 '정상 실행'만 평가합니다. 벤치마크는 `FlightRecorder` 의 `snapshot()` 를 통해 특정 시간 창 (`retentionNs`) 의 데이터를 추출하여 분석하며, LIVE 관측은 연속적인 흐름을 추적합니다. 이력은 `BenchmarkStore` 를 통해 로드되며, `RegressionDetector` 가 두 실행의 `MetricComparison` 을 계산하고 `ResultPresenter` 나 `ComparePresenter` 에서 화면에 표시합니다.

코드를 처음 읽을 때는 먼저 `app/src/main/java/dev/halcamera/telemetry/Telemetry.kt` 와 `FlightRecorder.kt` 를 통해 데이터 수집 구조를 이해한 후, 카메라 제어 로직인 `app/src/main/java/dev/halcamera/camera/CameraEngine.kt` 와 UI 진입점인 `app/src/main/java/dev/halcamera/MainActivity.kt` 를 확인해야 합니다. 평가 로직은 `app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt` 에서 처리됩니다.
