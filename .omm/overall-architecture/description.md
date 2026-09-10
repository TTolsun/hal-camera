HAL Camera는 단일 app 모듈의 Android 앱입니다. HomeActivity와 CheckActivity는 Auto Check 건강 판정 경로를, MainActivity는 LIVE 관측과 BenchmarkActivity 진입을 제공합니다.

Camera2Engine과 CameraXEngine이 카메라를 구동하고 Telemetry와 FlightRecorder가 콜백을 기록합니다. AutoCheckRunner와 CheckEvaluator는 v0.2 지표·진단을 만듭니다. v0.3은 BenchmarkRunner, RunAssembler, BenchmarkEvaluator, RunValidityEvaluator를 거쳐 BenchmarkReport로 schema 3 실행 파일을 저장하는 경로까지 연결돼 있습니다.

RegressionDetector, BaselineManager, ReferenceResolver, ResultPresenter도 구현되어 있지만 현재 BenchmarkActivity는 실행 요약만 표시하며 이 비교 테이블 경로를 호출하지 않습니다. 서버나 데이터베이스는 없고 결과는 앱 내부 파일에 저장됩니다.
