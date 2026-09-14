---
based_on: ["data-flow","state-transitions"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt","app/src/main/java/dev/halcamera/cli/CommandStore.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt"]
decisions: []
verifications: []
---
앱이 기록하는 이벤트와 러너 표시 중 프레임워크 경계 바깥의 사실은 `FlightRecorder` 가 기록한 원시 이벤트 (예: `capture_started`, `capture_result`, `buffer_lost`) 와 센서 타임스탬프 (`atNs`) 입니다. 이 데이터는 앱 계층에서 해석되기 전의 raw 값으로, 실제 하드웨어와 프레임워크가 생성한 신호를 반영합니다. 반면, 러너 표시 중 앱의 해석은 `MetricExtractor` 가 계산한 지표 (예: `intervalRefMs`, `resultFps`) 와 `HistoryActivity` 가 적용한 필터링 결과입니다. 예를 들어, `updateReadout` 함수는 원시 이벤트를 기반으로 프레임 간격을 계산하여 `LiveReadout.panelText` 에 표시하며, 이는 실제 측정값이 아닌 앱의 해석적 산출물입니다.

세션 ID 필터링은 조건에 맞는 실행이 없으면 "이 조건에 맞는 실행이 없습니다"라는 메시지를 생성합니다. 초기 프레임 제외 (warmupFrames) 는 H.1~H.5 계산에서 첫 N 개 프레임을 제외하여 steadyFrames 를 줄이고, warmupFrames 이 전체 윈도우를 차지하면 지표가 null 이 됩니다. INSUFFICIENT_SAMPLES 은 steadyFrames.size < 15 일 때 각 지표에 UnknownReason.INSUFFICIENT_SAMPLES 를 부여하고 값을 null 로 설정하며, RunValidityEvaluator 는 이를 통해 실행의 유효성을 판단합니다. 이러한 앱 쪽 규칙은 데이터 부족이나 초기화 상태에서의 증상을 생성합니다.

계층을 좁히기 위해 먼저 `FlightRecorder` 를 통해 전체 이벤트 로그를 확인하여 세션 ID 필터링이 올바르게 적용되었는지 검증합니다. 다음으로 `capture_result`, `image_available`, `capture_started` 등의 이벤트 타입과 시간戳 (`sensorNs`, `atNs`) 을 비교하여 프레임 간격과 처리 지연을 분석합니다. 센서 타임스탬프가 REALTIME 소스일 때만 앱 시각과 직접 비교하며, `INSUFFICIENT_SAMPLES` 와 같은 상태가 발생하면 데이터 부족으로 인한 정확한 계산 불가능함을 확인합니다. 마지막으로 `MetricExtractor`, `RunAssembler`, `HistoryActivity` 의 계층별 로직을 통해 원시 데이터에서 최종 증상을 생성하는 과정을 추적합니다.
