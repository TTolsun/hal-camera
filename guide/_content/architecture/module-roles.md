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
  - app/src/main/java/dev/halcamera/cts/vendored/VendoredCaseActivity.kt
  - app/src/main/java/dev/halcamera/cts/vendored/VendoredCtsListActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/CtsChecklistActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/CtsSuiteRunActivity.kt
  - app/src/main/java/dev/halcamera/cts/suite/SuitePlan.kt
  - app/src/main/java/dev/halcamera/cts/suite/SuiteReport.kt
  - ctsvendor/src/main/java/dev/halcamera/ctsvendor/VendoredCatalog.kt
  - app/src/main/java/dev/halcamera/CameraProbeActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbe.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbeReader.kt
decisions: []
verifications: []
---

**변경할 기능의 패키지부터 여세요.** 아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다.

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `cli/`와 `tools/halcam/` | shell 호출자 검사, 영속 요청 상태, artifact 등록과 PC 파일 수집을 담당합니다. 화면 어댑터 `LiveController`, `BenchmarkController`, `CtsController`도 이 패키지에 두어 `camera/`와 `benchmark/`가 `cli/`를 import하지 않게 합니다. `BenchmarkController.reportSaved`는 run ID·중단 사유·schema 버전을, `CtsController.reportSaved`는 결과 수와 보고서 파일을 스칼라로 받으므로 `cli/`는 benchmark·cts 결과 타입을 알지 못합니다. `probe`와 `cts.cases`는 화면 없이 `CommandCoordinator`가 직접 처리하며, 이때만 `cli/`가 `camera/CameraProbeReader`와 `cts/` 카탈로그를 읽습니다. protocol v1을 변경할 때 양쪽 검증기를 함께 확인합니다. |
| `camera/` | 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다. `close(done)` 완료 전에 다음 카메라를 열지 않습니다. |
| `metrics/` | `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다. 화면과 회귀 판정을 담당하지 않습니다. |
| `benchmark/` | 루트에는 `BenchmarkActivity`·`HistoryActivity`·`ProfileComparisonActivity` 세 화면만 둡니다. `benchmark/domain/`은 profile, 러너, 지표 계산, validity, 내부 점수, 비교 규칙, presenter를 담는 순수 Kotlin 층이며 `android.*`·`org.json`·`benchmark/platform/`을 import하지 않습니다. `benchmark/platform/`은 `BenchmarkStore`·`BenchmarkReport`(org.json 파일 경계)·`ProfileLibrary`·`SubjectPrefs`·`ThermalTracker`·`ProfileCompatibilityChecker` 같은 파일·기기 어댑터입니다. 이 방향은 `LayerIsolationTest`가 소스를 읽어 검사하므로 어기면 JVM 테스트가 실패합니다. |
| `telemetry/` | `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성합니다. listener는 기록 스레드에서 동기 실행됩니다. |
| `cts/` | AOSP CTS 카메라 테스트를 앱 안에서 실행하는 화면입니다. `vendored/VendoredCtsListActivity`가 `:ctsvendor` 모듈의 `VendoredCatalog`가 나열한 `@Test` 메서드를 클래스별 체크리스트로 보여 주고, 체크한 메서드는 `suite/CtsSuiteRunActivity`가 차례로 실행합니다. `suite/SuitePlan`이 항목 키(`vendored:<class>#<method>`)·선택 순서·예상 시간을, `suite/SuiteReport`가 항목별 결과와 보고서 텍스트·JSON을 순수 Kotlin으로 만듭니다. 커스텀으로 옮겨 적은 케이스는 두지 않으며, 어느 결과도 공식 CTS 판정을 대체하지 않습니다. |
| `ctsvendor/` (별도 Gradle 모듈) | AOSP `android16-release`의 camera2 CTS 소스(`RecordingTest`, `StillCaptureTest`, `BurstCaptureTest`, `Camera2SurfaceViewTestCase`, `CtsCameraUtils`, `com.android.ex.camera2`)를 그대로 두고, instrumentation 없이 돌도록 같은 이름의 `androidx.test` 대역과 `@TestApi` 치환 패치를 더한 모듈입니다. `VendoredCts`가 Instrumentation 대역과 host Activity를 등록하고, `VendoredRun`이 JUnit runner로 테스트 메서드 하나를 실행하며, `VendoredCatalog`가 reflection으로 `@Test` 메서드를 나열합니다. 패치 목록과 재동기화 절차는 `ctsvendor/UPSTREAM.md`에 있습니다. |
| `ui/`와 `MainActivity.kt` | Live 관측값과 Canvas 그래프, 공통 `Look` 토큰, 카메라 선택과 권한 처리를 제공합니다. |

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 삭제한 실행을 가리키는 포인터는 정리하며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장합니다. `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달합니다. 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다.

### 반복 측정 프로파일 비교

ProfileLibrary와 ProfileArchive는 외부 JSON의 검증·출처·별도 파일 보관을 담당합니다. ProfileComparison은 기기·빌드·계약·환경·중복 검사를, RepeatStatistics는 실행 단위 기술 통계와 순열검정을 담당합니다. ProfileComparisonActivity가 시스템 파일 선택기와 묶음 선택·결과 공유를 연결합니다.

`CameraProbeActivity`(Probe)는 카메라를 열지 않고 `CameraCharacteristics`를 읽어 표로 보여 줍니다. `camera/CameraProbeReader`가 공개 카메라 ID 전부와 논리 카메라 뒤의 물리 카메라를 섹션 단위로 읽고, `camera/CameraProbe`의 순수 Kotlin 모델과 TXT·JSON 렌더러가 화면과 공유 파일을 만듭니다. enum 값의 이름은 `CameraMetadata` 상수에서 reflection으로 읽으므로 새 API 값도 그대로 이름이 붙습니다. 이 화면은 HAL이 공개한 사양을 보여 줄 뿐 측정하지 않으며, 측정은 Benchmark가 담당합니다. 필터에 단어를 넣으면 그 단어가 든 줄만 목록으로 나오고, 항목을 누르면 해당 줄로 이동합니다.

CTS는 Live 상단 `도구` 메뉴에서 Live 카메라를 닫은 뒤 `vendored/VendoredCtsListActivity`로 바로 열립니다(`VendoredCts.MIN_SDK`(34) 아래 기기에서는 메뉴가 안내만 띄웁니다). 이 체크리스트는 `:ctsvendor` 모듈의 `VendoredCatalog`가 reflection으로 나열한 `@Test` 메서드를 클래스별로 보여 주고, `suite/SuitePlan`이 선택 순서와 예상 시간을, `suite/SuiteReport`가 항목별 결과를 모은 보고서를 순수 Kotlin으로 만듭니다. 항목 키는 `vendored:<class>#<method>`이며 CLI의 `cts.cases`·`cts.run`이 같은 키를 씁니다.

체크한 메서드는 `suite/CtsSuiteRunActivity`가 차례로, 행의 `›`는 `VendoredCaseActivity`가 그 메서드 하나를 실행합니다. 두 Activity는 가져온 `Camera2SurfaceViewCtsActivity`를 상속해 테스트의 `ActivityTestRule`이 돌려받는 인스턴스가 되고, 가져온 레이아웃의 SurfaceView를 앱 화면으로 옮겨 붙입니다. `VendoredRun`은 작업 스레드에서 JUnit runner를 돌리고 `RunListener`로 결과를 받습니다. 결과는 메서드 하나에 PASS·FAIL·SKIP 하나이고 실패 문구는 CTS의 assertion 메시지 그대로입니다. 중단은 실행 중인 테스트가 쥔 `mCamera`를 닫아 테스트를 실패시키는 방식입니다. JUnit이 실행 중인 본문을 멈출 수단이 없기 때문입니다. 가져온 클래스는 `RecordingTest`, `StillCaptureTest`, `BurstCaptureTest`이며, 실기기에서 PASS를 확인한 메서드는 `docs/STATUS.md`에 적습니다.
