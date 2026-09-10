benchmark/BenchmarkModel.kt는 BenchmarkRun, BenchmarkMetric, MeasurementContract와 실행 메타데이터를 정의합니다. comparisonContractId는 프로파일·지표 정의 버전·통계 방법·시계의 조합입니다.

RunAssembler가 만드는 측정 결과는 비교 필드를 UNKNOWN(no_baseline)으로 두고, RegressionDetector는 저장된 두 실행으로 RunComparison을 별도로 계산합니다. SubjectLabel은 측정 대상 빌드, AppInfo는 측정 도구 버전을 나타냅니다.
