---
based_on: ["overall-architecture","data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","tools/halcam/halcam/cli.py"]
decisions: []
verifications: []
---
앱은 CLI 명령을 `CommandCoordinator`가 관리하고, 카메라 구동은 `CameraEngine`이 담당하며, 데이터 수집은 `Telemetry`와 `FlightRecorder`가 수행합니다. `BenchmarkRunner`는 실행 순서와 실패 처리를 주도하고, `RunAssembler`는 러너 결과와 이벤트를 결합하여 JSON 형식의 실행 결과를 만듭니다. 내부 점수는 `ScoreComposer`가 검토한 calibration 범위에 맞는 적격 release run에 계산하며, 저장·비교·표시는 `BenchmarkReport`, `BaselineManager`, `RegressionDetector` 및 각 Activity 가 담당합니다.

LIVE 관측은 `FlightRecorder`의 실시간 리스너를 통해 즉시 이벤트 처리하며, 데이터는 인메모리 또는 임시 저장소에 기록됩니다. 반면 벤치마크는 `ScoreComposer`가 정의된 엄격한 조건을 충족하는 '정상 실행'만 평가하며, `FlightRecorder`의 snapshot() 를 통해 특정 시간 창 데이터를 추출하여 분석합니다. 이력은 `HistoryActivity`와 `RunIndex`에서 관리되며, `BenchmarkStore`, `BenchmarkReport`, `StoreRunCatalog` 등을 통해 실행 기록을 로드하고 필터링합니다.

코드를 처음 읽을 때는 `MainActivity.kt`에서 앱의 핵심 로직이 시작되며, 벤치마크 관련 코드라면 `BenchmarkRunner.kt` 파일을 먼저 확인해야 합니다. 카메라 제어는 `CameraEngine.kt`와 UI 진입점인 `MainActivity`를 통해 이루어지며, 데이터 수집 구조는 `Telemetry.kt`와 `FlightRecorder.kt`에서 이해할 수 있습니다.
