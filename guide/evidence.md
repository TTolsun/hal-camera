---
title: Validation
---
<h1 lang="en">Trust starts with a trace.</h1>

검증 결과는, 어떤 조건에서 남겼는지와 함께 읽습니다.

**기능에 맞는 기록을 고르고 앱 버전·기기·설정을 확인하세요.** 다른 기기나 현재 빌드에서 같은 결과를 보장하지는 않습니다.

## 기기 검증 기록

- **Split·카메라별 사진·영상:** [Multi 검증](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/multi-split-20261012.md)을 확인하세요.
- **PIP 원본·크기·지원 검사:** [PIP 검증](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/pip-availability-2026-10-11.md)을 확인하세요.
- **HAL Recording과 앱 인코더:** [Callback 검증](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/recording-callback-20261011.md)에서 구분합니다.
- **현재 배포 버전과 확인 범위:** [최신 릴리스](https://github.com/TTolsun/hal-camera/releases/latest)를 확인하세요.

<details markdown="1" id="previous-device-observations" data-search-section>
<summary>이전 화면·기기 관찰 기록</summary>

### 앱 화면 촬영

스크린샷은 당시의 조작과 화면을 보여 줍니다. 표시된 수치는 대표 성능값이 아니며 최신 사용법은 각 기능의 본문을 따릅니다.

- **2026-09-27:** Galaxy S25+·Android 16의 0.15.0(versionCode 543)에서 16개 화면을 촬영했습니다. CTS 원문 목록·CLI 설정·ZIP 목록은 조회만 확인했습니다. [촬영 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/2026-09-27-app-screenshots.md)을 참고하세요.
- **2026-09-29:** 같은 기기의 Lab 디자인 빌드로 이미지 11개를 교체했습니다. 빠른 켜기·끄기 35초 PASS와 Benchmark 완료·저장을 확인했습니다. [Lab 검증](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/lab-ui-20260929.md)에 범위가 있습니다.
- **2026-09-30:** 0.16.0(versionCode 593) 디자인 빌드의 [Gallery·Lab 촬영 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/gallery-lab-ui-20260930.md)입니다. Callback의 촬영·녹화 이미지는 9월 27일 기록을 유지했습니다.
- **2026-10-11:** Live 첫 화면을 [AWB 실시간 정보 검증](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/live-awb.md)의 최종 촬영본으로 교체했습니다. 같은 기기에서 FPS·EXP·AE·AF·AWB의 배치와 가독성을 확인했습니다.

### 연사와 AEB

**2026-10-09 · Galaxy S25+·Android 16 · 후면 기본 카메라**에서 Camera2·CameraX를 확인했습니다. 연사는 손을 떼면 멈췄고 AEB는 원본 3장 저장 뒤 EV를 복원했습니다.

요청한 ±2 EV와 실제 노출 차이는 같지 않을 수 있었습니다. [실기기 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/live-burst-bracketing-20261009.md)은 HDR 합성 검증을 포함하지 않습니다.

저장 공간 부족·일부 촬영 실패·화면 종료는 단위 테스트 범위입니다. Android 8–9의 권한 대화상자는 기기에서 확인하지 않았습니다.

### 녹화 중 사진

**2026-10-05 · Galaxy S25+·Android 16 · 0.19.0(versionCode 628)** 기록입니다. 후면·전면·초광각을 확인했습니다.

- **CameraX 1.6.2:** 사진 시점에 약 두 프레임 길이의 영상 간격이 생겼습니다.
- **Camera2 1080p 30fps:** 사진 3장씩을 저장해도 같은 간격 증가는 없었습니다.

HEVC 60fps·정지 직후 사진과 남은 조건은 [후속 검증 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/validation/video-snapshot-20261005.md)에 있습니다. 다른 기기·설정의 연속성을 보장하지 않습니다.

### CTS 원문 케이스

**2026-09-17–19 · Galaxy S25+·Android 16 · versionCode 106–108** 기록입니다. 여러 실행과 재실행을 합쳤으며, versionCode 108의 SKIP 판정으로 **40개 메서드 중 PASS 33 · FAIL 1 · SKIP 6**입니다.

`testAeCompensation`은 카메라 0의 노출 시간 범위 초과와 카메라 2의 AE 보정 미적용으로 실패했습니다. 미지원 기능과 실행별 로그는 [당시 작업 기록](https://github.com/TTolsun/hal-camera/blob/main/docs/archive/STATUS-before-0.24.0.md)을 확인하세요.

공식 CTS 인증이나 현재 빌드의 결과가 아닙니다. 판정의 뜻과 실행 절차는 [CTS](cts.md)에 있습니다.

### CameraX 기기 관찰

**2026-09-27 · Galaxy S25+·Android 16 · 카메라 0 · 0.14.0 로컬 release(versionCode 540–542)** 기록입니다. 원본의 검증 ID는 V-002입니다.

사진·녹화 저장, AE 잠금 중 EV·플래시, 녹화 중 AF 잠금을 관찰했습니다. 전면 카메라·다른 기기·`yuvOffsetNs`는 검증하지 않았습니다.

파일 크기·노출·이벤트 값은 [기기 관찰 원본](https://github.com/TTolsun/hal-camera/blob/main/guide/_inputs/device-verification.yaml)에서 V-002를 확인하세요. 현재 앱의 모든 촬영 조건을 검증한 결과는 아닙니다.

</details>

**다음 단계:** [Download](download.md)에서 앱을 받아 같은 조건으로 확인하세요.
