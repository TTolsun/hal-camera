---
based_on: ["overall-architecture"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/BenchmarkStore.kt","app/src/main/java/dev/halcamera/benchmark/RegressionRules.kt","app/src/main/java/dev/halcamera/benchmark/RunValidity.kt","app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt","app/src/main/java/dev/halcamera/camera/CameraEndpointResolver.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/cli/CliProvider.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/cli/CommandStore.kt","app/src/main/java/dev/halcamera/telemetry/IncidentExporter.kt"]
decisions: []
verifications: []
---
카메라 점유와 시계 비교에서 지켜야 하는 제약은 카메라를 점유하는 `CameraEngine`이 항상 하나여야 한다는 점과, 완료 콜백 호출 전까지 기기를 반환하지 않는다는 것입니다. `BenchmarkRunner`는 `Driver`, `Scheduler`, `clock`을 주입받아 `elapsedRealtimeNanos` 기반의 절대 시간을 사용하며, 센서 시각은 기기에서 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 앱 시각과 직접 비교합니다. 공개 Camera2 API로 카메라를 열거하며 논리 카메라의 물리 endpoint를 독립적으로 열 수 있다고 가정하지 않습니다. `close(done)` 완료 전에 다음 카메라를 열지 않으며, 벤치마크 JSON 과 incident ZIP에는 이미지 픽셀을 저장하지 않습니다.

계산 로직과 저장 경계에서 지켜야 하는 규칙은 `BenchmarkRunner`가 JVM 테스트로 검증 가능한 계산 로직을 포함하고, Activity와 파일 어댑터는 Android 의존성을 가지는 것입니다. 벤치마크의 `org.json`은 파일 경계에서 사용하고, `BenchmarkReportCodec`의 데이터 계약은 `Map<String, Any?>`로 전달하며 schema 3도 읽습니다. `RunValidity`는 알 수 없는 flag가 존재하면 비교와 점수 산정을 막고, `ScoreComposer`는 calibration과 모델·endpoint·계약이 다르거나 필수 지표가 누락되면 내부 점수를 계산하지 않습니다. 회귀 임계값은 `RegressionRules`에서 관리하며 baseline은 명시적으로만 지정합니다. CLI의 접근은 shell UID 2000과 DUMP 권한을 검사하고, 파일 접근은 CLI 요청이 등록한 artifact ID에 한정합니다.
