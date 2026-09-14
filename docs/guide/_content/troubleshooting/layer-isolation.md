---
based_on: ["data-flow","state-transitions"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/RunValidity.kt","app/src/main/java/dev/halcamera/cli/CommandStore.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt"]
decisions: []
verifications: []
---
앱이 기록하는 이벤트와 러너 표시 중 프레임워크 경계 바깥의 사실은 `FlightRecorder` 가 기록한 원시 이벤트 (`Event.atNs`, `sensorNs`) 와 `BenchmarkEvaluator` 의 계산 결과입니다. `Event.atNs` 는 `Telemetry` 를 통해 주입된 시계로, 앱 시각과 센서 시각 (`sensorNs`) 을 구분하여 기록합니다. `intervalMs`, `resultFps`, `observedResultGap` 은 앱이 계산한 파생값으로, HAL 내부 상태의 직접 측정으로 해석하지 않습니다. 러너 표시 중 `unknownReason` 이 `INSUFFICIENT_SAMPLES` 로 설정된 경우, 이는 프레임워크가 충분한 표본을 수집하지 못했음을 의미하며, 이는 앱 측정이 수행되지 않은 결과입니다.

세션 ID 필터링은 `HistoryActivity.visible()` 에서 특정 실행만 표시하여, 해당 세션의 데이터가 누락되었거나 다른 세션과 혼동되었음을 시사합니다. 초기 프레임 제외는 `MetricExtractor.observe()` 의 `warmupFrames` 로 처리되며, 이는 H.1~H.5 통계에서 제외되지만 3A 수렴에는 포함됩니다. `INSUFFICIENT_SAMPLES` 는 `RunValidityEvaluator.flags` 에서 측정 유효성 (`measurementValid`) 을 false 로 설정하여 앱 UI 에 "실행이 유효하지 않음" 증상을 만듭니다. 이는 프레임워크가 충분한 데이터를 수집하지 못했거나, 앱 측정이 수행되지 않았음을 의미합니다.

계층을 좁히기 위해 먼저 `FlightRecorder` 를 통해 전체 이벤트 로그를 확인하여 세션 ID 필터링이 올바르게 적용되었는지 검증합니다. 다음으로 `capture_result`, `image_available`, `capture_started` 등의 이벤트 타입과 시간戳 (`sensorNs`, `atNs`) 을 비교하여 프레임 간격과 처리 지연을 분석합니다. `INSUFFICIENT_SAMPLES` 와 같은 상태가 발생하면 데이터 부족으로 인해 정확한 계산이 불가능함을 알립니다. 이는 프레임 수집이 충분하지 않거나 세션 초기화 중일 수 있음을 시사합니다. 앱 기록만으로 프레임워크와 HAL 중 원인을 확정하지 않으며, 시스템 트레이스와 카메라 서비스 로그로 추가 확인해야 합니다.
