# HALCamera

사진 한 장이 완성되기까지의 과정을 들여다봅니다.

**HALCamera는 Android 카메라의 촬영·콜백 관찰·반복 측정을 한곳에 모은 개발자 도구입니다.**

Camera2·CameraX로 사진과 동영상을 찍고, 같은 프레임의 기록을 읽으며, 같은 조건에서 측정한 결과를 비교합니다.

[최신 APK 받기](https://github.com/TTolsun/hal-camera/releases/latest) · [사용자 가이드](https://ttolsun.github.io/hal-camera/) · [첫 실행 안내](https://ttolsun.github.io/hal-camera/getting-started.html)

## 먼저, 한 장을 찍어 보세요

첫 촬영에는 Android 기기와 APK만 있으면 됩니다. PC나 소스 빌드는 필요하지 않습니다.

1. Android 기기에서 최신 릴리스를 열고 Assets의 `HAL-Camera-*.apk`를 받아 설치합니다.
2. HAL CAMERA를 열고 카메라 권한을 허용합니다. 소리를 함께 녹음하려면 마이크 권한도 필요합니다.
3. Live의 엔진·카메라는 기본값으로 두고, `Photo`에서 하단 가운데 셔터를 짧게 한 번 누릅니다.
4. 저장 완료 안내 뒤 셔터 왼쪽 썸네일을 열고 방금 찍은 사진을 확인합니다.

설치·권한·사진 확인에서 막히면 [첫 실행 안내](https://ttolsun.github.io/hal-camera/getting-started.html)를 따라가세요. Callback과 Benchmark는 첫 촬영을 마친 뒤 필요한 경우에 사용합니다.

앱은 **Android 8.0 이상**에서 설치할 수 있습니다. Android 8–9에서는 사진·동영상 저장에 저장소 권한도 필요합니다.

PIP·Multi는 Android 11 이상과 해당 카메라 조합의 지원이 필요합니다. AOSP CTS 원문 케이스는 Android 14 이상에서 실행합니다.

업데이트할 때 서명이 맞지 않으면 기존 앱과 같은 서명의 APK를 받으세요. 앱을 삭제하면 내부 실행 기록도 사라집니다.

## 사진과 기록을 나란히 봅니다

[Live](https://ttolsun.github.io/hal-camera/live.html)에서는 출력 크기·프레임률·노출·초점·화이트밸런스를 바꾸며 촬영합니다. 기기가 지원하면 연사·AEB와 녹화 중 사진도 시험할 수 있습니다.

두 장면을 한 화면으로 남기고 싶다면 PIP를, 카메라마다 원본을 남기고 싶다면 Multi를 사용합니다. 여러 센서의 동기 촬영을 보장하지는 않습니다.

[Callback](https://ttolsun.github.io/hal-camera/callback.html)에서는 같은 시간축의 점을 따라 Shutter·Metadata·이미지의 도착 순서를 읽습니다. 그래프를 잠시 고정해도 촬영과 녹화는 계속됩니다.

카메라가 공개한 사양은 [Probe](https://ttolsun.github.io/hal-camera/probe.html)에서, 커스텀 검사와 AOSP 원문 케이스는 [CTS](https://ttolsun.github.io/hal-camera/cts.html)에서 확인하세요.

## 한 번의 차이보다, 같은 조건의 반복을 봅니다

[Benchmark](https://ttolsun.github.io/hal-camera/benchmark.html)는 **Camera2**로 카메라 열기·프리뷰·촬영·녹화에 걸린 시간과 콜백을 기록합니다. Live의 엔진·출력 설정과 별개의 프로파일을 사용합니다.

기기·카메라·프로파일과 발열·절전·충전 조건을 맞추고 반복하세요. 측정 유효성과 비교 가능 여부는 보고서의 `validity`로 확인합니다.

정상 실행을 **baseline**으로 직접 지정하면 지표별 변화량과 성능 저하 판정인 `REGRESSED`를 확인할 수 있습니다.

baseline 없는 자동 비교는 이전 실행과의 변화량만 표시합니다. `두 실행 비교`에서는 먼저 고른 실행을 이번 비교의 기준으로 판정합니다.

결과는 앱에서 비교하거나 JSON·CSV로 내보낼 수 있습니다. 현재 앱은 종합 점수를 계산하지 않습니다.

## 같은 질문을, PC에서도 반복합니다

**기본 CLI에는 ADB와 APK만 있으면 됩니다.** Python이나 별도 클라이언트 없이 사진·녹화·사양 조회·CTS·벤치마크를 실행할 수 있습니다.

[CLI 가이드](https://ttolsun.github.io/hal-camera/cli.html)에서 기기를 연결하고, APK에 포함된 스크립트로 작업한 뒤 파일을 받으세요. 요청 접수와 파일 저장 완료는 다릅니다. 연결이 끊겼다면 다시 촬영하기 전에 기존 요청 ID로 상태를 확인합니다.

코딩 에이전트에 맡기려면 [에이전트 사용법](https://ttolsun.github.io/hal-camera/coding-agents.html)을 따르세요. APK와 같은 릴리스의 `halcam-cli-*-SKILL.md`를 함께 사용합니다.

## 관찰한 것의 범위를 함께 남깁니다

HALCamera가 관찰하는 것은 앱이 받은 콜백·이미지·결과 메타데이터입니다. 콜백 간격만으로 HAL 내부 처리 시간이나 프레임 드롭을 확정할 수는 없습니다.

Camera2와 CameraX는 관측 지점과 지원 기능이 다릅니다. [Engine Comparison](https://ttolsun.github.io/hal-camera/engine.html)에서 비교 조건을 먼저 확인하세요.

앱의 CTS 결과는 사전 점검용이며 공식 CTS 인증을 대신하지 않습니다. Benchmark의 성능 저하 판정과도 별개입니다.

**벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.** 보고서에는 측정값·원시 표본과 기기·빌드 식별자·실행 환경이 담기며, 사진·동영상은 별도로 저장합니다.

기기에서 확인한 조건과 한계는 [Validation](https://ttolsun.github.io/hal-camera/evidence.html)에, 예상과 다른 결과를 확인하는 순서는 [Troubleshooting](https://ttolsun.github.io/hal-camera/troubleshooting.html)에 있습니다.

## 소스에서 시작하려면

JDK 17과 Android SDK 36을 준비하고 저장소 루트에서 실행합니다.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows PowerShell에서는 `.\gradlew.bat`을 사용하고, `JAVA_HOME`을 JDK 17 설치 경로로 설정합니다. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

빌드·서명은 [Build](https://ttolsun.github.io/hal-camera/building.html), 구조와 기여 규칙은 [Architecture](guide/architecture.md)·[Contributing](guide/contributing.md)에서 확인하세요. 문서 생성·배포는 [문서 파이프라인 README](tools/docgen/README.md)에 정리했습니다.

---

*Engineering the invisible. · by K.H. Kim*
