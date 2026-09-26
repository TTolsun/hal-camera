MainActivity는 Live 관측값을 표시합니다. BenchmarkActivity는 실행 완료 또는 이력 파일 로드 후 기준을 선택하고 RegressionDetector의 결과를 ResultPresenter의 막대와 눈금으로 표시합니다. HistoryActivity는 RunIndex 목록에서 두 실행을 선택하고 같은 비교 규칙과 같은 결과 카드를 씁니다.

ResultPresenter.metricBars가 측정한 모든 항목을 카테고리별 막대 모델로 바꿉니다. 막대 옆 숫자는 표본의 중앙값이고 라벨이 median이라고 적습니다. 이름에 이미 통계가 들어간 항목(Interval p50·p95)과 개수 항목에는 라벨을 붙이지 않으며, 수렴하지 못한 3A 항목은 timeout으로 표시하고 막대를 비웁니다. 실행 정보는 runFacts가 만드는 라벨·값 쌍으로 접힘 아래에 있고, 결과를 밖으로 옮기는 경로는 Export 하나입니다. 실행 기록은 두 실행 비교 버튼으로 선택을 시작하고 각 행의 작업 메뉴로 baseline·내보내기·삭제에 접근합니다.

일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.
