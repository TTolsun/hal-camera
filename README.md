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
| 3A | AE / AF / AWB 수렴 시간 (정보용, 점수 가중치 0) |

지표의 시작점과 종료점, 시계 종류, 통계 규칙은 [METRICS.md](docs/METRICS.md)에 정의되어 있습니다.

## 화면

| 화면 | 하는 일 |
|---|---|
| **LIVE** (런처) | CameraX / Camera2 전환, 카메라 선택, 실시간 프레임 간격과 콜백 지연 표시. `MARK` 버튼으로 직전 10초와 이후 5초를 incident ZIP으로 저장합니다 |
| **BENCHMARK** | profile 시작 카드 → 6단계 진행 → 결과 표. `SET AS BASELINE`, `COMPARE`, `EXPORT` |
| **RESULTS** | 실행 이력을 eligibility·profile·camera로 필터링하고, 임의의 두 실행을 비교하거나 JSON·CSV로 내보냅니다. BENCHMARK의 `RESULTS · 실행 이력`에서 엽니다 |

LIVE는 관측한 숫자만 보여 주며 정상 / 이상을 판정하지 않습니다. 판정은 baseline과 비교할 때에만 성립하고, 그 일은 BENCHMARK가 합니다.

### 사진과 동영상

- LIVE의 **사진 촬영**을 누르면 **하나의 Camera2 still 요청에 YUV와 JPEG 출력을 함께 지정**합니다. 센서 타임스탬프가 일치하는 두 버퍼를 받아 YUV는 JPEG으로 변환하고, 카메라 JPEG은 원본 바이트로 저장합니다. 파일명은 같은 촬영 식별자에 `_YUV.jpg`, `_JPEG.jpg`를 붙입니다.
- 현재 LIVE 스트림 선택 기준은 YUV 최대 640×480 픽셀 예산, JPEG 최대 1920×1080 픽셀 예산입니다. 실제 크기는 카메라가 제공하는 목록에서 정하며 두 이미지의 해상도는 다를 수 있습니다. 출력 조합을 거부한 카메라는 상태 메시지를 표시합니다.
- **동영상 녹화**는 마이크 권한을 요청한 뒤 H.264/AAC MP4 녹화를 시작합니다. **중지**를 누르거나 앱을 나가거나 카메라를 바꾸면 녹화를 종료하고 저장합니다. 너무 짧거나 실패한 녹화는 오류 메시지와 함께 폐기합니다. 녹화 중에는 사진 촬영과 줌 변경을 받지 않습니다.
- 녹화는 프리뷰와 인코더 출력으로 세션을 다시 구성합니다. 종료 후에는 사진용 프리뷰 세션으로 복귀합니다. CameraX 프리뷰에서 사진 촬영이나 동영상 녹화를 누르면 Camera2로 전환합니다.
- 저장한 사진과 동영상은 **DCIM/HALCamera** 앨범과 앱의 **갤러리**에서 볼 수 있습니다. Android 10 이상에서는 저장 완료 후 MediaStore에 공개하며 별도 저장소 권한이 필요하지 않습니다. Android 8–9에서는 저장소 권한을 요청합니다.
- BENCHMARK의 기존 스트림 구성과 JPEG 측정 요청은 유지하며, 벤치마크에서 촬영한 이미지는 갤러리에 저장하지 않습니다. MARK의 incident ZIP에도 이미지 픽셀을 넣지 않습니다.

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

## 내부 점수 초안

검토한 정상 측정 분포가 있는 Galaxy S25+ (`SM-S936N`) 후면 메인 endpoint `0`에는 `score-v1-draft` Camera Endpoint Score와 카테고리 점수를 표시합니다. release 빌드·적격 환경·동일 측정 계약이 필요하며, 3A는 점수에서 제외합니다. 다른 기기와의 순위를 나타내는 공개 점수는 아닙니다. 계산 규칙과 검증 범위는 [SCORING.md](docs/SCORING.md)를 참고하십시오.

## 빌드

JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13을 사용합니다.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties`는 PC마다 직접 만들며 저장소에 포함하지 않습니다. release 서명을 하려면 `settings.gradle.kts` 옆에 `keystore.properties`를 두고 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적습니다. 이 파일이 없으면 release는 서명 없이 빌드되므로 키가 없는 PC에서도 debug 빌드와 CI가 동작합니다.

Windows에서 프로젝트 경로에 한글이 있으면 Android Gradle Plugin이 빌드를 거부합니다. `subst`로 ASCII 드라이브 문자를 만든 뒤 그 경로에서 빌드하십시오.

## run JSON

run 하나는 `files/benchmarks/<run_id>.json`에 schema 4로 저장됩니다(schema 3 파일도 읽습니다). 결과 화면의 `EXPORT`로 공유할 수 있습니다. 파일에는 지표값, profile, 기기와 빌드 식별자, 환경값(thermal 시작 · 최고 · 종료, 절전 모드, 충전 상태), validity flag, 그리고 raw 표본이 들어 있습니다. **사진이나 프리뷰 픽셀은 저장하지 않습니다.**

## 실행 이력과 CSV 내보내기

RESULTS는 기본으로 현재 profile과 camera의 **비교 가능한 실행**을 보여 줍니다. 필터를 `전체`로 바꾸면 중단되었거나 비교할 수 없는 실행도 확인할 수 있습니다. 행을 누르면 저장된 결과가 열리고, 길게 누르면 baseline 지정·해제, 비교, JSON·CSV 내보내기, 삭제 메뉴가 나옵니다. 삭제할 때는 실행 ID를 확인하며, baseline으로 지정된 실행을 삭제하면 해당 지정도 해제됩니다.

`COMPARE`에서 첫 번째 실행을 선택한 뒤 다른 행을 누르면 두 실행을 비교합니다. 선택한 기준이 현재 baseline일 때만 회귀 판정을 표시하고, 그 외에는 변화량만 표시합니다. 비교 화면에서 기준과 현재 실행을 바꿀 수 있습니다.

`CSV EXPORT`는 현재 필터에 보이는 모든 실행을 내보냅니다. CSV 한 행은 실행의 지표 하나이며, 형상 정보, 기기·앱 정보, 환경, eligibility, 지표값과 통계가 함께 들어갑니다. 측정값이 없으면 빈 칸으로 남기고, 지표가 없는 중단 실행도 한 행으로 유지합니다. 쉼표·따옴표·줄바꿈을 지원하며, 스프레드시트 수식으로 해석될 수 있는 텍스트에는 작은따옴표를 붙입니다. JSON 원본은 바뀌지 않습니다. Subject build·commit·branch는 다음 실행에서 재사용하고, 실행별 Note는 재사용하지 않습니다.

PC에서는 Python 3 표준 라이브러리만으로 같은 열 형식의 CSV를 만들 수 있습니다. 기본 필터는 **점수 산정 가능한 실행**입니다.

```bash
python tools/aggregate.py ./runs -o scores.csv
python tools/aggregate.py ./runs --eligibility comparison_eligible -o comparison.csv
python tools/aggregate.py ./runs --eligibility all --profile camera2-standard-v1 --endpoint 0 -o all.csv
python -m unittest discover -s tools/tests -v
```

스크립트는 폴더 바로 아래의 schema 3·4 benchmark JSON을 읽고 `index.json`은 제외합니다. eligibility는 앱과 같은 validity flag 규칙으로 다시 계산합니다. 알 수 없는 flag가 있으면 비교와 점수 산정 대상에서 제외합니다. 손상된 파일은 파일명과 이유를 표준 오류로 알리고, 읽을 수 있는 실행은 계속 내보낸 뒤 종료 코드 1을 반환합니다. CSV에는 저장 당시의 회귀 판정이나 점수를 재계산해서 넣지 않습니다.

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
