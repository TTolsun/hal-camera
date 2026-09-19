MainActivity는 Live 관측값을 표시합니다. BenchmarkActivity는 실행 완료 또는 이력 파일 로드 후 기준을 선택하고 RegressionDetector의 결과를 ResultPresenter·ComparePresenter로 표시합니다. HistoryActivity는 RunIndex 목록에서 두 실행을 선택하고 같은 비교 규칙을 적용합니다.

ResultPresenter는 독립된 행에서도 통계값을 식별하도록 max/p95 라벨을 제공합니다. ui/MetricRows가 수치와 판정을 줄바꿈해 표시하며 회귀 지표를 먼저 배치합니다. 전체 지표와 실행 정보는 펼쳐 볼 수 있고 텍스트 복사는 별도 버튼으로 제공합니다. Results는 두 실행 비교 버튼으로 선택을 시작하고 각 행의 작업 메뉴로 baseline·내보내기·삭제에 접근합니다.

일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.
