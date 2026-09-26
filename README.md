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
| **Live** (런처) | CameraX / Camera2 전환, 카메라 선택, 실시간 프레임 간격과 콜백 지연 표시. `Mark` 버튼으로 직전 10초와 이후 5초를 incident ZIP으로 저장합니다 |
| **Benchmark** | profile 시작 카드 → 6단계 진행 → 지표별 막대 결과. `baseline으로 지정`, `비교`, `내보내기`, `실행 기록` |
| **실행 기록** | 실행 이력을 eligibility·profile·camera로 필터링하고, 임의의 두 실행을 비교하거나 JSON·CSV로 내보냅니다. Benchmark의 `실행 기록` 버튼으로 엽니다 |

Live는 관측한 숫자만 보여 주며 정상 / 이상을 판정하지 않습니다. 판정은 baseline과 비교할 때에만 성립하고, 그 일은 Benchmark가 합니다.

### 사진과 동영상

- Live의 **사진 촬영**을 누르면 **하나의 Camera2 still 요청에 YUV와 JPEG 출력을 함께 지정**합니다. 센서 타임스탬프가 일치하는 두 버퍼를 받아 YUV는 JPEG으로 변환하고, 카메라 JPEG은 원본 바이트로 저장합니다. 파일명은 같은 촬영 식별자에 `_YUV.jpg`, `_JPEG.jpg`를 붙입니다.
- 현재 Live 스트림 선택 기준은 YUV 최대 640×480 픽셀 예산, JPEG 최대 1920×1080 픽셀 예산입니다. 실제 크기는 카메라가 제공하는 목록에서 정하며 두 이미지의 해상도는 다를 수 있습니다. 출력 조합을 거부한 카메라는 상태 메시지를 표시합니다.
- **동영상 녹화**는 마이크 권한을 요청한 뒤 H.264/AAC MP4 녹화를 시작합니다. **중지**를 누르거나 앱을 나가거나 카메라를 바꾸면 녹화를 종료하고 저장합니다. 너무 짧거나 실패한 녹화는 오류 메시지와 함께 폐기합니다. 녹화 중에는 사진 촬영과 줌 변경을 받지 않습니다.
- 녹화는 프리뷰와 인코더 출력으로 세션을 다시 구성합니다. 종료 후에는 사진용 프리뷰 세션으로 복귀합니다. CameraX 프리뷰에서 사진 촬영이나 동영상 녹화를 누르면 Camera2로 전환합니다.
- 저장한 사진과 동영상은 **DCIM/HALCamera** 앨범과 앱의 **갤러리**에서 볼 수 있습니다. Android 10 이상에서는 저장 완료 후 MediaStore에 공개하며 별도 저장소 권한이 필요하지 않습니다. Android 8–9에서는 저장소 권한을 요청합니다.
- Benchmark의 기존 스트림 구성과 JPEG 측정 요청은 유지하며, 벤치마크에서 촬영한 이미지는 갤러리에 저장하지 않습니다. Mark의 incident ZIP에도 이미지 픽셀을 넣지 않습니다.

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

검토한 정상 측정 분포가 있는 Galaxy S25+ (`SM-S936N`)의 `Camera · 0 (Wide · Rear)` endpoint에는 `score-v1-draft` Camera Endpoint Score와 카테고리 점수를 표시합니다. release 빌드·적격 환경·동일 측정 계약이 필요하며, 3A는 점수에서 제외합니다. 다른 기기와의 순위를 나타내는 공개 점수는 아닙니다. 계산 규칙과 검증 범위는 [SCORING.md](docs/SCORING.md)를 참고하십시오.

## 빌드

JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13을 사용합니다.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties`는 PC마다 직접 만들며 저장소에 포함하지 않습니다. release 서명을 하려면 `settings.gradle.kts` 옆에 `keystore.properties`를 두고 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적습니다. 이 파일이 없으면 release는 서명 없이 빌드되므로 키가 없는 PC에서도 debug 빌드와 CI가 동작합니다.

Windows에서 프로젝트 경로에 한글이 있으면 Android Gradle Plugin이 빌드를 거부합니다. `subst`로 ASCII 드라이브 문자를 만든 뒤 그 경로에서 빌드하십시오.

## run JSON

run 하나는 `files/benchmarks/<run_id>.json`에 schema 4로 저장됩니다(schema 3 파일도 읽습니다). 결과 화면의 `내보내기`로 공유할 수 있습니다. 파일에는 지표값, profile, 기기와 빌드 식별자, 환경값(thermal 시작 · 최고 · 종료, 절전 모드, 충전 상태), validity flag, 그리고 raw 표본이 들어 있습니다. **사진이나 프리뷰 픽셀은 저장하지 않습니다.**

## 실행 이력과 CSV 내보내기

실행 기록은 기본으로 현재 profile과 camera의 **비교 가능한 실행**을 보여 줍니다. 필터를 `전체`로 바꾸면 중단되었거나 비교할 수 없는 실행도 확인할 수 있습니다. 행을 누르면 저장된 결과가 열리고, 길게 누르면 baseline 지정·해제, 비교, JSON·CSV 내보내기, 삭제 메뉴가 나옵니다. 삭제할 때는 실행 ID를 확인하며, baseline으로 지정된 실행을 삭제하면 해당 지정도 해제됩니다.

`두 실행 비교`에서 첫 번째 실행을 선택한 뒤 다른 행을 누르면 두 실행을 비교합니다. 선택한 기준이 현재 baseline일 때만 회귀 판정을 표시하고, 그 외에는 변화량만 표시합니다. 비교 화면에서 기준과 현재 실행을 바꿀 수 있습니다.

`목록 CSV 내보내기`는 현재 필터에 보이는 모든 실행을 CSV 파일 하나로 내보냅니다. 각 행의 `⋮` 메뉴에 있는 `CSV 내보내기`는 그 실행 하나만 내보냅니다. CSV 한 행은 실행의 지표 하나이며, 형상 정보, 기기·앱 정보, 환경, eligibility, 지표값과 통계가 함께 들어갑니다. 측정값이 없으면 빈 칸으로 남기고, 지표가 없는 중단 실행도 한 행으로 유지합니다. 쉼표·따옴표·줄바꿈을 지원하며, 스프레드시트 수식으로 해석될 수 있는 텍스트에는 작은따옴표를 붙입니다. JSON 원본은 바뀌지 않습니다. Subject build·commit·branch는 다음 실행에서 재사용하고, 실행별 Note는 재사용하지 않습니다.

PC에서는 Python 3 표준 라이브러리만으로 같은 열 형식의 CSV를 만들 수 있습니다. 기본 필터는 **점수 산정 가능한 실행**입니다.

```bash
python tools/aggregate.py ./runs -o scores.csv
python tools/aggregate.py ./runs --eligibility comparison_eligible -o comparison.csv
python tools/aggregate.py ./runs --eligibility all --profile camera2-standard-v1 --endpoint 0 -o all.csv
python -m unittest discover -s tools/tests -v
```

스크립트는 폴더 바로 아래의 schema 3·4 benchmark JSON을 읽고 `index.json`은 제외합니다. eligibility는 앱과 같은 validity flag 규칙으로 다시 계산합니다. 알 수 없는 flag가 있으면 비교와 점수 산정 대상에서 제외합니다. 손상된 파일은 파일명과 이유를 표준 오류로 알리고, 읽을 수 있는 실행은 계속 내보낸 뒤 종료 코드 1을 반환합니다. CSV에는 저장 당시의 회귀 판정이나 점수를 재계산해서 넣지 않습니다.

## 문서

### ADB CLI

PC에는 `adb`만 있으면 됩니다. HAL CAM APK를 설치하고 앱의 진단 패널에서 **ADB CLI 허용**을 켭니다. 카메라 권한을 허용하고 화면 잠금을 해제합니다. 소리를 포함하여 녹화하려면 마이크 권한도 필요합니다.

무선 디버깅 기기는 `adb connect IP:PORT`로 연결합니다. 최초 페어링이 필요하면 기기의 무선 디버깅 화면에서 표시되는 주소와 코드로 `adb pair IP:PAIR_PORT`를 실행합니다. 연결 포트와 페어링 포트는 다를 수 있습니다. 여러 기기가 연결되어 있으면 아래의 모든 명령에 `adb -s IP:PORT`를 사용합니다.

앱에 포함된 스크립트를 기기에 한 번 준비합니다. APK를 업데이트한 뒤에도 같은 명령으로 갱신할 수 있습니다.

```sh
adb shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
```

이후 다음 명령을 사용합니다. 앱 열기, 요청 ID 생성, 완료 대기는 자동으로 처리합니다.

```sh
adb shell sh /data/local/tmp/halcam help
adb shell sh /data/local/tmp/halcam cameras
adb shell sh /data/local/tmp/halcam preview --camera 0
adb shell sh /data/local/tmp/halcam capture --camera 0
adb shell sh /data/local/tmp/halcam record start --camera 0
adb shell sh /data/local/tmp/halcam record stop
adb shell sh /data/local/tmp/halcam preview stop
adb shell sh /data/local/tmp/halcam status
```

카메라 ID의 기본값은 `0`이며, `cameras`로 사용 가능한 ID를 확인합니다. 사진은 YUV 변환 JPEG과 카메라 JPEG 두 장입니다. 녹화는 MP4이며 `--no-audio`로 무음 녹화를 선택할 수 있습니다. `record start`는 실제 녹화 시작을 확인한 뒤 반환하고, `record stop`은 MP4 저장과 결과 등록까지 기다립니다. 녹화 실행 제한의 기본값은 1시간이며, `--timeout 초`로 줄일 수 있습니다. 제한에 도달하면 녹화를 종료하고 요청에 시간 제한 오류를 기록합니다.

결과 파일이 있으면 기기의 `Download/HALCamera-cli/요청ID`에 크기와 SHA-256을 검증한 복사본을 준비하고, PC로 받는 `adb pull` 명령 한 줄을 출력합니다. `adb pull`은 CMD와 PowerShell에서도 바이너리를 그대로 복사합니다. 기기 복사본은 자동으로 삭제하지 않습니다.

`--no-wait`은 요청 접수 뒤 바로 반환합니다. 무선 연결이 끊겨도 같은 작업을 다시 실행하지 말고, 재연결 후 `status 요청ID` 또는 `fetch 요청ID`로 확인합니다. ID를 생략하면 스크립트가 마지막으로 제출한 요청을 사용합니다. `cancel 요청ID`로 취소할 수 있으며, 이미 제출한 사진 저장은 완료될 수 있습니다.

`probe`, `cts cases`, `cts run --cases KEY[,KEY...]`도 지원합니다. 벤치마크는 CLI 지원 범위에서 제외하며 앱 화면에서 실행합니다. Python `halcam`은 사진·probe·CTS 파일 수집을 위한 선택 도구입니다. 기본 CLI에 Python이나 pip는 필요하지 않습니다.

자세한 사용법은 [CLI 가이드](guide/cli.md)를 참고하세요.

| 문서 | 내용 |
|---|---|
| [PLAN-BenchMarker-v0.3.md](docs/PLAN-BenchMarker-v0.3.md) | 현재 제품 계획. 지표, 화면, 비교 규칙, 마일스톤 |
| [METRICS.md](docs/METRICS.md) | 지표 정의. 시작 · 종료 지점, 시계, 통계 |
| [METRICS-REVIEW.md](docs/METRICS-REVIEW.md) | 지표 정의에 대한 검토 기록 |
| [design/DESIGN.md](docs/design/DESIGN.md) | 화면 색과 타이포그래피 토큰 |
| [archive/PRODUCT-v0.2.md](docs/archive/PRODUCT-v0.2.md) | 이전 제품 정의(Camera Doctor). 카메라 열거 절차와 디자인 토큰은 v0.3도 참조합니다 |

### 개발자 가이드 사이트

개발자 가이드의 원고는 `guide/`에, 배포용 정적 사이트는 `docs/`에 있습니다. 사이트는 GitHub Actions나 GitHub 측 Jekyll 빌드 없이 로컬에서 검사·빌드하여 `main`에 커밋합니다. 저장소를 upstream으로 자동 동기화하는 사내 미러도 Pages 설정만으로 같은 사이트를 제공합니다.

| 경로 | 역할 |
|---|---|
| `guide/*.md`, `guide/_content/`, `guide/_inputs/` | 원고. 마커 블록은 `node tools/docgen/docflow.mjs generate`가 채웁니다 |
| `guide/_layouts/default.html`, `guide/_config.yml`, `guide/assets/` | 레이아웃, 사이트 설정과 메뉴, 생성된 CSS |
| `docs/index.html`, `docs/*.html`, `docs/assets/`, `docs/_inputs/`, `docs/.nojekyll` | `node tools/docgen/docflow.mjs site build`가 만든 배포 산출물. 직접 편집하지 않습니다 |
| `docs/.site-manifest.json` | 빌더가 만든 파일 목록. 정리와 최신성 검사는 이 목록 안에서만 수행하므로 `docs/`의 다른 문서는 지우지 않습니다 |

Node.js 24 이상이 필요합니다. 문서 파이프라인은 공용 엔진 [`@ttolsun/omm-doc-workflow`](https://github.com/TTolsun/omm-doc-workflow)이며, 이 저장소의 `tools/docgen/`에는 설정(`project.json`), 프로젝트 어댑터(사실 추출·표 렌더링·담당 요소 대상), 검토 상태(`state/`)만 있습니다. 먼저 `npm ci --prefix tools/docgen --ignore-scripts`로 엔진을 설치합니다. 절차는 다음 순서로 진행합니다.

1. 검사: `node tools/docgen/docflow.mjs check`. CI(`.github/workflows/docs-check.yml`)와 같은 단계를 같은 순서로 실행합니다. 실패하면 종료 코드 1과 실패한 단계 이름을 출력하고 뒤 단계를 실행하지 않습니다. 환경 문제(Node 버전, 인자)는 종료 코드 2입니다.
2. 빌드: `node tools/docgen/docflow.mjs check --build`. 모든 검사가 통과한 뒤에만 `guide/`를 `docs/`로 빌드합니다. 검사에 실패하면 산출물을 쓰지 않습니다. 빌드만 다시 하려면 `node tools/docgen/docflow.mjs site build`를 실행합니다.
3. 미리보기: `node tools/docgen/docflow.mjs site serve --base hal-camera`를 실행하고 `http://127.0.0.1:4000/hal-camera/`를 엽니다. `--base`는 배포 URL의 하위 경로를 흉내 냅니다. 사내 GitHub의 경로가 다르면 그 값을 지정하여 CSS·링크가 하위 경로에서도 동작하는지 확인합니다.
4. 커밋: `guide/`, `docs/`, `tools/docgen/state/`를 함께 커밋합니다. `node tools/docgen/docflow.mjs site check`는 `docs/`가 원고에서 다시 빌드한 결과와 같은지, 내부 링크와 조각(`#id`)이 유효한지, `/`로 시작하는 루트 고정 경로나 `github.io` 도메인이 없는지 검사합니다. CI는 여기에 `git diff --exit-code`를 더해 커밋되지 않은 산출물 변경을 잡습니다.
5. 동기화: `main`에 머지하면 사내 미러가 upstream을 가져옵니다. 별도의 배포 단계는 없습니다.

Pages 설정은 공개 저장소와 사내 미러 모두 **Settings → Pages → Build and deployment → Source: Deploy from a branch → Branch: `main`, Folder: `/docs`**입니다. `docs/.nojekyll`이 있으므로 GitHub은 Jekyll을 실행하지 않고 파일을 그대로 제공합니다. GitHub.com은 2024년 6월 30일 이후 브랜치 배포도 내부적으로 Actions를 사용하지만, `.nojekyll`이 있으면 사용자 정의 워크플로 없이 배포됩니다([GitHub 안내](https://github.blog/changelog/2024-07-08-pages-legacy-worker-sunset/)).

산출물은 결정론적입니다. 시각이나 환경 정보를 넣지 않으므로 입력이 같으면 다시 빌드해도 차이가 없습니다. 링크와 자산 경로는 모두 상대 경로이며 페이지 깊이에 따라 `./` 또는 `../`를 붙입니다. 구조도는 레이아웃이 `cdn.jsdelivr.net`에서 Mermaid를 불러와 그리므로, 해당 CDN에 접근할 수 없는 네트워크에서는 구조도 자리에 소스 텍스트가 남습니다.

사내 GitHub Enterprise Server에서는 다음 항목을 확인하지 못했습니다. 미러에서 처음 Pages를 켤 때 확인하고 결과를 이 절에 기록하십시오.

- 관리자가 GitHub Pages를 켰는지(Enterprise 설정 → Pages), 그리고 저장소 설정에서 `Deploy from a branch`가 선택 가능한지. GHES는 Actions 없이도 브랜치 배포를 지원하는 것으로 안내되지만 이 저장소에서는 실측하지 않았습니다. 서브도메인 격리 설정에 따라 사이트 URL 형식이 `https://<host>/pages/<org>/<repo>/` 또는 `https://pages.<host>/<org>/<repo>/`로 달라지며, 산출물은 어느 형식이든 상대 경로로 동작합니다.
- GHES 버전이 `.nojekyll`을 인식하는지. 인식하지 않더라도 산출물에는 Jekyll이 해석할 front matter나 Liquid 문법이 없으므로 Jekyll을 거쳐도 같은 파일이 나와야 하지만, 이 경로는 검증하지 않았습니다.
- 사내 네트워크에서 `cdn.jsdelivr.net` 접근 여부.

## 코드 구조

```
camera/     Camera2 / CameraX 엔진, 카메라 엔드포인트 열거
telemetry/  Telemetry, FlightRecorder(30초 순환 버퍼), incident ZIP
metrics/    이벤트 → 지표 계산. 화면도 판정도 모르는 leaf
benchmark/  Benchmark·실행 기록·비교 화면(Activity)만 루트에 둔다
  domain/     profile, runner, 통계, validity, 점수, 비교 규칙, presenter. Android 의존 없음
  platform/   BenchmarkStore, BenchmarkReport(org.json 경계), DeviceInstance, ThermalTracker 같은 파일·기기 어댑터
cli/        ADB 명령 접수·상태 저장, 화면 어댑터(LiveController, BenchmarkController)
cts/        앱 안에서 실행하는 CTS 카메라 케이스
ui/         Look 토큰, 그래프 뷰, Live 실시간 수치
```

`benchmark/domain/`과 `metrics/`에 `android.*`·`org.json` import가 들어오거나 `domain/`이 `platform/`을 참조하면 `LayerIsolationTest`(JVM 테스트)가 실패합니다.

화면은 XML 없이 Kotlin 코드로 만듭니다. 판정과 배치 규칙은 순수 Kotlin 객체에 두어 기기 없이 JVM 테스트로 검증합니다.
