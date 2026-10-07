---
based_on: [overall-architecture, data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - tools/halcam/halcam/cli.py
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/WorkbenchActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt
decisions: []
verifications: []
---

앱은 카메라 성능을 관측하고 저장된 실행을 비교합니다. Android 앱 모듈과 CTS 원문을 실행하는 `:ctsvendor` 모듈로 구성됩니다.

| 단계 | 담당 코드 | 책임 |
| --- | --- | --- |
| CLI 제어 | `CliProvider`, `CommandCoordinator`, `assets/halcam.sh` | ADB 명령으로 스트림 지원 목록을 조회하고 엔진·크기를 지정한 프리뷰·사진·녹화·CTS 경로에 연결하며 요청 상태와 검증 가능한 결과 파일을 반환합니다. Python 도구는 선택 사항이며 `benchmark.run`은 화면과 같은 Camera2 표준 v2 측정과 JSON 저장 경로에 연결합니다. |
| 카메라 구동 | `CameraEngine`, `Camera2Engine`, `CameraXEngine` | 엔진 수명주기와 카메라 요청을 처리합니다. 계약과 두 엔진의 차이는 [Engine](engine.md)에 있습니다. |
| 콜백 기록 | `Telemetry`, `FlightRecorder` | 세션·프레임·시각·메타데이터를 이벤트로 기록합니다. |
| 지표 계산 | `BenchmarkRunner`, `RunAssembler`, `BenchmarkEvaluator`, `metrics/MetricExtractor` | 러너의 실행 시각과 콜백을 합쳐 측정값을 만듭니다. |
| 내부 점수 | `ScoreComposer` | 검토한 calibration의 범위에 맞는 적격 release run에 점수와 카테고리 평균을 계산합니다. |
| 저장·비교·표시 | `BenchmarkReport`, `BaselineManager`, `RegressionDetector`, 각 Activity | JSON 저장과 화면을 구성하고, 현재 기준에 따른 비교 결과를 계산합니다. |

`MainActivity`가 런처이며 앱을 열면 바로 Live 프리뷰를 표시합니다. Live에서는 프리뷰와 촬영 조작부, 선택한 관측 정보를 표시합니다. Lab 버튼으로 바로 여는 `WorkbenchActivity`는 기기 식별 정보, 검사 도구, 저장된 결과와 설정을 모읍니다. `BenchmarkActivity`는 정해진 profile을 실행하고 결과를 저장합니다. `HistoryActivity`는 저장된 실행을 찾아 필터링하고 두 실행을 비교하거나 내보냅니다. 파일은 앱 내부에 저장하며 서버나 데이터베이스를 사용하지 않습니다.

`BaselineManager`는 사용자가 baseline에 추가한 정상 실행의 집합을 관리합니다. `HistoryActivity`의 두 실행 비교에서는 먼저 선택한 실행을 그 비교에만 쓰는 baseline으로 전달합니다. 이전 실행을 자동 선택한 reference 비교와 달리, 이 경로에서는 저하·개선을 판정합니다. 선택 절차와 표시 의미는 [Benchmark](benchmark.md)에 있습니다.

### 코드를 처음 읽는 순서

1. `camera/CameraEngine.kt`에서 열기·닫기 계약을 확인합니다.
2. `telemetry/Telemetry.kt`와 `FlightRecorder.kt`에서 이벤트와 보존 방식을 확인합니다.
3. `benchmark/domain/BenchmarkRunner.kt`에서 실행 순서와 실패 처리를 읽습니다.
4. `benchmark/domain/RunAssembler.kt`에서 러너 결과와 이벤트를 결합하는 지점을 확인합니다.
5. `MainActivity.kt`, `BenchmarkActivity.kt`, `HistoryActivity.kt`에서 화면과 실행 코드의 연결을 확인합니다.

Live의 사진·동영상은 MediaLibrary를 거쳐 DCIM/HALCamera 앨범에 저장하며 GalleryActivity에서 조회합니다. 측정 파일과 미디어 파일의 저장 경로를 구분하려면 아래 모듈 역할을 확인하세요.

YUV Save Format에서 JPEG 또는 NV21을 선택합니다. JPEG는 기존 `_YUV.jpg`를, NV21은 `_YUV.nv21`을 저장하며 두 파일을 함께 만들지 않습니다. 사진 모드는 선택한 포맷과 관계없이 `_metadata.json`을 함께 저장합니다. JPEG는 DCIM/HALCamera에, NV21과 JSON은 Download/HALCamera에 있습니다. 벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.

