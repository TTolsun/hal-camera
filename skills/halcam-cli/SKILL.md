---
name: halcam-cli
description: HAL CAM 앱을 adb로 조작하는 절차. 기기에서 스트림 크기·프리뷰·사진·녹화·probe·CTS·벤치마크를 실행하거나, 결과 파일을 PC로 가져오거나, CLI 요청 상태·취소·BUSY·CLI_DISABLED 같은 오류를 다룰 때 사용한다. Windows·WSL에서 연결된 Android 기기를 조작하고 결과를 로컬 PC로 회수할 때 사용한다.
---

# HAL CAM CLI로 기기 작업 수행하기

이 파일은 팀원에게 단독으로 전달할 수 있는 실행 지침입니다. 앱 소스나 저장소 경로가 없어도 사용할 수 있습니다. 로컬 명령을 실행할 수 있는 에이전트, `adb`, 기기에 설치된 HAL CAM APK가 필요합니다. 촬영 대상·횟수, 녹화 길이·소리 포함 여부, 결과 저장 위치는 사용자의 요청을 따르고 필요한 값이 빠졌으면 작업 전에 확인합니다.

PC 도구로 필요한 것은 `adb` 하나입니다. 명령을 해석하고 완료를 기다리는 스크립트(`halcam.sh`)는 APK 안에 들어 있으며 기기에서 실행됩니다. Python 클라이언트는 선택 도구이므로 기본 경로에서는 설치하지 않습니다. `benchmark run --camera 0`은 앱과 같은 `camera2-standard-v2` 프로파일을 실행하고 원본 JSON을 회수합니다. 실행 제한은 기본 600초이며 `--timeout`으로 최대 3,600초까지 지정합니다. `--profile camera2-standard-v2`는 생략할 수 있으며 다른 프로파일·엔진·Live 스트림 옵션과 `--no-audio`는 받지 않습니다. 크기를 자동 대체하거나 baseline을 자동 지정하지 않습니다.

## 1. 준비 (기기마다 한 번, APK를 갱신한 뒤에도 같은 명령)

Windows(PowerShell·CMD)와 WSL에서 같은 기기 명령을 사용합니다. 아래 명령은 `adb`가 PATH에 있는 경우의 예시입니다. 실행 파일 경로나 WSL 설정이 필요하면 이 파일의 "실행 환경별 주의"를 먼저 확인합니다.

1. `adb version`과 `adb devices -l`을 확인합니다. `unauthorized`이면 기기에서 디버깅 연결을 허용하도록 요청하고, `offline`이면 연결을 복구합니다. 여러 연결이 있으면 대상 SERIAL을 확인하고 모든 명령과 파일 회수에 같은 `adb -s SERIAL`을 사용합니다. 같은 기기의 USB·무선 연결도 서로 다른 연결입니다.
2. APK가 없으면 전달받은 APK를 `adb install -r APK_PATH`로 설치합니다. 서명이 맞지 않으면 같은 서명의 APK를 요청하며 기존 앱을 삭제하지 않습니다. 소스 빌드나 인터넷에서 임의 APK를 찾는 과정은 필요하지 않습니다.
3. 아래 `hello`에서 `app_version`, `protocol_version`, `commands`, `controls`를 확인합니다. 문서에 있더라도 설치된 앱이 지원하지 않는 명령은 제출하지 않고 필요한 업데이트를 알립니다. 이전 APK에서는 CLI 기본값이나 `doctor` 지원이 다를 수 있습니다.

가장 먼저 기기 상태를 읽습니다. 응답의 `error`, `camera_permission`, `locked`가 아래 1·2번 중 무엇을 사람에게 요청해야 하는지 알려 주므로, 명령을 시도하다가 오류로 알아내는 것보다 빠릅니다.

```sh
adb exec-out content read --uri content://dev.halcamera.cli/v1/hello
```

1. `CLI_DISABLED` 오류이면 사용자에게 앱의 **Lab → ADB CLI**에서 **ADB CLI 허용**을 켜 달라고 요청합니다. 기본 허용 변경이 포함된 APK에서는 기본값이 켜짐이며, 사용자가 저장한 꺼짐 설정은 업데이트 후에도 유지합니다. 꺼져 있으면 작업과 상태 조회가 `CLI_DISABLED`로 끝납니다. 스크립트를 내려받는 `/v1/shell`과 스크립트 자체의 `help`는 사용할 수 있습니다.
2. `camera_permission`이 `false`이거나 `locked`가 `true`이면 카메라 권한 허용과 화면 잠금 해제를 요청합니다. 소리를 포함한 녹화에는 마이크 권한도 필요합니다.
3. 기기에 스크립트를 내려놓습니다.

```sh
adb shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
adb shell sh /data/local/tmp/halcam help
adb shell sh /data/local/tmp/halcam doctor
```

`hello` 응답의 `commands` 배열은 이 빌드가 접수하는 작업 명령이고, 상태 조회와 취소처럼 작업을 만들지 않는 조작은 `controls` 배열(`record.stop`, `status`, `request`, `request.cancel`)에 따로 있습니다. 기기가 여러 대이면 모든 명령에 `adb -s SERIAL`을 붙입니다.

## 스트림 크기를 지정하세요

지원 목록을 먼저 조회한 뒤 프리뷰·촬영·녹화 명령에 크기 옵션을 붙입니다. 엔진의 기본값은 Camera2이며, CameraX를 사용할 때에는 조회와 실행 모두에 `--engine CameraX`를 지정합니다.

```sh
adb shell sh /data/local/tmp/halcam streams --camera 0
adb shell sh /data/local/tmp/halcam streams --camera 0 --engine CameraX
adb shell sh /data/local/tmp/halcam preview --camera 0 --preview-size 1280x720 --yuv-size 640x480 --jpeg-size 1920x1080
adb shell sh /data/local/tmp/halcam capture --camera 0 --engine CameraX --yuv-size off --jpeg-size 1920x1080
adb shell sh /data/local/tmp/halcam record start --camera 0 --video-size 1280x720 --video-fps 30 --codec HEVC --no-audio
adb shell sh /data/local/tmp/halcam record stop
```

`--preview-size`, `--yuv-size`, `--jpeg-size`, `--video-size`는 `너비x높이` 형식을 사용합니다. YUV와 JPEG는 `off`로 끌 수 있지만, `capture`에는 적어도 하나가 필요합니다. `--video-fps`는 녹화 프레임 레이트이고 `--codec`은 Camera2에서 H264·HEVC, CameraX에서 Auto를 사용합니다. CameraX의 실제 녹화 코덱은 라이브러리가 선택합니다.

생략한 값은 기본 설정을 사용하며 이전 UI·CLI 설정을 이어받지 않습니다. 적용한 설정은 명령 완료 후 Live에 남지만 다음 CLI 카메라 명령은 다시 기본값과 명시한 옵션으로 구성합니다. 지원하지 않는 값은 `PREFLIGHT_FAILED`로 거부하며 다른 크기로 자동 변경하지 않습니다. 지원 목록은 개별 크기와 인코더 조건을 나타내며 출력 조합의 성공까지 보장하지 않습니다. 실제 세션 구성에서 실패할 수도 있습니다.

`preview` 완료 결과와 녹화 시작 상태의 `result.streams`에서 적용된 크기를 확인할 수 있습니다. Provider 직접 호출에서는 `--extra preview_size:s:1280x720`, `--extra engine:s:CameraX`처럼 문자열로 전달합니다. JSON 제출은 `params.engine`과 `params.streams` 객체를 사용하며, 크기·FPS·코덱 값은 모두 문자열입니다. 예를 들어 `"streams":{"preview_size":"1280x720","yuv_size":"off"}`로 전달합니다.

## 2. 자주 쓰는 명령

```sh
adb shell sh /data/local/tmp/halcam cameras                    # 논리 카메라와 물리 endpoint 목록
adb shell sh /data/local/tmp/halcam capture --camera 0         # 사진 두 장 (YUV 변환 JPEG + 카메라 JPEG)
adb shell sh /data/local/tmp/halcam preview --camera 0         # 첫 프레임까지 대기, 이후 프리뷰 유지
adb shell sh /data/local/tmp/halcam preview stop
adb shell sh /data/local/tmp/halcam record start --camera 0 --no-audio
adb shell sh /data/local/tmp/halcam record stop                # MP4 저장 완료까지 대기
adb shell sh /data/local/tmp/halcam probe                      # 카메라를 열지 않고 사양 JSON·TXT 생성
adb shell sh /data/local/tmp/halcam benchmark run --camera 0    # Camera2 표준 v2 측정과 JSON 회수
adb shell sh /data/local/tmp/halcam cts cases
adb shell sh /data/local/tmp/halcam cts run --cases custom:fast_on_off
adb shell sh /data/local/tmp/halcam status                     # 마지막 제출 요청, ID를 주면 그 요청
adb shell sh /data/local/tmp/halcam status --app               # 이전 요청과 관계없이 현재 앱 상태 조회
adb shell sh /data/local/tmp/halcam cancel
adb shell sh /data/local/tmp/halcam fetch [REQUEST_ID]         # 촬영을 반복하지 않고 파일만 다시 준비. ID를 생략하면 마지막 요청
```

스크립트가 앱 실행, 요청 ID 생성, 완료 대기를 모두 처리합니다. 작업을 제출하면 첫 줄에 `request_id=UUID`를 출력하고, 완료 시 명령에 따라 결과 JSON이나 완료 안내를 출력합니다. 성공이면 0, 오류나 성공하지 않은 완료 상태이면 1을 반환합니다.

`--camera`에는 `cameras` 결과에서 `selectable: true`인 논리 ID만 넣습니다. 물리 endpoint(예: `logicalCameraId: "0"`, `physicalCameraId: "2"`)는 단독으로 열 수 없으므로 카메라 ID로 전달하면 거부됩니다. 다른 카메라로 자동 대체하지 않습니다.

실행 제한의 기본값은 프리뷰·사진·probe가 30초, 벤치마크가 600초, `record start`가 3,600초, `cts run`이 1,800초이며 상한은 3,600초입니다. 스크립트에서는 `--timeout 초`로 1–3,600초 범위에서 지정합니다. `--no-wait`을 주면 접수 직후 요청 ID만 남기고 반환하므로, 무선 연결이 끊겼을 때에는 같은 작업을 다시 실행하지 말고 재연결 후 `status REQUEST_ID`나 `fetch REQUEST_ID`로 이어갑니다. 다른 요청이 마지막 요청 기록을 바꿀 수 있으므로 접수한 ID를 보관하고 이후 조회·회수·취소에 명시합니다.

## 3. 결과 파일을 PC로 가져오기

결과가 있는 요청은 기기의 `Download/HALCamera-cli/요청ID`에 크기와 SHA-256을 검증한 복사본을 만들고, PC로 받는 `adb pull` 명령 한 줄을 출력합니다. 연결이 하나이면 그 줄을 그대로 실행합니다. 여러 연결이면 실행할 때와 같은 `-s SERIAL`을 `adb pull`에도 붙입니다.

```sh
adb pull /sdcard/Download/HALCamera-cli/REQUEST_ID ./artifacts
```

파일 목록만 먼저 확인하려면 manifest를 읽습니다. `artifact_id`, 파일 이름, 바이트 수, SHA-256이 탭으로 구분되어 나옵니다.

```sh
adb exec-out content read --uri content://dev.halcamera.cli/v1/requests/REQUEST_ID/files
```

바이너리 파일은 PC 셸의 `>` 리다이렉션 대신 `adb pull`로 받습니다. 스크립트의 해시 검사는 기기에 준비한 복사본을 대상으로 하므로 PC에 받은 파일도 manifest의 크기·SHA-256과 대조합니다. PowerShell에서는 `(Get-Item -LiteralPath 'FILE').Length`와 `Get-FileHash -LiteralPath 'FILE' -Algorithm SHA256`을, WSL에서는 `wc -c < 'FILE'`과 `sha256sum 'FILE'`을 사용할 수 있습니다. `FILE`은 실제 회수한 파일 경로로 바꿉니다.

기기 복사본은 자동으로 삭제되지 않습니다. 사용자 요청 없이 회수 후 정리하거나 원본 사진·동영상, 앱 데이터를 삭제하지 않습니다.

## 4. 기계가 읽을 JSON이 필요할 때

스크립트를 거치지 않고 provider를 직접 호출합니다. 응답은 Base64가 아니라 평문 JSON이고 `Result: Bundle[{json=...}]` 형태로 감싸여 나옵니다.

```sh
adb shell am start -W -n dev.halcamera/.cli.CliLaunchActivity          # 프리뷰·사진·녹화·CTS·벤치마크 전에 Live를 연다
adb shell content call --uri content://dev.halcamera.cli --method capture --extra camera:s:0
adb shell content call --uri content://dev.halcamera.cli --method record.start --extra camera:s:0 --extra audio:b:false
adb shell content call --uri content://dev.halcamera.cli --method record.stop
adb shell content call --uri content://dev.halcamera.cli --method cts.run --arg custom:fast_on_off
adb exec-out content read --uri content://dev.halcamera.cli/v1/status
adb exec-out content read --uri content://dev.halcamera.cli/v1/requests/REQUEST_ID
```

CTS 항목만 `--arg`로 전달하고 `--extra cases:s:...`를 쓰지 않습니다. `--extra`는 값을 `키:타입:값`으로 자르는데 suite 키에는 `custom:`이나 `vendored:` 접두어의 콜론이 있어서, `content`가 `Binding not well formed`로 거부하고 앱에 닿지 않습니다. 여러 항목은 쉼표로 이어 붙이며, 둘을 함께 주면 앱이 거부합니다.

직접 호출은 접수 결과만 반환하므로, 응답의 `request_id`로 `/v1/requests/UUID`를 읽어 `completed`가 `true`가 될 때까지 확인합니다. `--extra request_id:s:UUID`로 같은 ID를 재사용하면 중복 촬영을 막을 수 있고, 같은 ID에 다른 인자를 주면 `REQUEST_CONFLICT`가 됩니다. 실행 제한은 `--extra timeout_ms:l:30000`으로 지정합니다. 알 수 없는 인자와 잘못된 타입은 거부됩니다.

읽기 전용 경로는 `/v1/hello`, `/v1/status`, `/v1/requests/<uuid>`, `/v1/requests/<uuid>/files`, `/v1/requests/<uuid>/artifacts/<artifact-id>`입니다. 상태를 바꾸는 명령은 `content call`로만 보냅니다.

## 5. 오류를 만났을 때

| 오류 | 원인과 대응 |
| --- | --- |
| `CLI_DISABLED` | 앱의 **ADB CLI 허용** 스위치가 꺼져 있습니다. 사람이 앱에서 켜야 하며 adb로 켤 수 없습니다. |
| `PERMISSION_REQUIRED` | 카메라 또는 마이크 권한이 없거나 Android 8–9의 저장소 권한이 없습니다. 앱에서 허용한 뒤 다시 실행합니다. |
| `BUSY` | 화면이나 다른 CLI 요청이 작업을 소유하고 있습니다. 대기열이 없으므로 `status --app`으로 확인하고 끝난 뒤 다시 제출합니다. `record stop`과 `cancel`은 실행 중에도 받습니다. |
| `APP_NOT_FOREGROUND` | Live 화면이 앞에 없습니다. 프리뷰·사진·녹화·CTS·벤치마크는 Live를 요구하므로 `CliLaunchActivity`로 앱을 열고 다시 제출합니다. 준비 단계(accepted·preparing)에서 앱이 뒤로 물러나도 같은 코드로 실패합니다. |
| `DEVICE_LOCKED` | 화면이 잠겨 있습니다. 사람이 잠금을 풀어야 하며 adb로 풀지 않습니다. |
| `UNSUPPORTED_CAMERA` | `--camera`에 단독으로 열 수 없는 ID를 주었습니다. `cameras`에서 `selectable: true`인 논리 ID를 고릅니다. |
| `NOT_RECORDING` | 멈출 CLI 녹화가 없습니다. `record start`가 먼저 성공했는지 `status`로 확인합니다. 화면에서 시작한 녹화는 CLI가 멈추지 않습니다. |
| `RECORDING_INTERRUPTED` | `record stop` 없이 화면을 벗어나 녹화가 끝났습니다. 저장된 MP4가 있으면 `fetch`로 회수하고, 정상 종료와 구분해서 보고합니다. |
| `EXECUTION_TIMEOUT` | 앱 실행 제한을 넘겨 요청이 failed로 끝났습니다. 기존 요청과 남은 파일을 확인하고 실패로 보고합니다. 재실행이 필요한 경우에만 사용자 요청 범위에서 제한 시간이나 작업을 조정합니다. |
| `interrupted` | 앱 프로세스가 재시작되어 요청이 끊겼습니다. 자동으로 다시 실행되지 않으므로, 저장된 파일이 있으면 `fetch`로 회수하고 필요하면 새 요청을 제출합니다. |
| `REQUEST_NOT_FOUND`, `ARTIFACT_EXPIRED` | 기록이 없거나 보존 기간이 지났습니다. PC에 받은 파일과 기기의 `Download/HALCamera-cli/요청ID` 복사본을 먼저 확인합니다. 없으면 결과 회수가 불가능하다고 알리고, 재실행은 사용자가 요청한 범위에서 결정합니다. |
| `UNKNOWN_CASE` | CTS 키가 목록에 없습니다. `cts cases` 결과의 `key` 값을 그대로 씁니다. |
| 응답이 비어 있거나 `Cannot reach HAL CAM` | APK가 설치되어 있지 않거나 provider에 닿지 못한 상태입니다. `adb devices`와 `/v1/hello`를 먼저 확인합니다. |

완료 기록은 24시간, 최대 200개까지만 남습니다. 그 뒤에는 `status`와 `fetch`가 실패하므로 필요한 파일은 실행 직후에 내려받습니다.

## 6. 실행 환경별 주의

- `adb`가 PATH에 없으면 Android SDK의 `platform-tools/adb`를 전체 경로로 호출합니다. 위치는 PC마다 다르므로 `adb version`이 실패하면 사용자에게 SDK 경로를 확인합니다.
- Windows의 Git Bash(MSYS)는 `/data/local/tmp/halcam` 같은 기기 경로 인자를 Windows 경로로 바꿔서 `sh: C:/Program: No such file or directory`가 납니다. adb를 호출하기 전에 `export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1`을 설정합니다. PowerShell과 CMD, macOS·Linux 셸에는 이 문제가 없습니다.
- 사진·프리뷰·녹화·CTS·벤치마크는 Live 화면이 필요합니다. 스크립트는 직접 열지만, `content call`을 직접 쓸 때에는 `CliLaunchActivity`를 먼저 실행합니다. `probe`와 `cts cases`는 화면이 필요 없습니다.
- 기기 화면을 사람이 만지면 진행 중인 CLI 작업이 취소될 수 있습니다. 측정 중에는 화면을 건드리지 않습니다.
- WSL의 Linux `adb`는 무선 디버깅으로 연결하거나 WSL에 연결된 USB 기기를 사용합니다. Windows에서 연결한 USB 기기는 Windows `adb.exe`를 호출하는 방법도 있습니다. 아래 경로의 `USERNAME`과 SDK 위치를 실제 설치 경로로 바꿉니다.

```sh
adb() { "/mnt/c/Users/USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe" "$@"; }
adb devices -l
```

Windows `adb.exe`로 파일을 받을 때에는 `adb pull /sdcard/Download/HALCamera-cli/REQUEST_ID C:/Users/USERNAME/Downloads`처럼 Windows 대상 경로를 사용합니다. 이 폴더는 WSL에서 `/mnt/c/Users/USERNAME/Downloads`로 확인합니다. Linux `adb`는 WSL 대상 경로를 사용합니다. 실행·조회·회수에는 같은 ADB 실행 파일과 SERIAL을 유지합니다.

무선 디버깅은 기기의 주소·코드로 `adb pair IP:PAIR_PORT` 후 `adb connect IP:PORT`를 실행합니다. 페어링 포트와 연결 포트는 다를 수 있습니다.

## 7. 완료 보고

기기 SERIAL·모델·Android 버전, `hello`의 앱 버전, 실행한 명령과 요청 ID, 최종 상태, PC 파일 경로와 검증 결과를 보고합니다. `record start` 성공은 녹화 시작일 뿐이며 지정한 시점에 `record stop`으로 저장 완료를 확인합니다. 벤치마크는 원본 JSON의 validity·측정 결과를 실행 상태와 구분하고, 벤치마크 산출물에는 이미지 픽셀이나 녹화 MP4가 포함되지 않음을 설명합니다. 확인하지 못한 항목은 검증 완료로 표시하지 않습니다.

최신 명령 계약은 [CLI 가이드](https://ttolsun.github.io/hal-camera/cli.html)를 참고합니다. 사내 환경에서 외부 링크에 접근할 수 없어도 이 파일과 설치된 앱의 `hello`·`help`로 기본 작업을 수행할 수 있습니다.
