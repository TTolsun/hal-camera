`app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt`(198줄). 한 세션의 이벤트에서 시간 창 안의 관측 지표 H.1~H.10을 계산합니다. Android import가 없으며, 패키지 그래프의 leaf입니다. 이벤트를 숫자로 바꿀 뿐 비교·판정·표시는 모릅니다. M3에서 `diagnosis`가 삭제될 때 이 계산 부분만 `metrics`로 옮겨졌습니다.

`frames()`는 capture result마다 `FrameObservation` 하나를 세 이벤트를 join해 재구성합니다. 프레임 번호로 맞춘 `capture_started`가 시작 시각을, `request_observed`가 result에 없는 `afMode`를, 센서 타임스탬프로 맞춘 `image_available`이 버퍼 도착을 줍니다. 여기서 `intervalMs`(`FrameTracker` 값이 있으면 신뢰), `partialMs`(started에서 result까지), `bufferMs`(started에서 image까지)를 구합니다. `previousSensor`를 창 **이전**의 마지막 result로 seed하므로 창 안의 첫 간격이 null이 아니라 실제 간격입니다.

`observe()`가 집계합니다. `warmupFrames`는 새 스트림의 첫 프레임들을 간격·partial·버퍼·stall 지표에서 빼되 3A 수렴에는 그대로 씁니다. 3A는 첫 result부터 재기 때문입니다. Galaxy S25+에서는 프레임 #1이 33.3ms 길이인데도 #0 뒤 66.7ms에 도착하는 시작 아티팩트가 있습니다. 벤치마크에서는 `RunAssembler`가 관측 시작 전에 도착한 프레임 수를 세어 이 값을 넘깁니다. 프레임은 간격이 자기 `SENSOR_FRAME_DURATION`의 1.5배와 baseline 간격의 1.5배를 모두 넘으면 stall이며, 자기 길이가 없으면 baseline 비교만 적용합니다.

같은 코드에 집계 모드가 둘 있습니다. PERCENTILE(벤치마크의 p50/p95)과 MAX입니다. MAX 모드는 현재 `MetricExtractorTest`에서만 쓰이며, LIVE의 `LiveReadout`은 기본 PERCENTILE로 기준선을 구하고 최근 창의 최댓값은 직접 계산합니다. `minSamples`(벤치마크는 15) 미만이면 값은 `INSUFFICIENT_SAMPLES`와 함께 null이 되지만 통계는 그대로 보고합니다. H.10은 steady 간격의 모집단 표준편차(ddof = 0)입니다.

AF 처리는 흔한 오독을 피합니다. `CONTROL_AF_STATE` INACTIVE는 "AF가 꺼짐"일 수도 "아직 수렴 전"일 수도 있으므로 `autofocusEnabled`는 관측된 **모드**(0 = OFF, 5 = EDOF)로 판단하고, AF를 돌린 프레임이 없으면 H.7은 실패가 아니라 `UNSUPPORTED`가 됩니다.

`percentile()`은 `METRICS.md` 0.2의 nearest-rank 정의이며 `BenchmarkEvaluator.Stats`도 같은 구현을 부릅니다.
