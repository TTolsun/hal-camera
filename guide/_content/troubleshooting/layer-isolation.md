---
based_on: ["data-flow","state-transitions"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkEvaluator.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt","app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunValidity.kt","app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt","app/src/main/java/dev/halcamera/cli/CommandStore.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","tools/halcam/halcam/cli.py"]
decisions: []
verifications: []
---
앱이 기록하는 이벤트와 러너 표시 중 프레임워크 경계 바깥의 사실은 `FlightRecorder`가 직접 기록한 원시 데이터입니다. `Event.atNs`는 `elapsedRealtimeNanos` 도메인에서 센서 타임스탬프를 기반으로 기록된 값이며, 이는 프레임워크가 제공하는 실제 시계값을 반영합니다. 반면, 앱이 계산하거나 필터링한 값은 해석의 영역에 속합니다. 예를 들어, 세션 ID(`sessionId`) 필터링이나 초기 프레임 제외는 `MainActivity.kt`와 같은 앱 계층에서 수행되는 규칙입니다. `INSUFFICIENT_SAMPLES` 역시 앱이 관측된 표본 수를 기반으로 생성하는 플래그로, 프레임워크가 직접 기록하지 않는 값입니다.

세션 ID 필터링은 해당 세션과 일치하지 않는 이벤트를 제외하여 데이터 양을 줄이고, 특정 시점의 정보를 누락할 수 있습니다. 초기 프레임 제외는 워밍업 기간의 노이즈를 제거하지만, 안정화 시간이 짧은 경우 정상적인 동작이 비정상적으로 기록될 수 있습니다. `INSUFFICIENT_SAMPLES`는 관측된 표본 수가 15 개 미만일 때 발생하며, 이 경우 지표 값이 `null`로 설정되어 측정 결과가 무효로 간주됩니다. 이러한 규칙들은 `MetricExtractor.kt`와 `RunAssembler.kt`에서 적용되며, 프레임워크의 실제 동작과 별개로 앱이 생성한 증상을 만들어냅니다.

계층을 좁히기 위해 먼저 `FlightRecorder`를 통해 러너 표시와 원시 이벤트를 확인합니다. `Event.atNs`와 `sensorNs` 필드를 대조하여 프레임워크가 제공한 실제 시계값과 앱이 기록한 값을 비교합니다. 다음으로 `capacityEvictions`를 확인하여 이벤트 버퍼가 가득 차 데이터 손실이 발생했는지 검증합니다. 마지막으로 `INSUFFICIENT_SAMPLES` 플래그와 함께 관측된 표본 수를 확인하여 샘플 부족으로 인한 무효화를 판단합니다. 이 순서는 앱 계층의 필터링 규칙을 먼저 확인한 후 프레임워크의 원시 데이터를 분석하는 방식입니다.
