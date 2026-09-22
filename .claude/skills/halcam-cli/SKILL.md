---
name: halcam-cli
description: HAL CAM 앱을 adb로 조작하는 절차. 기기에서 프리뷰·사진·녹화·probe·CTS를 실행하거나, 결과 파일을 PC로 가져오거나, CLI 요청 상태·취소·BUSY·CLI_DISABLED 같은 오류를 다룰 때 사용한다. 계약 원본은 guide/cli.md와 app/src/main/java/dev/halcamera/cli/이다.
---

# HAL CAM CLI를 adb로 운전하기

PC에 필요한 것은 `adb` 하나입니다. 명령을 해석하고 완료를 기다리는 스크립트(`halcam.sh`)는 APK 안에 들어 있으며 기기에서 실행됩니다. Python 패키지 `tools/halcam`은 선택 도구이므로 기본 경로에서는 설치하지 않습니다. `benchmark.run`은 CLI 계약에서 빠졌습니다. 벤치마크는 앱 화면에서만 실행하므로, 측정을 요청받으면 CLI로 우회하지 말고 화면 조작이 필요하다고 알립니다.

## 1. 준비 (기기마다 한 번, APK를 갱신한 뒤에도 같은 명령)

1. 앱의 Benchmark 진단 패널에서 **ADB CLI 허용**을 켭니다. 초기값이 꺼짐이므로 사람이 직접 켜야 하며, 꺼져 있으면 모든 명령이 `CLI_DISABLED`로 끝납니다.
2. 카메라 권한을 허용하고 화면 잠금을 해제합니다. 소리를 포함한 녹화에는 마이크 권한도 필요합니다.
3. 기기에 스크립트를 내려놓습니다.

```sh
adb shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
adb shell sh /data/local/tmp/halcam help
```

연결 상태는 `adb exec-out content read --uri content://dev.halcamera.cli/v1/hello`로 확인합니다. 응답의 `enabled`, `camera_permission`, `locked`가 준비 상태를 그대로 알려 주며, `commands` 배열이 이 빌드가 접수하는 명령의 전체 목록입니다. 기기가 여러 대이면 모든 명령에 `adb -s SERIAL`을 붙입니다.

## 2. 자주 쓰는 명령

```sh
adb shell sh /data/local/tmp/halcam cameras                    # 논리 카메라와 물리 endpoint 목록
adb shell sh /data/local/tmp/halcam capture --camera 0         # 사진 두 장 (YUV 변환 JPEG + 카메라 JPEG)
adb shell sh /data/local/tmp/halcam preview --camera 0         # 첫 프레임까지 대기, 이후 프리뷰 유지
adb shell sh /data/local/tmp/halcam preview stop
adb shell sh /data/local/tmp/halcam record start --camera 0 --no-audio
adb shell sh /data/local/tmp/halcam record stop                # MP4 저장 완료까지 대기
adb shell sh /data/local/tmp/halcam probe                      # 카메라를 열지 않고 사양 JSON·TXT 생성
adb shell sh /data/local/tmp/halcam cts cases
adb shell sh /data/local/tmp/halcam cts run --cases custom:fast_on_off
adb shell sh /data/local/tmp/halcam status                     # 마지막 제출 요청, ID를 주면 그 요청
adb shell sh /data/local/tmp/halcam cancel
adb shell sh /data/local/tmp/halcam fetch REQUEST_ID           # 촬영을 반복하지 않고 파일만 다시 준비
```

스크립트가 앱 실행, 요청 ID 생성, 완료 대기를 모두 처리합니다. 첫 줄에 `request_id=UUID`를 출력하고 마지막에 완료된 요청 JSON을 출력하며, 성공이면 0, 오류면 1을 반환합니다. S25+에서 `capture`는 앱 실행을 포함해 약 10초가 걸립니다.

`--camera`에는 `cameras` 결과에서 `selectable: true`인 논리 ID만 넣습니다. 물리 endpoint(예: `logicalCameraId: "0"`, `physicalCameraId: "2"`)는 단독으로 열 수 없으므로 카메라 ID로 전달하면 거부됩니다. 다른 카메라로 자동 대체하지 않습니다.

실행 제한의 기본값은 프리뷰·사진·probe가 30초, `record start`가 3,600초, `cts run`이 1,800초이며 상한은 3,600초입니다. 스크립트에서는 `--timeout 초`로 줄입니다. `--no-wait`을 주면 접수 직후 요청 ID만 남기고 반환하므로, 무선 연결이 끊겼을 때에는 같은 작업을 다시 실행하지 말고 재연결 후 `status`나 `fetch`로 이어갑니다.

## 3. 결과 파일을 PC로 가져오기

결과가 있는 요청은 기기의 `Download/HALCamera-cli/요청ID`에 크기와 SHA-256을 검증한 복사본을 만들고, PC로 받는 `adb pull` 명령 한 줄을 출력합니다. 그 줄을 그대로 실행합니다.

```sh
adb pull /sdcard/Download/HALCamera-cli/REQUEST_ID ./artifacts
```

파일 목록만 먼저 확인하려면 manifest를 읽습니다. `artifact_id`, 파일 이름, 바이트 수, SHA-256이 탭으로 구분되어 나옵니다.

```sh
adb exec-out content read --uri content://dev.halcamera.cli/v1/requests/REQUEST_ID/files
```

파일을 PC로 직접 스트리밍하지 말고 위 경로를 씁니다. `content read`의 출력을 PC 셸로 리다이렉션하면 PowerShell과 CMD가 바이너리를 손상시키기 때문에, 스크립트는 기기 안에서 복사하고 해시를 대조한 뒤 `adb pull`만 남깁니다. 기기 복사본은 자동으로 지워지지 않으므로 필요 없으면 직접 삭제합니다.

## 4. 기계가 읽을 JSON이 필요할 때

스크립트를 거치지 않고 provider를 직접 호출합니다. 응답은 Base64가 아니라 평문 JSON이고 `Result: Bundle[{json=...}]` 형태로 감싸여 나옵니다.

```sh
adb shell am start -W -n dev.halcamera/.cli.CliLaunchActivity          # 프리뷰·사진·녹화·CTS 전에 Live를 연다
adb shell content call --uri content://dev.halcamera.cli --method capture --extra camera:s:0
adb shell content call --uri content://dev.halcamera.cli --method record.start --extra camera:s:0 --extra audio:b:false
adb shell content call --uri content://dev.halcamera.cli --method record.stop
adb exec-out content read --uri content://dev.halcamera.cli/v1/status
adb exec-out content read --uri content://dev.halcamera.cli/v1/requests/REQUEST_ID
```

직접 호출은 접수 결과만 반환하므로, 응답의 `request_id`로 `/v1/requests/UUID`를 읽어 `completed`가 `true`가 될 때까지 확인합니다. `--extra request_id:s:UUID`로 같은 ID를 재사용하면 중복 촬영을 막을 수 있고, 같은 ID에 다른 인자를 주면 `REQUEST_CONFLICT`가 됩니다. 실행 제한은 `--extra timeout_ms:l:30000`으로 지정합니다. 알 수 없는 인자와 잘못된 타입은 거부됩니다.

읽기 전용 경로는 `/v1/hello`, `/v1/status`, `/v1/requests/<uuid>`, `/v1/requests/<uuid>/files`, `/v1/requests/<uuid>/artifacts/<artifact-id>`입니다. 상태를 바꾸는 명령은 `content call`로만 보냅니다.

## 5. 오류를 만났을 때

| 오류 | 원인과 대응 |
| --- | --- |
| `CLI_DISABLED` | 앱의 **ADB CLI 허용** 스위치가 꺼져 있습니다. 사람이 앱에서 켜야 하며 adb로 켤 수 없습니다. |
| `PERMISSION_REQUIRED` | 카메라 또는 마이크 권한이 없습니다. 앱에서 허용한 뒤 다시 실행합니다. |
| `BUSY` | 화면이나 다른 CLI 요청이 작업을 소유하고 있습니다. 대기열이 없으므로 `status`로 확인하고 끝난 뒤 다시 제출합니다. `record stop`과 `cancel`은 실행 중에도 받습니다. |
| `interrupted` | 앱 프로세스가 재시작되어 요청이 끊겼습니다. 자동으로 다시 실행되지 않으므로, 저장된 파일이 있으면 `fetch`로 회수하고 필요하면 새 요청을 제출합니다. |
| `UNKNOWN_CASE` | CTS 키가 목록에 없습니다. `cts cases` 결과의 `key` 값을 그대로 씁니다. |
| 응답이 비어 있거나 `Cannot reach HAL CAM` | APK가 설치되어 있지 않거나 provider에 닿지 못한 상태입니다. `adb devices`와 `/v1/hello`를 먼저 확인합니다. |

완료 기록은 24시간, 최대 200개까지만 남습니다. 그 뒤에는 `status`와 `fetch`가 실패하므로 필요한 파일은 실행 직후에 내려받습니다.

## 6. 이 PC에서의 함정

- `adb`가 PATH에 없습니다. `C:\Users\baboe\AppData\Local\Android\Sdk\platform-tools\adb.exe`를 전체 경로로 호출합니다.
- Git Bash는 `/data/local/tmp/halcam` 같은 인자를 Windows 경로로 바꿔 버려서 `sh: C:/Program: No such file or directory`가 납니다. adb를 호출하기 전에 `export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1`을 설정하거나 명령 전체를 따옴표로 감쌉니다.
- 사진·프리뷰·녹화·CTS는 Live 화면이 필요합니다. 스크립트는 직접 열지만, `content call`을 직접 쓸 때에는 `CliLaunchActivity`를 먼저 실행합니다. `probe`와 `cts cases`는 화면이 필요 없습니다.
- 기기 화면을 사람이 만지면 진행 중인 CLI 작업이 취소될 수 있습니다. 측정 중에는 화면을 건드리지 않습니다.
- 이 스킬의 서술과 `guide/cli.md`가 다르면 `guide/cli.md`와 `app/src/main/java/dev/halcamera/cli/`의 코드가 원본입니다. 명령 집합을 바꾸면 `CliCommand.COMMANDS`, `AdbArguments`, `app/src/main/assets/halcam.sh`, `guide/cli.md`를 함께 갱신합니다.
