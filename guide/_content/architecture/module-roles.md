---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt
  - app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt
  - app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt
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
| `cli/`와 `tools/halcam/` | shell 호출자 검사, 영속 요청 상태, artifact 등록과 PC 파일 수집을 담당합니다. 화면 어댑터 `LiveController`와 `BenchmarkController`도 이 패키지에 두어 `camera/`와 `benchmark/`가 `cli/`를 import하지 않게 합니다. `BenchmarkController.reportSaved`는 run ID·중단 사유·schema 버전을 스칼라로 받으므로 `cli/`는 benchmark 타입을 알지 못합니다. protocol v1을 변경할 때 양쪽 검증기를 함께 확인합니다. |
| `camera/` | 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다. `close(done)` 완료 전에 다음 카메라를 열지 않습니다. |
| `metrics/` | `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다. 화면과 회귀 판정을 담당하지 않습니다. |
| `benchmark/` | 루트에는 `BenchmarkActivity`·`HistoryActivity`·`ProfileComparisonActivity` 세 화면만 둡니다. `benchmark/domain/`은 profile, 러너, 지표 계산, validity, 내부 점수, 비교 규칙, presenter를 담는 순수 Kotlin 층이며 `android.*`·`org.json`·`benchmark/platform/`을 import하지 않습니다. `benchmark/platform/`은 `BenchmarkStore`·`BenchmarkReport`(org.json 파일 경계)·`ProfileLibrary`·`SubjectPrefs`·`ThermalTracker`·`ProfileCompatibilityChecker` 같은 파일·기기 어댑터입니다. 이 방향은 `LayerIsolationTest`가 소스를 읽어 검사하므로 어기면 JVM 테스트가 실패합니다. |
| `telemetry/` | `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성합니다. listener는 기록 스레드에서 동기 실행됩니다. |
| `cts/` | CTS 스타일 카메라 검사를 앱 안에서 실행합니다. `CtsEntryActivity`에서 두 방식 중 하나를 고릅니다. 커스텀 케이스는 `CtsCatalog`가 목록을 순수 Kotlin으로 정의하고, `CtsRunner` 계약과 `CameraCaseRunner` 기반 클래스, 공통 Camera2 호출 `Camera2Ops` 위에 케이스별 하위 패키지(`onoff/`·`switching/`·`sizes/`·`combination/`·`snapshot/`)가 놓이며, `recording/`은 녹화 케이스들이 공유하는 규칙과 MediaRecorder 도우미입니다. 각 패키지는 판정을 순수 Kotlin `…Rules`에, 카메라 호출을 `…Runner`에 둡니다. `vendored/`는 `:ctsvendor` 모듈의 AOSP CTS 테스트를 JUnit으로 실행하는 화면입니다. 어느 쪽도 공식 CTS 판정을 대체하지 않습니다. |
| `ctsvendor/` (별도 Gradle 모듈) | AOSP `android16-release`의 camera2 CTS 소스(`RecordingTest`, `Camera2SurfaceViewTestCase`, `CtsCameraUtils`, `com.android.ex.camera2`)를 그대로 두고, instrumentation 없이 돌도록 같은 이름의 `androidx.test` 대역과 `@TestApi` 치환 패치를 더한 모듈입니다. `VendoredCts`가 Instrumentation 대역과 host Activity를 등록하고, `VendoredRun`이 JUnit runner로 테스트 메서드 하나를 실행하며, `VendoredCatalog`가 reflection으로 `@Test` 메서드를 나열합니다. 패치 목록과 재동기화 절차는 `ctsvendor/UPSTREAM.md`에 있습니다. |
| `ui/`와 `MainActivity.kt` | LIVE 관측값과 Canvas 그래프, 공통 `Look` 토큰, 카메라 선택과 권한 처리를 제공합니다. |

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 삭제한 실행을 가리키는 포인터는 정리하며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장합니다. `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달합니다. 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다.

### 반복 측정 프로파일 비교

ProfileLibrary와 ProfileArchive는 외부 JSON의 검증·출처·별도 파일 보관을 담당합니다. ProfileComparison은 기기·빌드·계약·환경·중복 검사를, RepeatStatistics는 실행 단위 기술 통계와 순열검정을 담당합니다. ProfileComparisonActivity가 시스템 파일 선택기와 묶음 선택·결과 공유를 연결합니다.

`CameraProbeActivity`(PROBE)는 카메라를 열지 않고 `CameraCharacteristics`를 읽어 표로 보여 줍니다. `camera/CameraProbeReader`가 공개 카메라 ID 전부와 논리 카메라 뒤의 물리 카메라를 섹션 단위로 읽고, `camera/CameraProbe`의 순수 Kotlin 모델과 TXT·JSON 렌더러가 화면과 공유 파일을 만듭니다. enum 값의 이름은 `CameraMetadata` 상수에서 reflection으로 읽으므로 새 API 값도 그대로 이름이 붙습니다. 이 화면은 HAL이 공개한 사양을 보여 줄 뿐 측정하지 않으며, 측정은 BENCHMARK가 담당합니다. 필터에 단어를 넣으면 그 단어가 든 줄만 목록으로 나오고, 항목을 누르면 해당 줄로 이동합니다.

`cts/CtsEntryActivity`는 LIVE 상단 `도구` 메뉴에서 LIVE 카메라를 닫은 뒤 열리는 선택 화면이고, 커스텀 케이스와 CTS 원문 케이스 중 하나를 고릅니다. 커스텀 케이스는 `CtsCaseListActivity`의 체크리스트에서 항목을 고르고 `실행`을 누르면 `suite/CtsSuiteRunActivity`가 차례로 실행하며, 행의 `›`로 `CtsCaseActivity`를 열면 그 케이스 하나만 실행합니다. 두 목록은 `suite/CtsChecklistActivity`를 공유하고, `SuitePlan`이 선택 순서와 예상 시간을, `SuiteReportPresenter`가 항목별 결과를 모은 보고서를 순수 Kotlin으로 만듭니다. 화면의 SurfaceView가 CTS의 `Camera2SurfaceViewCtsActivity` 역할을 하며, 러너가 필요한 크기로 버퍼를 바꾸고 `surfaceChanged`를 기다린 뒤 세션을 엽니다. 케이스는 다섯 가지입니다. `FastOnOffRunner`는 표준 열기와 열자마자 닫는 빠른 열기를 번갈아 5회 수행하고 첫 프레임의 SENSOR_TIMESTAMP를 검사합니다. `SwitchingRunner`는 카메라를 차례로 5회 순회한 뒤 카메라마다 3초 녹화합니다. `AllSizeOnOffRunner`는 SurfaceHolder 크기 전부를 하나씩 열고, `StillPreviewCombinationRunner`는 JPEG 크기와 프리뷰 크기의 모든 조합에서 정지 영상을 한 장 찍으며, `VideoSnapshotRunner`는 25초 녹화 중 5~20초 사이 무작위 시점에 같은 세션으로 스냅샷을 찍습니다. 녹화 케이스들은 `recording/BasicRecordingRules`의 길이 오차·프레임 드롭률 판정과 `CamcorderRecording`을 공유합니다. CTS와 달리 한 단계가 실패해도 나머지를 계속 실행하고, 모든 케이스는 실행 중 중단할 수 있습니다. 케이스를 추가하면 규칙은 순수 Kotlin에 두고 JVM 테스트를 함께 쓰며, `CtsCatalog`와 `CtsRunners`에 등록합니다.

CTS 원문 케이스는 `vendored/VendoredCtsListActivity`가 `:ctsvendor` 모듈의 `VendoredCatalog`에서 `@Test` 메서드를 체크리스트로 나열하고, 체크한 메서드는 같은 `CtsSuiteRunActivity`가 차례로, 행의 `›`는 `VendoredCaseActivity`가 그 메서드 하나를 실행합니다. 이 Activity는 가져온 `Camera2SurfaceViewCtsActivity`를 상속해 테스트의 `ActivityTestRule`이 돌려받는 인스턴스가 되고, 가져온 레이아웃의 SurfaceView를 앱 화면으로 옮겨 붙입니다. `VendoredRun`은 작업 스레드에서 JUnit runner를 돌리고 `RunListener`로 결과를 받습니다. `RecordingTest#testBasicRecording`은 이 경로로만 실행하며, 결과는 메서드 하나에 PASS·FAIL 하나이고 실패 문구는 CTS의 assertion 메시지 그대로입니다. 중단은 실행 중인 테스트가 쥔 `mCamera`를 닫아 테스트를 실패시키는 방식입니다. JUnit이 실행 중인 본문을 멈출 수단이 없기 때문입니다. `VendoredCts.MIN_SDK`(34) 아래 기기에서는 선택 화면이 이 경로를 비활성화합니다.
