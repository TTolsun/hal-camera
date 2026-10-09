---
title: Build
---
<h1 lang="en">Build the app.</h1>

**소스에서 APK를 만들어야 할 때만 이 절차를 사용하세요.** 배포된 앱을 사용하는 절차는 [시작하기](getting-started.md)에 있습니다.

## 실행 전에 준비하세요

JDK 17과 Android SDK 36, `adb`가 필요합니다. PowerShell에서 `$env:JAVA_HOME`을 JDK 설치 경로로 설정합니다. SDK와 JVM 대상 버전은 [아래 빌드 정보](getting-started.md#앱-버전과-빌드-설정)를 확인하세요.

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

4. 앱을 열고 카메라 권한을 허용합니다. Live에서 프리뷰가 나오는지 확인합니다.

PC 터미널에서 촬영·녹화·CTS를 실행하려면 [CLI](cli.md)를 따르세요. ADB CLI는 기본으로 허용되며, 직접 꺼 둔 경우에만 앱의 `Lab → ADB CLI`에서 다시 켭니다. PC에는 `adb`만 있으면 되며, 프리뷰·사진·녹화 명령과 무선 연결 절차도 그 페이지에 있습니다.

빌드와 테스트가 통과하면 APK 생성과 JVM 테스트 결과를 확인한 것입니다. 측정 정확성과 기기 동작은 별도로 검증해야 합니다. 검증 자료의 구분과 기록 위치는 [Evidence](evidence.md)를 확인하세요.



**다음 단계:** [Live](live.md)에서 설치한 앱의 첫 촬영을 확인하세요.
