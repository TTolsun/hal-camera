# ctsvendor: AOSP CTS 카메라 테스트를 앱 안에서 실행하는 모듈

이 모듈은 AOSP CTS의 camera2 테스트 소스를 그대로 가져와(vendoring) 앱 프로세스 안의 JUnit 4로 실행합니다. `src/main/java/android/`과 `src/main/java/com/android/` 아래는 업스트림 파일이고, `src/main/java/dev/halcamera/ctsvendor/`와 `src/main/java/androidx/test/` 아래는 그 파일들이 instrumentation 없이 돌도록 만드는 이 저장소의 코드입니다. 라이선스는 Apache 2.0이며 전문은 `LICENSE`에 있습니다.

## 업스트림 출처

| 저장소 | 브랜치 | 커밋 | 가져온 경로 |
|---|---|---|---|
| `platform/cts` | `android16-release` | `d3cf8ecdfe20325e5d0815c9ef527b332b041d61` | `tests/camera/src/android/hardware/camera2/cts/{RecordingTest,Camera2SurfaceViewCtsActivity}.java`, `tests/camera/src/android/hardware/camera2/cts/testcases/Camera2SurfaceViewTestCase.java`, `tests/camera/utils/src/**`, `tests/camera/res/layout/surface_view_2.xml` |
| `platform/frameworks/ex` | `android16-release` | `06933c05c643430497ea48c713db04c0feb70d2e` | `camera2/public/src/com/android/ex/camera2/**` |

이 조합은 업스트림 `tests/camera/Android.bp`의 `cts-camera-performance-tests` 라이브러리(`min_sdk_version: 34`)와 같은 파일 집합에 `CtsCameraUtils`를 더한 것입니다. 그래서 `VendoredCts.MIN_SDK`는 34이고, 앱은 그 아래 기기에서 이 모듈로 진입하지 못하게 막습니다.

## 업스트림 파일에 가한 패치

모든 패치 줄에는 `halcamera` 주석이 있습니다. `grep -rn halcamera src/main/java/android src/main/java/com/android`로 전체를 볼 수 있습니다.

1. `Camera2SurfaceViewCtsActivity.java`: `import android.camera.cts.R` → `dev.halcamera.ctsvendor.R`. 레이아웃 리소스가 이 모듈의 namespace로 옮겨졌기 때문입니다.
2. `Camera2SurfaceViewTestCase.java` `setUp()`/`tearDown()`: `VendoredCts.attachTest(this)`/`detachTest(this)` 호출을 추가했습니다. 중단 버튼이 실행 중인 테스트의 `mCamera`를 닫아 테스트를 실패시키는 데 씁니다. JUnit은 실행 중인 테스트 본문을 중단할 수단이 없습니다.
3. `CameraManager.getCameraIdListNoLazy()` → `getCameraIdList()` (`Camera2ParameterizedTestCase`, `CameraTestUtils`, `CameraMetadataGetter`, `CameraUtils`). `getCameraIdListNoLazy`는 `@TestApi`라서 일반 앱에서 호출할 수 없습니다. lazy HAL 카메라가 없는 휴대전화에서는 두 목록이 같습니다.
4. `CameraTestUtils.java` `BlockingCameraManager.OpenListener`: `openCamera(String, boolean overrideToPortrait, Handler, StateCallback)`은 `@TestApi`이므로 공개 `openCamera(String, StateCallback, Handler)`로 바꾸고, `overrideToPortrait=true` 요청은 `UnsupportedOperationException`을 던집니다. 가져온 테스트 중 true를 넘기는 곳은 없습니다.
5. `StaticMetadata.java`: `sharedSessionConfigurationPresent()`와 `getSharedSessionConfiguration()`을 제거했습니다. `CameraCharacteristics.SHARED_SESSION_CONFIGURATION`은 `@FlaggedApi`라서 SDK 36 공개 stub에 없습니다.
6. `CameraUtils.java` `isDeviceFoldable()`: 본문을 `return false`로 바꿨습니다. `DeviceStateManager`가 공개 stub에 없습니다. 접히는 기기에서는 폴더블 전용 검사가 빠집니다.
7. `CameraParameterizedTestCase.java` `data()`: `adoptShellPerm=true` 행을 만들지 않습니다. 일반 앱에는 shell 권한을 채택할 `UiAutomation`이 없고, 그 행은 시스템 카메라만 검사합니다. 업스트림은 `perf-measure=on` 인수가 있을 때만 이 행을 생략하는데, 그 인수는 `RecordingTest`가 가장 큰 프로파일 하나만 검증 없이 녹화하게 만들므로 쓰지 않습니다(첫 실기기 실행에서 4대 카메라가 16초 만에 PASS로 끝나 발견).

## 업스트림 의존성을 대신하는 파일

- `com/android/compatibility/common/util/MediaUtils.java`: `compatibility-device-util-axt`의 `MediaUtils` 중 `checkCodecForDomain` 하나만 구현했습니다.
- `com/android/internal/camera/flags/Flags.java`: aconfig가 생성하는 카메라 플래그 클래스 대신, 각 플래그가 공개된 API 레벨 이상이면 true를 돌려줍니다.
- `androidx/test/InstrumentationRegistry.java`, `androidx/test/rule/ActivityTestRule.java`, `androidx/test/filters/LargeTest.java`: androidx.test 라이브러리를 앱에 넣지 않고, 같은 이름의 클래스로 `VendoredCts`가 등록한 Instrumentation과 host Activity를 돌려줍니다. 이 저장소에 androidx.test 의존성을 추가하면 클래스가 충돌하므로 추가하지 않습니다.
- Mockito는 `CameraTestUtils`와 `CameraSessionUtils`가 참조하므로 `mockito-android`를 의존성으로 둡니다. 가져온 테스트 본문 중 mock을 쓰는 것만 이 경로를 탑니다.

## 실행 모델

1. `VendoredCts.install(context)`가 빈 인수를 가진 Instrumentation을 등록합니다. 인수는 static 초기화에서 읽히므로 테스트 클래스를 로드하기 전에 호출해야 합니다. `camera-id`를 주면 그 뒤 모든 실행이 한 카메라에 고정되고, `perf-measure=on`을 주면 녹화 테스트가 검증을 건너뛰므로 둘 다 주지 않습니다.
2. 앱의 `VendoredCaseActivity`는 업스트림 `Camera2SurfaceViewCtsActivity`를 상속하고 `VendoredCts.attachActivity(this)`로 자신을 등록합니다. `ActivityTestRule.getActivity()`는 그 인스턴스를 돌려줍니다.
3. `VendoredRun`이 `Request.aClass(...).filterWith(메서드 필터)`로 JUnit runner를 만들고 `RunNotifier`로 결과를 받습니다. `VendoredCatalog`는 reflection으로 `@Test` 메서드를 나열하므로, 테스트 클래스를 추가하려면 파일을 복사하고 `VendoredCatalog.classes`에 넣기만 하면 됩니다.

## 알려진 한계

- 결과는 테스트 메서드 하나에 PASS·FAIL 하나입니다. 카메라별·프로파일별 세부는 CTS가 `Log`로만 남기며, 앱은 logcat을 읽을 권한이 없습니다.
- `UiAutomation`, `@TestApi`, JNI(`NativeCamera*Test`)를 쓰는 테스트는 이 모듈에서 돌지 않습니다. 컴파일 오류가 그 목록을 알려 주므로, 새 클래스를 가져올 때 오류가 난 호출을 위 패치처럼 공개 API로 바꾸거나 해당 메서드를 제거합니다.
- 공식 판정은 cts-tradefed가 쓰는 `test_result.xml`뿐입니다. 이 모듈의 결과는 참고용입니다.

## 다시 동기화하는 절차

1. 위 표의 경로를 새 브랜치에서 받습니다. 디렉터리 단위 tarball은 `https://android.googlesource.com/platform/cts/+archive/refs/heads/<branch>/tests/camera.tar.gz` 형식으로 받을 수 있습니다.
2. 파일을 덮어쓴 뒤 `grep -rn halcamera`로 패치 목록과 대조해 다시 적용합니다.
3. `./gradlew :ctsvendor:compileDebugJavaWithJavac`로 새 `@TestApi`·`@FlaggedApi` 호출을 찾아 같은 방식으로 처리하고, 위 표의 커밋 해시를 갱신합니다.
