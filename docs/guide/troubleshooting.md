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

앱이 기록하는 이벤트와 러너 표시 중 프레임워크 경계 바깥의 사실은 `FlightRecorder` 가 기록한 원시 이벤트 (`Event.atNs`, `sensorNs`) 와 `BenchmarkEvaluator` 의 계산 결과입니다. `Event.atNs` 는 `Telemetry` 를 통해 주입된 시계로, 앱 시각과 센서 시각 (`sensorNs`) 을 구분하여 기록합니다. `intervalMs`, `resultFps`, `observedResultGap` 은 앱이 계산한 파생값으로, HAL 내부 상태의 직접 측정으로 해석하지 않습니다. 러너 표시 중 `unknownReason` 이 `INSUFFICIENT_SAMPLES` 로 설정된 경우, 이는 프레임워크가 충분한 표본을 수집하지 못했음을 의미하며, 이는 앱 측정이 수행되지 않은 결과입니다.

세션 ID 필터링은 `HistoryActivity.visible()` 에서 특정 실행만 표시하여, 해당 세션의 데이터가 누락되었거나 다른 세션과 혼동되었음을 시사합니다. 초기 프레임 제외는 `MetricExtractor.observe()` 의 `warmupFrames` 로 처리되며, 이는 H.1~H.5 통계에서 제외되지만 3A 수렴에는 포함됩니다. `INSUFFICIENT_SAMPLES` 는 `RunValidityEvaluator.flags` 에서 측정 유효성 (`measurementValid`) 을 false 로 설정하여 앱 UI 에 "실행이 유효하지 않음" 증상을 만듭니다. 이는 프레임워크가 충분한 데이터를 수집하지 못했거나, 앱 측정이 수행되지 않았음을 의미합니다.

계층을 좁히기 위해 먼저 `FlightRecorder` 를 통해 전체 이벤트 로그를 확인하여 세션 ID 필터링이 올바르게 적용되었는지 검증합니다. 다음으로 `capture_result`, `image_available`, `capture_started` 등의 이벤트 타입과 시간戳 (`sensorNs`, `atNs`) 을 비교하여 프레임 간격과 처리 지연을 분석합니다. `INSUFFICIENT_SAMPLES` 와 같은 상태가 발생하면 데이터 부족으로 인해 정확한 계산이 불가능함을 알립니다. 이는 프레임 수집이 충분하지 않거나 세션 초기화 중일 수 있음을 시사합니다. 앱 기록만으로 프레임워크와 HAL 중 원인을 확정하지 않으며, 시스템 트레이스와 카메라 서비스 로그로 추가 확인해야 합니다.

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거 파일: `app/src/main/java/dev/halcamera/MainActivity.kt`, `app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt`, `app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt`, `app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt`, `app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt`, `app/src/main/java/dev/halcamera/benchmark/RunValidity.kt`, `app/src/main/java/dev/halcamera/cli/CommandStore.kt`, `app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt`, `app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt`, `app/src/main/java/dev/halcamera/telemetry/Telemetry.kt`
- 근거 수준: 코드 확인
- 검토 상태: 관련 소스 변경됨: 재검토 필요

</details>

<!-- omm:end id=layer-isolation -->

## 증상별 절차와 알려진 제약

개별 증상에 대한 재현·분석 절차는 아직 정리하지 않았습니다. 먼저 위의 계층 구분 순서를 적용하고, 확인한 조건과 결과를 기록하세요.

<!-- omm:begin id=open-questions -->

다음 항목은 구조 스캔에서 확인한 제약이나 추가 검증이 필요한 사항입니다.

- 콜백과 파일 작업의 실행 스레드, close 완료와 늦은 신호 처리는 변경 시 함께 검증해야 합니다.
- RESULTS의 화면 배치·공유·삭제 동작은 기기 검증이 추가로 필요합니다.
- 촬영 중 프리뷰 stall 지표 2.7은 여전히 NOT_RUN입니다.

**후속 작업**

1. RESULTS의 필터·임의 비교·baseline·삭제·공유 동작을 기기에서 검증하고 근거를 남깁니다.
2. 촬영 중 프리뷰 stall 지표 2.7의 관측·계산 규칙을 구현합니다.

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
| `versionName` | `0.6.0` |
| `versionCode` | `9` |
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

- 검증 기준 앱 버전: 0.6.0 (versionCode 9)

| 항목 | 최신성 | 검토 |
| --- | --- | --- |
| 구조 원본 `data-flow` | 관련 소스 변경됨: 재검토 필요 | 검토 2026-09-14 @ `d2249fa` · Codex |
| 구조 원본 `state-transitions` | 관련 소스 변경됨: 재검토 필요 | 검토 2026-09-14 @ `d2249fa` · Codex |
| 원고 `layer-isolation` | 관련 소스 변경됨: 재검토 필요 | 검토 2026-09-14 @ `d2249fa` · Codex |

<!-- omm:end id=status -->

`코드 확인`과 실제 기기 검증은 서로 다른 근거입니다. 표의 검토 기록만으로 특정 기기에서 재현됐다고 판단하지 않습니다.

</details>

## 관련 코드

- `app/src/main/java/dev/halcamera/telemetry/`에서 이벤트 기록과 incident 내보내기를 확인합니다.
- `app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt`에서 지표 계산 규칙을 확인합니다.

**다음 단계:** [원인을 좁히는 순서](#원인을-좁히는-순서)의 첫 단계에 따라 문제 실행의 `unknownReason`과 입력 조건을 확인하세요.
