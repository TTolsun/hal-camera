---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/RunIndex.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkCsv.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
decisions: []
verifications: []
---

**변경할 기능의 패키지부터 여세요.** 아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다.

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `camera/` | 엔진 계약, Camera2·CameraX 구현, 엔드포인트 열거를 제공합니다. `close(done)` 완료 전에 다음 카메라를 열지 않습니다. |
| `metrics/` | `MetricExtractor`가 이벤트를 관측 표본과 통계로 바꿉니다. 화면과 회귀 판정을 담당하지 않습니다. |
| `benchmark/` | profile, 러너, 지표 계산, validity, 저장, 비교와 이력 화면을 제공합니다. Android 의존성이 있는 Activity·저장 어댑터와 순수 계산 로직을 구분합니다. |
| `telemetry/` | `Telemetry`가 이벤트를 만들고 `FlightRecorder`가 보존합니다. `IncidentExporter`는 incident ZIP을 작성합니다. listener는 기록 스레드에서 동기 실행됩니다. |
| `ui/`와 `MainActivity.kt` | LIVE 관측값과 Canvas 그래프, 공통 `Look` 토큰, 카메라 선택과 권한 처리를 제공합니다. |

`BenchmarkActivity`는 실행 완료 후 별도 입출력 스레드에서 조립·저장·비교를 처리하고 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

`HistoryActivity`는 같은 저장소를 읽습니다. `RunIndex`는 이벤트와 표본 배열을 제거한 목록 데이터를 유지하고, 결과를 열 때 원본 JSON을 다시 읽습니다. 파일 읽기·삭제·CSV 생성은 별도 스레드에서 처리합니다. `BenchmarkCsv`는 지표당 한 행을 작성하고 FileProvider로 공유합니다.

`BenchmarkReport`는 schema 4를 쓰고 schema 3·4를 읽습니다. `BenchmarkStore`는 실행 파일과 baseline 인덱스를 관리합니다. 삭제한 실행을 가리키는 포인터는 정리하며, 기존 실행 JSON은 비교 상태가 바뀌어도 다시 쓰지 않습니다.

`camera/MediaLibrary`는 사진 쌍과 동영상을 MediaStore에 저장합니다. `StillPair`와 `YuvPacking`은 버퍼 연결과 YUV 변환을 담당합니다. `GalleryActivity`는 HALCamera 앨범을 조회합니다. 미디어 저장은 벤치마크 지표 계산과 분리되어 있습니다.
