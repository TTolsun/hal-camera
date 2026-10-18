---
name: halcam-cli
description: ADB로 HAL CAM의 촬영·녹화·CTS·벤치마크를 실행하고 결과 파일을 PC로 회수합니다. 연결된 Android 기기의 CLI 작업, 상태 조회와 오류 복구에 사용합니다.
---

# HAL CAM 기기 작업

사용자가 요청한 작업을 실행하고 **저장된 파일과 최종 상태**를 확인합니다. 이 파일 하나와 `adb`, 설치된 HAL CAM APK로 사용할 수 있습니다. 횟수·녹화 길이·소리 포함 여부·PC 저장 위치는 사용자 요청을 따릅니다.

## 1. 연결과 지원 확인

1. `adb devices -l`에서 연결을 선택합니다. 이후 실행·조회·회수에 같은 `adb -s SERIAL`을 사용합니다. `unauthorized`는 기기에서 디버깅 허용이 필요합니다.
2. APK가 없으면 전달받은 APK를 `adb -s SERIAL install -r APK_PATH`로 설치합니다. 서명이 맞지 않으면 같은 서명의 APK를 준비합니다. 기존 앱을 삭제하지 않습니다.
3. APK에 포함된 스크립트를 준비합니다. APK 업데이트 후에도 같은 명령으로 갱신합니다.

   ```sh
   adb -s SERIAL shell "content read --uri content://dev.halcamera.cli/v1/shell > /data/local/tmp/halcam"
   ```

4. 설치된 앱의 지원 계약과 준비 상태를 읽습니다.

   ```sh
   adb -s SERIAL exec-out content read --uri content://dev.halcamera.cli/v1/hello
   adb -s SERIAL shell sh /data/local/tmp/halcam doctor
   ```

   앱·프로토콜 버전, `commands`, `controls`, 제공된다면 `command_specs`를 확인합니다. 지원하지 않는 새 옵션은 업데이트가 필요합니다. `CLI_DISABLED`는 사람이 앱의 **Lab → ADB CLI**에서 켭니다. 카메라 권한·잠금 해제도 앱에서 처리합니다. 유음 녹화에는 마이크 권한, Android 8–9의 저장에는 저장소 권한이 필요합니다.
5. `cameras`에서 `selectable: true`인 논리 ID를 고릅니다. 물리 endpoint를 단일 `--camera`로 열지 않습니다. 기본 카메라는 `0`입니다.

## 2. 작업 선택

**아래 명령을 `adb -s SERIAL shell sh /data/local/tmp/halcam` 뒤에 붙입니다.** 전체 문법은 기기 스크립트의 `help all`, 수동 제어는 `help controls`로 확인합니다.

| 목적 | 명령과 완료 기준 |
| --- | --- |
| 사진 | `capture --camera 0`은 기본 HAL JPEG와 촬영 JSON을 저장합니다. `burst --count 3`은 연사, `bracket`은 AEB 원본과 가능한 HDR을 저장합니다. |
| 영상 | `record start --camera 0 --no-audio`로 시작하고 요청한 길이 뒤에 `record stop`으로 저장 완료를 확인합니다. 소리를 요청했다면 `--no-audio`를 생략합니다. |
| 프리뷰 | `preview --camera 0`은 첫 프레임 뒤에도 유지합니다. `preview stop`으로 닫습니다. |
| CTS | `cts cases`에서 키와 마이크 필요 여부를 확인하고 `cts run --cases KEY[,KEY...]`로 실행합니다. |
| 사양·측정 | `probe`는 카메라를 열지 않고 JSON·TXT를 만듭니다. `benchmark run --camera 0`은 고정 `camera2-standard-v2` 프로파일의 JSON을 만듭니다. |

### Photo·Video와 설정

`capture`·`burst`·`bracket`은 Photo, `record start`는 Video입니다. `preview`·`streams`는 기본 Photo이므로 영상 준비·조회에는 `--mode Video`를 붙입니다. 기본 엔진은 Camera2, CameraX는 `--engine CameraX`입니다. 조회와 실행의 카메라·엔진·모드를 맞춥니다.

- **지원 조회:** `streams --camera 0` 또는 `streams --camera 0 --mode Video`로 크기·FPS·제어 범위를 확인합니다. 개별 출력도 조합에 따라 구성에 실패할 수 있습니다. 다른 크기·엔진으로 자동 대체하지 않습니다.
- **사진 출력:** `--jpeg-size WIDTHxHEIGHT`는 HAL JPEG, `--jpeg-size yuv`는 YUV로 만든 JPEG입니다. RAW 단독은 `--yuv-size off --jpeg-size off --raw-size WIDTHxHEIGHT`이며 Camera2와 RAW 지원이 필요합니다. 촬영에는 JPEG 또는 RAW가 있어야 합니다.
- **영상 설정:** `--video-size`, `--video-fps`, `--codec`, `--stabilization`을 사용합니다. Camera2 코덱은 H264·HEVC, CameraX는 Auto입니다. Photo의 `--fps`·RAW를 Video에 사용하지 않습니다. Photo 보정은 Off이며 모드에 맞지 않는 설정은 거부됩니다.
- **수동 제어:** EV는 보정 단계이며 `ev_step`을 곱하면 EV가 됩니다. ISO·`--exposure-ns`는 함께 지정하며 시간은 ns입니다. 초점은 디옵터이고 0은 무한대입니다. CUSTOM WB에는 수동 노출·네 gains·아홉 matrix 값이 필요합니다. 적용 여부는 `live info`·사진 메타데이터로 확인합니다.
- **설정 유지:** 새 카메라 작업은 기본값과 명시한 옵션으로 구성하며 이전 UI·CLI 설정과 PIP를 이어받지 않습니다. 현재 프리뷰·CLI 녹화를 유지하며 바꾸려면 `live set --zoom 2`, 제어 초기화는 `live reset`을 사용합니다.

### PIP·Dual

PIP는 원하는 모드의 `preview`를 연 뒤 `pip sources`로 조회합니다. 같은 카메라·엔진으로 `capture` 또는 `record start`에 `--pip-type physical|service --pip-camera ID`를 붙입니다. `selectable: true`여도 `Not verified`이면 실패할 수 있습니다.

사진은 합성과 카메라별 원본, 영상은 합성 MP4를 받습니다. CameraX Physical·Flash Auto/On·연사·AEB는 지원하지 않습니다. 위치는 `--pip-x`, `--pip-y`, `--pip-width`, `--pip-height` 네 값을 함께 지정하며 0–1 안에 들어야 합니다. PIP 크기 옵션은 `help all`에 있습니다.

Dual은 `dual cameras`로 ID를 찾고 `dual preview|capture|record --camera ID --first ID --second ID`를 사용합니다. 사진·수동 제어는 Camera2, 영상은 두 개의 무음 MP4이며 `record stop`으로 종료합니다. 지원하지 않는 조합을 다른 센서·엔진으로 대체하지 않습니다.

Multi(여러 독립 카메라 동시 사용)는 현재 `multi cameras` 조회만 지원합니다. `advertised: true`인 조합과 카메라별 크기를 확인하며 카메라를 열지 않습니다. Multi 촬영·녹화를 요청받으면 아직 CLI로 실행할 수 없다고 알립니다.

### 그 밖의 작업

- `record snapshot`은 CLI 녹화에 사진을 추가하며 지원은 엔진·출력 구성에 따라 다릅니다. 사진 저장 중 `record stop`은 저장을 기다리며 영상·사진은 녹화 요청에 함께 등록됩니다.
- `meter --x 0.5 --y 0.5 --meter focus`의 좌표는 왼쪽 위 0, 오른쪽 아래 1입니다. 노출 측광은 `--meter exposure`입니다.
- `results list`, `gallery list`, `incidents list`, `settings show`로 먼저 ID·설정을 찾습니다. 상세·export·비교·삭제·보관 한도 문법은 `help all`에 있습니다.
- baseline은 명시적으로 지정합니다. 없으면 변화량만 표시하며 REGRESSED 판정을 붙이지 않습니다. 삭제·baseline 변경·보관 한도 적용은 사용자 요청 범위에서만 합니다. `settings limit --limit N`은 미리보기이며 `--confirm true`로 적용합니다.
- Benchmark에는 엔진·Live 스트림·무음 옵션을 붙이지 않습니다. 라벨에 콜론이 필요하면 아래 Python 경로를 사용합니다.

## 3. 파일 회수와 연결 끊김 복구

스크립트는 앱 실행·요청 ID 생성·완료 대기를 처리합니다. 첫 줄의 `request_id=UUID`를 보관합니다. 성공 종료 코드는 0, 오류는 1입니다.

1. 완료 후 출력된 `adb pull` 명령에 같은 `-s SERIAL`을 붙여 PC 저장 위치로 받습니다. 바이너리를 PC 셸의 `>`로 받지 않습니다.
2. `/v1/requests/REQUEST_ID/files`의 manifest로 PC 파일의 크기·SHA-256을 대조합니다. PowerShell은 `Get-FileHash -Algorithm SHA256`, Linux·WSL은 `sha256sum`을 사용합니다. 스크립트는 기기의 회수용 복사본만 검증합니다.
3. 대기가 끝나거나 연결이 끊기면 **재촬영 전에 기존 ID를 조회합니다.** 실행 중이면 기다리거나 취소하고, 파일이 있으면 회수만 다시 준비합니다. 실패·취소 요청에도 파일이 남을 수 있습니다.

   ```sh
   adb -s SERIAL shell sh /data/local/tmp/halcam status REQUEST_ID
   adb -s SERIAL shell sh /data/local/tmp/halcam cancel REQUEST_ID
   adb -s SERIAL shell sh /data/local/tmp/halcam fetch REQUEST_ID
   ```

위 세 명령은 상황에 맞게 선택합니다. `status`만 실행하면 앱 상태를 읽습니다. `status --last`와 ID 없는 `fetch`·`cancel`은 마지막 제출 요청을 사용하므로 복구에는 ID를 명시합니다.

완료 기록은 최대 **24시간·200개**입니다. 만료 뒤 조회·회수는 실패하지만 원본과 `Download/HALCamera-cli/요청ID` 복사본은 삭제하지 않습니다. 파일은 실행 직후 받고 사용자 요청 없이 원본·복사본·앱 데이터를 정리하지 않습니다.

## 4. 막혔을 때

| 상태·오류 | 다음 행동 |
| --- | --- |
| 연결·권한·잠금 | `adb devices -l`, `hello`, `doctor`로 원인을 읽습니다. 연결을 복구하고 권한·CLI 허용·잠금 해제는 사람에게 요청합니다. |
| `BUSY` | 대기열이 없습니다. `status`로 작업을 확인하고 끝날 때까지 기다립니다. 기존 CLI 녹화의 `record stop`과 요청 `cancel`은 가능합니다. |
| `PREFLIGHT_FAILED`, `UNSUPPORTED_CAMERA`, `UNKNOWN_CASE` | 오류 사유와 `streams`·`cameras`·`cts cases`를 대조합니다. Benchmark의 발열·절전 조건도 확인합니다. |
| `EXECUTION_TIMEOUT`, `interrupted`, `RECORDING_INTERRUPTED` | 정상 완료로 보고하지 않습니다. 기존 파일부터 확인합니다. `NOT_RECORDING`이면 CLI 녹화 시작 여부를 확인하며 UI 녹화를 CLI로 멈추지 않습니다. |
| `REQUEST_NOT_FOUND`, `ARTIFACT_EXPIRED`, `REQUEST_CONFLICT` | 받은 PC 파일·기기 복사본을 확인합니다. 같은 ID에 다른 인자는 충돌입니다. 남은 결과가 없으면 회수 불가를 알리고 재실행 여부를 정합니다. |

앱 실행 제한은 사진·프리뷰·probe 30초, benchmark 600초, CTS 1,800초, 녹화 3,600초입니다. `--timeout 초`는 1–3,600초이며 `--no-wait`는 접수 후 반환합니다. PC의 대기 종료는 앱 작업 취소가 아닙니다. 준비·측정 중에는 사람이 화면을 조작하지 않도록 안내합니다.

## 5. 환경·자동화와 보고

- `adb`가 PATH에 없으면 설치된 platform-tools 경로를 사용합니다. Git Bash에서는 먼저 `export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1`을 설정합니다.
- WSL은 Linux `adb`의 무선·USB 연결이나 Windows `adb.exe`를 사용합니다. Windows 실행 파일로 `pull`하면 Windows 대상 경로를 지정합니다. 무선 연결은 `adb pair IP:PAIR_PORT` 후 `adb connect IP:PORT`이며 두 포트는 다를 수 있습니다.
- 직접 Provider 호출은 `hello`의 계약을 따릅니다. Live 작업 전에 `adb -s SERIAL shell am start -W -n dev.halcamera/.cli.CliLaunchActivity`를 실행합니다. `content call`은 접수만 반환하므로 ID로 완료를 조회합니다. CTS 키는 콜론 때문에 `--extra` 대신 `--method cts.run --arg KEY[,KEY...]`로 전달합니다. 같은 ID·같은 명령은 기존 결과를 돌려줍니다.
- 이미 Python 클라이언트가 있다면 PC에서 `halcam --serial SERIAL … --output DIRECTORY --json`을 사용합니다. 0.4.0은 `run OPERATION --option KEY=VALUE --stream KEY=VALUE`, `run cts.run --cases KEY`, `record stop --output DIRECTORY`를 지원합니다. `--wait-timeout`은 PC 대기만 제한하며 이전 `control record.stop`은 저장 완료를 기다리지 않습니다. APK만 받은 환경에는 Python을 설치할 필요가 없습니다.

대상 기기·모델·Android·앱 버전, 명령·요청 ID, 최종 상태, PC 파일 경로와 검증 결과를 보고합니다. Benchmark의 보고서 저장 성공과 validity·성능 판정은 구분합니다. Benchmark JSON과 진단 ZIP에는 이미지 픽셀이 없으며 녹화 MP4를 benchmark 산출물로 회수하지 않습니다. 확인하지 못한 항목은 검증 완료로 표시하지 않습니다.

사람이 읽는 예시는 [CLI 가이드](https://ttolsun.github.io/hal-camera/cli.html)에 있습니다. 외부 링크 없이도 이 파일과 설치된 APK의 `hello`·`help all`로 작업할 수 있습니다.
