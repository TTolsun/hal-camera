---
based_on: [overall-architecture, data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - tools/halcam/halcam/cli.py
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/ConcurrentCameraActivity.kt
  - app/src/main/java/dev/halcamera/WorkbenchActivity.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt
decisions: []
verifications: []
---

**카메라 콜백을 기록하고, 측정값으로 바꾼 뒤, 저장된 실행과 비교합니다.** 앱 모듈은 화면과 측정을 담당합니다. 별도 모듈인 `:ctsvendor`는 AOSP CTS 원문을 실행합니다.

| 순서 | 담당 코드 | 하는 일 |
| --- | --- | --- |
| 1. 카메라를 엽니다. | `CameraEngine` | Camera2 또는 CameraX로 카메라를 구동합니다. |
| 2. 콜백을 기록합니다. | `Telemetry`, `FlightRecorder` | 프레임과 메타데이터를 이벤트로 남깁니다. |
| 3. 측정값을 만듭니다. | `BenchmarkRunner`, `RunAssembler`, `MetricExtractor` | 실행 시각과 이벤트를 합쳐 지표를 계산합니다. |
| 4. 저장하고 비교합니다. | `BenchmarkReport`, `BaselineManager`, `RegressionDetector` | JSON을 저장하고 비교 결과를 계산합니다. |

화면은 다음 역할로 나뉩니다. 측정 결과는 앱 내부 파일에 저장하며 서버나 데이터베이스를 사용하지 않습니다.

| 화면 | 할 수 있는 일 |
| --- | --- |
| Live · `MainActivity` | 앱을 열면 나오는 화면입니다. 프리뷰를 보고 사진·동영상을 촬영합니다. |
| Multi · `ConcurrentCameraActivity` | 독립 카메라 장치별 사진·영상을 제공합니다. Live의 Multi · P와 Multi · V에서 엽니다. |
| Lab · `WorkbenchActivity` | 기기 정보, 검사 도구, 저장된 결과와 설정을 엽니다. |
| Benchmark · `BenchmarkActivity` | 정해진 조건(profile)으로 측정하고 결과를 저장합니다. |
| 실행 기록 · `HistoryActivity` | 저장된 실행을 찾고 비교하거나 내보냅니다. |

PC에서 같은 기능을 실행하려면 [CLI](cli.md)를 사용합니다. `CliProvider`와 `CommandCoordinator`가 ADB 요청을 받아 화면이나 조회 기능에 연결합니다. Python 도구는 선택 사항입니다.

비교 기준을 고르는 방법은 [Benchmark](benchmark.md#비교-기준을-고르세요)에 있습니다. Live의 사진·동영상은 측정 파일과 별도로 저장합니다. 포맷과 폴더는 [저장 파일](engine.md#yuv-저장-포맷)을 확인하세요. 벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.

### 코드를 처음 읽는 순서

1. `camera/CameraEngine.kt`에서 열기·닫기 계약을 확인합니다.
2. `telemetry/Telemetry.kt`와 `FlightRecorder.kt`에서 이벤트와 보존 방식을 확인합니다.
3. `benchmark/domain/BenchmarkRunner.kt`에서 실행 순서와 실패 처리를 읽습니다.
4. `benchmark/domain/RunAssembler.kt`에서 실행 결과와 이벤트가 합쳐지는 지점을 확인합니다.
5. `MainActivity.kt`, `BenchmarkActivity.kt`, `HistoryActivity.kt`에서 화면과 실행 코드의 연결을 확인합니다.
