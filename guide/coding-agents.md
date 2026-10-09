---
title: Agents
---
<h1 lang="en">Hand the camera to a coding agent.</h1>

**팀에서 전달받은 HAL CAM APK와 `halcam-cli/SKILL.md`로 에이전트에게 기기 작업을 맡기세요.** Windows 또는 WSL에서 로컬 명령을 실행할 수 있는 에이전트와 `adb`가 필요합니다. 앱 소스나 빌드 도구는 필요하지 않습니다.

## 작업을 준비하세요

1. [CLI 연결 절차](cli.md#처음-실행하세요)에 따라 기기를 연결하고 전달받은 APK를 설치합니다. 카메라 권한과 화면 잠금을 확인합니다. ADB CLI가 꺼져 있다면 앱의 **Lab → ADB CLI**에서 켭니다.
2. [스킬 원문](https://github.com/TTolsun/hal-camera/blob/main/skills/halcam-cli/SKILL.md)을 확인하거나 [SKILL.md 파일](https://raw.githubusercontent.com/TTolsun/hal-camera/main/skills/halcam-cli/SKILL.md)을 받아 작업 폴더의 `halcam-cli/SKILL.md`로 저장합니다. 사내에서 APK와 함께 받은 파일이 있으면 그 파일을 사용합니다. 스킬은 이 파일 하나로 배포할 수 있습니다.
3. 에이전트에게 파일 경로와 대상 기기, 카메라, 작업, 결과 저장 위치를 알려 줍니다. 에이전트가 해당 PC의 ADB를 실행할 수 있어야 하며, 웹 채팅에 파일을 첨부하는 것만으로 기기가 연결되지는 않습니다.

저장소를 사용하는 팀원은 기존 `skills/halcam-cli/SKILL.md`를 지정하면 됩니다. `.claude/skills/halcam-cli/SKILL.md`는 저장소 안의 본문을 가리키는 진입점이므로 단독 배포하지 않습니다. 앱 업데이트 후에는 에이전트가 APK에 포함된 실행 스크립트를 다시 준비합니다.

## 다음과 같이 요청하세요

기기 식별자는 `adb devices -l`의 SERIAL로 바꾸고, 스킬과 결과 폴더 경로는 자신의 PC에 맞게 지정합니다.

```text
halcam-cli/SKILL.md를 읽고 기기 SERIAL의 카메라 0으로 사진을 한 번 촬영해 줘.
결과를 ./photos에 받고 요청 ID, 완료 상태, 파일 검증 결과를 알려 줘.
```

```text
halcam-cli/SKILL.md를 읽고 기기 SERIAL의 카메라 0에서 표준 벤치마크를 한 번 실행해 줘.
원본 JSON을 ./runs에 받고 실행 완료 여부와 측정 validity를 구분해서 알려 줘.
```

촬영·녹화·Probe·CTS·벤치마크를 지원합니다. 설치된 APK의 지원 명령을 먼저 확인하며, 벤치마크는 `camera2-standard-v2` 프로파일을 사용합니다. 녹화를 요청할 때에는 길이와 소리 포함 여부도 지정합니다. 명령과 옵션은 [CLI 가이드](cli.md)에서 확인하세요.

요청 접수와 파일 저장 완료의 차이는 [CLI 요청 흐름](cli.md#요청과-완료를-구분하세요)을 확인하세요.

## 결과를 확인하세요

에이전트가 보고한 기기·앱 버전, 요청 ID와 최종 상태, PC에 받은 파일 경로와 크기·SHA-256 검증 결과를 확인합니다. 연결이 끊기면 같은 촬영이나 측정을 다시 제출하기 전에 기존 요청을 조회합니다. 벤치마크의 실행 성공은 보고서 저장 완료를 뜻하므로 점수·비교에 사용할 수 있는지는 JSON의 validity를 따로 확인합니다.

**다음 단계:** 결과를 공유할 때에는 [Evidence](evidence.md)의 검증 범위를 확인하세요.
