---
based_on: ["overall-architecture","data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt","app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt","app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","tools/halcam/halcam/cli.py"]
decisions: []
verifications: []
---
앱은 CLI 명령어를 통해 시작되며, Live 관측과 벤치마크 실행, 결과 저장 및 비교의 세 가지 주요 단계로 동작합니다. `MainActivity`는 카메라 엔진을 제어하고 실시간 데이터를 수집하는 역할을 하며, `Telemetry`와 `FlightRecorder`는 이벤트와 시스템 상태를 기록합니다. `BenchmarkRunner`가 실행 순서를 관리하고, `RunAssembler`는 러너 결과와 이벤트를 결합하여 표준화된 실행 객체를 생성합니다. 저장된 실행은 `BenchmarkReport`로 JSON 파일로 기록되며, `BaselineManager`와 `RegressionDetector`가 기준 실행과 비교 결과를 산출합니다.

Live 관측은 현재 카메라 상태를 실시간으로 모니터링하는 과정이며, `MainActivity`에서 수집된 데이터를 UI에 표시합니다. 반면 벤치마크 비교는 저장된 실행 기록을 기반으로 반복 측정을 수행하고 회귀 여부를 분석하는 과정입니다. Live 관측은 `Telemetry`를 통해 데이터를 수집하고, 벤치마크 비교는 `FlightRecorder`와 `RunAssembler`를 사용하여 실행 이력을 기록하고 분석합니다. 이력은 `HistoryActivity`에서 관리되며, `ProfileComparisonActivity`에서 직접 선택된 파일들을 기반으로 분석됩니다.

코드를 처음 읽을 때는 `MainActivity.kt` 파일을 열어야 합니다. 여기서는 앱의 전체 흐름과 각 단계의 책임 (카메라 연결, 데이터 수집, 저장)을 파악할 수 있습니다. 개발자는 먼저 카메라 엔진 관리, 데이터 기록 (`Telemetry`, `FlightRecorder`), 그리고 UI 업데이트가 어떻게 처리되는지 확인해야 하며, 이후 벤치마크 실행과 결과 분석 로직으로 넘어갈 수 있습니다.
