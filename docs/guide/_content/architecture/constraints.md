---
based_on: [overall-architecture]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CliProvider.kt
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - app/src/main/java/dev/halcamera/cli/CommandStore.kt
  - app/src/main/java/dev/halcamera/camera/CameraEngine.kt
  - app/src/main/java/dev/halcamera/camera/CameraEndpointResolver.kt
  - app/src/main/java/dev/halcamera/telemetry/IncidentExporter.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/RunValidity.kt
  - app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt
  - app/src/main/java/dev/halcamera/benchmark/RegressionRules.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkStore.kt
decisions: []
verifications: []
---

**카메라 점유, 시계, 계산 로직의 경계를 유지하세요.** 이 규칙을 바꾸면 실행 순서나 측정값의 의미가 달라질 수 있습니다.

### 실행과 측정의 제약

1. 카메라를 점유하는 `CameraEngine`은 하나만 유지합니다. `close(done)`은 기기를 실제로 반환한 뒤 완료 콜백을 호출해야 합니다.
2. 앱의 시각은 `elapsedRealtimeNanos`를 사용합니다. 센서 시각은 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 앱 시각과 직접 비교합니다.
3. 카메라 열거에는 공개 Camera2 API만 사용합니다. 논리 카메라의 물리 endpoint를 독립적으로 열 수 있다고 가정하지 않습니다.
4. 벤치마크 JSON과 incident ZIP에는 관측 이벤트·메타데이터를 저장하며 이미지 픽셀을 저장하지 않습니다.

### 계산과 저장의 제약

1. `BenchmarkRunner`는 `Driver`, `Scheduler`, `clock`을 통해 카메라와 시계에 접근합니다. 지표·통계·회귀 계산은 JVM에서 테스트할 수 있으며 Activity와 파일 어댑터는 Android 의존성이 있습니다.
2. 벤치마크의 `org.json`은 파일 경계에서 사용합니다. `BenchmarkReportCodec`의 데이터 계약은 `Map<String, Any?>`로 전달합니다. schema 4를 쓰되 schema 3도 읽습니다. CLI protocol v1은 별도로 JSON 명령·상태를 정의하며 측정 보고서 schema를 바꾸지 않습니다.
3. `RunValidity`는 flag 규칙에서 measurement·comparison·scoring eligibility를 계산합니다. 알 수 없는 flag는 비교와 점수 산정을 막습니다. `ScoreComposer`는 release 빌드와 기록된 환경값도 확인하며, calibration과 모델·endpoint·계약이 다르거나 필수 지표가 누락되면 내부 점수를 계산하지 않습니다.
4. 회귀 임계값은 `RegressionRules`에서 관리합니다. baseline은 자동으로 지정하지 않으며 임의의 두 실행을 고르는 동작도 baseline을 바꾸지 않습니다.
5. 파일 삭제 실패 시 baseline 포인터를 먼저 없애지 않습니다. 포인터 정리 실패 후 남은 잘못된 참조는 `BaselineManager`가 이후 조회에서 정리합니다.

LIVE의 사진·동영상만 이미지 픽셀을 저장합니다. Android 8–9에서는 저장소 권한을, 녹화에는 마이크 권한을 요청합니다. 벤치마크의 StreamSpec과 메타데이터 전용 내보내기 계약은 유지합니다.

### CLI의 접근과 완료 경계

`CliProvider`는 각 진입점에서 shell UID 2000과 DUMP 권한을 검사합니다. 호출자 확인 전에는 Binder identity를 해제하지 않습니다. 파일 접근은 CLI 요청이 등록한 artifact ID에 한정하며 임의 경로나 쓰기 모드를 받지 않습니다.

명령은 동작 전에 저장하며 한 번에 하나의 변경 작업을 처리합니다. 사진 저장과 benchmark JSON 쓰기를 마친 뒤 결과 파일을 등록합니다. 프로세스가 종료된 미완료 요청은 재실행하지 않습니다. CLI 기록 정리와 원본 사진·benchmark 이력 삭제는 서로 다른 동작입니다.
