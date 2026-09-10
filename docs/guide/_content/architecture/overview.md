---
based_on: [overall-architecture, data-flow]
confidence: code
sources:
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
HALCamera는 단일 모듈 Android 앱입니다(`:app`, applicationId `dev.halcamera`, minSdk 26, compileSdk 36). 기기 카메라를 정해진 순서로 구동하면서 프레임워크가 보내는 콜백을 전부 기록하고, 그 기록을 지표로 바꿔 기기 내부 저장소에 JSON 실행 파일로 남깁니다. 서버, 데이터베이스, 외부 서비스는 없습니다. 앱 바깥에 있는 것은 Android 카메라 스택뿐입니다.

### 네 단계의 책임

동작은 한 방향으로 흐르는 네 단계로 나뉩니다.

1. **카메라 구동** — `camera/` 패키지. `CameraEngine` 인터페이스 뒤에 `Camera2Engine`과 `CameraXEngine`이 있습니다. 카메라를 점유하는 엔진은 항상 하나뿐이어야 하며, `close(done)`은 기기를 실제로 반납한 뒤에만 콜백을 호출합니다. 다음 open이 그 콜백에서 시작되기 때문입니다.
2. **기록** — `telemetry/` 패키지. `Telemetry.callback()`이 만들어 주는 `CaptureCallback`이 `onCaptureStarted`, `onCaptureCompleted`, `onCaptureFailed`, `onCaptureBufferLost`를 각각 `Event`로 바꿔 `FlightRecorder` 링 버퍼에 넣습니다. 모든 `atNs`는 `SystemClock.elapsedRealtimeNanos()` 하나의 시계를 씁니다. 센서 타임스탬프는 `sensorNs`에 따로 담깁니다.
3. **평가** — `check/`, `diagnosis/`, `benchmark/` 패키지. 러너·지표 계산·비교 로직은 Android 의존성에서 분리돼 JVM 단위 테스트로 검증됩니다. 같은 패키지의 Activity, ThermalTracker, 파일 저장 어댑터는 Android 의존성이 있으므로 패키지 전체가 순수 Kotlin인 것은 아닙니다. 러너(`AutoCheckRunner`, `BenchmarkRunner`)는 카메라와 시계를 `Driver`, `Scheduler`, `clock` 파라미터를 통해서만 만집니다.
4. **저장과 표시** — 평가가 끝난 실행 결과는 파일로 기록되고, 화면은 그 파일을 다시 읽습니다. 측정값을 읽는 경로와 비교 상태를 계산하는 경로를 구분합니다. RegressionDetector는 저장된 측정값 두 개로 비교 결과를 계산하는 순수 로직입니다.

### 평가 계층이 두 개인 이유

`diagnosis/`는 v0.2의 건강 판정 계층(PASS / WARN / FAIL)이고, `benchmark/`는 v0.3 Camera BenchMarker의 측정 전용 데이터 계약(IMPROVED / STABLE / REGRESSED / UNKNOWN)입니다. 2026-09-09에 v0.2를 중단하고 v0.3으로 전환하기로 결정했기 때문에(D-002) 두 계층이 M3까지 공존합니다. 전환 이유 자체는 결정 기록에 아직 적혀 있지 않으므로 확인 필요입니다.

Home·Auto Check는 v0.2 건강 판정을 사용하고, LIVE에서 진입하는 BenchmarkActivity는 v0.3 측정 경로를 실행합니다. `MainActivity`가 `FlightRecorder`, `Telemetry`, `HealthMonitor`를 직접 들고 있고, 카메라 열기, 권한 처리, incident 내보내기, CPU 샘플링까지 한 클래스에서 처리합니다. `BenchmarkRunner`는 코드에 존재하며 warm reopen 반복과 관측 세션을 구동하는 상태 기계입니다. BenchmarkActivity가 러너를 구동하고, 완료 후 RunAssembler로 결과를 조립해 BenchmarkReport로 저장합니다.

Camera2 엔진과 CameraX 엔진을 둘 다 유지하는 것은 2026-09-08의 결정입니다(D-001). Camera2가 측정 경로, CameraX가 비교 경로입니다. 이 결정의 이유는 결정 기록에 아직 없으므로 확인 필요입니다.

### 코드를 처음 읽는 순서

1. `camera/CameraEngine.kt` — 엔진이 지켜야 할 계약을 봅니다.
2. `telemetry/Telemetry.kt`와 `telemetry/FlightRecorder.kt` — 무엇이 어떤 이름의 이벤트로 기록되는지 봅니다. 이 두 파일을 합쳐도 200줄이 되지 않습니다.
3. `check/AutoCheckRunner.kt` — 엔드포인트마다 OPEN, CONFIGURE, FIRST_FRAME, OBSERVE(10초), STILL 3회, CLOSE 순서로 진행하는 상태 기계입니다.
4. `check/CheckEvaluator.kt` — 러너 결과와 이벤트가 만나서 지표가 되는 지점입니다.
5. `MainActivity.kt` — 위 요소들을 조립하는 곳입니다. 566줄이므로 마지막에 읽습니다.
