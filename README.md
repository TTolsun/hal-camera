# HALCamera

사진 한 장이 완성되기까지의 과정을 들여다봅니다.

**HALCamera는 Android 카메라의 촬영·콜백 관찰·반복 측정을 한곳에 모은 개발자 도구입니다.** Camera2·CameraX로 사진과 동영상을 찍고, 여러 카메라의 출력을 살펴보며, 같은 조건에서 측정한 결과를 비교합니다.

사진은 저장됐는데 메타데이터는 언제 도착했을까요. 녹화하며 사진을 찍으면 프레임 간격은 어떻게 달라질까요. 앞과 뒤의 카메라로 찍은 영상은 각각 어떤 파일로 남을까요. HALCamera는 이런 질문을 직접 촬영하고 기록하며 확인할 수 있게 만듭니다.

[최신 APK 받기](https://github.com/TTolsun/hal-camera/releases/latest) · [사용자 가이드](https://ttolsun.github.io/hal-camera/) · [첫 실행 안내](https://ttolsun.github.io/hal-camera/getting-started.html)

## 먼저, 한 장을 찍어 보세요

1. [최신 릴리스](https://github.com/TTolsun/hal-camera/releases/latest)의 Assets에서 APK를 받아 Android 기기에 설치합니다.
2. HAL CAMERA를 열고 카메라 권한을 허용합니다. 소리를 함께 녹음하려면 마이크 권한도 필요합니다.
3. Live에서 엔진과 카메라를 고르고 셔터를 누릅니다. 최근 썸네일을 열어 저장된 사진을 확인합니다.
4. `Callback`을 켜고 `Hold 3s`를 선택한 뒤 한 장 더 찍습니다. 같은 프레임의 이미지와 메타데이터가 도착하는 순서를 살펴봅니다.

앱은 **Android 8.0 이상**에서 설치할 수 있습니다. PIP·Multi는 Android 11 이상과 해당 카메라 조합의 지원이 필요하며, AOSP CTS 원문 케이스는 Android 14 이상에서 실행합니다. 실제로 사용할 수 있는 출력과 제어는 기기·엔진에 따라 다릅니다.

PC에서 설치하려면 `adb install -r 받은-파일.apk`를 사용합니다. 업데이트할 때 서명이 맞지 않으면 기존 앱과 같은 서명의 APK를 받으세요. 앱을 삭제하면 내부 실행 기록도 사라집니다.

## 화면에 담긴 장면, 그 뒤에 도착한 기록

Live에서는 Camera2와 CameraX를 바꾸어 촬영하고, 출력 크기와 프레임률, 노출·초점·화이트밸런스 같은 조건을 조절합니다. 기기가 지원하는 범위에서 연사·AEB, 녹화 중 사진도 시험할 수 있습니다.

**두 장면을 한 화면으로 남기고 싶다면 PIP를, 카메라마다 원본을 남기고 싶다면 Multi를 사용합니다.** PIP 사진은 출력 설정에 따라 합성 사진과 메인·보조 원본을 함께 남기고, PIP 동영상은 합성 MP4 하나를 저장합니다.

Multi에서는 화면을 나누거나 배치를 바꾸어도 카메라별 파일을 따로 저장합니다. Multi V는 같은 마이크의 소리를 각 MP4에 담습니다. 여러 센서의 동기 촬영이나 완전히 일치하는 영상·소리 재생을 보장하지는 않습니다.

Callback은 촬영 결과를 다른 방식으로 읽게 해 줍니다. 같은 시간축에 놓인 점을 따라가며 Shutter·Metadata·이미지의 도착 순서를 확인합니다. 그래프를 잠시 고정해도 촬영과 녹화는 계속됩니다.

| 확인하고 싶은 것 | 시작할 곳 |
| --- | --- |
| 사진·동영상과 촬영 조건을 바꿔 봅니다. | [Live](https://ttolsun.github.io/hal-camera/live.html) |
| 같은 프레임의 콜백과 이미지 도착 시각을 읽습니다. | [Callback](https://ttolsun.github.io/hal-camera/callback.html) |
| 카메라가 공개한 사양과 지원 값을 찾습니다. | [Probe](https://ttolsun.github.io/hal-camera/probe.html) |
| 커스텀 검사나 AOSP CTS 원문 케이스를 실행합니다. | [CTS](https://ttolsun.github.io/hal-camera/cts.html) |
| 같은 조건으로 반복 측정하고 이전 실행과 비교합니다. | [Benchmark](https://ttolsun.github.io/hal-camera/benchmark.html) |
| PC 명령이나 코딩 에이전트로 실행하고 결과를 받습니다. | [Automation](https://ttolsun.github.io/hal-camera/automation.html) |
| 예상과 다른 동작의 조건을 좁히고 기록을 모읍니다. | [Troubleshooting](https://ttolsun.github.io/hal-camera/troubleshooting.html) |

## 한 번의 차이보다, 같은 조건의 반복을 봅니다

Benchmark는 카메라 열기·프리뷰·촬영·녹화에 걸린 시간과 콜백을 기록합니다. 정상 실행을 비교 기준인 **baseline**으로 직접 지정하면, 이후 실행에서 지표별 변화량과 성능 저하 판정인 `REGRESSED`를 확인할 수 있습니다.

비교할 때에는 기기·카메라·프로파일과 발열·절전·충전 같은 실행 조건을 함께 맞춥니다. 프로파일의 출력 크기는 그대로 사용하며, 지원하지 않는 조합을 작은 크기로 자동 대체하지 않습니다. 측정 유효성과 비교 가능 여부는 보고서의 `validity`로 확인합니다.

baseline이 없으면 변화량만 표시하고 `REGRESSED` 판정을 붙이지 않습니다. **현재 앱은 종합 점수를 계산하지 않습니다.** 예전 보고서의 점수 필드는 읽을 수 있지만 새 실행에서는 점수를 만들지 않습니다.

결과는 앱에서 비교하거나 JSON·CSV로 내보낼 수 있습니다. 새 보고서는 schema 5로 저장하고 schema 3·4도 읽습니다. PC에서 모은 JSON을 CSV로 바꾸려면 Python 3으로 다음 명령을 실행합니다.

```sh
python tools/aggregate.py ./runs --eligibility comparison_eligible -o comparison.csv
```

`--eligibility all`은 비교 불가 실행도 포함합니다. 옵션을 생략하면 기존 계약의 `scoring_eligible` 필터를 사용하므로, 실행 전체를 보려는 경우에는 필터를 명시하세요. 통계와 임계값의 정의는 [METRICS.md](docs/METRICS.md), 비교·내보내기 절차는 [Benchmark 가이드](https://ttolsun.github.io/hal-camera/benchmark.html)에 있습니다.

## 같은 질문을, PC에서도 반복합니다

**기본 CLI에는 ADB와 APK만 있으면 됩니다.** PC에 Python이나 별도 클라이언트를 설치하지 않아도 사진·녹화·사양 조회·CTS·벤치마크를 실행할 수 있습니다.

1. 기기의 개발자 옵션에서 USB 디버깅을 켜고 PC와 연결한 뒤 디버깅 연결을 승인합니다. 앱의 카메라 권한을 허용하고 화면 잠금을 해제한 뒤 `adb devices -l`로 연결을 확인합니다. 기기가 여러 대면 이후 모든 명령에 같은 `adb -s SERIAL`을 사용합니다.
2. APK에 포함된 실행 스크립트를 준비하고 연결 상태와 카메라 ID를 확인합니다. APK를 업데이트한 뒤에도 첫 명령으로 스크립트를 갱신합니다.

   ```sh
   adb shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
   adb shell sh /data/local/tmp/halcam doctor
   adb shell sh /data/local/tmp/halcam cameras
   ```

3. 조회된 선택 가능 카메라 ID로 아래의 `0`을 바꿔 촬영합니다. 완료 후 출력된 `adb pull` 명령으로 파일을 PC에 받습니다.

   ```sh
   adb shell sh /data/local/tmp/halcam capture --camera 0
   ```

ADB CLI는 기본으로 허용됩니다. 직접 꺼 두었다면 앱의 `Lab → ADB CLI`에서 다시 켭니다. Android 8–9에서 파일을 저장할 때에는 저장소 권한도 필요합니다. Git Bash에서는 실행 전에 `export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1`을 설정하여 기기 경로의 자동 변환을 막으세요.

무음 녹화와 벤치마크도 같은 스크립트로 시작합니다. 녹화는 시작 확인 후 원하는 시간만큼 진행하고 `record stop`으로 끝냅니다.

```sh
adb shell sh /data/local/tmp/halcam record start --camera 0 --no-audio
adb shell sh /data/local/tmp/halcam record stop
adb shell sh /data/local/tmp/halcam benchmark run --camera 0
```

벤치마크 CLI는 고정된 `camera2-standard-v2` 프로파일을 사용합니다. **요청 접수·녹화 시작·파일 저장 완료는 서로 다릅니다.** 연결이 끊겼다면 같은 촬영을 다시 요청하기 전에 `status REQUEST_ID`로 기존 작업을 확인하세요. 스크립트는 파일을 회수할 때 기기 복사본의 크기와 SHA-256을 확인하며, PC에 받은 파일도 출력된 값과 대조할 수 있습니다.

무선 디버깅, 촬영 옵션, CTS 키, 결과·baseline 관리와 Python 클라이언트는 [CLI 가이드](https://ttolsun.github.io/hal-camera/cli.html)에 모았습니다. 코딩 에이전트로 조작하려면 [에이전트 사용법](https://ttolsun.github.io/hal-camera/coding-agents.html)과 APK에 맞는 [halcam-cli 스킬](skills/halcam-cli/SKILL.md)을 사용하세요.

## 관찰한 것의 범위를 함께 남깁니다

HALCamera가 관찰하는 것은 앱이 받은 콜백·이미지·결과 메타데이터입니다. 콜백의 간격만으로 HAL 내부 처리 시간, 프레임 드롭, 실제 화면 표시 시각을 확정할 수 없습니다. Camera2와 CameraX는 관측할 수 있는 지점도 다릅니다.

Probe는 기기가 공개한 사양을 보여 줍니다. CTS 화면의 결과는 사전 점검용이며, 공식 CTS 판정은 `cts-tradefed`의 `test_result.xml`로 확인합니다. Benchmark의 성능 저하 판정은 이 적합성 검사와 별개입니다.

사진·동영상은 별도의 미디어 경로에 저장합니다. **벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.** 보고서에는 측정값과 원시 표본, 기기·빌드 식별자와 실행 환경이 담깁니다.

엔진별 차이는 [Engine Comparison](https://ttolsun.github.io/hal-camera/engine.html), 기기 모델·Android 버전·검증 시나리오와 알려진 제약은 [Validation](https://ttolsun.github.io/hal-camera/evidence.html)에서 확인할 수 있습니다. 최신 변경 사항은 [릴리스 기록](https://github.com/TTolsun/hal-camera/releases)에 남깁니다.

## 소스에서 시작하려면

JDK 17과 Android SDK 36을 준비합니다. 프로젝트는 Kotlin 2.2.21, AGP 8.13.2, Gradle 8.13을 사용합니다. SDK 경로는 개인 환경의 `local.properties`에 설정하며, 서명 키와 함께 저장소에 넣지 않습니다.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용하고, `JAVA_HOME`을 JDK 17 설치 경로로 설정합니다. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. release 빌드는 `keystore.properties`가 있으면 해당 키로 서명하고, 없으면 unsigned로 생성합니다. 자세한 절차는 [Build 가이드](guide/building.md)에 있습니다.

화면과 파일·기기 어댑터는 Android를 사용하고, 지표 계산과 비교·판정은 순수 Kotlin에 둡니다. `LayerIsolationTest`가 `metrics/`와 `benchmark/domain/`의 Android·`org.json` 의존성을 검사합니다.

| 경로 | 역할 |
| --- | --- |
| `app/` | 촬영 엔진, 화면, 콜백 수집, 벤치마크와 ADB 명령을 구현합니다. |
| `ctsvendor/` | 앱에서 실행하는 AOSP CTS 원문 테스트를 둡니다. |
| `tools/` | PC 집계·CLI와 문서 파이프라인 연결 도구를 둡니다. |
| `.omm/` · `guide/` | 구조 근거와 사용자·개발자 가이드 원고를 관리합니다. |
| `docs/` | 배포용 HTML과 지표 정의·설계·검증·릴리스 기록을 둡니다. |

JVM 테스트 통과와 실제 기기의 카메라 동작 검증은 구분하여 기록합니다. 구조와 기여 규칙은 [Architecture](guide/architecture.md), [Contributing](guide/contributing.md), [AGENTS.md](AGENTS.md)를 참고하세요. CLI 계약을 바꿀 때에는 앱·셸 스크립트·가이드·배포용 스킬을 함께 확인합니다.

<details>
<summary>문서 사이트를 빌드하거나 사내 미러에서 제공하려면</summary>

Node.js 24 이상에서 저장소 루트를 기준으로 실행합니다.

```sh
npm ci --prefix tools/docgen --ignore-scripts
node tools/docgen/docflow.mjs check --build
```

`guide/`가 원고이고 `docs/`는 생성물입니다. 마커 블록과 배포 HTML을 직접 고치지 않습니다. 검사·생성 결과를 함께 커밋하며, `docs-check`가 원고 최신성·담당 요소·생성 결과·사이트 링크를 검사합니다. 생성기는 파일 내용이 같으면 같은 결과를 만듭니다.

Pages는 `Deploy from a branch`, `main`, `/docs`로 설정합니다. `docs/.nojekyll`이 있어 Jekyll을 실행하지 않고 산출물을 제공하며, GitHub.com에서는 내부 Pages Actions가 배포를 처리합니다. 사내 미러도 같은 산출물을 사용할 수 있습니다. GHES의 Pages 활성화·URL 형식·`.nojekyll` 지원은 해당 미러에서 별도로 확인해야 하며, 이 저장소에서는 실측하지 않았습니다.

링크와 자산은 상대 경로를 사용합니다. `node tools/docgen/docflow.mjs site serve --base hal-camera`로 하위 경로를 포함한 미리보기를 열 수 있습니다. 문서의 Mermaid 그림은 외부 CDN을 사용하므로 사내 네트워크의 접근 허용도 확인하세요.

문서 파이프라인은 `tools/docgen/package.json`에 고정한 공용 엔진 `@ttolsun/omm-doc-workflow`를 설치해 사용합니다. 원본·검토 기록·자동 동기화와 운영 절차는 [문서 파이프라인 README](tools/docgen/README.md)에 있습니다.

</details>

---

*Engineering the invisible. · by K.H. Kim*
