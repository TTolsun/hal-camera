---
based_on: [overall-architecture, data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/diagnosis/Model.kt
  - app/build.gradle.kts
  - app/src/main/java/dev/halcamera/MainActivity.kt#MainActivity
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt#CameraEngine
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt#Telemetry
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt#FlightRecorder
  - app/src/main/java/dev/halcamera/check/AutoCheckRunner.kt#AutoCheckRunner
  - app/src/main/java/dev/halcamera/check/CheckEvaluator.kt#CheckEvaluator
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt#BenchmarkRunner
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt#finishRun
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt#assemble
  - app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt#compare
decisions: [D-001, D-002]
verifications: []
---
**수정할 기능이 카메라 구동, 이벤트 기록, 지표 계산, 결과 표시 중 어디에 속하는지 먼저 확인하세요.** HALCamera는 이 네 책임을 나누어 구현한 단일 모듈 Android 앱입니다.

앱 모듈은 `:app`이며 `applicationId`는 `dev.halcamera`입니다. `minSdk`는 26, `compileSdk`는 36입니다. 카메라를 정해진 순서로 구동하고, 기록한 이벤트와 실행 시각을 측정값으로 바꿉니다. 결과는 기기 내부의 JSON 실행 파일에 저장합니다. 서버나 데이터베이스, 외부 서비스는 사용하지 않습니다.

### 네 단계의 책임

| 단계 | 담당 코드 | 확인할 내용 |
| --- | --- | --- |
| 카메라를 구동합니다. | `camera/CameraEngine.kt`와 두 엔진 구현 | 엔진은 하나만 카메라를 점유해야 합니다. 다음 카메라 열기는 `close(done)`으로 반환이 끝났음을 확인한 뒤 시작합니다. |
| 콜백을 기록합니다. | `telemetry/Telemetry.kt`, `FlightRecorder.kt` | 콜백을 `Event`로 바꾸어 메모리 링 버퍼에 저장합니다. |
| 지표를 계산합니다. | `check/`, `diagnosis/`, `benchmark/` | 러너가 기록한 실행 시각과 콜백 이벤트를 사용해 측정값·상태·비교 결과를 계산합니다. |
| 결과를 저장하고 표시합니다. | 실행 파일 작성 코드와 각 Activity | 측정값을 저장하는 과정과, 저장된 실행을 읽어 비교 상태를 계산하는 과정을 구분합니다. |

`Telemetry.callback()`은 `onCaptureStarted`, `onCaptureCompleted`, `onCaptureFailed`, `onCaptureBufferLost`를 이벤트로 기록합니다. 이벤트의 `atNs`는 `SystemClock.elapsedRealtimeNanos()`를 사용하고, 센서 타임스탬프는 별도의 `sensorNs` 필드에 담습니다.

러너와 지표 계산·비교 로직은 Android 의존성에서 분리되어 JVM 테스트로 확인할 수 있습니다. `AutoCheckRunner`와 `BenchmarkRunner`는 `Driver`, `Scheduler`, `clock`을 통해 카메라와 시계에 접근합니다. 같은 패키지에 있는 Activity, `ThermalTracker`, 파일 저장 어댑터까지 순수 Kotlin인 것은 아닙니다.

### 두 평가 경로의 차이

| 구분 | Auto Check·건강 표시 | 벤치마크 |
| --- | --- | --- |
| 평가 코드 | `check/`, `diagnosis/`를 사용합니다. | `benchmark/`를 사용합니다. |
| 결과의 의미 | v0.2 지표 상태인 PASS / WARN / FAIL / UNKNOWN과 종합 건강 수준을 판정합니다. | v0.3의 측정값과 IMPROVED / STABLE / REGRESSED / UNKNOWN 비교 상태를 제공합니다. |
| 화면 연결 | Home과 Auto Check는 건강 판정을 사용합니다. `MainActivity`의 LIVE에는 `HealthMonitor`가 연결됩니다. | LIVE에서 연 `BenchmarkActivity`가 실행·저장·비교 결과를 표시합니다. |

`HealthLevelV2`의 종합 건강 수준은 NORMAL / WARNING / ISSUE / INSUFFICIENT입니다. 지표별 상태와 종합 건강 수준은 서로 다른 값입니다.

2026-09-09의 [D-002 결정](_inputs/decisions.md)은 v0.2에서 v0.3으로 전환하고 M3까지 두 계층을 함께 유지하는 계획입니다. 전환 이유는 기록에 없어 **확인 필요**입니다.

`MainActivity`는 화면뿐 아니라 `FlightRecorder`, `Telemetry`, `HealthMonitor`, 권한 처리, 카메라 열기, incident 내보내기와 CPU 샘플링도 관리합니다. 이 클래스를 수정할 때는 화면 표시와 실행 동작을 함께 확인해야 합니다.

`BenchmarkActivity`는 `BenchmarkRunner`의 카메라 재열기 반복과 관측 세션을 실행합니다. 완료 후 `RunAssembler`로 결과를 조립하고 `BenchmarkReport`로 저장합니다. 이어서 기준 실행인 baseline 또는 이전 실행을 골라 `RegressionDetector`로 비교합니다. `RegressionDetector`는 두 실행의 저장된 측정값을 비교하는 순수 계산 로직입니다.

2026-09-08의 D-001 결정은 Camera2를 측정 경로로, CameraX를 비교 경로로 유지하는 것입니다. 두 엔진을 선택한 이유도 기록에 없어 **확인 필요**입니다.

### 코드를 처음 읽는 순서

아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다.

1. `camera/CameraEngine.kt`에서 엔진이 지켜야 할 열기·닫기 계약을 확인합니다.
2. `telemetry/Telemetry.kt`와 `FlightRecorder.kt`에서 이벤트 이름과 보존 방식을 확인합니다.
3. `check/AutoCheckRunner.kt`에서 엔드포인트별 실행 순서와 실패 처리를 읽습니다.
4. `check/CheckEvaluator.kt`에서 러너 결과와 이벤트가 지표로 바뀌는 지점을 확인합니다.
5. `MainActivity.kt`에서 화면과 실행 코드가 연결되는 방식을 확인합니다.
