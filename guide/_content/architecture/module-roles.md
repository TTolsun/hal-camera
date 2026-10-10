---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - app/src/main/java/dev/halcamera/cli/LiveController.kt
  - app/src/main/java/dev/halcamera/cli/BenchmarkController.kt
  - app/src/main/java/dev/halcamera/cli/CliLibrary.kt
  - app/src/main/java/dev/halcamera/MainCliBridge.kt
  - app/src/main/java/dev/halcamera/DualCliBridge.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/camera/BenchmarkRecorder.kt
  - app/src/main/java/dev/halcamera/benchmark/platform/EnvironmentProbe.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunIndex.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkCsv.kt
  - app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/ConcurrentCameraActivity.kt
  - app/src/main/java/dev/halcamera/camera/ConcurrentSession.kt
  - app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt
  - app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt
  - app/src/main/java/dev/halcamera/ui/Look.kt
  - app/src/main/java/dev/halcamera/cts/recording/BasicRecordingRules.kt
  - app/src/main/java/dev/halcamera/cts/CtsEntryActivity.kt
  - app/src/main/java/dev/halcamera/cts/vendored/VendoredCaseActivity.kt
  - app/src/main/java/dev/halcamera/cts/vendored/VendoredCtsListActivity.kt
  - app/src/main/java/dev/halcamera/cts/CtsCaseActivity.kt
  - app/src/main/java/dev/halcamera/cts/CtsCaseListActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/CtsChecklistActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/CtsSuiteRunActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/SuitePlan.kt
  - app/src/main/java/dev/halcamera/cts/suite/SuiteReport.kt
  - app/src/main/java/dev/halcamera/cts/CtsCatalog.kt
  - app/src/main/java/dev/halcamera/cts/CtsRunner.kt
  - app/src/main/java/dev/halcamera/cts/CameraCaseRunner.kt
  - app/src/main/java/dev/halcamera/cts/Camera2Ops.kt
  - app/src/main/java/dev/halcamera/cts/onoff/FastOnOffRules.kt
  - app/src/main/java/dev/halcamera/cts/switching/SwitchingRules.kt
  - app/src/main/java/dev/halcamera/cts/sizes/AllSizeOnOffRules.kt
  - app/src/main/java/dev/halcamera/cts/combination/StillPreviewCombinationRules.kt
  - app/src/main/java/dev/halcamera/cts/snapshot/VideoSnapshotRules.kt
  - app/src/main/java/dev/halcamera/CameraProbeActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbe.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbeReader.kt
decisions: []
verifications: []
---

**변경할 기능의 패키지부터 여세요.** Kotlin 패키지는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다. `app/src/main/assets/halcam.sh`, `tools/halcam/`, `ctsvendor/`는 저장소 루트 기준 경로입니다.

| 영역 | 하는 일 | 자세히 |
| --- | --- | --- |
| `cli/`, `app/src/main/assets/halcam.sh`, `tools/halcam/` | PC 명령을 앱 기능에 연결합니다. | [CLI](cli.md) |
| `camera/` | 카메라를 열고 촬영합니다. | [Engine Comparison](engine.md) |
| `metrics/` | 이벤트로 지표를 계산합니다. | [측정 흐름](#주요-실행-흐름) |
| `benchmark/` | 측정·저장·비교를 처리합니다. | [Benchmark](benchmark.md) |
| `telemetry/` | 이벤트를 기록하고 ZIP으로 내보냅니다. | [Troubleshooting](troubleshooting.md) |
| `cts/` | 앱에서 카메라 검사를 실행합니다. | [CTS](cts.md) |
| `ctsvendor/` (별도 Gradle 모듈) | AOSP CTS 원문과 호환 패치를 담습니다. | [CTS 원문](cts.md#cts-원문-메서드를-선택하세요) |
| `ui/`와 `MainActivity.kt` | 화면과 공통 디자인을 구성합니다. | [Live](live.md) |

<details markdown="1" id="detail-6a4f150e2a" data-search-section>
<summary>cli/, app/src/main/assets/halcam.sh, tools/halcam/</summary>

- shell 호출자 검사, 영속 요청 상태, artifact 등록과 PC 파일 수집을 담당합니다.
- 화면 어댑터 `LiveController`, `CtsController`, `BenchmarkController`도 이 패키지에 둡니다.
- Activity는 이 어댑터를 연결하며 카메라 엔진과 순수 benchmark/domain은 CLI를 알지 못합니다.
- `MainCliBridge`와 `DualCliBridge`는 Activity의 카메라 동작을 연결합니다. `LiveController`는 프리뷰·사진·녹화와 저장 완료를 관리하며 `CliSequence`는 연속 촬영과 AEB의 일부 저장 결과도 보존합니다.
- `CtsController.reportSaved`와 `BenchmarkController.reportSaved`는 완료 결과와 보고서 파일을 받습니다. 저장된 결과를 조회·비교하는 `CliLibrary`는 기존 benchmark 도메인 타입과 저장소를 재사용하며, 도메인에서 CLI로 향하는 의존성은 없습니다.
- `streams`·`probe`·`cts.cases`는 화면 없이 `CommandCoordinator`가 처리합니다. 결과·baseline·갤러리·진단 ZIP·보관 한도는 `CliLibrary`가 IO 스레드에서 처리합니다.
- `CliStreams`는 요청별 크기 옵션을 검증하며 Camera2·CameraX 설정으로 변환합니다.
- protocol v1을 변경할 때 양쪽 검증기를 함께 확인합니다.

</details>

<details markdown="1" id="detail-f44ea46951" data-search-section>
<summary>camera/</summary>

- 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다.
- `close(done)` 완료 전에 다음 카메라를 열지 않습니다.
- 엔진 계약(`MediaCapture`, `LiveTuning`, `TouchMetering`)과 두 엔진의 구현, 두 엔진의 차이는 [Engine Comparison](engine.md)에 있습니다.
- 벤치마크 RECORD 단계의 recorder 상태 기계는 `BenchmarkRecorder`가 담당하고 `Camera2Engine`은 그 호출을 위임합니다.
- 카메라를 화면에 적는 이름(`Camera · 0 (Wide · Rear)`)도 이 패키지의 `CameraLabel` 하나가 만들며, Live·Benchmark·Probe·CTS가 모두 그것을 부릅니다.
- 렌즈 이름을 붙이지 못한 카메라에는 HAL이 보고한 35mm 환산 초점거리를 덧붙여 `Camera · 1 (Front · 26 mm)`처럼 적으므로, 전면 카메라가 둘인 기기에서도 목록이 두 항목을 구별합니다.
- 기존 `DualPreviewSession`과 `DualCameraXSession`의 물리 출력 경로는 CLI 호환용으로 남아 있으며 Live의 Multi에서는 사용하지 않습니다.
- Multi는 `ConcurrentCameraActivity`와 Camera2 `ConcurrentSession`으로 선택한 카메라 ID의 장치를 각각 엽니다. 모든 장치를 연 뒤 세션을 구성하고, 카메라별 JPEG와 성공·실패 정보를 공통 촬영 묶음으로 저장합니다.
- CLI 호환용 Dual에서 두 엔진의 줌은 두 물리 출력에 공통 적용합니다. Camera2는 메인 센서의 노출·초점 제어와 한 요청으로 촬영하는 두 센서 사진을 지원합니다. CameraX는 메인 개별 제어와 두 센서 사진을 지원하지 않습니다.
- CLI 호환용 Dual의 CameraX는 `ConcurrentCamera`의 물리 ID 선택과 `DualPreviewRelay`를 사용해 각 Preview를 화면과 개별 MP4 인코더에 전달합니다.

</details>

<details markdown="1" id="detail-0f55ef1eda" data-search-section>
<summary>metrics/</summary>

- `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다.
- 화면과 회귀 판정을 담당하지 않습니다.

</details>

<details markdown="1" id="detail-31129cb7cd" data-search-section>
<summary>benchmark/</summary>

- 루트에는 `BenchmarkActivity`·`HistoryActivity` 두 화면만 둡니다.
- `benchmark/domain/`은 profile, 러너, 지표 계산, validity, 비교 규칙, 보관 규칙, presenter를 담는 순수 Kotlin 층이며 `android.*`·`org.json`·`benchmark/platform/`을 import하지 않습니다.
- `benchmark/platform/`은 `BenchmarkStore`·`BenchmarkReport`(org.json 파일 경계)·`DeviceInstance`·`SubjectPrefs`·`BenchmarkPrefs`·`ThermalTracker`·`ProfileCompatibilityChecker`·`EnvironmentProbe`(배터리·발열·기기 식별 읽기) 같은 파일·기기 어댑터입니다.
- 이 방향은 `LayerIsolationTest`가 소스를 읽어 검사하므로 어기면 JVM 테스트가 실패합니다.

</details>

<details markdown="1" id="detail-54901c3aa6" data-search-section>
<summary>telemetry/</summary>

- `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다.
- `IncidentExporter`는 incident ZIP을 작성합니다.
- listener는 기록 스레드에서 동기 실행됩니다.

</details>

<details markdown="1" id="detail-296948fbb4" data-search-section>
<summary>cts/</summary>

- CTS 스타일 카메라 검사를 앱 안에서 실행합니다.
- `CtsEntryActivity`에서 두 방식 중 하나를 고릅니다.
- 커스텀 케이스는 `CtsCatalog`가 목록을 순수 Kotlin으로 정의하고, `CtsRunner` 계약과 `CameraCaseRunner` 기반 클래스, 공통 Camera2 호출 `Camera2Ops` 위에 케이스별 하위 패키지(`onoff/`·`switching/`·`sizes/`·`combination/`·`snapshot/`)가 놓이며, `recording/`은 녹화 케이스들이 공유하는 규칙과 MediaRecorder 도우미입니다.
- 각 패키지는 판정을 순수 Kotlin `…Rules`에, 카메라 호출을 `…Runner`에 둡니다.
- `vendored/`는 `:ctsvendor` 모듈의 AOSP CTS 테스트를 JUnit으로 실행하는 화면입니다.
- 어느 쪽도 공식 CTS 판정을 대체하지 않습니다.

</details>

<details markdown="1" id="detail-a9f4e6711c" data-search-section>
<summary>ctsvendor/ (별도 Gradle 모듈)</summary>

- AOSP `android16-release`의 camera2 CTS 소스(`RecordingTest`, `StillCaptureTest`, `BurstCaptureTest`, `Camera2SurfaceViewTestCase`, `CtsCameraUtils`, `com.android.ex.camera2`)를 그대로 두고, instrumentation 없이 돌도록 같은 이름의 `androidx.test` 대역과 `@TestApi` 치환 패치를 더한 모듈입니다.
- `VendoredCts`가 Instrumentation 대역과 host Activity를 등록하고, `VendoredRun`이 JUnit runner로 테스트 메서드 하나를 실행하며, `VendoredCatalog`가 reflection으로 `@Test` 메서드를 나열합니다.
- 패치 목록과 재동기화 절차는 `ctsvendor/UPSTREAM.md`에 있습니다.

</details>

<details markdown="1" id="detail-82d45526df" data-search-section>
<summary>ui/와 MainActivity.kt</summary>

- Live 관측값과 결과 비교 막대, 공통 `Look` 토큰과 접기·펼치기, 카메라 선택과 권한 처리를 제공합니다.

</details>

<details markdown="1" id="detail-531107fb1a" data-search-section>
<summary>저장·목록·썸네일 구현</summary>

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 5를 쓰고 schema 3·4·5를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 인덱스는 측정 계약·endpoint마다 baseline 실행 목록을 저장합니다. 삭제한 실행은 목록에서 빼며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 두 엔진이 만든 사진 쌍과 동영상을 MediaStore에 저장합니다. 사진 쌍을 만드는 순서는 엔진마다 다르며 [Engine Comparison](engine.md)에 있습니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

저장 포맷과 폴더는 [Engine의 저장 파일 표](engine.md#yuv-저장-포맷)를 확인하세요. `OriginalYuv`가 NV21의 샘플과 plane 배치를 준비하고 `MediaLibrary`가 파일과 JSON을 저장합니다. Camera2의 RAW (DNG)는 `RawFrame`이 RAW 샘플을 복사하고 `DngOutput`이 `DngCreator`로 `_RAW.dng`를 씁니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달합니다. 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다. `ui/RecentMediaButton`은 받은 썸네일을 원형으로 그리며, 항목이 동영상이면 가운데에 재생 표시를 겹쳐 접근성 설명뿐 아니라 화면으로도 종류를 알 수 있게 합니다.

</details>
