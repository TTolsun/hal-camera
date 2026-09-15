---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/benchmark/ProfileComparison.kt
  - app/src/main/java/dev/halcamera/benchmark/RepeatStatistics.kt
  - app/src/main/java/dev/halcamera/benchmark/ProfileLibrary.kt
  - app/src/main/java/dev/halcamera/benchmark/ProfileArchive.kt
  - app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - app/src/main/java/dev/halcamera/camera/LiveController.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkController.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/RunIndex.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkCsv.kt
  - app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt
  - app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt
  - app/src/main/java/dev/halcamera/cts/recording/BasicRecordingRules.kt
  - app/src/main/java/dev/halcamera/cts/recording/BasicRecordingRunner.kt
  - app/src/main/java/dev/halcamera/cts/CtsCaseActivity.kt
  - app/src/main/java/dev/halcamera/CameraProbeActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbe.kt
  - app/src/main/java/dev/halcamera/camera/CameraProbeReader.kt
decisions: []
verifications: []
---

**변경할 기능의 패키지부터 여세요.** 아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다.

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `cli/`와 `tools/halcam/` | shell 호출자 검사, 영속 요청 상태, artifact 등록과 PC 파일 수집을 담당합니다. protocol v1을 변경할 때 양쪽 검증기를 함께 확인합니다. |
| `camera/` | 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다. `close(done)` 완료 전에 다음 카메라를 열지 않습니다. |
| `metrics/` | `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다. 화면과 회귀 판정을 담당하지 않습니다. |
| `benchmark/` | profile, 러너, 지표 계산, validity, 내부 점수, 저장, 비교와 이력 화면을 제공합니다. Android 의존성이 있는 Activity·저장 어댑터와 순수 계산 로직을 구분합니다. |
| `telemetry/` | `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성합니다. listener는 기록 스레드에서 동기 실행됩니다. |
| `cts/` | CTS 카메라 테스트를 앱 안에서 실행합니다. 케이스는 CTS 클래스별 하위 패키지(`cts/recording/`)에 둡니다. `BasicRecordingRules`가 `RecordingTest#testBasicRecording`의 판정을 순수 Kotlin으로 옮기고, `BasicRecordingRunner`가 카메라와 MediaRecorder를 다룹니다. 공식 CTS 판정을 대체하지 않습니다. |
| `ui/`와 `MainActivity.kt` | LIVE 관측값과 Canvas 그래프, 공통 `Look` 토큰, 카메라 선택과 권한 처리를 제공합니다. |

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 삭제한 실행을 가리키는 포인터는 정리하며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장합니다. `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.

`camera/RecentMediaThumbnail`은 저장 완료된 앨범 항목의 썸네일을 별도 작업 스레드에서 읽고 `ui/RecentMediaButton`에 전달합니다. 화면을 나가면 관찰을 중단하고 뒤늦은 조회 결과는 반영하지 않습니다.

### 반복 측정 프로파일 비교

ProfileLibrary와 ProfileArchive는 외부 JSON의 검증·출처·별도 파일 보관을 담당합니다. ProfileComparison은 기기·빌드·계약·환경·중복 검사를, RepeatStatistics는 실행 단위 기술 통계와 순열검정을 담당합니다. ProfileComparisonActivity가 시스템 파일 선택기와 묶음 선택·결과 공유를 연결합니다.

`CameraProbeActivity`(PROBE)는 카메라를 열지 않고 `CameraCharacteristics`를 읽어 표로 보여 줍니다. `camera/CameraProbeReader`가 공개 카메라 ID 전부와 논리 카메라 뒤의 물리 카메라를 섹션 단위로 읽고, `camera/CameraProbe`의 순수 Kotlin 모델과 TXT·JSON 렌더러가 화면과 공유 파일을 만듭니다. enum 값의 이름은 `CameraMetadata` 상수에서 reflection으로 읽으므로 새 API 값도 그대로 이름이 붙습니다. 이 화면은 HAL이 공개한 사양을 보여 줄 뿐 측정하지 않으며, 측정은 BENCHMARK가 담당합니다. 필터에 단어를 넣으면 그 단어가 든 줄만 목록으로 나오고, 항목을 누르면 해당 줄로 이동합니다.

`cts/CtsCaseActivity`는 LIVE 상단 `도구` 메뉴에서 LIVE 카메라를 닫은 뒤 열리며, 화면의 SurfaceView가 CTS의 `Camera2SurfaceViewCtsActivity` 역할을 합니다. `BasicRecordingRunner`는 카메라마다 CamcorderProfile을 CTS 순서대로 3초씩 녹화하고, `BasicRecordingRules.validate`가 MediaExtractor로 읽은 샘플 시각으로 길이 오차와 프레임 드롭률을 판정합니다. CTS와 달리 한 프로파일이 실패해도 나머지 프로파일을 계속 실행합니다. 케이스를 추가하면 규칙은 순수 Kotlin에 두고 JVM 테스트를 함께 씁니다.
