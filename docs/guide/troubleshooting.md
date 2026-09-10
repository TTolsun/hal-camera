---
title: 디버깅 및 문제 해결
nav_order: 7
---

# 디버깅 및 문제 해결

> 이 페이지는 측정 결과가 예상과 다를 때 무엇을 수집하고, 어느 계층에서 문제가 생겼는지 좁혀 가는 방법을 설명합니다. 읽고 나면 앱의 해석 규칙 때문에 생긴 증상과 프레임워크 바깥에서 온 증상을 구분할 수 있습니다.

- 대상: 증상이 앱, 프레임워크, HAL 중 어디에서 왔는지 판단해야 하는 개발자
- 선행 조건: [아키텍처 및 코드 구조](architecture.md)의 4.3 주요 실행 흐름
- 검증 기준: 아래 상태 표를 참고합니다.

<!-- omm:begin id=status -->

- 검증 기준 앱 버전: 0.3.1 (versionCode 4)

| 항목 | 최신성 | 검토 |
| --- | --- | --- |
| 구조 원본 `data-flow` | 최신 | 검토 2026-09-10 @ `037db0d` · Codex |
| 구조 원본 `state-transitions` | 최신 | 검토 2026-09-10 @ `037db0d` · Codex |
| 원고 `layer-isolation` | 최신 | 검토 2026-09-10 @ `037db0d` · Codex |

<!-- omm:end id=status -->

## 개요

앱은 프레임워크 콜백을 그대로 기록한 이벤트와, 그 이벤트를 해석해 만든 지표를 분리해서 저장합니다. 문제를 좁힐 때는 항상 이벤트에서 시작하고 지표는 나중에 봅니다. 지표에는 앱의 규칙(세션 필터, 초기 프레임 제외, 표본 수 하한)이 이미 적용되어 있기 때문입니다.

## 상세 내용

### 7.1 문제 발생 시 수집 정보

다음 값을 함께 기록합니다. 앱 버전과 빌드 식별자는 아래 표의 값을 기준으로 합니다.

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

<sub>근거: `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` · 근거 수준: 코드 확인</sub>

<!-- omm:end id=build-identity -->

- 기기 모델과 Android 빌드 번호
- 사용한 엔진(Camera2 / CameraX)과 카메라 엔드포인트
- 재현 절차와 재현 빈도
- incident 번들(`MARK INCIDENT` 버튼으로 생성한 zip)

### 7.2 로그 및 진단 자료 수집

로그 태그는 코드에서 자동으로 추출한 목록입니다. 목록에 없는 태그는 현재 코드가 사용하지 않는 것입니다.

<!-- omm:begin id=log-tags -->

| 로그 태그 | 선언 위치 |
| --- | --- |
| `BenchmarkReport` | `app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt` |
| `BenchmarkStore` | `app/src/main/java/dev/halcamera/benchmark/BenchmarkStore.kt` |
| `ProfileCompatibility` | `app/src/main/java/dev/halcamera/benchmark/ProfileCompatibility.kt` |

<sub>근거: `app/src/main/java/**/*.kt` 의 `TAG` 상수와 `Log.*` 호출 · 근거 수준: 코드 확인</sub>

<!-- omm:end id=log-tags -->

```bash
adb logcat -s BenchmarkReport BenchmarkStore ProfileCompatibility
```

incident 번들에는 이벤트와 메타데이터만 담기고 이미지 픽셀은 담기지 않습니다.

### 7.3 증상별 확인 절차

확인 필요. 증상별 절차는 아직 정리하지 않았습니다. 7.4의 계층 구분 절차를 먼저 적용합니다.

### 7.4 앱·프레임워크·HAL 문제 구분

<!-- omm:begin id=layer-isolation -->

앱이 남기는 기록은 두 종류입니다. 프레임워크가 준 값을 그대로 옮긴 것과, 앱이 계산한 것입니다. 계층을 좁히려면 이 둘을 먼저 구분해야 합니다.

### 프레임워크 경계 바깥의 사실

다음은 프레임워크 콜백의 인자를 그대로 기록한 값이므로 앱이 만들어 낸 것이 아닙니다.

- `capture_started`의 `frameNumber`와 `timestamp`
- `capture_result`의 센서 타임스탬프(`sensorNs`), AE/AF/AWB 상태, `exposureNs`, `iso`, `frameDurationNs`, `focusDiopters`, `zoomRatio`, `cropRegion`
- `capture_failed`의 `reason`과 `imageCaptured`
- `buffer_lost`의 `frameNumber`
- `image_available`의 해상도, 포맷, 스트림 이름

단, `request_observed`에 담긴 요청 내용은 `onCaptureStarted` 시점에 본 것이지 요청을 제출한 시각의 것이 아닙니다. `Telemetry`가 이 사실을 `observation` 필드에 명시해 둡니다.

### 앱의 해석

다음은 앱이 위 사실에서 계산한 값입니다. 여기서 이상이 보이면 먼저 앱의 규칙을 의심합니다.

- `intervalMs`, `resultFps`, `observedResultGap` — 센서 타임스탬프 차이로 계산한 간격·FPS와 결과 프레임 번호 차이로 계산한 gap입니다. `FrameStats`의 주석대로 이것은 관측된 결과 간격이지 HAL의 드롭 횟수도 프리뷰 렌더링 드롭 횟수도 아닙니다.
- H.x 지표와 상태 판정 전부.

### 앱 규칙이 만들 수 있는 증상

| 규칙 | 코드 위치 | 만들 수 있는 증상 |
| --- | --- | --- |
| 이미 닫힌 세션의 콜백은 기록하지 않음 | `Telemetry.callback`의 `alive()` 검사 | 닫는 도중의 마지막 프레임들이 이벤트에 없음 |
| 지표는 같은 세션 ID의 이벤트만 사용 | `CheckEvaluator.evaluate`가 `result.session`을 넘김 | 다른 세션의 콜백이 섞이지 않는 대신, 세션 ID가 어긋나면 지표가 비어 있음 |
| Auto Check 관측 창의 첫 5프레임을 H.1~H.5 통계에서 제외 | `CheckEvaluator.WARMUP_FRAMES` | 첫 프레임들의 긴 간격이 H.1~H.5에 나타나지 않음. 3A 수렴은 제외 전 프레임 사용 |
| 워밍업 제외 후 steady 프레임 15개 미만이면 값 null | `MetricExtractor(minSamples = 15)` | 값 대신 `INSUFFICIENT_SAMPLES`가 표시됨. 통계 자체는 남아 있음 |
| 링 버퍼 18,000개 상한을 넘으면 가장 오래된 이벤트 폐기 | `FlightRecorder.store` | 창의 앞부분이 잘림. 잘렸다는 사실은 `capacityEvictions`로만 알 수 있고, 잘린 창에서 계산된 지표에 별도 표시는 없음 |
| 보존 기간 30초 | `FlightRecorder(retentionNs)` | 30초보다 오래된 이벤트는 `snapshot()`에 없음 |
| 라이브 tap은 기록 스레드에서 동기 실행 | `FlightRecorder.listener` | tap에 무거운 작업을 넣으면 기록 경로가 느려지고 측정 대상인 간격 자체가 왜곡됨 |

### 계층을 좁히는 순서

1. **앱 규칙인지 확인합니다.** 값이 null이면 `unknownReason`을 봅니다. `capacityEvictions`가 0보다 크면 창이 잘린 것입니다. 관측 창 범위와 세션 ID가 기대와 맞는지 확인합니다. 의도된 규칙에 따른 결과인지 앱 구현의 오류인지 구분합니다.
2. **이벤트의 원시값을 봅니다.** `capture_failed`의 `reason`, `buffer_lost`의 발생 시점, `capture_result`의 `frameDurationNs`와 센서 타임스탬프 간격을 직접 봅니다. 이 값들은 프레임워크가 준 것이므로, 원시값 이상만으로 HAL 문제를 확정하지 말고 요청 설정·콜백 처리와 시스템 로그를 함께 확인합니다.
3. **센서 시계 도메인을 확인합니다.** 센서 타임스탬프는 기기가 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`을 보고할 때만 `atNs`와 비교할 수 있습니다. 그렇지 않은 기기에서 두 값을 빼면 의미 없는 차이가 나옵니다.
4. **프레임워크와 HAL을 나눕니다.** 앱의 관측만으로는 이 둘을 확정할 수 없습니다. 이 단계부터는 시스템 트레이스와 카메라 서비스 로그가 필요하며, 팀에서 쓰는 수집 절차는 확인 필요입니다.

<sub>근거 파일: `app/src/main/java/dev/halcamera/telemetry/Telemetry.kt`, `app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt`, `app/src/main/java/dev/halcamera/check/CheckEvaluator.kt`, `app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt` · 근거 수준: 코드 확인 · 검토 2026-09-10 @ `037db0d` · Codex</sub>

<!-- omm:end id=layer-isolation -->

### 7.5 알려진 문제와 우회 방법

<!-- omm:begin id=open-questions -->

**확인 필요** — 스캔 시점에 확신할 수 없었거나 현재 알려진 미완 사항입니다.

- 촬영 중 프리뷰 stall 2.7은 아직 NOT_RUN입니다.
- MetricExtractor와 BenchmarkEvaluator가 일부 통계 계산을 나눠 수행하므로 워밍업과 표본 수 기준을 함께 검토해야 합니다.
- FlightRecorder의 이벤트 상한을 넘으면 오래된 이벤트가 제거됩니다. incident의 capacityEvictions와 incidentTruncated를 확인해야 합니다.
- FlightRecorder.listener는 기록 스레드에서 동기 실행되므로 무거운 처리는 측정 경로에 영향을 줍니다.

**후속 작업**

1. 비교 UI를 연결할 때 저장된 측정값과 다시 계산하는 비교 결과를 분리해 표시합니다.
2. 2.7을 위한 촬영 구간 프레임 관측을 구현합니다.
3. 원시 이벤트와 표본 부족·조건 불일치 상태를 함께 점검하는 기기 검증 절차를 기록합니다.

<sub>근거: `.omm/data-flow/concern.md`, `.omm/data-flow/todo.md` · 근거 수준: 설계 의도 / 추정</sub>

<!-- omm:end id=open-questions -->

## 확인 방법

앱 규칙이 만든 증상인지 확인하려면, 같은 이벤트 목록을 `MetricExtractor`에 직접 넣는 JVM 테스트를 작성합니다. `MetricExtractorTest`가 표본 수 하한(14개는 표본 부족, 15개는 정상)을 검증하는 방식을 따르면 됩니다.

## 제약 및 알려진 문제

- 앱의 관측만으로는 HAL 내부의 프레임 드롭을 확정할 수 없습니다. 앱이 세는 것은 결과 콜백 사이의 간격이지 HAL의 드롭 횟수가 아닙니다.
- 실제 기기 검증 기록은 [기기 검증 기록](_inputs/device-verification.yaml)에 있는 것이 전부입니다.

## 관련 코드 및 문서

- `app/src/main/java/dev/halcamera/telemetry/` — 이벤트 기록과 incident 내보내기
- `app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt` — 지표 계산 규칙
- [아키텍처 및 코드 구조](architecture.md)
