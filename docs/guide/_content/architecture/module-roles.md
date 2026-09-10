---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/check/AutoCheckRunner.kt
  - app/src/main/java/dev/halcamera/check/CheckEvaluator.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/MainActivity.kt
decisions: []
verifications: []
---
**작업할 기능에 해당하는 패키지부터 여세요.** 아래 경로는 `app/src/main/java/dev/halcamera/`를 기준으로 합니다. 구조도에 표시한 영역은 실제 패키지와 항상 일대일로 대응하지는 않습니다.

### 카메라 실행과 평가

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `camera/` | `CameraEngine`의 계약과 `Camera2Engine`·`CameraXEngine` 구현을 제공합니다. Camera2는 기본 크기 선택과 벤치마크용 `StreamSpec` 경로를 구분합니다. 세션 콜백과 `close(done)` 완료 통지를 러너에 전달합니다. |
| `check/` | 엔드포인트 열거, `AutoCheckRunner`의 상태 전이, `CheckEvaluator`의 평가와 `CheckActivity`를 담당합니다. 기본 관측 시간은 10초이며 촬영은 3회입니다. |
| `diagnosis/` | Auto Check와 LIVE 건강 표시에 사용하는 v0.2 지표·임계값·진단 로직입니다. `MetricExtractor`와 `UnknownReason`은 벤치마크도 사용합니다. 모든 화면이 이 건강 판정을 사용하는 것은 아닙니다. |
| `benchmark/` | 프로파일, 러너, 지표 계산, 유효성, schema 3 저장과 실행 비교를 담당합니다. 계산 로직과 Android 의존성이 있는 `BenchmarkActivity`·`ThermalTracker`를 구분합니다. |
| `home/`, `check/`, `benchmark/`, `MainActivity.kt` | `HomeActivity`, `CheckActivity`, `BenchmarkActivity`, `MainActivity`의 네 화면을 구성합니다. Home이 시작 화면이며, LIVE의 벤치마크 버튼은 `BenchmarkActivity`를 엽니다. 뷰는 Kotlin 코드로 생성합니다. |

벤치마크 결과가 잘못 표시되면 계산 코드와 표시 코드를 나누어 확인하세요. `BenchmarkActivity`는 `RunAssembler`, `BenchmarkReport`, `BaselineManager`, `RegressionDetector`를 입출력 실행기에서 호출합니다. 메인 스레드로 돌아온 뒤 `ResultPresenter`와 `ComparePresenter`의 결과를 표시합니다. 시작 카드와 진행률에는 `StartCardPresenter`와 `ProgressPresenter`를 사용합니다.

### 기록·저장·화면 지원

| 영역 | 역할과 수정 시 확인할 내용 |
| --- | --- |
| `telemetry/` | `Telemetry`가 콜백을 이벤트로 바꾸고, `FlightRecorder`가 링 버퍼와 incident 전후의 이벤트를 보존합니다. `IncidentExporter`는 이벤트와 환경 정보를 ZIP으로 내보냅니다. 라이브 listener는 기록 스레드에서 동기 실행됩니다. |
| `report/`, `baseline/`, `benchmark/` | 앱 내부 파일과 SharedPreferences를 사용합니다. `HealthReport`는 Auto Check 결과, `BenchmarkReport`는 schema 3 실행과 이벤트, `BenchmarkStore`는 실행 인덱스, `BaselineManager`는 비교 기준 포인터를 관리합니다. 공유는 사용자가 FileProvider를 통해 시작합니다. |
| `ui/` | `ScopeView`, `StripView`, `TimelineView`가 Canvas에 관측 데이터를 그립니다. `Look`은 공통 시각 설정을 제공합니다. |
| Android 카메라 스택 | 앱의 직접 측정 범위 밖에 있습니다. 앱이 기록한 API·콜백 시각을 HAL 내부 처리 시간이나 프리뷰 표시 완료 시각으로 해석하지 않습니다. |
