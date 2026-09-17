MainActivity는 Live 관측값을 표시합니다. BenchmarkActivity는 실행 완료 또는 이력 파일 로드 후 기준을 선택하고 RegressionDetector의 결과를 ResultPresenter·ComparePresenter로 표시합니다. HistoryActivity는 RunIndex 목록에서 두 실행을 선택하고 같은 비교 규칙을 적용합니다.

일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.
