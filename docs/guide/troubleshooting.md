---
title: 디버깅 및 문제 해결
nav_order: 7
---
# 디버깅 및 문제 해결

**측정 결과가 예상과 다르면 세션 ID, 관측 창, `unknownReason`부터 확인하세요.** 그다음 원시 이벤트와 계산된 지표를 대조합니다. 지표에는 앱의 필터와 표본 수 규칙이 적용되어 있습니다.

| 지금 하려는 작업 | 이동할 절 |
| --- | --- |
| 재현에 필요한 정보를 모읍니다. | [문제 발생 시 수집할 정보](#문제-발생-시-수집할-정보) |
| 로그와 incident 번들을 확보합니다. | [로그와 진단 자료](#로그와-진단-자료) |
| 증상이 시작된 계층을 좁힙니다. | [앱과 프레임워크·HAL 구분](#앱과-프레임워크hal을-구분하세요) |
| 앱 버전과 SDK 설정을 확인합니다. | [앱 버전과 빌드 설정](#앱-버전과-빌드-설정) |

실행 순서가 익숙하지 않다면 [주요 실행 흐름](architecture.md#주요-실행-흐름)을 먼저 읽으세요.

## 문제 발생 시 수집할 정보

재현 조건과 기록을 함께 남기세요. 앱 버전은 이 페이지 아래의 빌드 정보와 대조합니다.

1. 기기 모델과 Android 빌드 번호를 기록합니다.
2. 사용한 엔진인 Camera2 또는 CameraX와 카메라 엔드포인트를 기록합니다.
3. 재현 절차와 반복 횟수 중 문제가 발생한 횟수를 기록합니다.
4. `MARK INCIDENT`에서 생성한 ZIP 번들과 관련 로그를 확보합니다.

## 로그와 진단 자료

아래는 현재 추출기가 `TAG` 상수와 `Log.*` 호출에서 찾은 로그 태그입니다. 목록에 없다는 이유만으로 코드 전체에서 해당 태그를 사용하지 않는다고 단정할 수는 없습니다.

<!-- omm:begin id=log-tags -->

| 로그 태그 | 선언 위치 |
| --- | --- |
| `BenchmarkReport` | `app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt` |
| `BenchmarkStore` | `app/src/main/java/dev/halcamera/benchmark/BenchmarkStore.kt` |
| `ProfileCompatibility` | `app/src/main/java/dev/halcamera/benchmark/ProfileCompatibility.kt` |

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거: `app/src/main/java/**/*.kt` 의 `TAG` 상수와 `Log.*` 호출
- 근거 수준: 코드 확인

</details>

<!-- omm:end id=log-tags -->

다음 명령으로 이 태그의 로그를 확인합니다.

~~~powershell
adb logcat -s BenchmarkReport BenchmarkStore ProfileCompatibility
~~~

incident 번들은 이벤트와 메타데이터를 담습니다. 이미지 픽셀은 저장하지 않습니다.

## 앱과 프레임워크·HAL을 구분하세요

<!-- omm:begin id=layer-isolation -->

**먼저 앱의 필터와 계산 규칙을 확인한 뒤 원시 이벤트를 대조하세요.** 앱은 프레임워크에서 받은 값과, 그 값으로 계산한 지표를 함께 기록합니다. 둘을 구분해야 문제가 생긴 계층을 좁힐 수 있습니다.

### 원인을 좁히는 순서

1. **앱의 입력 조건을 확인합니다.** 값이 `null`이면 `unknownReason`을 봅니다. 세션 ID와 관측 창이 기대한 값인지 확인하고, `capacityEvictions`가 0보다 큰지 확인합니다. 이벤트가 용량 한도로 제거됐다면 계산에 필요한 기록이 부족할 수 있습니다.
2. **원시 이벤트를 대조합니다.** `capture_failed.reason`, `buffer_lost`의 발생 시점, `capture_result.frameDurationNs`와 센서 시각의 간격을 확인합니다. 원시값이 이상하더라도 요청 설정·콜백 처리·시스템 로그를 함께 확인해야 합니다.
3. **시계 도메인을 확인합니다.** 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 센서 타임스탬프와 앱의 `atNs`를 직접 비교합니다. 다른 시계의 값을 빼면 의미 없는 차이가 나옵니다.
4. **프레임워크와 HAL을 추가 자료로 구분합니다.** 앱 기록만으로 두 계층 중 어디가 원인인지 확정할 수 없습니다. 시스템 트레이스와 카메라 서비스 로그가 필요합니다. 팀에서 사용할 구체적인 수집 절차는 아직 정리하지 않았습니다.

### 프레임워크에서 받은 값

아래 항목은 앱이 계산한 지표가 아니라 콜백을 통해 관측한 정보입니다.

| 이벤트 | 기록하는 정보 |
| --- | --- |
| `capture_started` | `frameNumber`와 `timestamp`를 기록합니다. |
| `capture_result` | `sensorNs`, AE/AF/AWB 상태, `exposureNs`, `iso`, `frameDurationNs`, `focusDiopters`, `zoomRatio`, `cropRegion`을 기록합니다. |
| `capture_failed` | `reason`과 `imageCaptured`를 기록합니다. |
| `buffer_lost` | `frameNumber`를 기록합니다. |
| `image_available` | 해상도, 포맷, 스트림 이름을 기록합니다. |

`request_observed`는 **요청을 제출한 시점의 기록이 아닙니다.** `onCaptureStarted`에서 관측한 요청 내용을 담으며, `Telemetry`는 이 구분을 `observation` 필드에 기록합니다.

### 앱에서 계산한 값

`intervalMs`와 `resultFps`는 센서 타임스탬프 차이로 계산합니다. `observedResultGap`은 관측된 결과 프레임 번호의 차이로 계산합니다. 이 값들은 HAL의 프레임 드롭 횟수나 프리뷰 렌더링 드롭 횟수를 직접 나타내지 않습니다.

H.x 지표와 상태 판정도 앱의 계산 결과입니다. 이 값이 예상과 다르면 아래의 필터와 표본 수 기준부터 확인하세요.

### 세션과 표본 규칙

| 규칙 | 코드 위치 | 결과에 나타나는 영향 |
| --- | --- | --- |
| 닫힌 세션의 늦은 콜백은 기록하지 않습니다. | `Telemetry.callback`의 `alive()` | 닫는 도중 도착한 마지막 프레임이 이벤트 목록에 없을 수 있습니다. |
| 같은 세션 ID의 이벤트만 지표에 사용합니다. | `CheckEvaluator.evaluate`의 `result.session` | 다른 세션은 계산에서 제외합니다. 세션 ID가 어긋나면 지표의 입력이 비게 됩니다. |
| Auto Check는 첫 결과 프레임 5개를 H.1~H.5 통계에서 제외합니다. | `CheckEvaluator.WARMUP_FRAMES` | 첫 프레임의 긴 간격이 해당 통계에 나타나지 않습니다. 3A 수렴은 제외 전 프레임을 사용합니다. |
| 워밍업을 제외한 steady 프레임이 15개 미만이면 값을 `null`로 표시합니다. | `MetricExtractor(minSamples = 15)` | `INSUFFICIENT_SAMPLES`로 표시합니다. 수집한 통계 자체는 남아 있습니다. |

### 기록 보존과 실행 스레드

| 규칙 | 코드 위치 | 결과에 나타나는 영향 |
| --- | --- | --- |
| 링 버퍼의 이벤트 상한은 18,000개입니다. | `FlightRecorder.store` | 상한을 넘으면 오래된 이벤트부터 제거합니다. 제거 횟수는 `capacityEvictions`에서 확인합니다. 이 때문에 일부 기록이 빠진 지표에 별도 표시는 하지 않습니다. |
| 이벤트 보존 기간은 30초입니다. | `FlightRecorder(retentionNs)` | 30초보다 오래된 이벤트는 `snapshot()`에 남지 않습니다. |
| 라이브 listener는 기록 스레드에서 동기 실행됩니다. | `FlightRecorder.listener` | 무거운 처리를 추가하면 기록 경로가 느려지고 관측 간격에도 영향을 줄 수 있습니다. |

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거 파일: `app/src/main/java/dev/halcamera/telemetry/Telemetry.kt`, `app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt`, `app/src/main/java/dev/halcamera/check/CheckEvaluator.kt`, `app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt`
- 근거 수준: 코드 확인
- 검토 2026-09-10 @ `5474f70` · Codex

</details>

<!-- omm:end id=layer-isolation -->

## 증상별 절차와 알려진 제약

개별 증상에 대한 재현·분석 절차는 아직 정리하지 않았습니다. 먼저 위의 계층 구분 순서를 적용하고, 확인한 조건과 결과를 기록하세요.

<!-- omm:begin id=open-questions -->

다음 항목은 구조 스캔에서 확인한 제약이나 추가 검증이 필요한 사항입니다.

- 촬영 중 프리뷰 stall 2.7은 아직 NOT_RUN입니다.
- MetricExtractor와 BenchmarkEvaluator가 일부 통계 계산을 나눠 수행하므로 워밍업과 표본 수 기준을 함께 검토해야 합니다.
- FlightRecorder의 이벤트 상한을 넘으면 오래된 이벤트가 제거됩니다. incident의 capacityEvictions와 incidentTruncated를 확인해야 합니다.
- FlightRecorder.listener는 기록 스레드에서 동기 실행되므로 무거운 처리는 측정 경로에 영향을 줍니다.

**후속 작업**

1. baseline 설정·해제와 이전 실행 선택 시 저장된 측정값과 다시 계산하는 비교 결과를 구분해 검증합니다.
2. 2.7을 위한 촬영 구간 프레임 관측을 구현합니다.
3. 원시 이벤트와 표본 부족·조건 불일치 상태를 함께 점검하는 기기 검증 절차를 기록합니다.

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거: `.omm/data-flow/concern.md`, `.omm/data-flow/todo.md`
- 근거 수준: 설계 의도 / 추정

</details>

<!-- omm:end id=open-questions -->

## 앱 규칙을 테스트로 확인하세요

동일한 이벤트 목록을 `MetricExtractor`에 입력하는 JVM 테스트로 계산 규칙을 확인할 수 있습니다. `MetricExtractorTest`는 표본 수가 14개일 때 부족으로 판정하고, 15개일 때 정상으로 판정하는 경계를 검사합니다.

앱의 결과 콜백 간격만으로 HAL 내부 프레임 드롭을 확정할 수 없습니다. 시스템 자료로 확인한 사실과 앱이 계산한 값을 구분해 기록하세요. 현재 기기 관찰 기록은 [기기 검증 기록](_inputs/device-verification.yaml)에서 확인할 수 있습니다.

## 앱 버전과 빌드 설정

<!-- omm:begin id=build-identity -->

| 항목 | 값 |
| --- | --- |
| `applicationId` | `dev.halcamera` |
| `namespace` | `dev.halcamera` |
| `versionName` | `0.3.1` |
| `versionCode` | `4` |
| `minSdk` | `26` |
| `targetSdk` | `36` |
| `compileSdk` | `36` |
| `gradleVersion` | `8.13` |
| `jvmTarget` | `17` |

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거: `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`
- 근거 수준: 코드 확인

</details>

<!-- omm:end id=build-identity -->

## 문서 검토 상태

<details markdown="1">
<summary>기준 앱 버전과 검토 커밋 확인</summary>

<!-- omm:begin id=status -->

- 검증 기준 앱 버전: 0.3.1 (versionCode 4)

| 항목 | 최신성 | 검토 |
| --- | --- | --- |
| 구조 원본 `data-flow` | 최신 | 검토 2026-09-10 @ `5474f70` · Codex |
| 구조 원본 `state-transitions` | 최신 | 검토 2026-09-10 @ `5474f70` · Codex |
| 원고 `layer-isolation` | 최신 | 검토 2026-09-10 @ `5474f70` · Codex |

<!-- omm:end id=status -->

`코드 확인`과 실제 기기 검증은 서로 다른 근거입니다. 표의 검토 기록만으로 특정 기기에서 재현됐다고 판단하지 않습니다.

</details>

## 관련 코드

- `app/src/main/java/dev/halcamera/telemetry/`에서 이벤트 기록과 incident 내보내기를 확인합니다.
- `app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt`에서 지표 계산 규칙을 확인합니다.

**다음 단계:** [원인을 좁히는 순서](#원인을-좁히는-순서)의 첫 단계에 따라 문제 실행의 `unknownReason`과 입력 조건을 확인하세요.
