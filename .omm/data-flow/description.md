측정 입력은 콜백 이벤트와 러너 타임스탬프의 두 경로입니다. Telemetry는 프레임워크 콜백을 FlightRecorder에 남기고, 러너는 API 호출 직전과 신호 수신 시각을 기록합니다.

Auto Check에서는 CheckEvaluator가 EndpointResult와 이벤트를 합쳐 MetricExtractor·ThresholdEngine·DiagnosisRules·HealthComposer를 호출합니다. BenchmarkActivity는 BenchmarkRunner.Result와 이벤트를 RunAssembler에 전달하고, RunAssembler가 BenchmarkEvaluator와 RunValidityEvaluator를 호출한 결과를 BenchmarkReport로 저장합니다.

RegressionDetector는 저장된 측정값을 비교하는 별도 순수 로직입니다. BenchmarkActivity는 저장 후 baseline 또는 이전 실행을 선택해 비교하고, ResultPresenter·ComparePresenter로 결과를 표시합니다. 기준을 바꾸면 비교 결과를 다시 계산합니다.

