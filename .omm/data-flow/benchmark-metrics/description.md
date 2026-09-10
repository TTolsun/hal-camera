RunAssembler가 BenchmarkRunner의 cycles·stills와 관측 세션 이벤트로 BenchmarkEvaluator.Input을 구성합니다. BenchmarkEvaluator는 측정 통계를 계산하고 비교 필드는 UNKNOWN(no_baseline)으로 둡니다. RegressionDetector는 별도로 두 실행을 비교하며 BenchmarkActivity의 실행 완료 및 baseline 변경 경로에서 호출됩니다. 측정 통계와 비교 판정은 별도의 산출물입니다.

