---
title: Quickstart
---
<h1 lang="en">Install. Open. Check.</h1>

첫 사진을 저장하면, 카메라를 살펴볼 준비가 됩니다.

**첫 촬영에는 Android 기기와 APK만 있으면 됩니다.** PC나 소스 빌드는 필요하지 않습니다.

## APK를 설치하세요

1. Android 기기에서 [Download](download.md)를 열고 안내에 따라 APK를 받습니다.
2. 다운로드 완료 알림이나 파일 앱의 다운로드 폴더에서 APK를 엽니다.
3. 설치 허용을 요청하면 해당 파일을 연 앱에 허용한 뒤 APK로 돌아와 설치합니다.
4. HAL CAMERA를 열고 카메라 권한을 허용합니다.

마이크 권한은 소리를 녹음할 때만 필요합니다. Android 8–9에서는 사진·동영상 저장에 저장소 권한도 필요합니다.

업데이트의 서명 오류는 [Download](download.md)에서 확인하세요. 앱을 삭제하면 내부 실행 기록이 사라집니다.

<details markdown="1">
<summary>PC에서 설치하려면</summary>

기기의 USB 디버깅을 켜고 PC에 연결합니다. 기기에서 연결 요청을 승인한 뒤 다음 명령으로 설치합니다.

~~~powershell
adb install -r "받은-파일.apk"
~~~

</details>

## 첫 화면을 확인하세요

1. 처음 선택된 엔진과 카메라는 그대로 두고 프리뷰가 나오는지 확인합니다.
2. `Photo`를 선택합니다. 출력 크기나 수동 설정은 바꾸지 않아도 됩니다.
3. 하단 가운데의 큰 셔터를 짧게 한 번 누르고 저장 완료 안내를 기다립니다.
4. 셔터 왼쪽 썸네일로 앱 갤러리를 엽니다. 방금 찍은 사진을 눌러 크게 확인합니다.

**갤러리에서 방금 찍은 사진을 찾았다면 첫 촬영은 끝났습니다.**

사진은 `DCIM/HALCamera`에 저장됩니다. 사진을 연 뒤 오른쪽 위 정보 버튼(ⓘ)을 누르면 파일 이름·해상도·크기를 확인할 수 있습니다.

프리뷰가 없거나 사진을 찾지 못했다면 [첫 촬영 복구 순서](troubleshooting.md#첫-촬영에서-막혔을-때)를 확인하세요.

이미지와 메타데이터의 도착 순서가 궁금하다면 `Callback`을 켜고 `Hold 3s`로 한 장 더 찍어 보세요.

프리뷰·저장 성공만으로 HAL 적합성이 검증되는 것은 아닙니다.

## 확인할 작업을 고르세요

낯선 용어는 [Glossary](glossary.md)에서 필요한 뜻만 확인하세요.

- **촬영과 관찰:** [Live](live.md)에서 조건을 바꾸고 [Callback](callback.md)에서 도착 순서를 읽습니다.
- **사양과 검사:** [Probe](probe.md)에서 지원 값을 찾고 [CTS](cts.md)로 사전 점검합니다.
- **반복과 비교:** [Benchmark](benchmark.md)로 측정하고 [Automation](automation.md)으로 PC에서 실행합니다.

**다음 단계:** [Live](live.md)에서 촬영 조건을 바꾸세요. 예상과 다르면 [Troubleshooting](troubleshooting.md)을 확인합니다.
