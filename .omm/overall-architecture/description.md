현재 앱은 MainActivity의 Live, BenchmarkActivity의 벤치마크 실행·결과, HistoryActivity의 실행 기록으로 구성됩니다. Camera2Engine과 CameraXEngine이 카메라를 구동하고 Telemetry와 FlightRecorder가 콜백을 기록합니다.

BenchmarkRunner의 결과와 이벤트는 RunAssembler, BenchmarkEvaluator, RunValidityEvaluator를 거쳐 schema 5 실행 JSON이 됩니다. BenchmarkReport는 schema 3부터 5까지 읽습니다. BaselineManager와 RegressionDetector가 비교를 수행하고, 이력의 두 실행 비교는 먼저 선택한 실행을 비교용 baseline으로 사용하며 저하·개선을 판정합니다. RunIndex는 목록 데이터를 줄여 보관하며 BenchmarkCsv와 tools/aggregate.py가 지표별 CSV를 작성합니다.

Home·Auto Check·건강 판정 코드는 M3에서 제거되었고, 이를 설명하던 diagnosis·check-runner·health-report·baseline-store·home-screen·check-screen·run-summary 요소도 이 관점에서 제거했습니다. 공통 지표 추출은 metrics/, endpoint 열거는 camera/에 있습니다.

Live는 사진·동영상을 MediaLibrary를 통해 DCIM/HALCamera에 저장하고 GalleryActivity가 해당 앨범을 조회합니다. 이 픽셀 저장 경로는 벤치마크 JSON과 incident ZIP의 메타데이터 경로와 구분합니다.

Live는 API 토글, 그 아래의 Live 표시등, 중앙의 촬영 제어 화살표와 핵심 측정값을 표시합니다. ExpandingZoomControl은 선택한 배율에서 펼쳐지고 자동으로 접히며, 사진·동영상 버튼은 직접 선택을 제공합니다. 카메라 선택은 목록 방식을 유지합니다. RecentMediaThumbnail은 완성된 앨범 항목을 백그라운드에서 읽어 RecentMediaButton에 표시합니다. 하단에 `Save Events · ZIP` 작업 행을 고정하고 상단의 `Callback`은 프리뷰 위의 콜백 그래프를 켜고 끕니다. 배경 박스나 카메라 선택 버튼은 없으며 그래프를 켜면 FPS·ISO·노출·AF·AE의 실시간 정보 두 줄을 숨기며, 그래프를 닫으면 다시 표시합니다. 촬영 조작부는 유지합니다. Shutter와 Metadata를 기본 행으로 두고 현재 구성된 출력 스트림을 순서대로 추가합니다. Partial은 표시하지 않습니다. 모든 행은 이전 프레임의 onCaptureStarted 수신 시각을 공통 기준으로 사용하며 축 범위는 자동으로 조절합니다. 첫 Shutter는 0ms이고 이후 Shutter는 직전 Shutter와의 간격입니다. 앱에 프레임별 콜백을 주지 않는 출력은 `콜백 없음`으로 표시합니다. Probe·CTS·Benchmark는 Lab에서 이 순서로 독립 화면을 엽니다.

PC의 기본 CLI는 ADB이며 APK의 assets/halcam.sh를 기기에 한 번 준비하여 실행합니다. CliProvider는 직접 명령 인자와 기존 Base64 JSON 전송을 받고 CommandCoordinator와 CommandStore가 요청·결과를 관리합니다. LiveController는 프리뷰 시작·종료, 사진, 녹화를 MainActivity의 선택한 Camera2·CameraX 엔진에 연결합니다. 크기와 녹화 설정은 요청별 옵션으로 적용하며 streams 명령으로 지원 후보를 조회합니다. 녹화는 실제 시작 콜백과 저장 완료 콜백을 구분합니다. CTS와 benchmark.run은 Live 카메라 종료 후 전용 화면으로 인계합니다. BenchmarkController는 Camera2 표준 v2 측정과 보고서 저장 완료를 연결하며 Live의 엔진·스트림 재정의 옵션은 받지 않습니다. Python tools/halcam은 선택 도구입니다.

앱을 열 때는 투명한 `CliLaunchActivity`가 main thread에서 작업 상태를 다시 확인합니다. 실행 중인 작업이 있으면 Live로 전환하지 않습니다. 상태 조회는 `CommandStore`의 메모리 snapshot을 읽으며 파일 기록은 상태 전환 때만 수행합니다.

벤치마크 비교는 baseline과 이번 실행, 두 개 사이에서만 이루어집니다. 여러 실행을 묶어 순열검정을 수행하던 ProfileComparisonActivity와 외부 JSON archive(ProfileLibrary, ProfileArchive)는 0.13.0에서 제거했습니다. 다기기 분포 수집을 하지 않기로 결정해 그 경로의 용도가 사라졌기 때문입니다. 실행 JSON의 device_instance_id는 DeviceInstance가 계속 채웁니다.
