---
title: Quickstart
---
<h1 lang="en">Install. Open. Check.</h1>

첫 사진을 저장하면, 카메라를 살펴볼 준비가 됩니다.

APK를 설치하고 Live에서 한 장을 찍어 보세요. 낯선 용어는 [Glossary](glossary.md)에서 확인할 수 있습니다.

## APK를 설치하세요

1. [Download](download.md)에서 GitHub 릴리스를 열고 Assets의 APK를 받습니다.
2. 기기에서 APK를 열어 설치합니다. 설치 허용을 요청하면 해당 파일을 연 앱에 허용합니다.
3. HAL CAMERA를 열고 카메라 권한을 허용합니다.

소리를 함께 녹음하려면 마이크 권한도 허용합니다.

업데이트의 서명 오류는 [Download](download.md)에서 확인하세요. 앱을 삭제하면 내부 실행 기록이 사라집니다.

<details markdown="1">
<summary>PC에서 설치하려면</summary>

기기의 USB 디버깅을 켜고 PC에 연결합니다. 기기에서 연결 요청을 승인한 뒤 다음 명령으로 설치합니다.

~~~powershell
adb install -r "받은-파일.apk"
~~~

</details>

## 첫 화면을 확인하세요

1. Live 상단에서 엔진을 고르고, 오른쪽 아래 카메라 버튼에서 카메라를 선택합니다.
2. 프리뷰가 나오는지 확인합니다.
3. 사진 모드에서 셔터를 누르고 저장 완료 안내를 확인합니다.
4. 최근 썸네일을 눌러 저장된 사진을 확인합니다.

**갤러리에서 방금 찍은 사진을 찾았다면 첫 촬영은 끝났습니다.**

다음에는 `Callback`을 켜고 `Hold 3s`를 선택한 뒤 한 장 더 찍어 보세요. 이미지와 메타데이터가 도착하는 순서를 비교할 수 있습니다.

프리뷰·저장 성공만으로 HAL 적합성이 검증되는 것은 아닙니다.

## 확인할 작업을 고르세요

- **촬영과 관찰:** [Live](live.md)에서 조건을 바꾸고 [Callback](callback.md)에서 도착 순서를 읽습니다.
- **사양과 검사:** [Probe](probe.md)에서 지원 값을 찾고 [CTS](cts.md)로 사전 점검합니다.
- **반복과 비교:** [Benchmark](benchmark.md)로 측정하고 [Automation](automation.md)으로 PC에서 실행합니다.

**다음 단계:** [Live](live.md)에서 촬영 조건을 바꾸세요. 예상과 다르면 [Troubleshooting](troubleshooting.md)을 확인합니다.
