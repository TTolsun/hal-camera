---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - app/src/main/java/dev/halcamera/cli/LiveController.kt
  - app/src/main/java/dev/halcamera/cli/BenchmarkController.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunIndex.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkCsv.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt
  - app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt
  - app/src/main/java/dev/halcamera/ui/MetricRows.kt
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

**변경할 기능의 패키지부터 여세요.** 아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다.

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `cli/`, `assets/halcam.sh`, `tools/halcam/` | shell 호출자 검사, 영속 요청 상태, artifact 등록과 PC 파일 수집을 담당합니다. 화면 어댑터 `LiveController`, `CtsController`도 이 패키지에 두어 `camera/`와 `benchmark/`가 `cli/`를 import하지 않게 합니다. `LiveController`는 프리뷰·사진·녹화를 연결하고 `CtsController.reportSaved`는 결과 수와 보고서 파일을 스칼라로 받으므로 `cli/`는 benchmark·cts 결과 타입을 알지 못합니다. `probe`와 `cts.cases`는 화면 없이 `CommandCoordinator`가 직접 처리하며, 이때만 `cli/`가 `camera/CameraProbeReader`와 `cts/` 카탈로그를 읽습니다. protocol v1을 변경할 때 양쪽 검증기를 함께 확인합니다. |
| `camera/` | 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다. `close(done)` 완료 전에 다음 카메라를 열지 않습니다. |
| `metrics/` | `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다. 화면과 회귀 판정을 담당하지 않습니다. |
| `benchmark/` | 루트에는 `BenchmarkActivity`·`HistoryActivity` 두 화면만 둡니다. `benchmark/domain/`은 profile, 러너, 지표 계산, validity, 내부 점수, 비교 규칙, 보관 규칙, presenter를 담는 순수 Kotlin 층이며 `android.*`·`org.json`·`benchmark/platform/`을 import하지 않습니다. `benchmark/platform/`은 `BenchmarkStore`·`BenchmarkReport`(org.json 파일 경계)·`DeviceInstance`·`SubjectPrefs`·`BenchmarkPrefs`·`ThermalTracker`·`ProfileCompatibilityChecker` 같은 파일·기기 어댑터입니다. 이 방향은 `LayerIsolationTest`가 소스를 읽어 검사하므로 어기면 JVM 테스트가 실패합니다. |
| `telemetry/` | `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성합니다. listener는 기록 스레드에서 동기 실행됩니다. |
| `cts/` | CTS 스타일 카메라 검사를 앱 안에서 실행합니다. `CtsEntryActivity`에서 두 방식 중 하나를 고릅니다. 커스텀 케이스는 `CtsCatalog`가 목록을 순수 Kotlin으로 정의하고, `CtsRunner` 계약과 `CameraCaseRunner` 기반 클래스, 공통 Camera2 호출 `Camera2Ops` 위에 케이스별 하위 패키지(`onoff/`·`switching/`·`sizes/`·`combination/`·`snapshot/`)가 놓이며, `recording/`은 녹화 케이스들이 공유하는 규칙과 MediaRecorder 도우미입니다. 각 패키지는 판정을 순수 Kotlin `…Rules`에, 카메라 호출을 `…Runner`에 둡니다. `vendored/`는 `:ctsvendor` 모듈의 AOSP CTS 테스트를 JUnit으로 실행하는 화면입니다. 어느 쪽도 공식 CTS 판정을 대체하지 않습니다. |
| `ctsvendor/` (별도 Gradle 모듈) | AOSP `android16-release`의 camera2 CTS 소스(`RecordingTest`, `StillCaptureTest`, `BurstCaptureTest`, `Camera2SurfaceViewTestCase`, `CtsCameraUtils`, `com.android.ex.camera2`)를 그대로 두고, instrumentation 없이 돌도록 같은 이름의 `androidx.test` 대역과 `@TestApi` 치환 패치를 더한 모듈입니다. `VendoredCts`가 Instrumentation 대역과 host Activity를 등록하고, `VendoredRun`이 JUnit runner로 테스트 메서드 하나를 실행하며, `VendoredCatalog`가 reflection으로 `@Test` 메서드를 나열합니다. 패치 목록과 재동기화 절차는 `ctsvendor/UPSTREAM.md`에 있습니다. |
| `ui/`와 `MainActivity.kt` | Live 관측값과 Canvas 그래프, 공통 `Look` 토큰과 접기·펼치기, `MetricRows`의 결과·비교 행, 카메라 선택과 권한 처리를 제공합니다. |

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 삭제한 실행을 가리키는 포인터는 정리하며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장합니다. `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달합니다. 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다.

