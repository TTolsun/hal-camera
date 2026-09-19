---
based_on: ["overall-architecture"]
confidence: "code"
sources: ["app/src/main/java/dev/halcamera/benchmark/ProfileComparisonActivity.kt","app/src/main/java/dev/halcamera/benchmark/domain/AtomicFiles.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkIndex.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt","app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkRunner.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileArchive.kt","app/src/main/java/dev/halcamera/benchmark/domain/ProfileComparison.kt","app/src/main/java/dev/halcamera/benchmark/domain/RegressionRules.kt","app/src/main/java/dev/halcamera/benchmark/domain/RepeatStatistics.kt","app/src/main/java/dev/halcamera/benchmark/domain/RunValidity.kt","app/src/main/java/dev/halcamera/benchmark/domain/ScoreComposer.kt","app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkReport.kt","app/src/main/java/dev/halcamera/benchmark/platform/BenchmarkStore.kt","app/src/main/java/dev/halcamera/benchmark/platform/ProfileLibrary.kt","app/src/main/java/dev/halcamera/camera/CameraEndpointResolver.kt","app/src/main/java/dev/halcamera/camera/CameraEngine.kt","app/src/main/java/dev/halcamera/cli/CliProvider.kt","app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt","app/src/main/java/dev/halcamera/cli/CommandStore.kt","app/src/main/java/dev/halcamera/telemetry/IncidentExporter.kt"]
decisions: []
verifications: []
---
카메라 점유와 시계 비교에서 지켜야 하는 제약은 카메라를 점유하는 `CameraEngine`이 항상 하나여야 한다는 점과, `close(done)` 완료 콜백을 호출하기 전에 기기를 실제로 반환해야 한다는 것입니다. 두 엔진을 유지하더라도 이 제약은 그대로 적용됩니다.

앱의 시각은 `elapsedRealtimeNanos`를 사용합니다. 센서 시각은 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 앱 시각과 직접 비교합니다. 카메라 열거에는 공개 Camera2 API만 사용하며, 논리 카메라의 물리 endpoint를 독립적으로 열 수 있다고 가정하지 않습니다.

벤치마크 JSON과 incident ZIP에는 관측 이벤트와 메타데이터를 저장하며 이미지 픽셀은 저장하지 않습니다. `BenchmarkRunner`는 `Driver`, `Scheduler`, `clock`을 통해 카메라와 시계에 접근합니다. 지표·통계·회귀 계산은 JVM에서 수행되며, Activity와 파일 어댑터만 Android 의존성을 가집니다.

`RunValidity`는 flag 규칙에서 measurement·comparison·scoring eligibility를 계산합니다. 알 수 없는 flag는 비교와 점수 산정을 막습니다. `ScoreComposer`는 release 빌드와 기록된 환경값도 확인하며, calibration과 모델·endpoint·계약이 다르거나 필수 지표가 누락되면 내부 점수를 계산하지 않습니다.

회귀 임계값은 `RegressionRules`에서 관리합니다. baseline은 자동으로 지정하지 않으며 임의의 두 실행을 고르는 동작도 baseline을 바꾸지 않습니다. 파일 삭제 실패 시 baseline 포인터를 먼저 없애지 않습니다. 포인터 정리 실패 후 남은 잘못된 참조는 `BaselineManager`가 이후 조회에서 정리합니다.

계산 로직과 저장 경계에서 지켜야 하는 규칙은 `BenchmarkRunner`가 `Driver`, `Scheduler`, `clock`을 통해 카메라와 시계에 접근한다는 점입니다. 지표·통계·회귀 계산은 JVM 테스트로 검증하며, Activity와 파일 어댑터는 Android 의존성이 있습니다.

벤치마크의 `org.json`은 파일 경계에서 사용하고 측정 데이터 계약은 `Map`으로 전달합니다. CLI의 `org.json`은 별도의 명령 전송·상태 저장 경계에서 사용합니다. 앱 시각은 `elapsedRealtimeNanos`이며 센서 시각과의 차이는 REALTIME 소스가 확인될 때만 해석합니다.

`RunValidity`는 flag 규칙에서 measurement·comparison·scoring eligibility를 계산합니다. 알 수 없는 flag는 비교와 점수 산정을 막습니다. `ScoreComposer`는 release 빌드와 기록된 환경값도 확인하며, calibration과 모델·endpoint·계약이 다르거나 필수 지표가 누락되면 내부 점수를 계산하지 않습니다.

회귀 임계값은 `RegressionRules`에서 관리합니다. baseline은 명시적으로만 지정합니다. 파일 삭제 실패 시 baseline 포인터를 먼저 없애지 않습니다. 포인터 정리 실패 후 남은 잘못된 참조는 `BaselineManager`가 이후 조회에서 정리합니다.

CLI 실행 시 카메라 권한 및 저장소 권한을 필수로 검증하며, UI가 전방위 상태가 아니면 명령을 실행하지 않습니다. 요청 ID는 중복 사용이 금지되며, 상태 전이는 'accepted' → 'preparing' → 'saving' → 'succeeded/failed/cancelled' 순서로 제한됩니다.

프로브와 CTS 실행 시에는 화면 접근 없이 카메라 특성만 읽으며, 결과 파일은 요청 ID 기반 디렉토리에 저장 후 SHA-256 해시를 생성합니다. 명령 실행 중 취소는 코드를 통해 처리되며, 완료된 요청은 24 시간 유지됩니다.

Incident Export 시에는 앱 버전과 기기 정보를 포함하고, 센서 타임스탬프가 REALTIME 이 아니면 클록 도메인 차이를 고려해야 합니다. 모든 기록은 AtomicFile을 사용하여 디스크 실패 시에도 마지막 상태를 보존합니다.
