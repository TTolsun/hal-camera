# HAL CAM

Android 카메라의 **launch · preview · capture 성능을 반복 측정하고, 이전 실행과 비교해 회귀를 찾는 개발자 도구**입니다.

같은 기기에서 같은 profile로 run을 반복하고, 그중 하나를 baseline으로 지정하면, 이후 run은 baseline 대비 지표별 delta와 REGRESSED 판정을 표시합니다. 측정 대상은 앱이 관측할 수 있는 것, 곧 Camera2 콜백의 도착 시각과 metadata뿐입니다. HAL 내부 처리 시간이나 화면에 실제로 표시된 프레임은 측정하지 않습니다.

## 무엇을 측정하는가

| 범주 | 지표 |
|---|---|
| LAUNCH | 카메라 열기, 세션 구성, 첫 started 콜백, 첫 YUV, 첫 프레임, 닫기 |
| PREVIEW | 프레임 간격 p50 / p95, partial 지연, buffer 지연, jitter |
| CAPTURE | 촬영 지연, 결과 metadata 지연, 연속 촬영 간격 |
| STABILITY | stall 횟수, 콜백 실패 횟수, 촬영 중 stall |
| 3A | AE / AF / AWB 수렴 시간 (M5까지 informational) |

지표의 시작점과 종료점, 시계 종류, 통계 규칙은 [METRICS.md](docs/METRICS.md)에 정의되어 있습니다.

## 화면

| 화면 | 하는 일 |
|---|---|
| **LIVE** (런처) | CameraX / Camera2 전환, 카메라 선택, 실시간 프레임 간격과 콜백 지연 표시. `MARK` 버튼으로 직전 10초와 이후 5초를 incident ZIP으로 저장합니다 |
| **BENCHMARK** | profile 시작 카드 → 6단계 진행 → 결과 표. `SET AS BASELINE`, `COMPARE`, `EXPORT` |

LIVE는 관측한 숫자만 보여 주며 정상 / 이상을 판정하지 않습니다. 판정은 baseline과 비교할 때에만 성립하고, 그 일은 BENCHMARK가 합니다.

## profile

현재 profile은 `camera2-standard-v1` 하나입니다.

```
Camera2 · 1080p30 · warm reopen
open 10회 반복 → 3초 warm-up → 10초 관측 → 정지 영상 10장 → close
첫 반복은 통계에서 제외
```

실행 전에 preflight로 이 카메라가 profile을 지원하는지 확인합니다. API 35 이상에서는 `CameraDeviceSetup`으로 정확히 질의하고, 그 이하에서는 출력 크기 목록으로 판단합니다.

## 비교 규칙

- **baseline은 명시적으로만 지정됩니다.** 자동으로 만들어지지 않습니다.
- baseline이 없으면 직전 run과 비교하되, **delta만 표시하고 REGRESSED 판정은 붙이지 않습니다.** 아무도 기준으로 고르지 않은 run에 대한 회귀는 회귀가 아니기 때문입니다.
- 지표마다 임계 비율과 noise floor가 따로 있습니다. 5 ms가 11 ms로 늘어나면 +120 %이지만 절대 차이가 6 ms이므로 회귀로 보지 않습니다.
- run은 세 단계로 걸러집니다. 측정 유효 → 비교 가능 → 점수 가능. 충전 중인 run은 비교에는 쓰이지만 점수에서는 빠집니다.
- thermal, 절전 모드, 충전 상태, 노출 부하가 크게 다르면 비교 시점 조건 차이로 표시합니다.

## 빌드

JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13을 사용합니다.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties`는 PC마다 직접 만들며 저장소에 포함하지 않습니다. release 서명을 하려면 `settings.gradle.kts` 옆에 `keystore.properties`를 두고 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적습니다. 이 파일이 없으면 release는 서명 없이 빌드되므로 키가 없는 PC에서도 debug 빌드와 CI가 동작합니다.

Windows에서 프로젝트 경로에 한글이 있으면 Android Gradle Plugin이 빌드를 거부합니다. `subst`로 ASCII 드라이브 문자를 만든 뒤 그 경로에서 빌드하십시오.

## run JSON

run 하나는 `files/benchmarks/<run_id>.json`에 schema 3으로 저장됩니다. 결과 화면의 `EXPORT`로 공유할 수 있습니다. 파일에는 지표값, profile, 기기와 빌드 식별자, 환경값(thermal 시작 · 최고 · 종료, 절전 모드, 충전 상태), validity flag, 그리고 raw 표본이 들어 있습니다. **사진이나 프리뷰 픽셀은 저장하지 않습니다.**

## 문서

| 문서 | 내용 |
|---|---|
| [PLAN-BenchMarker-v0.3.md](docs/PLAN-BenchMarker-v0.3.md) | 현재 제품 계획. 지표, 화면, 비교 규칙, 마일스톤 |
| [METRICS.md](docs/METRICS.md) | 지표 정의. 시작 · 종료 지점, 시계, 통계 |
| [METRICS-REVIEW.md](docs/METRICS-REVIEW.md) | 지표 정의에 대한 검토 기록 |
| [design/DESIGN.md](docs/design/DESIGN.md) | 화면 색과 타이포그래피 토큰 |
| [archive/PRODUCT-v0.2.md](docs/archive/PRODUCT-v0.2.md) | 이전 제품 정의(Camera Doctor). 카메라 열거 절차와 디자인 토큰은 v0.3도 참조합니다 |

## 코드 구조

```
camera/     Camera2 / CameraX 엔진, 카메라 엔드포인트 열거
telemetry/  Telemetry, FlightRecorder(30초 순환 버퍼), incident ZIP
metrics/    이벤트 → 지표 계산. 화면도 판정도 모르는 leaf
benchmark/  profile, runner, 통계, 비교 규칙, 저장, 화면
ui/         Look 토큰, 그래프 뷰, LIVE 실시간 수치
```

화면은 XML 없이 Kotlin 코드로 만듭니다. 판정과 배치 규칙은 순수 Kotlin 객체에 두어 기기 없이 JVM 테스트로 검증합니다.
