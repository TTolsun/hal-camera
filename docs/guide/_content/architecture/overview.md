---
based_on: [overall-architecture, data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt
decisions: []
verifications: []
---

**LIVE, BENCHMARK, RESULTS 중 수정할 화면과 연결된 코드를 먼저 확인하세요.** 현재 앱은 카메라 성능을 관측하고, 저장된 실행을 비교하는 단일 Android 앱 모듈입니다. v0.2의 Home·Auto Check·건강 판정 화면은 제거되었습니다.

| 단계 | 담당 코드 | 책임 |
| --- | --- | --- |
| 카메라 구동 | `CameraEngine`, `Camera2Engine`, `CameraXEngine` | 엔진 수명주기와 카메라 요청을 처리합니다. |
| 콜백 기록 | `Telemetry`, `FlightRecorder` | 세션·프레임·시각·메타데이터를 이벤트로 기록합니다. |
| 지표 계산 | `BenchmarkRunner`, `RunAssembler`, `BenchmarkEvaluator`, `metrics/MetricExtractor` | 러너의 실행 시각과 콜백을 합쳐 측정값을 만듭니다. |
| 내부 점수 | `ScoreComposer` | 검토한 calibration의 범위에 맞는 적격 release run에 점수와 카테고리 평균을 계산합니다. |
| 저장·비교·표시 | `BenchmarkReport`, `BaselineManager`, `RegressionDetector`, 각 Activity | JSON 저장과 화면을 구성하고, 현재 기준에 따른 비교 결과를 계산합니다. |

`MainActivity`가 런처이며 LIVE에서는 관측한 수치만 표시합니다. `BenchmarkActivity`는 정해진 profile을 실행하고 결과를 저장합니다. `HistoryActivity`는 저장된 실행을 찾아 필터링하고 두 실행을 비교하거나 내보냅니다. 파일은 앱 내부에 저장하며 서버나 데이터베이스를 사용하지 않습니다.

baseline은 사용자가 명시적으로 지정합니다. baseline이 없으면 결과 화면은 이전의 비교 가능한 실행 대비 변화량만 표시합니다. 이력에서 임의로 선택한 실행도 실제 baseline이 아닌 한 회귀 판정의 기준이 되지 않습니다.

### 코드를 처음 읽는 순서

1. `camera/CameraEngine.kt`에서 열기·닫기 계약을 확인합니다.
2. `telemetry/Telemetry.kt`와 `FlightRecorder.kt`에서 이벤트와 보존 방식을 확인합니다.
3. `benchmark/BenchmarkRunner.kt`에서 실행 순서와 실패 처리를 읽습니다.
4. `benchmark/RunAssembler.kt`에서 러너 결과와 이벤트를 결합하는 지점을 확인합니다.
5. `MainActivity.kt`, `BenchmarkActivity.kt`, `HistoryActivity.kt`에서 화면과 실행 코드의 연결을 확인합니다.

LIVE의 사진·동영상은 MediaLibrary를 거쳐 DCIM/HALCamera 앨범에 저장하며 GalleryActivity에서 조회합니다. 측정 파일과 미디어 파일의 저장 경로를 구분하려면 아래 모듈 역할을 확인하세요.
