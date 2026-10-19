---
title: Build
---
<h1 lang="en">Build the app.</h1>

**소스에서 APK를 만들어야 할 때만 이 절차를 사용하세요.** 배포된 앱을 사용하는 절차는 [Quickstart](getting-started.md)에 있습니다.

## 실행 전에 준비하세요

JDK 17과 Android SDK 36, `adb`가 필요합니다. 명령은 저장소 루트에서 실행합니다.

PowerShell에서 `$env:JAVA_HOME`을 JDK 설치 경로로 설정합니다.

SDK와 JVM 대상 버전은 [빌드 설정 상세](https://github.com/TTolsun/hal-camera/blob/main/guide/_reference/getting-started.md#앱-버전과-빌드-설정)에서 확인하세요.

Android SDK 경로는 저장소 루트의 `local.properties`에 설정합니다. 이 파일은 개인 환경 설정이므로 커밋하지 않습니다.

기기에서 USB 디버깅을 켜고 PC의 연결 요청을 허용한 뒤 `adb devices`로 연결 상태를 확인하세요.

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

4. 앱을 열고 카메라 권한을 허용합니다. Live에서 프리뷰가 나오는지 확인합니다.

PC에서 촬영·녹화·CTS를 실행하려면 [CLI](cli.md)의 연결 절차를 따르세요.

빌드·JVM 테스트 통과와 실제 기기 동작 검증은 별개입니다. 확인한 조건은 [Validation](evidence.md)에 기록합니다.

**다음 단계:** [Live](live.md)에서 설치한 앱의 첫 촬영을 확인하세요.
