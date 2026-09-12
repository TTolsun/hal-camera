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

**먼저 앱의 필터와 계산 규칙을 확인한 뒤 원시 이벤트를 대조하세요.** 프레임워크에서 받은 값과 앱이 계산한 지표를 구분해야 원인 계층을 좁힐 수 있습니다.

1. `unknownReason`, 세션 ID, 관측 창과 표본 수를 확인합니다. `INSUFFICIENT_SAMPLES`는 측정 대상의 고장을 뜻하지 않습니다.
2. `capture_failed.reason`, `buffer_lost`, `capture_result`의 센서 시각과 `frameDurationNs`를 대조합니다. `request_observed`는 요청 제출 시각이 아닙니다.
3. 센서 타임스탬프가 REALTIME 소스일 때만 앱 시각과 직접 비교합니다. 다른 시계의 값을 빼면 지연을 해석할 수 없습니다.
4. 앱 기록만으로 프레임워크와 HAL 중 원인을 확정하지 않습니다. 시스템 트레이스와 카메라 서비스 로그로 추가 확인해야 합니다.

### 표본과 보존 규칙

| 규칙 | 확인할 코드와 영향 |
| --- | --- |
| 닫힌 세션의 콜백을 버립니다. | `Telemetry.callback`의 `alive()`를 확인합니다. |
| 관측 세션과 시간 창을 선택합니다. | `RunAssembler.observe()`가 관측 시작 이전 결과를 워밍업으로 제외합니다. 고정 5프레임 규칙은 사용하지 않습니다. |
| 표본이 부족하면 값이 비어 있습니다. | `BenchmarkEvaluator`의 관측 통계는 해당 지표 표본 수가 15개 미만이면 `null`을 반환합니다. 3A 수렴은 워밍업 전 프레임도 사용합니다. |
| 오래된 이벤트를 제거합니다. | `FlightRecorder` 기본 설정은 30초·18,000개이며 `BenchmarkActivity`는 180초·60,000개로 구성합니다. 제거 횟수는 `Incident.capacityEvictions`이며 incident ZIP의 `incident.json`에는 `ringCapacityEvictionsSinceAppStart`(앱 시작 이후 누적)로 기록됩니다. |
| listener는 동기로 실행합니다. | `FlightRecorder.listener`에 무거운 처리를 추가하면 관측 경로에 영향을 줄 수 있습니다. |

`intervalMs`, `resultFps`, `observedResultGap`은 관측값으로 계산한 수치입니다. HAL 내부의 처리 시간이나 화면에 표시된 프레임 수를 직접 측정한 값이 아닙니다.

### 이력과 CSV가 예상과 다를 때

RESULTS는 기본적으로 비교 가능한 실행을 표시합니다. 중단된 실행을 찾으려면 상태 필터를 `전체`로 바꾸고 profile·camera 필터도 확인합니다. PC의 `tools/aggregate.py`는 기본적으로 점수 산정 가능한 실행만 내보내므로, 앱과 같은 범위를 보려면 `--eligibility comparison_eligible`을 사용합니다.

손상된 JSON은 목록과 PC 집계에서 별도로 알립니다. CSV 출력 장치의 오류는 입력 파일 오류와 구분하며 작업을 실패로 종료합니다. baseline 파일을 읽을 수 없으면 baseline 변경과 삭제를 중단합니다. 기기 화면·공유·삭제 동작의 실제 검증 기록은 이 문서에서 주장하지 않습니다.

사진·동영상 저장 실패는 벤치마크 비교와 구분해 확인합니다. 사진은 같은 센서 타임스탬프의 YUV·JPEG 버퍼가 모두 있어야 저장됩니다. 녹화 중에는 엔진·카메라·줌·촬영 모드 변경과 일시정지·갤러리·벤치마크를 비활성화합니다. 셔터는 정지 동작을 제공하고 경과 시간은 셔터 아래의 모드 위치에 표시합니다. 정지를 누르면 녹화 종료 처리 동안 셔터를 비활성화하며, 앨범 저장 완료는 별도 알림으로 표시합니다. 녹화 종료 후 사진용 프리뷰로 복귀하며 동영상 모드 선택은 유지합니다. 벤치마크 비교 화면에서 뒤로 가기를 누르면 결과 화면으로 돌아갑니다.

### CLI 작업이 끝나지 않거나 파일이 없을 때

`status --request REQUEST_UUID`로 앱의 상태를 먼저 확인합니다. PC의 대기 시간 종료는 앱 실행 실패를 뜻하지 않습니다. `fetch`는 이미 생성된 파일을 회수하며 촬영을 반복하지 않습니다. `interrupted`는 앱 프로세스가 종료된 미완료 기록이며 자동으로 재실행되지 않습니다.

`BUSY`가 반환되면 현재 UI 또는 CLI 작업이 끝날 때까지 기다립니다. `CLI_DISABLED`는 측정 상세의 ADB CLI 허용 설정을 확인합니다. 카메라·저장소 권한은 앱에서 허용해야 하며 CLI가 자동 부여하지 않습니다. benchmark의 취소·실패 시 partial report와 실행 상태를 함께 확인합니다.

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거 파일: `app/src/main/java/dev/halcamera/cli/CommandStore.kt`, `tools/halcam/halcam/cli.py`, `app/src/main/java/dev/halcamera/MainActivity.kt`, `app/src/main/java/dev/halcamera/telemetry/Telemetry.kt`, `app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt`, `app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt`, `app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt`, `app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt`, `app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt`, `app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt`
- 근거 수준: 코드 확인
- 검토 2026-09-12 @ `7e3a7c9` · Codex

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
| 구조 원본 `data-flow` | 최신 | 검토 2026-09-12 @ `7e3a7c9` · Codex |
| 구조 원본 `state-transitions` | 최신 | 검토 2026-09-12 @ `7e3a7c9` · Codex |
| 원고 `layer-isolation` | 최신 | 검토 2026-09-12 @ `7e3a7c9` · Codex |

<!-- omm:end id=status -->

`코드 확인`과 실제 기기 검증은 서로 다른 근거입니다. 표의 검토 기록만으로 특정 기기에서 재현됐다고 판단하지 않습니다.

</details>

## 관련 코드

- `app/src/main/java/dev/halcamera/telemetry/`에서 이벤트 기록과 incident 내보내기를 확인합니다.
- `app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt`에서 지표 계산 규칙을 확인합니다.

**다음 단계:** [원인을 좁히는 순서](#원인을-좁히는-순서)의 첫 단계에 따라 문제 실행의 `unknownReason`과 입력 조건을 확인하세요.
