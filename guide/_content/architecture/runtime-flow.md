---
based_on: ["data-flow"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/GalleryActivity.kt","app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt","app/src/main/java/dev/halcamera/benchmark/domain/BaselineManager.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkEvaluator.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt","app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt","app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunValidity.kt","app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt","app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt","app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt","app/src/main/java/dev/halcamera/benchmark/platform/StoreRunCatalog.kt","app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt","app/src/main/java/dev/halcamera/cli/CliProvider.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/telemetry/Telemetry.kt","app/src/main/java/dev/halcamera/ui/ExpandingZoomControl.kt","app/src/main/java/dev/halcamera/ui/GalleryImageView.kt","app/src/main/java/dev/halcamera/ui/IconButton.kt","app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt","app/src/main/java/dev/halcamera/ui/SelectionPopup.kt","app/src/main/java/dev/halcamera/ui/ShutterButton.kt","tools/halcam/halcam/cli.py"]
decisions: []
verifications: []
---
사용자 조작이 카메라 호출, 콜백 기록, 지표 계산, 화면 표시로 이어지는 과정은 앱의 역할과 평가 경로를 따라 진행됩니다. Live 셔터 조작은 MainActivity에서 선택된 엔진의 촬영·녹화 동작으로 이어집니다. 벤치마크는 별도 화면인 BenchmarkActivity가 준비를 마친 뒤 BenchmarkRunner.start()를 호출하여 시작합니다. Telemetry.callback(...) 이 반환한 Camera2 콜백의 onCaptureStarted는 프레임워크 신호를 기록하며, Live 셔터와 벤치마크 시작을 연결하는 메서드가 아닙니다.

실행에서 저장까지의 과정은 다음과 같습니다. 먼저 BenchmarkActivity가 선택한 카메라와 profile을 사전 확인합니다. BenchmarkRunner가 열기·닫기 반복을 수행하며 각 사이클은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE로 진행합니다. 별도 관측 세션에서 WARMUP → OBSERVE → STILL → CLOSE를 진행하며 profile은 반복 횟수와 관측·촬영 조건을 정합니다. RunAssembler가 러너 결과와 이벤트를 결합해 BenchmarkEvaluator와 RunValidityEvaluator를 호출합니다. ScoreComposer는 calibration의 적용 범위와 적격 조건에 맞는 run 에만 내부 점수를 채웁니다.

BenchmarkReport가 실행 JSON을 저장합니다. Activity는 baseline 또는 이전 실행을 찾아 비교 결과를 별도로 계산합니다. Telemetry.callback()은 capture_started, request_observed, capture_result, capture_failed, buffer_lost를 기록합니다. 콜백의 alive()가 거짓이면 이미 닫힌 세션의 늦은 이벤트를 버립니다. request_observed는 요청 제출 시각이 아니라 onCaptureStarted에서 관측한 요청 내용입니다.

러너는 API 호출과 완료 신호 사이의 시각 차이를 기록합니다. RunAssembler는 관측 세션의 프레임 중 관측 시작 이전에 도착한 프레임 수를 워밍업으로 계산합니다. MetricExtractor가 이벤트를 표본으로 연결하고, BenchmarkEvaluator가 profile에 따라 초기 반복을 제외하고 통계를 계산합니다. 3A 수렴은 관측 세션의 첫 결과부터 계산합니다. RunAssembler.ObservationInput.observedFrames는 워밍업을 제외한 steadyFrames의 수입니다. 이 값은 RunValidityEvaluator의 표본 수 검사에 전달됩니다.

RunAssembler가 구성한 BenchmarkRun은 BenchmarkReportCodec에서 schema 4로 직렬화하며, 파일 쓰기는 조립기 밖에서 처리합니다.

이벤트 경로와 러너 경로는 UI 이벤트 처리와 데이터 처리/비동기 작업이 분리되어 있습니다. 이는 UI 응답성을 보장하기 위한 것으로, 이벤트가 발생하면 메인 스레드가 UI 상태만 업데이트하고, 실제 데이터 조회나 이미지 디코딩은 별도의 스레드에서 처리한 후 결과를 메인 스레드로 전달합니다.

벤치마크의 경우 FlightRecorder는 모든 시스템 이벤트를 원본으로 저장하며, MetricExtractor(외부 모듈)가 이를 분석하여 메트릭을 추출합니다. 반면 BenchmarkRunner는 직접적인 실행 흐름을 관리합니다. 두 경로는 RunAssembler에서 합쳐집니다. RunAssembler.assemble() 함수는 FlightRecorder의 이벤트 로그와 BenchmarkRunner의 결과 데이터를 결합하여 최종 BenchmarkRun 객체를 생성합니다.

CLI 요청과 결과 수집의 경우 CLI 명령은 ADB와 CliProvider를 거쳐 CommandCoordinator에 접수됩니다. 요청 ID와 내용을 먼저 저장하고 LiveController, BenchmarkController, CtsController가 화면의 카메라·벤치마크·CTS suite 동작을 실행합니다. 사진은 두 이미지의 저장 완료, 벤치마크는 report 파일 쓰기 완료, CTS suite는 보고서 JSON·텍스트 쓰기 완료 후 artifact를 등록합니다.

PC는 요청 상태를 조회하고 완료된 artifact의 크기와 SHA-256을 확인합니다. 같은 요청 ID와 같은 내용은 기존 결과를 반환하며 새로운 촬영을 시작하지 않습니다. 원본 파일이 삭제되거나 전송이 끊긴 경우에는 요청 ID로 상태를 확인한 다음 파일 수집을 재시도합니다.

결과가 예상과 다를 때 먼저 확인해야 할 출력은 해당 단계의 실패 여부입니다. 갤러리 화면에서 이미지/동영상 데이터를 로드할 때 showCurrent 또는 startVideo 함수 내부의 runCatching 블록에서 예외가 발생하면 loading.text에 오류 메시지가 표시됩니다.

LoadMedia 함수에서 데이터 조회 실패 시 "앨범을 불러오지 못했습니다"라는 알림이 표시되며, 이 경우 queryIo 스레드의 실행 결과를 먼저 확인해야 합니다. 이미지 디코딩 실패 시 failedThumbnails 세트로 기록되고, 그리드 아이템의 badge에 "YUV" 또는 "JPEG" 라벨이 표시됩니다.

벤치마크 실행에서 BenchmarkRunner의 step과 marks (시간 마크)를 확인한 후, MetricExtractor가 생성한 Observation 데이터를 검증하고, RunValidityEvaluator가 validity를 판단하는지 확인합니다. ProfileArchive는 SHA-256 해시를 사용하여 파일 무결성을 검증하며, ProfileComparison는 조건 불일치 (발열, 절전 등)를 ConditionMismatch으로 분류하여 지표 비교를 제한합니다.

RegressionDetector는 deltaPct와 noiseFloor을 기반으로 REGRESSED/IMPROVED/STABLE 상태를 판정하며, RunAssembler는 모든 데이터를 BenchmarkRun으로 통합합니다. 유효하지 않은 데이터는 ScoreComposer.evaluate() 함수에서 점수 계산이 차단됩니다. 정상적인 경우, ScoreCalibration 객체가 생성되어 각 지표에 대해 중앙값과 범위를 계산한 후 최종 점수를 산출합니다.

결과가 예상과 다를 경우 우선 validity.flags 목록을 확인하여 측정 자체의 유효성을 검증한 후, ScoreComposer의 점수 계산 단계로 넘어갑니다. ProfileLibrary는 JSON 파싱 시 필수 필드 누락이나 형식 오류를 즉시 감지하며, BenchmarkReport.write()는 파일 생성 중 프로세스 종료 시 데이터 손상을 방지합니다.
