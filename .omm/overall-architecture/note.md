빌드와 테스트 진입점은 다음과 같습니다.

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — `lint { abortOnError = true }`이므로 lint 실패가 빌드를 중단시킵니다.
- `local.properties`는 필수이며 gitignore 대상입니다. `sdk.dir`이 Android SDK를 가리켜야 하고, 콜론을 escape해야 합니다(`sdk.dir=C\:/path/to/android-sdk`). 그렇지 않으면 lint의 PropertyEscape 검사가 실패합니다.
- `app/src/test/`의 단위 테스트는 JVM 전용이며, 평가 계층의 실질적인 명세 역할을 합니다. 실제 로직이 있는 부분은 `RunValidityTest`, `RegressionRulesTest`, `BenchmarkEvaluatorTest`, `BenchmarkReportCodecTest`, `ThresholdEngineTest`, `MetricExtractorTest`, `AutoCheckRunnerTest`가 덮고 있습니다.
- 의존성은 의도적으로 적습니다. `activity-ktx`, `core-ktx`, CameraX 아티팩트 네 개(1.6.2), JUnit 4뿐이며, 플랫폼 위젯 외의 UI 프레임워크는 사용하지 않습니다.

이름에 관한 사항입니다. 제품명이 Camera BenchMarker로 바뀌었는데도 applicationId, 패키지, Gradle 루트 프로젝트는 여전히 `dev.halcamera` 및 `HALCamera`입니다. applicationId를 유지하는 것은 v0.3 계획의 명시적 결정이며, 이름을 바꾸더라도 설치된 실행 기록과 저장된 baseline이 살아남도록 하기 위해서입니다.

이 문서는 main의 `1e3d2a0` 시점을 기준으로 작성되었습니다(PR #12 후속 수정과 `.gitignore` 변경이 병합된 상태). 큰 변경 뒤에는 `omm scan`으로 다시 생성하십시오. `.omm/`은 gitignore 대상이므로 저장소에는 포함되지 않습니다.
