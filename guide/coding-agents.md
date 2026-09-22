---
title: Agents
---
<h1 lang="en">Hand the camera to a coding agent.</h1>

저장소의 `skills/halcam-cli/SKILL.md` 한 파일이 에이전트용 운전 지침입니다. 준비 절차, 명령 목록, 결과 파일 회수, 오류 코드 대응을 담고 있으므로, AI 코딩 에이전트에게 이 파일을 읽히면 사람이 매번 `adb` 사용법을 설명하지 않아도 됩니다. 사람이 읽는 CLI 설명은 [CLI 가이드](cli.md)이고, 계약의 원본은 `app/src/main/java/dev/halcamera/cli/`의 코드입니다. 스킬은 그 둘을 에이전트가 실행할 수 있는 순서로 옮겨 적은 문서입니다.

<h2 lang="en">Use it.</h2>

1. 저장소를 clone하고 APK를 기기에 설치합니다.
2. Live 화면의 **진단** 버튼으로 진단 패널을 열고 **ADB CLI 허용**을 켭니다. 초기값이 꺼짐이며 adb로는 켤 수 없습니다.
3. 에이전트를 저장소 루트에서 실행합니다. Claude Code는 `.claude/skills/halcam-cli/`를 통해 스킬을 인식하며, 그 파일은 본문인 `skills/halcam-cli/SKILL.md`를 가리킵니다.
4. 다른 에이전트를 쓴다면 `skills/halcam-cli/SKILL.md`를 직접 읽히십시오. 저장소의 `AGENTS.md`에도 같은 경로를 적어 두었습니다.

에이전트가 먼저 하는 일은 기기에 스크립트를 내려놓는 것입니다. 사람이 손으로 준비하려면 다음 명령을 실행합니다.

```sh
adb shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
adb shell sh /data/local/tmp/halcam help
```

<h2 lang="en">What it covers.</h2>

| 주제 | 스킬이 알려주는 것 |
| --- | --- |
| 준비 | ADB CLI 허용 스위치, 카메라·마이크 권한, 화면 잠금 해제, 스크립트 설치와 갱신 |
| 명령 | `cameras`, `preview`, `capture`, `record start`·`stop`, `probe`, `cts cases`·`run`, `status`, `cancel`, `fetch`의 용도와 기본 실행 제한 |
| 카메라 선택 | `cameras` 결과에서 `selectable: true`인 논리 ID만 `--camera`에 넣는다는 규칙 |
| 결과 파일 | 기기의 `Download/HALCamera-cli/요청ID`와 `adb pull`, manifest로 크기와 SHA-256을 대조하는 방법 |
| 기계 판독 | `content call`과 `content read`로 JSON을 직접 주고받는 경로, 요청 ID 재사용으로 중복 촬영을 막는 방법 |
| 오류 | `CLI_DISABLED`, `PERMISSION_REQUIRED`, `BUSY`, `interrupted`, `UNKNOWN_CASE`의 원인과 다음 행동 |

<h2 lang="en">Boundaries.</h2>

벤치마크는 CLI 계약에서 제외되어 있습니다. 앱은 `benchmark.run`을 접수하지 않으므로, 측정을 요청받은 에이전트는 우회 경로를 만들지 말고 앱 화면에서 실행해야 한다고 알려야 합니다. 지원 범위의 실제 목록은 언제나 `/v1/hello`가 원본이며, 작업 명령은 `commands` 배열에, 상태 조회와 취소 같은 조작은 `controls` 배열에 들어 있습니다.

스킬은 사람의 승인을 대신하지 않습니다. ADB CLI 허용 스위치와 런타임 권한은 기기 앞의 사람이 켜야 하고, 에이전트는 그 상태를 읽어 다음 행동을 안내할 뿐입니다. 화면에서 촬영이나 벤치마크가 진행 중이면 새 CLI 작업은 `BUSY`로 거부되며, 이때 요청을 반복하지 않는 것이 스킬의 규칙입니다.

명령 집합을 바꿀 때에는 `CliCommand.COMMANDS`, `AdbArguments`, `app/src/main/assets/halcam.sh`, [CLI 가이드](cli.md), `skills/halcam-cli/SKILL.md`를 함께 갱신합니다. 스킬과 가이드가 어긋나면 코드와 가이드가 원본입니다.

명령의 자세한 설명과 직접 호출 예시는 [CLI 가이드](cli.md)에서 이어서 읽으세요.
