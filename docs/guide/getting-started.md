---
title: 개발 환경 및 빠른 시작
---
# 개발 환경 및 빠른 시작

**저장소 루트에서 Gradle Wrapper로 앱을 빌드하세요.** 아래 명령은 Windows PowerShell을 기준으로 작성했습니다.

## 실행 전에 준비하세요

프로젝트 설정에 맞는 JDK와 Android SDK, `adb`가 필요합니다. SDK와 JVM 대상 버전은 [빌드 정보](troubleshooting.md#앱-버전과-빌드-설정)를 확인하세요.

Android SDK 경로는 저장소 루트의 `local.properties`에 설정합니다. 이 파일은 개인 환경 설정이므로 커밋하지 않습니다. 기기에서 USB 디버깅을 켜고 PC의 연결 요청을 허용한 뒤 `adb devices`로 연결 상태를 확인하세요.

## 앱을 빌드하고 실행하세요

1. 디버그 APK를 빌드합니다.

   ~~~powershell
   .\gradlew.bat :app:assembleDebug
   ~~~

2. JVM 단위 테스트를 실행합니다.

   ~~~powershell
   .\gradlew.bat :app:testDebugUnitTest
   ~~~

3. 연결한 기기에 APK를 설치합니다.

   ~~~powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ~~~

4. 앱을 열고 카메라 권한을 허용합니다. LIVE에서 프리뷰가 나오는지 확인한 뒤 BENCHMARK로 진입합니다.

빌드와 테스트가 통과하면 APK 생성과 JVM 테스트 결과를 확인한 것입니다. 측정 정확성과 기기 동작은 별도로 검증해야 합니다. 관찰 결과는 [기기 검증 기록](_inputs/device-verification.yaml)에 남기세요.

## 문서를 수정한 뒤 확인하세요

문서 도구는 **Node.js 24**를 사용합니다. 생성된 본문의 마커 블록을 직접 고치지 말고, 원고인 `docs/guide/_content/` 또는 해당 근거를 수정하세요. 마커 바깥의 설명은 페이지에서 수정합니다.

1. 코드에서 사실 정보를 추출하고 원고의 검토 상태를 확인합니다.

   ~~~powershell
   node tools/docgen/extract.mjs
   node tools/docgen/verify.mjs
   ~~~

2. 수정한 원고를 페이지에 반영합니다.

   ~~~powershell
   node tools/docgen/generate.mjs
   ~~~

3. 문서 도구의 보존·재현성 검사를 실행합니다.

   ~~~powershell
   node tools/docgen/selftest.mjs
   node --test tools/docgen/regression.test.mjs
   ~~~

4. 원고와 근거를 대조해 검토한 뒤 변경 사항을 PR로 올립니다. 검토 기록과 배포 절차는 [문서 도구 안내](https://github.com/TTolsun/hal-camera/blob/main/tools/docgen/README.md)를 따릅니다.

모든 배포 페이지에는 [공통 집필 규칙](https://github.com/TTolsun/hal-camera/blob/main/tools/docgen/style/README.md)을 적용합니다. 조건과 예외는 남기고, 독자가 먼저 할 일을 앞에 씁니다.

**다음 단계:** [아키텍처 문서](architecture.md)에서 수정할 기능과 연결된 파일을 찾으세요.
