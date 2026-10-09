---
title: 검증 기록
---
<h1 lang="en">Trust starts with a trace.</h1>

**코드로 확인한 동작과 기기에서 관찰한 결과를 구분하세요.** 아래에서 근거의 종류와 기기 검증 기록을 확인하세요. 문서 갱신 절차는 [문서 관리](contributing.md)에 있습니다.

## 근거와 검토 범위

| 근거 수준 | 의미 | 확인할 자료 |
| --- | --- | --- |
| 코드 확인 | 구현을 읽고 설명과 대조했습니다. 기기 실측을 의미하지 않습니다. | 각 페이지의 코드 근거와 PR 검토 기록을 확인합니다. |
| 기기 검증 | 명시된 기기·빌드·조건에서 관찰한 결과입니다. | 아래의 기기 검증 자료를 확인합니다. |
| 설계 의도 | 과거 결정 기록에 명시된 선택입니다. 현재 구현과 다를 수 있습니다. | [설계 결정 원문](_inputs/decisions.md)의 D-번호를 확인합니다. |

[Architecture](architecture.md)·[Engine](engine.md)·[디버깅](troubleshooting.md)과 이 페이지 끝의 **문서 검토 상태**에는 해당 페이지에 연결된 구조 근거와 생성 원고의 기준 버전·검토 커밋이 있습니다. 관련 소스나 원고가 바뀌면 다시 검토해야 합니다. 이 상태는 수동으로 작성한 Probe·CTS·Benchmark·Callback 등 다른 페이지의 검토까지 보장하지 않습니다.

## 기기 검증 기록

검증 날짜, 앱 빌드, 기기와 실행 조건을 함께 확인하세요. 앱 화면 촬영 기록과 이전 기기 검증 기록은 수행 시점과 확인 범위가 다릅니다.

### 앱 화면 촬영

2026년 9월 27일 Galaxy S25+(SM-S936N)·Android 16(API 36)에서 배포된 0.15.0(versionCode 543)을 실행해 16개 화면을 촬영했습니다. 원본은 1440×3120 PNG이며, 각 기능의 담당 문서에 한 번씩 배치했습니다.

Live·CameraX 전환·사진 자동 고정·Recording 콜백·Probe 조회·CTS 실행·Benchmark 완료와 기록 저장을 확인했습니다. CTS 원문 목록, CLI 허용 설정, ZIP 기록은 화면 조회만 확인했습니다. 자세한 절차와 한계는 [촬영 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/2026-09-27-app-screenshots.md)에 있습니다. 화면에 보이는 수치는 해당 순간의 관찰이며 기기의 대표 성능값이 아닙니다.

2026년 9월 29일 같은 기기의 Lab 디자인 적용 빌드로 Probe, CTS 4개, Benchmark 4개, CLI 설정, ZIP 목록의 기존 이미지 11개를 교체했습니다. 설치 빌드는 기존 데이터 보존을 위해 versionCode 590을 유지했습니다. Rapid Open / Close는 35초 PASS로 완료했고, Benchmark 완료·결과·기록을 확인했습니다. Live 관련 이미지는 이전 촬영본입니다. [Lab 검증 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/lab-ui-20260929.md)에서 확인 범위를 구분합니다.

2026년 9월 30일 같은 기기의 0.16.0(versionCode 593) 디자인 변경 빌드로 Live·Live 제어·CameraX·CLI 설정·ZIP 목록의 이미지 5개를 교체하고 Gallery·About·기기 정보·ZIP 작업 화면 7개를 추가했습니다. Gallery 첫 타일의 모서리를 실기기 리뷰에서 수정했습니다. Callback의 촬영·녹화 화면은 9월 27일 당시 검증 기록이며 이번 UI 변경의 검증 자료가 아닙니다. [Gallery·Lab UI 리뷰와 촬영 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/gallery-lab-ui-20260930.md)에서 코드 리뷰와 기기 관찰을 확인하세요.

### 녹화 중 사진

2026년 10월 5일 Galaxy S25+(SM-S936N)·Android 16의 0.19.0(versionCode 628)에서 후면·전면·초광각 녹화 중 사진을 확인했습니다. CameraX 1.6.2에서는 사진 시점에 약 두 프레임 길이의 영상 간격이 생겼고, Camera2 1080p 30fps에서는 사진 3장씩을 저장해도 같은 간격 증가가 없었습니다. HEVC 60fps와 정지 직후의 사진 결과, 공식 소스에서 확인한 요청 출력 구성, 남은 검증 조건은 [녹화 중 사진 후속 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/video-snapshot-20261005.md)에 있습니다. CameraX의 영상 간격 증가는 알려진 제약으로 수용하고 문서에만 안내합니다. 남은 실패 경로와 기기 조합은 [#220](https://github.com/TTolsun/hal-camera/issues/220)과 [#221](https://github.com/TTolsun/hal-camera/issues/221)에서 추적합니다.

### CTS 원문 케이스

Galaxy S25+·Android 16에서 2026년 9월 17~19일, versionCode 106~108로 수행한 기록입니다. versionCode 108의 SKIP 판정을 적용한 결과는 40개 메서드 중 **PASS 33 · FAIL 1 · SKIP 6**입니다. 여러 실행과 재실행을 합친 기록입니다. 원본 요청 ID와 로그 해석은 [STATUS.md](https://github.com/TTolsun/hal-camera/blob/main/docs/STATUS.md)에 있습니다.

| 클래스 | 결과 | 확인한 내용 |
| --- | --- | --- |
| `RecordingTest` | PASS 14 · SKIP 2 | `testBasic10BitRecordingAV1`은 AV1 10비트 인코더와 업스트림의 AV1 매핑이 없었으며, `testSlowMotionRecording`은 모든 카메라에 `HIGH_SPEED_VIDEO` 지원이 없어 건너뛰었습니다. `testVideoSnapshot`은 9분 52초, `testSupportedVideoSizes`는 4분 46초가 걸렸습니다. |
| `StillCaptureTest` | PASS 17 · FAIL 1 · SKIP 3 | `testAeCompensation`은 카메라 0의 노출 시간 범위 초과와 카메라 2의 AE 보정 미적용으로 실패했습니다. HEIC·HEIC UltraHDR·dynamic depth 미지원으로 관련 세 메서드는 건너뛰었습니다. `testStillPreviewCombination`은 18분 29초가 걸렸습니다. |
| `BurstCaptureTest` | PASS 2 · SKIP 1 | 정지 영상 bokeh를 지원하지 않아 `testYuvBurstWithStillBokeh`를 건너뛰었습니다. |

이 기록에서 초기화 단계의 `UiAutomation`·`@TestApi` 실패는 없었습니다. 공식 CTS 인증 결과는 아니며 다른 기기나 현재 빌드의 결과를 보장하지 않습니다. 실행 방법과 PASS·FAIL·SKIP의 정의는 [CTS](cts.md)에 있습니다.

추가 관찰은 [기기 검증 입력](_inputs/device-verification.yaml), [검증 문서](https://github.com/TTolsun/hal-camera/tree/main/docs/validation), [릴리스 기록](https://github.com/TTolsun/hal-camera/tree/main/docs/releases)에서 확인하세요.

### CameraX 기기 관찰

2026년 9월 27일의 V-002 기록입니다. 앱은 0.14.0 로컬 release 빌드(versionCode 540~542)이며, 마지막 빌드의 앱 코드는 main의 `06717ba`와 같습니다. Galaxy S25+의 Android 16 환경에서 후면 카메라 0으로 확인했습니다. 전면 카메라·다른 기기와 `yuvOffsetNs` 값은 검증하지 않았습니다.

<!-- omm:begin id=device-notes -->

아래는 위 조건에서 수행한 V-002 기록의 관찰 결과입니다.

| 확인 항목 | 관찰 결과 |
| --- | --- |
| 사진 | 엔진 전환 없이 두 장이 저장됐습니다. JPEG는 4080×3060에 EXIF 방향 6, YUV는 480×640이었습니다. |
| 녹화 | H.264 1920×1080(90도 회전 정보), 약 10Mbps, AAC 48kHz 파일이 저장됐습니다. |
| AE 잠금 중 EV | EV +0.5를 적용하자 노출 시간×ISO가 1.42배가 됐고, 사진 EXIF는 1/59초, ISO 287, 노출 보정 +0.5였습니다. |
| AE 잠금 중 플래시 On 사진 | 플래시가 발광했고 1/1169초, ISO 25로 저장됐습니다. 잠금 직전 프리뷰는 ISO 161, 8.33ms였습니다. |
| 녹화 중 AF 잠금 | 첫 Status 이벤트에서 초점 요청을 다시 보내기 전에는 AF Idle, 다시 보낸 뒤에는 No focus(잠김)로 표시됐습니다. |
| `cancelFocusAndMetering` 뒤의 AE 잠금 | 이 호출을 쓰던 빌드에서는 탭 초점이 끝난 뒤 AE 잠금을 켜도 결과가 AE OK였고, 최신 프레임 메타데이터의 `android.control.aeLock`이 OFF였습니다. 호출을 없앤 빌드에서는 탭 초점 종료, 녹화 시작·종료, 플래시 사진 뒤에도 AE Locked가 유지됐습니다. |

`CameraXControls`의 `cancelFocusAndMetering` 우회와 녹화 중 초점 요청 재전송은 이 관찰에서 나온 수정입니다. 플래시 사진의 재측광은 CameraX ImageCapture의 precapture 순서에서 온 것으로 보지만, CameraX 내부 로그로 확인하지는 않았습니다.

<details class="doc-evidence" markdown="1">
<summary>근거와 검토 정보</summary>

- 근거 파일: `app/src/main/java/dev/halcamera/camera/CameraXControls.kt`, `app/src/main/java/dev/halcamera/camera/CameraXStillCapture.kt`, `app/src/main/java/dev/halcamera/camera/CameraXLiveRecorder.kt`
- 기기 검증: `V-002`
- 근거 수준: 기기 검증
- 검토 2026-10-09 @ `8bc5634c` · Claude

</details>

<!-- omm:end id=device-notes -->

## 문서를 수정하고 검증하세요

원본 위치, 생성 명령, 검사 순서는 [문서 관리](contributing.md)로 옮겼습니다.

## 문서 검토 상태

아래 표는 이 페이지에 연결된 CameraX 기기 관찰 원고의 검토 상태입니다. 위 CTS 요약을 포함한 수동 본문의 검토 범위는 PR 기록에서 확인합니다.

<!-- omm:begin id=status -->

- 검증 기준 앱 버전: 0.22.0 (versionCode 650)

| 항목 | 최신성 | 검토 |
| --- | --- | --- |
| 구조 원본 `overall-architecture` | 최신 | 검토 2026-10-09 @ `8bc5634c` · Claude |
| 원고 `device-notes` | 최신 | 검토 2026-10-09 @ `8bc5634c` · Claude |

<!-- omm:end id=status -->

**다음 단계:** 구현 구조는 [Architecture](architecture.md), 과거의 선택은 [설계 결정 원문](_inputs/decisions.md)에서 확인하세요.
