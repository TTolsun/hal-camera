---
title: Agents
---
<h1 lang="en">Hand the camera to a coding agent.</h1>

**에이전트에게 저장소의 `skills/halcam-cli/SKILL.md`를 읽히고 수행할 작업을 지정하세요.** 스킬에는 기기 준비 확인부터 명령 실행, 파일 회수와 오류 대응까지의 순서가 있습니다. 직접 사용할 명령과 옵션은 [CLI](cli.md)에서 관리합니다.

## 작업을 맡기는 순서

1. [빠른 시작](getting-started.md)에 따라 APK를 준비하고 [CLI의 연결 절차](cli.md)에 따라 기기를 연결합니다.
2. 저장소 루트에서 에이전트를 실행합니다. Claude Code는 `.claude/skills/halcam-cli/`를 통해 원본 스킬을 찾습니다. 다른 에이전트에는 `skills/halcam-cli/SKILL.md` 경로를 직접 지정합니다.
3. 대상 기기와 카메라, 촬영·녹화·Probe·CTS 중 수행할 작업, PC에 저장할 위치를 알려 줍니다.
4. 완료 후 요청 ID, 실행 상태, 회수한 파일과 검증 결과를 확인합니다.

예를 들어 “halcam-cli 스킬을 읽고 연결된 기기의 카메라 0으로 사진을 촬영한 뒤 결과를 ./photos에 받아 줘”처럼 요청할 수 있습니다.

## 실행 범위와 권한

스킬은 사용자의 작업 지시와 권한 승인을 대신하지 않습니다. ADB CLI는 기본으로 허용되며, 사용자가 꺼 둔 경우에는 앱에서 다시 켜야 합니다. 런타임 권한은 사람이 직접 허용합니다. 에이전트는 지원 명령을 확인하고, 진행 중인 작업이나 연결 오류가 있으면 [CLI 오류 대응](cli.md#요청이-끝나지-않거나-파일이-없을-때)에 따라 처리합니다.

CLI에서 지원하지 않는 Benchmark 실행을 요청받으면 앱 화면에서 실행해야 한다고 안내합니다. 지원 범위를 넓히는 우회 경로를 만들지 않습니다.

## 스킬을 유지하는 방법

명령 계약을 바꿀 때에는 `CliCommand.COMMANDS`, `AdbArguments`, `app/src/main/assets/halcam.sh`, [CLI](cli.md), `skills/halcam-cli/SKILL.md`, 그리고 같은 `description`을 복사해 둔 `.claude/skills/halcam-cli/SKILL.md`를 함께 대조합니다. 가이드에는 명령의 의미와 제한을, 스킬에는 에이전트가 확인하고 실행할 순서를 적습니다.

계약 원문은 [CLI.md](https://github.com/TTolsun/hal-camera/blob/main/docs/design/CLI.md), 구현은 `app/src/main/java/dev/halcamera/cli/`에 있습니다.

**다음 단계:** 작업 결과를 기록할 때에는 [Evidence](evidence.md)의 검증 범위를 따르세요.
