현재 앱은 MainActivity 의 LIVE, BenchmarkActivity 의 벤치마크 실행·결과, HistoryActivity 의 RESULTS 로 구성됩니다. Camera2Engine 과 CameraXEngine 이 카메라를 구동하고 Telemetry 와 FlightRecorder 가 콜백을 기록합니다.

BenchmarkRunner 의 결과와 이벤트는 RunAssembler, BenchmarkEvaluator, RunValidityEvaluator 를 거쳐 schema 4 실행 JSON 이 됩니다. BenchmarkReport 는 schema 3·4 를 읽습니다. BaselineManager 와 RegressionDetector 가 비교를 수행하고, 이력의 임의 선택 비교는 실제 baseline 이 아닌 경우 변화량만 표시합니다. RunIndex 는 목록 데이터를 줄여 보관하며 BenchmarkCsv 와 tools/aggregate.py 가 지표별 CSV 를 작성합니다.

Home·Auto Check·건강 판정 코드는 M3 에서 제거되었고, 이를 설명하던 diagnosis·check-runner·health-report·baseline-store·home-screen·check-screen·run-summary 요소도 이 관점에서 제거했습니다. 공통 지표 추출은 metrics/, endpoint 열거는 camera/ 에 있습니다.

LIVE 는 사진·동영상을 MediaLibrary 를 통해 DCIM/HALCamera 에 저장하고 GalleryActivity 가 해당 앨범을 조회합니다. 이 픽셀 저장 경로는 벤치마크 JSON 과 incident ZIP 의 메타데이터 경로와 구분합니다.

LIVE 는 API 토글과 상태, 핵심 측정값을 표시합니다. ExpandingZoomControl 은 선택한 배율에서 펼쳐지고 자동으로 접히며, 사진·동영상 버튼은 직접 선택을 제공합니다. 카메라 선택은 목록 방식을 유지합니다. RecentMediaThumbnail 은 완성된 앨범 항목을 백그라운드에서 읽어 RecentMediaButton 에 표시합니다. MARK·벤치마크·프리뷰 일시정지는 측정 상세에서 제공합니다.

PC 의 tools/halcam Python CLI 는 ADB 를 통해 shell 전용 CliProvider 에 명령을 전달합니다. CommandCoordinator 와 CommandStore 가 요청 ID·진행 상태·결과 파일 등록을 관리합니다. LiveController 는 MainActivity 의 실제 카메라를 사용하고 BenchmarkController 는 BenchmarkActivity 의 기존 실행·보고서 저장 경로를 연결합니다. CLI protocol v1 과 benchmark schema 4 는 별개의 계약입니다.

앱을 열 때는 투명한 CliLaunchActivity 가 main thread 에서 작업 상태를 다시 확인합니다. 실행 중인 작업이 있으면 LIVE 로 전환하지 않습니다. 상태 조회는 CommandStore 의 메모리 snapshot 을 읽으며 파일 기록은 상태 전환 때만 수행합니다.