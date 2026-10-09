---
title: Live
---
<h1 lang="en">Set. Capture. Inspect.</h1>

**엔진과 카메라를 고른 뒤 사진 한 장부터 확인하세요.** 촬영 조건을 바꾸려면 아래에서 필요한 설정만 여세요. Camera2와 CameraX의 관측 범위는 [엔진 차이](engine.md)에서 비교할 수 있습니다.

| 확인할 내용 | 열기 |
| --- | --- |
| 사진·연사·AEB·동영상을 촬영합니다. | [촬영](#live에서-촬영하세요) |
| ISO·노출 시간·초점을 고정합니다. | [수동 설정](#수동-촬영-조건을-고정하세요) |
| 출력 크기·FPS·포맷을 바꿉니다. | [스트림 설정](#live-스트림을-설정하세요) |
| 두 물리 카메라를 함께 확인합니다. | [Dual](#두-물리-카메라를-함께-확인하세요) |
| 보정·AF·AE를 조절합니다. | [손떨림 보정](#손떨림-보정을-선택하세요) · [초점과 노출](#초점과-노출을-조절하세요) |

<p class="doc-evidence">아래 화면은 2026년 9월 30일 Galaxy S25+·Android 16에서 0.16.0(versionCode 593) 디자인 변경 빌드를 실행해 촬영했습니다. 0.17.0에도 같은 Live 디자인을 사용합니다. <a href="evidence.html#앱-화면-촬영">촬영 조건과 확인 범위</a>를 함께 확인하세요. 이미지를 누르면 원본이 열립니다.</p>

## Live에서 촬영하세요

1. 상단에서 엔진과 카메라를 선택합니다.
2. 사진 모드에서 셔터를 누릅니다.
3. 저장 완료 안내가 나오면 최근 썸네일을 눌러 사진을 확인합니다.

**기본 설정에서는 사진 두 장이 저장됩니다.** YUV를 변환한 JPEG와 카메라가 만든 JPEG이며, `DCIM/HALCamera`에 있습니다. Camera2에서 출력을 하나만 켜면 해당 사진만 저장합니다. 두 엔진이 사진을 연결하는 차이는 [Engine](engine.md#사진-두-장은-어떻게-연결하나요)에 있습니다.

동영상을 찍으려면 동영상 모드로 바꿉니다. 셔터를 한 번 눌러 시작하고 다시 눌러 끝냅니다. 종료 처리 중에는 셔터를 사용할 수 없습니다. 저장이 끝나면 안내 문구가 나타납니다. Camera2와 CameraX 모두 선택한 엔진에서 촬영합니다.

<figure class="app-screenshot" id="screen-live">
<a href="assets/screenshots/live.png" aria-label="Camera2 Live 사진 모드와 실시간 정보 원본 보기"><img src="assets/screenshots/live.png" alt="Camera2 Live 사진 모드와 실시간 정보" width="1440" height="3120" loading="lazy" decoding="async"></a>
<figcaption>Camera2의 사진 모드입니다. 하단에서 실시간 정보, 줌, 셔터와 최근 썸네일을 확인할 수 있습니다. <a href="assets/screenshots/live.png">원본 보기</a></figcaption>
</figure>

### 연사

사진 모드에서 셔터를 길게 누르고 있으면 연사로 찍고, 손을 떼면 멈춥니다. 장수나 간격은 고르지 않으며 카메라와 저장이 허용하는 만큼 빠르게 한 장씩 찍습니다. 다음 장은 앞 장의 저장이 끝난 뒤에 찍고, 한 번에 최대 50장입니다. 각 장은 한 장 촬영과 같은 파일로 저장되고, 메타데이터 JSON의 `requestId`(`burst-<시각>-01` 형식)로 같은 연사임을 구분합니다. 손을 떼는 순간 이미 찍기 시작한 장은 저장됩니다. 끝나면 저장한 장수와 실패한 장을 알립니다. 한 장이 실패해도 계속하지만 두 장 연속 실패하면 멈춥니다.

### AEB

상단 컨트롤의 `AEB`를 켜면 셔터를 한 번 누를 때 노출을 바꿔 3장을 찍습니다(브라케팅, [#178](https://github.com/TTolsun/hal-camera/issues/178)). 길게 누르면 AEB가 아닌 일반 연사로 동작합니다. 지금 EV, 2 EV 어둡게, 2 EV 밝게 순서이며 카메라의 EV 범위를 넘는 값은 범위 끝으로 맞춥니다. 각 장 전에 EV를 바꾸고 0.5초 기다린 뒤 찍고, 끝나면 원래 EV로 돌아갑니다.

이 대기는 AE가 따라왔는지 확인하지 않으므로 실제 노출은 메타데이터 JSON의 노출 시간과 ISO로 확인합니다. 요청한 EV는 `requestId`(`bracket-<시각>-2-ev-2.0` 형식)에 남습니다.

3장을 원본 그대로 저장한 뒤, 세 장을 노출 융합으로 합친 네 번째 이미지(`_AEB_HDR.jpg`)를 따로 만듭니다. 원본 파일 이름에는 순서와 EV가 붙고(`_AEB2_EV-2.0_JPEG.jpg`), 합성 이미지 옆의 메타데이터 JSON에는 어느 원본으로 만들었는지 적힙니다. 갤러리 썸네일에는 `AEB −2.0 · JPEG`, `AEB · HDR`처럼 어떤 이미지인지 표시됩니다.

합성은 원본 3장이 모두 저장되고 각 장에 사용할 JPEG 파일이 있을 때 진행합니다. 카메라 JPEG를 먼저 사용하고, 없으면 YUV에서 변환한 JPEG를 사용합니다. NV21이나 RAW만 저장했다면 합성할 JPEG가 없습니다.

| 결과 안내 | 의미 |
| --- | --- |
| `HDR saved` | 합성 이미지가 저장됐습니다. |
| `HDR skipped: not every shot saved` | 원본 3장이 모두 저장되지 않아 합성을 시작하지 않았습니다. |
| `HDR skipped: no JPEG output` | 합성에 필요한 JPEG 파일이 없어 시작하지 않았습니다. |
| `HDR failed` | 합성을 시도했으나 실패했습니다. 이미 저장한 원본은 유지됩니다. |

합성은 촬영이 끝난 뒤 진행하므로 그동안에도 카메라를 쓸 수 있습니다. 세 장 사이에 움직인 물체는 정렬하지 않으므로 합성 이미지에 겹쳐 보일 수 있습니다. 수동 노출과 동영상 모드, EV를 지원하지 않는 카메라에서는 AEB를 사용할 수 없습니다.

연사 속도와 실제 노출 차이는 기기와 장면에 따라 달라집니다. [연사·AEB 검증 기록](evidence.md#연사와-aeb)에서 확인한 조건과 범위를 확인하세요. 해당 기록은 HDR 합성 검증을 포함하지 않습니다.

### 녹화 중 조작

녹화 중에는 셔터 아래에 경과 시간이 표시됩니다. 줌은 계속 바꿀 수 있지만 엔진·카메라·모드 변경, 일시정지, 갤러리와 다른 도구 진입은 제한됩니다. 녹화를 마치면 사진용 프리뷰로 돌아오며 동영상 모드 선택은 유지됩니다.

## 추가 설정

**첫 촬영을 마쳤다면 필요한 설정만 골라 보세요.** 기본 촬영에는 아래 설정을 모두 읽을 필요가 없습니다.

### 수동 촬영 조건을 고정하세요

<details markdown="1" id="detail-e76a9da117" data-search-section>
<summary>수동 촬영 설정</summary>

1. Camera2 Live 상단의 화살표를 펼치고 `M`을 누릅니다. 셔터 위에 Manual 패널이 열립니다.
2. `ISO` 또는 `Shutter`에서 노출을 `Manual`으로 바꿉니다. 두 값은 현재 관측값을 지원 범위에 맞춰 함께 고정합니다. 범위 때문에 값이 달라지면 안내합니다.
3. 슬라이더로 값을 조절합니다. 파란 숫자를 누르면 직접 입력할 수 있습니다. 셔터는 ms 또는 `1/125` 형식을 받습니다. 범위를 벗어나면 입력창에 오류를 표시합니다.
4. `Focus`에서는 Auto/Manual과 diopter를, `WB`에서는 기기가 지원하는 프리셋을 선택합니다. 수동 gains와 3×3 matrix는 해당 capability가 있는 기기의 수동 노출에서 사용합니다. Kelvin 값으로 변환하지 않습니다.
5. 입력한 숫자와 패널 아래의 실제 적용값을 비교합니다. 선택한 항목의 실제값은 하단 측정줄에서 중복 표시하지 않습니다. `Hide`와 시스템 뒤로 가기는 값을 유지하며, `Reset`은 노출·초점·WB를 자동으로 되돌립니다.

수동 노출에서는 AE 잠금·EV·자동/강제 플래시를 사용할 수 없으며 torch는 지원 기기에서 유지됩니다. 수동 초점에서는 화면 터치가 AF를 다시 켜지 않습니다. 수동 노출의 최대 시간은 현재 FPS와 센서 범위로 제한하며 Auto FPS에서는 30fps를 요청합니다. 출력 조합 때문에 실제 FPS가 다를 수 있으므로 프리뷰 수치를 확인하세요.

프리뷰·사진·녹화와 녹화 중 사진에 같은 수동 설정을 적용합니다. 동영상 모드 전환으로 노출 범위가 줄면 값을 조정하고 안내합니다. 카메라·엔진·스트림 구성을 바꾸면 자동으로 초기화합니다. CameraX에서는 미지원 사유와 기존 상단 엔진 버튼의 위치를 안내합니다. Benchmark의 측정 계약에는 영향을 주지 않습니다.

</details>

### Live 스트림을 설정하세요

<details markdown="1" id="detail-f29a963033" data-search-section>
<summary>스트림 설정</summary>

1. Live 옆의 크기 표시를 누릅니다. `Lab → Settings → Live Streams`에서도 같은 설정을 열 수 있습니다.
2. Preview 크기와 YUV·JPEG·RAW의 크기 또는 `Off`를 선택합니다. 세 출력을 모두 끄면 프리뷰만 실행하며 사진 셔터는 비활성화됩니다.
3. 프리뷰 FPS를 고르고 녹화는 Format → Resolution → Frame Rate 순서로 선택합니다. 해상도는 큰 순서로 표시합니다. 녹화는 Preview + Encoder로 전환하며, 고속 세션은 제공하지 않습니다.
4. 하단 고정 `Apply`를 누르면 진입한 화면으로 돌아가며 현재 Camera2·CameraX 엔진에 적용합니다. Live에서 직접 열었다면 바로 프리뷰로 복귀합니다. 상단·시스템 뒤로 가기는 저장하지 않고 돌아갑니다.
5. Live의 P·Y·J·RAW 표시에서 적용 크기를 확인합니다. 녹화 중에는 P·R과 H264·HEVC·Auto가 표시됩니다. 실패하면 설정 화면에서 이유를 확인하고 실패 시에만 나타나는 `이전 설정으로 복원` 버튼으로 복구합니다. 개별 크기를 지원해도 출력 조합은 거부될 수 있습니다.

설정은 카메라와 엔진마다 구분합니다. CameraX 녹화 포맷은 Auto이며 코덱은 라이브러리가 선택합니다. Live 설정은 Benchmark의 profile을 바꾸지 않습니다. API별 구성 검사와 저장 방식은 [Camera2 엔진](engine.md#camera2-엔진)에서 확인합니다.

</details>

### 두 물리 카메라를 함께 확인하세요

<details markdown="1" id="detail-e8f4afe277" data-search-section>
<summary>두 카메라 설정</summary>

1. Live 상단에서 Camera2 또는 CameraX를 선택하고 하단에서 `Dual · P`를 선택합니다. Camera 1은 메인 화면, Camera 2는 작은 서브 프리뷰에 표시됩니다.
2. 카메라 선택 버튼에서 Camera 1·2의 렌즈를 고르거나 메인과 서브를 교환합니다. 하단 줌은 두 센서에 공통 적용합니다.
3. 서브 프리뷰를 드래그하여 위치를 옮깁니다. 위치는 다음 진입에서도 유지됩니다. Camera2의 상단 펼침 버튼에서는 메인 센서의 노출·초점을 조절합니다.
4. Camera2의 `Dual · P`에서 셔터를 누르면 두 센서 사진을 함께 저장합니다. `Dual · V`에서는 녹화를 정지하면 카메라 ID가 포함된 무음 MP4 두 개를 저장합니다. CameraX는 두 센서 사진을 지원하지 않습니다.
5. 기존 위치의 크기 표시나 측정값을 눌러 physical ID, 실제 크기, 화면 FPS, 메타데이터 누락과 timestamp를 확인합니다. `Photo` 또는 `Video`를 누르면 해당 Live 모드로 돌아갑니다.

논리 멀티 카메라와 선택한 출력 조합을 지원하는 기기에서 사용할 수 있습니다. 두 파일의 시작 시각이나 길이가 같다고 보장하지 않습니다.

</details>

### 손떨림 보정을 선택하세요

<details markdown="1" id="detail-aa6a89b2d7" data-search-section>
<summary>손떨림 보정 설정</summary>

1. 촬영·녹화를 끝내고 Live Streams를 엽니다.
2. Stabilization의 Mode에서 모드를 선택합니다. 두 엔진 모두 Auto·Off와 지원되는 보정 모드를 표시합니다. CameraX의 EIS (Video)는 녹화 중에 적용됩니다.
3. 저장하여 프리뷰를 재개합니다. Auto는 카메라 요청 템플릿의 기본값으로 복귀합니다.
4. Live 상단의 P·Y·J 크기 표시 옆에서 실제 모드를 확인합니다. EIS: V는 Video, EIS: P, V는 Preview + Video이며, EIS: ?는 확인 불가입니다. EIS 표시를 누르면 Live Streams 설정을 엽니다. 정상적인 미적용 상태에서는 상태 영역을 숨깁니다. 요청과 다른 모드가 1초 이상 보고되면 주황색으로 요청값과 결과값을 표시합니다. 이 상태는 카메라의 결과 메타데이터이며 실제 흔들림 감소량을 뜻하지 않습니다.

</details>

### 초점과 노출을 조절하세요

<details markdown="1" id="detail-f2bbad292d" data-search-section>
<summary>초점·노출 조절</summary>

상단 가운데 화살표로 제어 줄을 펼칩니다. 선택한 Flash·AF·AE와 EV는 제어 줄을 접어도 유지됩니다. AF·AE 잠금은 같은 버튼으로 해제하고, 플래시는 `Off`로, EV는 눈금 옆 `0`으로 초기화합니다. 카메라나 엔진을 바꾸면 제어가 기본값으로 돌아갑니다.

| 조작 | 동작 |
| --- | --- |
| 프리뷰를 짧게 터치합니다. | 해당 지점에 초점을 맞춥니다. 모서리 사각형이 초록색이면 성공, 빨간색이면 실패입니다. 5초 뒤 전체 화면 기준으로 돌아가며, AF 잠금이 켜져 있으면 잠금을 풀 때까지 유지합니다. |
| 프리뷰를 길게 누릅니다. | 해당 지점을 기준으로 AE를 잠급니다. 원 위의 자물쇠와 AE 버튼으로 잠금 상태를 확인합니다. |
| 다른 지점을 짧게 터치하거나 AE 버튼을 누릅니다. | 터치로 설정한 AE 잠금을 해제합니다. |

<figure class="app-screenshot" id="screen-live-controls">
<a href="assets/screenshots/live-controls.png" aria-label="Live의 Flash AF AE EV 제어 줄 원본 보기"><img src="assets/screenshots/live-controls.png" alt="Live의 Flash AF AE EV 제어 줄" width="1440" height="3120" loading="lazy" decoding="async"></a>
<figcaption>상단 화살표로 제어 줄을 펼쳤습니다. Flash·AF·AE·EV를 조작할 수 있습니다. <a href="assets/screenshots/live-controls.png">원본 보기</a></figcaption>
</figure>

프리뷰 정보의 `AE Locked`는 노출 잠금, `AF No focus`는 AF 잠금 상태에서 초점을 맞추지 못했음을 뜻합니다. [Callback](callback.md) 그래프를 켜면 이 정보 줄을 숨기고 프레임별 콜백을 표시합니다. 촬영 지연이나 노출 변화가 예상과 다르면 [디버깅](troubleshooting.md#앱과-프레임워크hal을-구분하세요)을 확인하세요.

</details>


**다음 단계:** [Callback](callback.md)에서 촬영 프레임의 콜백과 시각을 확인하세요.
