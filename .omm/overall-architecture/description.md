HAL Camera는 단일 app 모듈의 Android 앱입니다. HomeActivity와 CheckActivity는 Auto Check 건강 판정 경로를, MainActivity는 LIVE 관측과 BenchmarkActivity 진입을 제공합니다.

Camera2Engine과 CameraXEngine이 카메라를 구동하고 Telemetry와 FlightRecorder가 콜백을 기록합니다. AutoCheckRunner와 CheckEvaluator는 v0.2 지표·진단을 만듭니다. v0.3은 BenchmarkRunner, RunAssembler, BenchmarkEvaluator, RunValidityEvaluator를 거쳐 BenchmarkReport로 schema 3 실행 파일을 저장하는 경로까지 연결돼 있습니다.

BenchmarkActivity는 저장 후 BaselineManager로 baseline 또는 이전 실행을 선택하고 RegressionDetector로 비교합니다. ResultPresenter와 ComparePresenter가 결과·비교 테이블을 표시하며, StartCardPresenter와 ProgressPresenter는 시작 카드와 실행 진행률을 구성합니다. 서버나 데이터베이스는 없고 결과는 앱 내부 파일에 저장됩니다.
