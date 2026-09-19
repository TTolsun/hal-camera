---
based_on: ["overall-architecture"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/CameraProbeActivity.kt","app/src/main/java/dev/halcamera/MainActivity.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt","app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt","app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkCsv.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt","app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunIndex.kt","app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt","app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt","app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/camera/CameraProbe.kt","app/src/main/java/dev/halcamera/camera/CameraProbeReader.kt","app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt","app/src/main/java/dev/halcamera/cli/BenchmarkController.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/cli/LiveController.kt","app/src/main/java/dev/halcamera/cts/Camera2Ops.kt","app/src/main/java/dev/halcamera/cts/CameraCaseRunner.kt","app/src/main/java/dev/halcamera/cts/CtsCaseActivity.kt","app/src/main/java/dev/halcamera/cts/CtsCaseListActivity.kt","app/src/main/java/dev/halcamera/cts/CtsCatalog.kt","app/src/main/java/dev/halcamera/cts/CtsEntryActivity.kt","app/src/main/java/dev/halcamera/cts/CtsRunner.kt","app/src/main/java/dev/halcamera/cts/combination/StillPreviewCombinationRules.kt","app/src/main/java/dev/halcamera/cts/onoff/FastOnOffRules.kt","app/src/main/java/dev/halcamera/cts/recording/BasicRecordingRules.kt","app/src/main/java/dev/halcamera/cts/sizes/AllSizeOnOffRules.kt","app/src/main/java/dev/halcamera/cts/snapshot/VideoSnapshotRules.kt","app/src/main/java/dev/halcamera/cts/suite/CtsChecklistActivity.kt","app/src/main/java/dev/halcamera/cts/suite/CtsSuiteRunActivity.kt","app/src/main/java/dev/halcamera/cts/suite/SuitePlan.kt","app/src/main/java/dev/halcamera/cts/suite/SuiteReport.kt","app/src/main/java/dev/halcamera/cts/switching/SwitchingRules.kt","app/src/main/java/dev/halcamera/cts/vendored/VendoredCaseActivity.kt","app/src/main/java/dev/halcamera/cts/vendored/VendoredCtsListActivity.kt","app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt","app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt"]
decisions: []
verifications: []
---
각 패키지는 명확한 역할을 수행하며 변경 시 해당 패키지 내의 클래스와 외부 의존성을 확인해야 합니다. `cli/`와 `tools/halcam/`는 shell 호출자 검사, 영속 요청 상태, artifact 등록 및 PC 파일 수집을 담당합니다. 이 영역은 화면 어댑터인 `LiveController`, `BenchmarkController`, `CtsController`를 포함하며, `camera/`와 `benchmark/`가 `cli/`를 import하지 않도록 설계되어 있습니다. 변경 시에는 `CommandCoordinator`의 요청 ID·진행 상태·결과 파일 등록 관리 로직과 `LiveController`의 실제 카메라 사용, `BenchmarkController`의 실행·보고서 저장 경로 연결을 확인해야 합니다.

`camera/`는 엔진 계약인 `CameraEngine` 인터페이스와 `Camera2Engine`, `CameraXEngine` 구현을 제공합니다. 이 패키지는 기본 크기 선택 경로와 벤치마크용 명시적 StreamSpec 경로를 구분하며, 세션별 콜백과 `close(done)` 완료 통지를 러너에 연결합니다. 변경 시에는 `CameraEngine` 인터페이스의 `busy()` 상태와 `mediaBusy` 속성을 확인해야 합니다.

`metrics/`는 `MetricExtractor`와 `MetricModel`을 포함하며, 한 세션의 이벤트를 관측 창 안에서 FrameObservation으로 재구성하고 H.1~H.10 표본을 계산합니다. 이 패키지는 Android import가 없는 순수 Kotlin 로직이며, `benchmark/`의 `RunAssembler`, `BenchmarkEvaluator`와 `ui/LiveReadout`이 사용합니다.

`benchmark/`는 루트에는 `BenchmarkActivity`, `HistoryActivity`, `ProfileComparisonActivity` 세 화면만 둡니다. `benchmark/domain/`은 profile, 러너, 지표 계산, validity, 내부 점수, 비교 규칙, presenter를 담는 순수 Kotlin 층이며 `android.*`, `org.json`, `benchmark/platform/`을 import하지 않습니다. `benchmark/platform/`는 `BenchmarkStore`, `BenchmarkReport`, `ProfileLibrary`, `SubjectPrefs`, `ThermalTracker`, `ProfileCompatibilityChecker` 같은 파일·기기 어댑터입니다.

`telemetry/`는 `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성하며, listener는 기록 스레드에서 동기 실행됩니다.

`cts/`는 CTS 스타일 카메라 검사를 앱 안에서 실행합니다. 커스텀 케이스는 `CtsCatalog`가 목록을 순수 Kotlin으로 정의하고, `CtsRunner` 계약과 `CameraCaseRunner` 기반 클래스, 공통 Camera2 호출 `Camera2Ops` 위에 케이스별 하위 패키지가 놓입니다. 각 패키지는 판정을 순수 Kotlin `…Rules`에, 카메라 호출을 `…Runner`에 둡니다.

`ctsvendor/`는 AOSP CTS 테스트를 JUnit으로 실행하는 별도 Gradle 모듈입니다. `ui/`와 `MainActivity.kt`는 Live 관측값과 Canvas 그래프, 공통 `Look` 토큰, 카메라 선택과 권한 처리를 제공합니다.

실행 영역은 `Camera2Engine` 또는 `CameraXEngine` 클래스로, 카메라 하드웨어 제어 및 녹화/촬영을 수행합니다. 이 영역은 `BenchmarkActivity`의 `start()` 호출 시 시작되며, `onEvent()` 함수가 `FlightRecorder`를 통해 카메라 상태, 오류, 프레임 데이터 등을 기록합니다. 평가 영역은 `RegressionDetector.compare()` 함수가 `BaselineManager`와 `BenchmarkStore`를 통해 기준 실행과 현재 실행을 비교하며, `ResultPresenter`와 `ComparePresenter`가 결과를 표시합니다.

기록과 저장 영역은 `FlightRecorder`의 30 초 순환 버퍼와 10 초 사전 기록 관리, `IncidentExporter`의 ZIP 파일 생성, `Telemetry`의 세션별 이벤트 흐름 추적, `Incident` 객체의 저장된 데이터 핵심으로 구성됩니다. `BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽으며, `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다.

`HistoryActivity`는 같은 저장소를 읽으며, `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리하며, `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장하며, `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달하며, 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다. `CameraProbeActivity`는 HAL에서 공개된 카메라 사양을 읽지 않고 열지 않으며, 실행과 평가 영역은 `CameraProbeReader`와 `CameraProbeSnapshot` 클래스를 통해 처리합니다.

기록과 저장 영역은 `CameraProbeText`, `CameraProbeEntry`, `CameraProbeRow`, `CameraProbeSection`, `CameraProbeFormat` 클래스가 담당하며, `CameraProbeActivity`는 이를 화면에 렌더링하고 필터링합니다. `ProfileLibrary`와 `ProfileArchive`는 외부 JSON의 검증·출처·별도 파일 보관을 담당하며, `ProfileComparison`은 기기·빌드·계약·환경·중복 검사를 수행합니다.

`RepeatStatistics`는 실행 단위 기술 통계와 순열검정을 담당하며, `ProfileComparisonActivity`가 시스템 파일 선택기와 묶음 선택·결과 공유를 연결합니다. `CameraProbeReader`는 `CameraManager`를 통해 `CameraCharacteristics` 데이터를 읽으며, `ScoreComposer`는 `BenchmarkRun`을 평가하여 점수를 계산합니다.

`CtsCatalog#cases`는 각 패키지의 역할과 의존성을 정의하며, `CtsRunner#run`은 CaseEnvironment#manager와 previewHost를 통해 카메라를 열고 닫는 사이클을 실행합니다. `FlightRecorder`는 이벤트 기록을 관리하며, 실행 전 카메라 및 마이크 권한이 필요하고, surface가 생성되지 않으면 실행이 지연됩니다.

중단 시 CANCELLED 결과로 표시되며, 실행 안 함 항목은 NOT_RUN으로 분류됩니다. `VendoredCtsListActivity`는 `:ctsvendor` 모듈의 `VendoredCatalog`에서 `@Test` 메서드를 체크리스트로 나열하고, `VendoredCaseActivity`가 그 메서드 하나를 실행합니다. 이 Activity는 가져온 `Camera2SurfaceViewCtsActivity`를 상속해 테스트의 `ActivityTestRule` 이 돌려받는 인스턴스가 됩니다.

`VendoredRun`은 작업 스레드에서 JUnit runner를 돌리고 `RunListener`로 결과를 받으며, 중단은 실행 중인 테스트가 쥔 `mCamera`를 닫아 테스트를 실패시키는 방식입니다. JUnit 이 실행 중인 본문을 멈출 수단이 없기 때문입니다.

필수 심볼인 `CameraEngine`, `BenchmarkActivity`, `FlightRecorder`는 각각 카메라 하드웨어 제어, 벤치마크 실행 관리, 이벤트 기록 관리를 수행하며, 변경 시에는 해당 클래스의 의존성과 동작을 확인해야 합니다.
