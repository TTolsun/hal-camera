benchmark/는 profile, 러너, 지표 계산, validity, 내부 점수, schema 4 저장, baseline·비교·이력을 제공합니다. ScoreComposer는 검토한 동일 모델·endpoint의 calibration을 적격 release run에만 적용합니다. BenchmarkActivity는 실행·결과를, HistoryActivity는 목록과 임의 비교를 표시합니다. RunIndex는 raw 이벤트와 표본 배열을 보관하지 않으며 BenchmarkCsv는 지표당 한 행을 작성합니다. 파일 작업과 비교용 파일 읽기는 별도 실행기에서 처리합니다.

ProfileComparisonActivity는 로컬·가져온 실행을 A/B 묶음으로 선택합니다. ProfileLibrary는 별도 archive를 읽으며 baseline이나 자동 reference에 가져온 파일을 넣지 않습니다. ProfileComparison은 기기·빌드·계약·환경과 중복 원본을 검사하고 RepeatStatistics에 실행 단위 값을 전달합니다. 순열검정의 통계적 유의성과 기존 RegressionRules의 실무 임계값을 별도로 표시합니다.
