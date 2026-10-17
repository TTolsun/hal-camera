# Android lint 전체 검토 — 2026-10-17

**0.29.2부터 Android lint는 오류 0개·경고 0개입니다.** 1차 검토에서 남긴 경고 132개를 2차 정리에서 코드 수정 77개와 근거를 적은 억제 55개로 처리했습니다. lint baseline 파일은 쓰지 않으며, 억제 근거는 `app/lint.xml`과 아래 표에 함께 남깁니다.

## 2차 정리 — 0.29.2

기준 `b6a0b6a6`에서 남은 경고 132개를 처리했습니다.

| 유형 | 개수 | 처리 |
| --- | ---: | --- |
| UseKtx | 72 | `toUri`, `toColorInt`, `isVisible`, `isNotEmpty`, `SharedPreferences.edit {}`, `createBitmap`, `scale`, `withClip`, `toDrawable`로 바꿨습니다. 동작은 같습니다. `DeviceInstance`는 동기 저장 성공값을 검사해야 하므로 `commit()`을 유지하고 그 함수만 억제했습니다. |
| SetTextI18n | 35 | 앱은 화면을 코드로 만들고 번역을 제공하지 않습니다. 일부 문구만 리소스로 옮기면 번역이 되는 것처럼 보이므로, `app/lint.xml`에서 유형 전체를 억제했습니다. 번역을 시작하면 이 억제를 제거합니다. |
| LockedOrientationActivity·DiscouragedApi | 20 | 카메라·CTS·benchmark 화면을 세로로 고정하는 것은 제품 결정입니다. 회전하면 측정 중에 Activity가 다시 만들어집니다. Android 16의 대화면(sw600dp 이상)에서는 고정이 무시될 수 있다는 한계를 `app/lint.xml`에 적고 억제했습니다. |
| MonochromeLauncherIcon | 2 | 로봇 외곽과 렌즈·상태 링·입을 단색 실루엣으로 그린 `ic_launcher_monochrome`을 추가했습니다. |
| GradleDependency | 2 | `activity-ktx`는 1.13.0으로, `core-ktx`는 1.18.0으로 올렸습니다. `core-ktx` 1.19 이상은 compileSdk 37과 AGP 9.1이 필요하므로, 그 알림만 `app/lint.xml`에서 억제했습니다. |
| InlinedApi | 1 | `KEY_MAX_B_FRAMES`는 API 26~28에서도 안전하게 전달되는 format 문자열입니다. `EncodedVideoTimeline`은 표시 순서로 출력되는 프레임이 필요하므로 모든 API에서 이 요청을 보냅니다. format을 만드는 함수만 억제했습니다. |

## 1차 검토 — 0.28.0

기준 `eaca83dc`의 경고 159개를 위치별로 검토했습니다. 실제 수정 9개와 오탐의 한정 억제 18개를 반영해 오류 0개·경고 132개가 남았습니다. [159개 개별 판정 CSV](lint-audit-20261017.csv)는 기준 소스의 경로·행과 각 판단 근거를 담습니다. 동일 행에서 여러 진단이 나온 경우도 원래 개수대로 남겼습니다. 아래 표에서 "유지"로 적은 항목은 2차 정리에서 처리했습니다.

### 유형별 판단

| 유형 | 기존 개수 | 처리 |
| --- | ---: | --- |
| ExifInterface | 3 | AndroidX EXIF 1.4.2로 전환했습니다. Gallery의 API 26·27 스트림 판독과 DNG 방향 상수가 대상입니다. |
| SwitchIntDef | 2 | 회전 숫자를 Surface.ROTATION_*로 교체했습니다. |
| DrawAllocation | 2 | Live 제어판 연결선과 결과 막대 화살표의 Path를 보관하고 rewind하여 재사용합니다. |
| DataExtractionRules | 1 | 기존 백업 금지 정책을 유지하고 Android 12 이상 cloud-backup·device-transfer의 모든 앱 데이터 영역을 명시적으로 제외합니다. 구형 버전에는 fullBackupContent=false를 지정합니다. |
| ObsoleteSdkInt | 1 | minSdk와 같은 v26 아이콘 디렉터리를 mipmap-anydpi로 옮겼습니다. |
| InlinedApi | 3 | 단순 줄바꿈과 EXTERNAL metadata 정수는 구버전에도 안전하게 비교·전달할 수 있는 인라인 값입니다. 최신 SDK의 IntDef와 맞는 이름을 유지하고 해당 함수에만 설명과 억제를 추가했습니다. |
| ClickableViewAccessibility | 3 | Gallery는 확인된 단일 탭에서 performClick을 호출합니다. 셔터는 터치를 소비하지 않고 Button에 넘기며, Split은 짧은 탭을 넘기고 드래그에 별도 접근성 액션을 제공합니다. 중복 클릭을 추가하지 않고 해당 함수만 억제했습니다. |
| ViewConstructor | 10 | XML에서 inflate하지 않고 필수 값·콜백을 주입하는 뷰입니다. 무의미한 XML 생성자를 만들지 않고 각 클래스에 근거를 적었습니다. |
| StaticFieldLeak | 1 | private 생성자는 get()의 applicationContext만 받습니다. Activity context를 보관한다는 진단을 해당 필드에서 억제했습니다. 별도 host의 attach/detach 수명주기는 유지합니다. |
| CustomSplashScreen | 1 | CliLaunchActivity는 UI 없는 BUSY 확인 gate이며 splash 화면이 아닙니다. 클래스에 근거를 적었습니다. |
| UseKtx | 73 | 동등한 문법 권고는 유지했습니다. 특히 DeviceInstance의 commit 성공값 검사를 KTX 편집 호출로 잃어버리지 않도록 보존했습니다. |
| SetTextI18n | 35 | 현재 고정 언어와 측정 표기의 리소스화 권고입니다. 일부 문구만 리소스로 옮겨 앱 전체 번역이 된 것처럼 처리하지 않고 경고를 유지했습니다. |
| LockedOrientationActivity·DiscouragedApi | 20 | 회전 허용은 실행 중 Activity 재생성과 카메라·CTS 상태 보존을 함께 다뤄야 합니다. Android 16 대화면에서 잠금이 무시될 수 있다는 제약을 남겼으며 억제하지 않았습니다. |
| GradleDependency | 2 | 최신 버전 알림 자체는 결함이 아니므로 Activity/Core의 고정 버전을 유지했습니다. 보안 취약점 감사를 수행했다는 의미는 아닙니다. |
| MonochromeLauncherIcon | 2 | 테마 아이콘용 단색 디자인이 아직 없습니다. 현재 컬러 로봇의 alpha만 재사용하면 내부 형상이 없어지므로 경고를 유지했습니다. |

### 1차 검증

- debug APK·instrumentation APK 빌드, JVM 테스트 828개와 lint가 통과했습니다. 백업 제외 정책과 DNG 방향 태그 계약에 JVM 회귀 검사를 추가했습니다. 문서 테스트 33개와 문서 파이프라인 6단계도 통과했습니다.
- SM-S936N, Android 16에서 별도 `dev.halcamera.lintverify` 패키지를 사용했습니다. 기존 앱을 삭제하거나 데이터를 초기화하지 않았습니다.
- `am instrument -w -e lint_checks true dev.halcamera.lintverify.test/dev.halcamera.cli.CliStoreInstrumentation`이 `LINT_REGRESSION_OK`를 반환했습니다. JPEG EXIF 방향값 1–8의 스트림 판독, Gallery·셔터의 터치 클릭과 접근성 클릭, Split 핸들 탭, 30회 결과 막대 그리기를 확인했습니다. 임시 JPEG는 앱 캐시에만 만들고 삭제했습니다.
- 첫 테스트는 화면에 붙지 않은 Button의 비동기 클릭을 즉시 검사해 실패했습니다. 실제 Activity에 뷰를 붙이고 메시지 처리를 기다리도록 테스트를 수정한 뒤 통과했습니다. 제품의 클릭 처리에는 변경이 없습니다.
- 실제 Live 프리뷰와 EV 패널을 열어 Path 재사용 후 연결선이 정상적으로 그려지는지 확인했습니다. [기기 화면](assets/lint-ev-20261017.png)을 함께 보관합니다.
- Android 8·8.1 실기기, TalkBack 사용 흐름 전체, 실제 OS 백업 복원, 대화면 회전은 이번 기기 검증에 포함되지 않습니다. XML 정책 테스트는 OS 전송 시험을 대신하지 않습니다.
- 코드와 문서 리뷰는 사용자의 요청에 따라 Codex가 수행했습니다. 문서 검토 기록은 사람의 승인 기록을 의미하지 않습니다.

EXIF 버전 근거는 [AndroidX 릴리스 문서](https://developer.android.com/jetpack/androidx/releases/exifinterface), 백업 규칙 근거는 [Android Auto Backup 문서](https://developer.android.com/identity/data/autobackup)입니다. 재검증 명령과 문서 생성 절차는 [저장소 기여 규칙](../../AGENTS.md)을 참고하세요.
