# 녹화 중 사진의 실패·정지 경합 검증

CameraX에서 JPEG를 받은 뒤 저장이 5초를 넘으면 사진 수신 타임아웃이 먼저 실패를 알리고 실제 파일은 뒤늦게 저장될 수 있었습니다. 수신 대기와 저장 중 상태를 분리해 이 경합을 수정했습니다. 정지·화면 종료·저장 실패 뒤에도 요청 결과는 한 번만 전달하고 다음 촬영을 받을 수 있도록 검증했습니다. [이슈 #220](https://github.com/TTolsun/hal-camera/issues/220)의 기록입니다.

## 기준과 변경

- 기기는 Galaxy S25+ SM-S936N이며 Android 16을 사용했습니다. 검증 날짜는 2026년 10월 6일(KST)입니다.
- 기준 소스는 `f866ded`이고, 검증 브랜치는 `codex/issue220-snapshot-races`입니다. 앱 버전은 기존과 같은 0.19.0(628)이므로 버전 문자열만으로 수정 전후를 구별할 수 없습니다.
- 기존 서명으로 release 앱과 테스트 APK를 빌드하고 `adb install -r`로 갱신했습니다. 앱 삭제나 기존 실행 데이터 삭제는 하지 않았습니다.
- `SnapshotRequest`는 WAITING → SAVING → DONE을 원자적으로 전환합니다. 타임아웃과 종료는 WAITING만 끝낼 수 있습니다. 늦거나 중복된 이미지와 오류 콜백은 저장이나 완료 통지를 반복하지 못합니다.
- CameraX 녹화 세션 해제와 카메라 종료에서 아직 수신 중인 사진을 중단합니다. 이미 복사한 JPEG는 저장 결과를 기다립니다. Camera2는 이미지 바이트 읽기 예외를 실패로 전달하고 버퍼를 닫습니다.
- Camera2의 세션 거부 재시도 상태를 `SnapshotSessionRetry`로 분리했습니다. 같은 거부 콜백을 중복 처리하지 않으며, 교체된 세션의 늦은 종료가 새 녹화를 끝내지 않도록 합니다.

CameraX 사진 시점의 영상 프레임 간격 증가는 이번 수정 대상이 아닙니다. 해당 제약은 [이전 검증 기록](video-snapshot-20261005.md)에 유지하고 앱에 새 경고를 추가하지 않았습니다.

## JVM 검증

`SnapshotRequestTest` 7개와 `SnapshotSessionRetryTest` 4개가 다음 조건을 검사합니다.

| 조건 | 확인한 결과 |
| --- | --- |
| 수신 뒤 저장 지연, 타임아웃, 정지 | 저장 전에는 성공·실패를 알리지 않고 실제 저장 결과만 전달합니다. |
| 수신 전 정지 또는 타임아웃 | 한 번 실패하고 늦은 이미지는 저장하지 않습니다. |
| 중복 이미지·늦은 오류·중복 저장 완료 | 결과와 저장 소유권을 중복 획득하지 못합니다. |
| 이미지 수신과 타임아웃의 동시 실행 | 두 스레드의 경합 100회에서 결과가 정확히 한 번 전달됩니다. |
| JPEG 세션 거부 | JPEG 자원을 해제한 뒤 사진 없는 세션을 한 번 재시도합니다. |
| 정지·카메라 종료·사진 없는 세션 거부 | 새 재시도를 만들지 않습니다. |
| 재시도 생성 실패 | 교체된 세션의 종료가 실패를 다시 처리하지 않습니다. |

이 테스트는 순수 상태 및 콜백 소유권 검증이며 실제 HAL의 지원 조합 판정이 아닙니다.

전체 JVM 검사는 앱 635개와 `ctsvendor` 12개, 합계 647개를 통과했습니다. `lintDebug`, release 앱·기기 테스트 APK 빌드, 문서 회귀 검사 23개와 문서 6단계 검사도 통과했습니다. 문서의 `Codex-issue220-code-review` 표기는 코드와 기존 설명의 대조 기록이며 사람의 승인이나 추가 기기 실측을 뜻하지 않습니다. APK SHA-256과 검증 환경은 [빌드 요약](assets/video-snapshot-failures-20261006/build-summary.json)에 있습니다.

## Android 실패 주입 검증

`SnapshotFailureChecks`는 실제 `Handler`, `CameraXVideoSnapshot`, `MediaLibrary`를 사용합니다. CameraX 이미지 공급은 가짜 `ImageProxy`로 제어하고, `ContentResolver.wrap`으로 연결한 시험용 provider가 파일을 앱 캐시의 전용 임시 디렉터리에 씁니다. 시험 파일은 종료 시 정리합니다. 사진용 바이트는 픽셀 검증용 JPEG가 아니라 저장 경로를 검사하는 작은 표본입니다.

| 시나리오 | 주입과 확인 |
| --- | --- |
| 저장이 수신 제한을 넘김 | 실제 저장 실행기를 5.2초 지연하고 `release`를 호출했습니다. BUSY를 유지하고 두 번째 촬영을 거절했으며, 저장 후 성공 한 번만 알렸습니다. |
| 저장 항목 열기 실패 | ENOSPC 예외를 주입했습니다. 실패를 알리고 생성한 항목을 삭제했으며 `media_saved`를 추가하지 않았습니다. |
| 쓰기 실패 | 읽기 끝을 닫은 파이프에 실제 쓰기를 시도해 EPIPE가 발생했습니다. 성공을 알리지 않고 항목을 삭제했습니다. |
| 공개 실패 | provider의 `update`가 0을 반환했습니다. `Cannot publish media entry`를 알리고 항목을 삭제했습니다. |
| 이미지 읽기 실패 | plane 접근에서 예외를 냈습니다. 이미지가 한 번 닫히고 촬영 자리가 해제됐습니다. |
| 실행기 종료 | `RejectedExecutionException`을 주입했습니다. 실패 후 BUSY가 해제됐습니다. |
| 사진 수신 전 카메라 종료 | 중단을 한 번 알렸습니다. 뒤늦은 이미지와 오류를 추가로 전달해도 저장·완료가 중복되지 않았습니다. |
| 사진 미도착 | 실제 5초 타임아웃 뒤 이미지를 전달했습니다. 실패 한 번, 이미지 닫기 한 번이며 저장은 없었습니다. |
| 다음 촬영과 중복 이미지 | 실패 뒤 새 요청이 성공했습니다. 동일 요청의 두 번째 이미지는 닫고 파일은 한 번만 썼습니다. |
| 사진 쌍 부분 실패 | 두 번째 항목 생성을 실패시켰습니다. 앞서 쓴 첫 항목도 삭제했습니다. |
| Camera2 JPEG 세션 거부 | 아래의 실제 카메라 재구성 검증을 수행했습니다. |

Camera2 검증은 `Camera2SnapshotFallbackCheck`가 후면 0을 열고 실제 preview·encoder·JPEG 세션의 콜백을 **한 번 거부로 바꾸어 주입**합니다. 그 뒤의 세션 생성은 가로채지 않습니다. 실제 `Camera2LiveRecorder`가 출력 3개에서 JPEG 없는 2개로 다시 구성하고, H264 1280×720 영상을 2초 동안 녹화해 저장한 뒤 프리뷰로 돌아왔습니다. `MediaMetadataRetriever`로 영상 트랙, 1280×720 크기와 1초 이상의 길이를 검사합니다. 이 결과는 HAL이 자연스럽게 그 조합을 거부했다는 뜻이 아닙니다.

확인한 안내 문구는 다음과 같습니다.

- `Snapshot saved. The show goes on.`
- `Snapshot failed: ENOSPC: injected storage failure`
- `Snapshot failed: write failed: EPIPE (Broken pipe)`
- `Snapshot failed: Cannot publish media entry`
- `Snapshot failed: The camera closed before the photo could be saved.`
- `Snapshot failed: Camera closed: photo capture was interrupted.`
- `Snapshot failed: No photo arrived within 5 seconds.`
- `Recording without snapshots: this camera cannot combine video and JPEG.`

시험 출력은 [실패 주입 로그](assets/video-snapshot-failures-20261006/snapshot-failure-checks.txt)에 있습니다. 테스트의 완료 콜백 수와 안내 수를 대조했으며, 실패 뒤 성공한 요청까지 확인했습니다.

## 실제 UI와 하드웨어 관찰

| 엔진·조건 | 결과 |
| --- | --- |
| CameraX, 후면 0, preview 1920×1080, JPEG 4080×3060, 영상 3840×2160·30fps·Auto | 사진 포함 bind가 거부되어 `snapshot: null`인 녹화가 시작됐습니다. 사진 버튼의 설명은 `Recording without snapshots: CameraX cannot combine video and photos.`였습니다. 정지 후 MP4 저장에 성공했습니다. JPEG 수동 off를 사용하지 않았습니다. |
| Camera2, 후면 0, preview 1920×1080, JPEG 4080×3060, 영상 3840×2160·30fps·HEVC | JPEG를 포함한 세션을 수락했습니다. 자연적인 거부 재현으로 합산하지 않습니다. |
| Camera2, 후면 0, preview·JPEG·영상 모두 4080×3060, 30fps·HEVC | 이 조합도 수락하고 녹화했습니다. 거부 경로는 위의 별도 주입 시험으로 확인했습니다. |
| Camera2, JPEG·영상 1920×1080, 사진 버튼 직후 정지 | 새 JPEG 1개와 MP4 1개를 저장하고 프리뷰로 복귀했습니다. |
| CameraX, JPEG·영상 1920×1080, 사진 버튼 직후 정지 | 새 JPEG 없이 MP4 1개를 저장하고 프리뷰로 복귀했습니다. |
| CameraX, 사진 버튼 직후 홈 화면 이동 | 새 JPEG 1개와 MP4 1개를 저장했습니다. 이후 CameraX JPEG 촬영에 성공했습니다. |
| Camera2, 사진 버튼 직후 홈 화면 이동 | 녹화 상태를 화면으로 확인한 뒤 실행했습니다. 새 JPEG 1개와 MP4 1개를 저장했고, 이후 Camera2 JPEG 촬영에도 성공했습니다. |

사진 직후 정지는 같은 ADB 셸에서 사진 버튼과 정지 버튼을 순서대로 눌렀습니다. 동시 입력이나 정확한 요청 간격을 주장하지 않습니다. UI의 마지막 안내는 `Video saved. That's a wrap.`였으므로, 사진 실패 안내가 실제 화면에 보인 시간과 중간 안내 순서는 포착하지 못했습니다. 사진 실패 문구와 단일 완료는 위의 결정론적 시험으로 확인한 것입니다.

## 검증 범위와 재현

실기기의 저장 공간을 실제로 채우지 않았습니다. ENOSPC·쓰기 실패·공개 실패는 통제한 provider에서 검증했으며, 저장 장치 전체가 부족한 상황에서의 시스템 동작이나 프로세스 강제 종료까지 PASS로 처리하지 않습니다. 검증한 기기는 한 대입니다. 미지원으로 건너뛴 조건은 없으며, Camera2의 자연적인 JPEG 조합 거부는 재현되지 않았습니다.

1. JVM과 lint는 JDK 17, Android SDK 36에서 `gradlew testDebugUnitTest lintDebug`로 실행합니다. Windows에서는 `gradlew.bat`을 사용합니다.
2. 앱과 기기 테스트 APK를 **같은 서명**으로 빌드해 데이터 보존 업데이트를 합니다. 기본 debug 환경에서는 `gradlew :app:assembleDebug :app:assembleDebugAndroidTest`를 사용합니다. 기존 release 앱에 debug APK를 설치하려고 앱을 삭제하지 않습니다.
3. Android 10 이상이며 후면 ID 0의 JPEG·H264 1280×720을 지원하는 검증 기기에서 다음을 실행합니다. 실행 중에는 앱을 조작하지 않습니다. Camera2 부분은 실제 카메라를 사용하고, CameraX 사진 공급·저장 오류 부분은 결정론적 주입입니다.

```text
adb shell am instrument -w -e snapshot_failures true dev.halcamera.test/dev.halcamera.cli.CliStoreInstrumentation
```

성공 출력은 `SNAPSHOT_FAILURE_CHECKS_PASSED=11`입니다. `SNAPSHOT_FAILURE_CHECKS_FAILED`가 있으면 실패이며 셸 종료 코드만으로 판정하지 않습니다. release 기기 검증에는 로컬 Gradle init script의 `android.testBuildType = 'release'`를 적용해 `:app:assembleRelease :app:assembleReleaseAndroidTest`를 빌드했습니다. 서명 설정과 init script는 커밋하지 않습니다.

4. UI 경합은 CLI `preview`가 완료된 뒤 Video 모드에서 시작합니다. CLI `record.start`가 실행 중이면 사진 버튼은 비활성화되므로 이 상태의 탭을 사진 촬영 검증으로 세지 않습니다. 각 실행 전후 파일 이름을 비교하고, 녹화 중임을 확인한 뒤 정지나 홈 이동을 수행합니다.

다음으로 [엔진의 실패·정지 처리](../../guide/engine.md#녹화-중-사진의-실패와-정지)를 확인하세요.
