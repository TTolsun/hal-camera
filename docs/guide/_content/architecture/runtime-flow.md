---
based_on: ["data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/ui/ShutterButton.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt"]
decisions: []
verifications: []
---
사용자 조작이 카메라 호출과 결과 표시로 이어지는 과정은 UI 계층에서 시작되어 Telemetry 콜백을 경유하고, 최종적으로 화면에 표시됩니다. 사용자가 설정을 변경하거나 촬영 버튼을 누르면 `ShutterButton` 또는 `IconButton` 이 해당 동작을 감지하여 `Telemetry.onCaptureStarted()` 와 같은 콜백을 기록합니다. 이 콜백은 `FlightRecorder` 에 이벤트 로그를 남기고 동시에 `BenchmarkRunner` 가 실행 시퀀스를 시작하는 신호로 작용합니다. `BenchmarkRunner` 는 카메라 엔진 (Camera2 또는 CameraX) 을 통해 실제 하드웨어 요청을 생성하고, 캡처 완료 시 `onCaptureCompleted()` 콜백을 발생시킵니다.

이벤트 경로와 러너 경로는 UI 이벤트 처리와 백그라운드 측정 로직의 분리 및 병렬 실행을 위해 따로 존재합니다. 이벤트 경로는 `FlightRecorder` 와 `Telemetry` 를 통해 원시 신호 (예: `request_observed`, `capture_failed`) 를 기록하며, 이는 실시간 상태 반영에 중점을 둡니다. 러너 경로는 `BenchmarkRunner` 의 내부 상태 기계와 타이머를 통해 측정 세션을 관리하고, `MetricExtractor` 가 이를 가공하여 통계치를 산출합니다. 두 경로는 `RunAssembler` 에서 합쳐지며, 이 클래스는 `FlightRecorder` 의 이벤트 로그와 `BenchmarkRunner` 의 결과 (`Result`) 를 결합하여 최종 실행 기록을 생성합니다.

결과가 예상과 다를 때는 우선 `FlightRecorder` 의 원시 타임스탬프와 신호 순서를 확인한 후, 이를 바탕으로 `MetricExtractor` 가 생성한 관찰 데이터와 `BenchmarkRunner` 가 계산한 사이클/캡처 샘플을 검증해야 합니다. `RegressionDetector` 는 두 실행 간의 조건 불일치 (예: 열량, 전력 절약 모드) 와 측정값 차이를 분석하여 상태 (`REGRESSED`, `IMPROVED`, `STABLE`) 를 결정하며, `HistoryActivity` 는 이를 화면에 표시하고 CSV/JSON 으로 내보냅니다. 모든 데이터는 `BenchmarkStore` 에 저장되며, `RunAssembler` 가 원시 샘플과 이벤트를 조합하여 최종 실행 기록을 생성합니다.
