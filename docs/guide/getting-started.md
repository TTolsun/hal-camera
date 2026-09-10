---
title: 개발 환경 및 빠른 시작
---
# 개발 환경 및 빠른 시작

저장소 루트에서 Gradle Wrapper를 사용합니다. SDK·앱 버전은 [디버깅 문서의 빌드 정보](troubleshooting.md)에서 확인하세요. Android SDK 경로는 커밋하지 않는 local.properties에 설정합니다.

~~~powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
~~~

앱을 실행하고 카메라 권한을 허용한 뒤 LIVE 화면의 카메라 프리뷰와 BENCHMARK 진입을 확인합니다. 실제 기기 검증 결과는 별도 기록하며, 빌드 성공만으로 측정 정확성을 보장하지 않습니다.

문서 파이프라인은 Node.js 24를 사용합니다.

~~~text
node tools/docgen/extract.mjs
node tools/docgen/verify.mjs
node tools/docgen/generate.mjs
node tools/docgen/selftest.mjs
node --test tools/docgen/regression.test.mjs
~~~

검토 기록은 원고와 근거를 확인한 뒤에만 남깁니다. 자세한 변경·검토 절차는 저장소의 tools/docgen/README.md를 참고하세요.
