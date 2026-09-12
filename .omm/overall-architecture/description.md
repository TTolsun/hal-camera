현재 앱은 MainActivity의 LIVE, BenchmarkActivity의 벤치마크 실행·결과, HistoryActivity의 RESULTS로 구성됩니다. Camera2Engine과 CameraXEngine이 카메라를 구동하고 Telemetry와 FlightRecorder가 콜백을 기록합니다.

BenchmarkRunner의 결과와 이벤트는 RunAssembler, BenchmarkEvaluator, RunValidityEvaluator를 거쳐 schema 4 실행 JSON이 됩니다. BenchmarkReport는 schema 3·4를 읽습니다. BaselineManager와 RegressionDetector가 비교를 수행하고, 이력의 임의 선택 비교는 실제 baseline이 아닌 경우 변화량만 표시합니다. RunIndex는 목록 데이터를 줄여 보관하며 BenchmarkCsv와 tools/aggregate.py가 지표별 CSV를 작성합니다.

Home·Auto Check·건강 판정 코드는 M3에서 제거되었고, 이를 설명하던 diagnosis·check-runner·health-report·baseline-store·home-screen·check-screen·run-summary 요소도 이 관점에서 제거했습니다. 공통 지표 추출은 metrics/, endpoint 열거는 camera/에 있습니다.

LIVE는 사진·동영상을 MediaLibrary를 통해 DCIM/HALCamera에 저장하고 GalleryActivity가 해당 앨범을 조회합니다. 이 픽셀 저장 경로는 벤치마크 JSON과 incident ZIP의 메타데이터 경로와 구분합니다.

LIVE는 API 토글과 상태, 핵심 측정값을 표시합니다. ExpandingZoomControl은 선택한 배율에서 펼쳐지고 자동으로 접히며, 사진·동영상 버튼은 직접 선택을 제공합니다. 카메라 선택은 목록 방식을 유지합니다. RecentMediaThumbnail은 완성된 앨범 항목을 백그라운드에서 읽어 RecentMediaButton에 표시합니다. MARK·벤치마크·프리뷰 일시정지는 측정 상세에서 제공합니다.
