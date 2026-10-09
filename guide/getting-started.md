---
title: Quickstart
---
<h1 lang="en">Install. Open. Check.</h1>

**배포된 APK를 설치하고 Live 프리뷰부터 확인하세요.** 이 가이드는 HALCamera로 기기를 검증하는 Camera HAL 개발자를 위한 문서입니다. 앱 소스를 빌드할 필요는 없습니다.

## APK를 설치하세요

1. [배포 페이지](https://github.com/TTolsun/hal-camera/releases/latest)의 Assets에서 APK를 받습니다. 팀에서 지정한 검증 빌드가 있다면 그 APK를 사용합니다.
2. 기기에서 APK를 열어 설치합니다. 설치 허용을 요청하면 해당 파일을 연 앱에 허용합니다. PC에서는 USB 디버깅을 켜고 연결을 승인한 뒤 아래 명령으로 설치할 수 있습니다.

   ~~~powershell
   adb install -r "받은-파일.apk"
   ~~~

3. HAL CAMERA를 열고 카메라 권한을 허용합니다. 소리를 녹음하려면 마이크 권한도 허용합니다.

업데이트에서 서명 오류가 나면 기존 설치와 같은 서명의 APK를 받으세요. 앱을 삭제하면 내부 실행 기록이 사라집니다. 소스 빌드가 필요한 경우에는 [앱 빌드](building.md)를 확인하세요.

## 첫 화면을 확인하세요

1. Live 상단에서 엔진과 카메라를 선택합니다.
2. 프리뷰가 나오는지 확인합니다.
3. 사진 모드에서 셔터를 누르고 저장 완료 안내를 확인합니다.
4. 최근 썸네일을 눌러 저장된 사진을 확인합니다.

기본 설정에서는 YUV를 변환한 JPEG와 카메라가 만든 JPEG가 저장됩니다. 출력 설정과 두 사진의 연결 기준은 [Live](live.md#live에서-촬영하세요)를 확인하세요. 화면이 나온다는 사실만으로 측정 정확성이나 HAL 적합성이 검증된 것은 아닙니다.

## 확인할 작업을 고르세요

| 목적 | 문서 |
| --- | --- |
| 촬영 조건을 바꾸고 파일을 확인합니다. | [Live](live.md) |
| 지원 사양을 확인하고 테스트합니다. | [Probe](probe.md) · [CTS](cts.md) |
| 지연을 반복 측정하거나 콜백을 관찰합니다. | [Benchmark](benchmark.md) · [Callback](callback.md) |
| PC에서 반복 실행하고 결과를 받습니다. | [자동화](automation.md) |
| 예상과 다른 결과의 원인을 좁힙니다. | [문제 찾기](troubleshooting.md) |

## 앱 버전을 기록하세요

재현 자료에는 기기에 설치한 앱 버전과 Android 빌드, 엔진, 카메라, 출력 조건을 함께 적으세요. 아래 표는 문서가 참조한 소스의 빌드 정보이며, 배포 APK나 기기의 설치 버전과 다를 수 있습니다.

<details markdown="1" id="build-reference" data-search-section>
<summary>문서 기준 빌드 정보</summary>

## 앱 버전과 빌드 설정

<!-- omm:begin id=build-identity -->

| 항목 | 값 |
| --- | --- |
| `applicationId` | `dev.halcamera` |
| `namespace` | `dev.halcamera` |
| `versionName` | `0.22.0` |
| `versionCode` | `650` |
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


</details>

<details markdown="1">
<summary>이전에 공유한 Live 링크</summary>

<p id="live에서-촬영하세요"><a href="live.html#live에서-촬영하세요">Live에서 촬영하세요</a></p>
<p id="추가-설정"><a href="live.html#추가-설정">추가 설정</a></p>
<p id="수동-촬영-조건을-고정하세요"><a href="live.html#수동-촬영-조건을-고정하세요">수동 촬영 조건을 고정하세요</a></p>
<p id="live-스트림을-설정하세요"><a href="live.html#live-스트림을-설정하세요">Live 스트림을 설정하세요</a></p>
<p id="두-물리-카메라를-함께-확인하세요"><a href="live.html#두-물리-카메라를-함께-확인하세요">두 물리 카메라를 함께 확인하세요</a></p>
<p id="손떨림-보정을-선택하세요"><a href="live.html#손떨림-보정을-선택하세요">손떨림 보정을 선택하세요</a></p>
<p id="초점과-노출을-조절하세요"><a href="live.html#초점과-노출을-조절하세요">초점과 노출을 조절하세요</a></p>
<p id="screen-live"><a href="live.html#screen-live">Live에서 보기</a></p>
<p id="detail-e76a9da117"><a href="live.html#detail-e76a9da117">Live에서 보기</a></p>
<p id="detail-f29a963033"><a href="live.html#detail-f29a963033">Live에서 보기</a></p>
<p id="detail-e8f4afe277"><a href="live.html#detail-e8f4afe277">Live에서 보기</a></p>
<p id="detail-aa6a89b2d7"><a href="live.html#detail-aa6a89b2d7">Live에서 보기</a></p>
<p id="detail-f2bbad292d"><a href="live.html#detail-f2bbad292d">Live에서 보기</a></p>
<p id="screen-live-controls"><a href="live.html#screen-live-controls">Live에서 보기</a></p>

</details>

<p id="실행-전에-준비하세요"><a href="building.html#실행-전에-준비하세요">소스 빌드 준비</a></p>
<p id="앱을-빌드하고-실행하세요"><a href="building.html#앱을-빌드하고-실행하세요">소스 빌드와 실행</a></p>

**다음 단계:** [Live](live.md)에서 검증할 촬영 조건을 설정하세요.
