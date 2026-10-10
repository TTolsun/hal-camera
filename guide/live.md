---
title: Live
---
<h1 lang="en">Set. Capture. Inspect.</h1>

**엔진과 카메라를 고른 뒤 사진 한 장부터 확인하세요.** 촬영 조건을 바꾸려면 아래에서 필요한 설정만 여세요. Camera2와 CameraX의 관측 범위는 [Engine Comparison](engine.md)에서 비교할 수 있습니다.

| 확인할 내용 | 열기 |
| --- | --- |
| 사진·연사·AEB·동영상을 촬영합니다. | [촬영](#live에서-촬영하세요) |
| ISO·노출 시간·초점을 고정합니다. | [수동 설정](#수동-촬영-조건을-고정하세요) |
| 출력 크기·FPS·포맷을 바꿉니다. | [스트림 설정](#live-스트림을-설정하세요) |
| 여러 카메라를 함께 촬영합니다. | [Multi](#여러-카메라를-함께-촬영하세요) |
| 보정·AF·AE를 조절합니다. | [손떨림 보정](#손떨림-보정을-선택하세요) · [초점과 노출](#초점과-노출을-조절하세요) |

<p class="doc-evidence">화면 예시는 0.16.0 개발 빌드의 촬영본으로, 현재 앱과 버튼·기능이 다를 수 있습니다. <a href="evidence.html#앱-화면-촬영">촬영 조건</a>을 확인하세요.</p>

## Live에서 촬영하세요

1. 상단에서 엔진을 고르고 오른쪽 아래 카메라 버튼에서 카메라를 선택합니다.
2. 사진 모드에서 셔터를 누릅니다.
3. 저장 완료 안내가 나오면 셔터 왼쪽 갤러리 썸네일을 눌러 결과를 확인합니다.

**기본 설정에서는 사진 두 장이 저장됩니다.** YUV를 변환한 JPEG와 카메라가 만든 JPEG이며, `DCIM/HALCamera`에 있습니다. Camera2에서 출력을 하나만 켜면 해당 사진만 저장합니다. 두 엔진이 사진을 연결하는 차이는 [Engine Comparison](engine.md#사진-두-장은-어떻게-연결하나요)에 있습니다.

동영상을 찍으려면 동영상 모드로 바꿉니다. Photo·Video를 전환하면 전체 스트림을 다시 구성하고 선택한 PIP도 복원합니다. 셔터를 한 번 눌러 시작하고 다시 눌러 끝냅니다. 종료 처리 중에는 셔터를 사용할 수 없습니다. 일반 사진·PIP 사진·동영상의 저장 완료 안내는 3초 뒤 사라집니다. 저장 실패 안내는 유지합니다. Camera2와 CameraX 모두 선택한 엔진에서 촬영합니다.

<figure class="app-screenshot" id="screen-live">
<a href="assets/screenshots/live.png" aria-label="Camera2 Live 사진 모드와 실시간 정보 원본 보기"><img src="assets/screenshots/live.png" alt="Camera2 Live 사진 모드와 실시간 정보" width="1440" height="3120" loading="lazy" decoding="async"></a>
<figcaption>Camera2의 사진 모드입니다. 하단에서 실시간 정보, 줌, 셔터와 최근 썸네일을 확인할 수 있습니다. <a href="assets/screenshots/live.png">원본 보기</a></figcaption>
</figure>

### 연사

1. 사진 모드에서 AEB를 끄고 셔터를 길게 누릅니다.
2. 원하는 만큼 찍었으면 손을 뗍니다.
3. 저장한 장수와 실패 안내를 확인합니다.

**저장 결과:** 손을 뗄 때 이미 시작한 사진은 저장됩니다. 다음 사진은 찍지 않습니다. 촬영 종료 안내는 자동으로 사라지지 않으며, 갤러리에서 저장물을 확인할 수 있습니다. 새 촬영을 시작하면 이전 안내를 지웁니다.

TalkBack에서는 `Start burst`로 시작하고 셔터의 `Stop burst`로 멈춥니다.

<details markdown="1" id="burst-limits" data-search-section>
<summary>촬영 속도·중단 조건·파일 식별자</summary>

- 앞 장의 저장이 끝나면 다음 장을 찍습니다. 장수나 간격은 고르지 않으며 카메라와 저장이 허용하는 속도로 촬영합니다.
- 한 번에 최대 50장입니다. 한 장이 실패해도 계속하지만 두 장 연속 실패하면 멈춥니다.
- 각 장은 일반 사진과 같은 파일로 저장합니다. 메타데이터 JSON의 `requestId`(`burst-<시각>-01` 형식)로 같은 연사인지 구분합니다.

</details>

### AEB

1. 상단 제어 줄에서 [AEB](glossary.md#aeb)를 켭니다.
2. 셔터를 한 번 누릅니다. AEB가 켜져 있으면 길게 눌러도 일반 연사로 바뀌지 않습니다.
3. 셔터 위의 진행 장수와 HDR 저장 결과를 확인합니다. 중단하려면 흰 정지 사각형으로 바뀐 셔터를 누릅니다.

**저장 결과:** 현재 EV, 2 EV 어둡게, 2 EV 밝게 원본 3장을 촬영합니다. 합성 조건을 충족하면 `_AEB_HDR.jpg`도 저장합니다. 촬영이 끝나면 원래 EV로 돌아갑니다.

수동 노출·동영상 모드·PIP 합성·EV 미지원 카메라에서는 AEB를 사용할 수 없습니다. 움직이는 물체는 합성 이미지에 겹쳐 보일 수 있습니다.

촬영 중에는 EV·수동 제어·줌·터치 측광을 잠급니다. 합성 중에는 `HDR…`를 표시하며, 다음 AEB는 합성이 끝난 뒤 시작합니다.

합성은 원본 3장이 모두 저장되고 각 장에 사용할 JPEG 파일이 있을 때 진행합니다. 카메라 JPEG를 먼저 사용하고, 없으면 YUV에서 변환한 JPEG를 사용합니다. NV21이나 RAW만 저장했다면 합성할 JPEG가 없습니다.

| 결과 안내 | 의미 |
| --- | --- |
| `HDR saved` | 합성 이미지가 저장됐습니다. |
| `HDR skipped` | 함께 표시된 저장 장수·중단·실패 결과를 확인하세요. 원본 3장이 모두 저장되지 않아 합성을 시작하지 않았습니다. |
| `HDR skipped: no JPEG output` | 합성에 필요한 JPEG 파일이 없어 시작하지 않았습니다. |
| `HDR failed` | 합성을 시도했으나 실패했습니다. 이미 저장한 원본은 유지됩니다. |


<details markdown="1" id="aeb-exposure-files" data-search-section>
<summary>실제 노출·원본 파일·합성 조건 자세히 보기</summary>

카메라의 EV 범위를 넘는 요청은 범위 끝으로 맞춥니다. 각 장의 EV를 바꾼 뒤 0.5초 기다리고 촬영합니다.

이 대기는 AE가 따라왔는지 확인하지 않으므로 실제 노출은 메타데이터 JSON의 노출 시간과 ISO로 확인합니다. 요청한 EV는 `requestId`(`bracket-<시각>-2-ev-2.0` 형식)에 남습니다.

원본 파일 이름에는 순서와 EV가 붙습니다(`_AEB2_EV-2.0_JPEG.jpg`). 합성 이미지의 메타데이터 JSON에는 사용한 원본이 기록됩니다. 갤러리에서는 `AEB −2.0 · JPEG`, `AEB · HDR` 표시로 구분합니다.

합성은 촬영 뒤에 진행하므로 그동안에도 카메라를 쓸 수 있습니다. 세 장 사이에 움직인 물체를 정렬하지는 않습니다.

연사 속도와 실제 노출 차이는 기기와 장면에 따라 달라집니다. [연사·AEB 검증 기록](evidence.md#연사와-aeb)에서 확인한 조건과 범위를 확인하세요. 해당 기록은 HDR 합성 검증을 포함하지 않습니다.

</details>

### 녹화 중 조작

녹화 중에는 셔터 아래에 경과 시간이 표시됩니다. 줌은 계속 바꿀 수 있지만 엔진·카메라·모드 변경, 일시정지, 갤러리와 다른 도구 진입은 제한됩니다. 녹화를 마치면 사진용 프리뷰로 돌아오며 동영상 모드 선택은 유지됩니다.

### 녹화 중 사진을 찍으세요

1. 동영상 녹화를 시작합니다.
2. 큰 셔터 **오른쪽의 사진 버튼**을 누릅니다. 큰 셔터는 녹화를 끝내는 버튼입니다.
3. 저장 완료를 기다립니다. 저장하는 동안에는 다음 사진을 찍을 수 없습니다.

**저장 결과:** JPEG 사진 한 장을 저장합니다. 일반 사진 모드의 YUV·JPEG 두 장 저장과 다릅니다. 사진 촬영이 실패해도 녹화는 계속됩니다.

사진 버튼이 흐리면 미지원 상태인지 눌러 안내를 확인하세요. JPEG 출력을 껐거나 기기가 녹화와 JPEG 조합을 지원하지 않으면 사용할 수 없습니다. PIP 녹화 중 사진은 지원하지 않습니다. 사진 저장 중에도 버튼이 흐려집니다.

CameraX에서는 사진 촬영 때 영상의 프레임 간격이 늘어날 수 있습니다. 녹화 연속성이 중요하면 [엔진별 제약과 검증 조건](engine.md#녹화-중-사진의-알려진-제약)을 먼저 확인하세요.

## 추가 설정

**첫 촬영을 마쳤다면 필요한 설정만 골라 보세요.** 기본 촬영에는 아래 설정을 모두 읽을 필요가 없습니다.

### 수동 촬영 조건을 고정하세요

<details markdown="1" id="detail-e76a9da117" data-search-section>
<summary>수동 촬영 설정</summary>

**Camera2에서만 사용할 수 있습니다.** 상단 화살표를 펼치고 `M`을 눌러 Manual 패널을 여세요. 닫은 뒤에도 설정은 실시간 정보에 표시됩니다. 별도 요약 패널은 남기지 않습니다.

M·EV·Flash는 도구 행을 유지한 채 선택한 버튼 아래로 펼쳐집니다. 버튼의 선택 상태와 연결 표시로 열린 패널을 구분합니다. 같은 버튼을 다시 누르거나 상단 화살표를 접으면 닫힙니다.

#### ISO와 노출 시간을 고정하세요

1. `ISO` 또는 `Shutter`에서 `Manual`을 선택합니다. 현재 ISO와 노출 시간이 함께 고정됩니다.
2. 슬라이더로 값을 조절합니다. 숫자를 누르면 직접 입력할 수 있습니다. 노출 시간은 ms 또는 `1/125` 형식을 사용합니다.
3. 패널 아래의 실제 적용값을 확인합니다. 지원 범위를 벗어나면 값 조정 안내나 입력 오류가 표시됩니다.

#### 초점과 화이트밸런스를 바꾸세요

| 할 일 | 조작 |
| --- | --- |
| 초점을 고정합니다. | `Focus → Manual`에서 초점 거리를 diopter 단위로 조절합니다. |
| 화이트밸런스를 고릅니다. | `WB`에서 지원되는 프리셋을 선택합니다. |
| 설정을 유지하고 패널을 닫습니다. | 닫기 아이콘 또는 시스템 뒤로 가기를 누릅니다. |
| 자동 촬영으로 돌아갑니다. | `Reset`을 누르면 노출·초점·WB가 모두 자동으로 돌아갑니다. |

카메라·엔진·스트림 구성을 바꾸면 수동 설정이 초기화됩니다. 프리뷰·사진·녹화·녹화 중 사진에 같은 설정을 적용하지만, 동영상 모드에서 노출 범위가 줄면 값을 조정하고 안내합니다.

<details markdown="1" id="manual-limits" data-search-section>
<summary>수동 설정의 제한과 고급 WB</summary>

- 수동 노출에서는 AE 잠금·EV·자동/강제 플래시를 사용할 수 없습니다. torch는 지원 기기에서 유지됩니다.
- 수동 초점에서는 화면을 터치해도 자동 초점으로 돌아가지 않습니다.
- 노출 시간은 현재 FPS와 센서 범위로 제한합니다. Auto FPS에서는 30fps를 요청하지만 출력 조합에 따라 실제 FPS가 달라질 수 있습니다.
- 수동 WB gains와 3×3 matrix는 해당 기능을 지원하는 기기의 수동 노출에서 사용합니다. Kelvin 값으로 변환하지 않습니다.
- Live의 수동 설정은 Benchmark 실행 조건에 영향을 주지 않습니다.

</details>


</details>

### Live 스트림을 설정하세요

<details markdown="1" id="detail-f29a963033" data-search-section>
<summary>스트림 설정</summary>

1. Live 옆의 크기 표시를 누릅니다. `Lab → Settings → Live Streams`에서도 열 수 있습니다.
2. 아래 표에서 바꾸려는 출력의 조건을 선택합니다.
3. `Apply`를 눌러 적용합니다. 뒤로 가기는 변경을 저장하지 않습니다.
4. Live 상단의 크기 표시에서 적용 결과를 확인합니다.

| 바꿀 대상 | 설정 |
| --- | --- |
| 프리뷰 | Preview 크기와 프리뷰 FPS를 선택합니다. |
| 사진 | Camera2는 YUV·JPEG·RAW, CameraX는 YUV·JPEG의 크기 또는 `Off`를 선택합니다. 모든 사진 출력을 끄면 사진 셔터를 사용할 수 없습니다. |
| 녹화 | Format → Resolution → Frame Rate 순서로 선택합니다. CameraX의 포맷은 Auto이며 코덱은 라이브러리가 정합니다. 고속 세션은 제공하지 않습니다. |

**적용에 실패하면** 설정 화면의 이유를 확인하고 `이전 설정으로 복원`을 누르세요. 이 버튼은 실패했을 때만 나타납니다. 개별 크기를 지원해도 여러 출력의 조합은 거부될 수 있습니다.

설정은 카메라·엔진마다 저장하며 Benchmark의 profile은 바꾸지 않습니다. 적용 후에는 설정을 연 화면으로 돌아갑니다.

<details markdown="1" id="stream-readout" data-search-section>
<summary>P·Y·J·RAW·R 표시 읽기</summary>

P는 Preview, Y는 YUV, J는 JPEG, RAW는 센서 원본 출력의 크기입니다. 녹화 중에는 P·R과 H264·HEVC·Auto를 표시하며, R은 녹화 출력입니다. 녹화 중 사진을 지원하는 구성에는 JPEG 출력도 추가됩니다. 구성 검사와 저장 방식은 [Engine Comparison](engine.md#camera2-엔진)에서 확인하세요.

</details>


</details>

<a id="두-물리-카메라를-함께-확인하세요"></a>
<a id="detail-e8f4afe277"></a>
<a id="전면과-후면을-함께-촬영하세요"></a>
### 여러 카메라를 함께 촬영하세요

`Live Streams → Multi → Maximum camera devices`에서 최대 동시 장치 수를 설정하고 `Apply`를 누릅니다. 기본값은 `All available`입니다. Multi 화면의 `Streams`에서도 같은 설정을 엽니다.

Live의 `Multi · P`(사진) 또는 `Multi · V`(영상)에서 카메라 조합을 선택합니다. Android 11 이상이며 기기가 독립 장치의 동시 실행을 지원해야 합니다. CameraX에서는 Camera2로 진입합니다.

1. 상단에서 공개 Service Camera ID 조합을 선택합니다. Logical capability가 없는 카메라도 조합에 포함될 수 있습니다. 장치별로 분할된 프리뷰가 열리며, 조합 선택창의 `Streams`에서 현재 구성 상태를 확인합니다.
2. 각 화면의 `PIP`에서 Physical 또는 다른 Service 카메라 하나를 선택합니다. 목록은 위쪽에 종류·ID, 아래쪽에 방향·렌즈 정보를 표시하며 다시 열면 이전 스크롤 위치로 돌아갑니다. 전면 카메라도 선택할 수 있습니다. `Turn off PIP`로 합성을 끕니다.
3. 보조 영상을 드래그하거나 해당 화면의 `Move`로 위치를 바꿉니다. 보조 영상은 해당 장치의 화면 안에서만 이동합니다. 이동한 위치는 메인 카메라별로 기억하며 모드·PIP 카메라 변경이나 PIP Off·On 후에도 유지합니다. Single과 Multi는 각각의 위치를 기억합니다.
4. `Multi · P`에서는 `Photo`로 촬영합니다. `Multi · V`에서는 `Record`로 녹화를 시작하고 `Stop`으로 저장합니다. Multi의 결과는 장치별 파일이며 PIP를 켠 장치에는 그 화면의 합성 구도가 저장됩니다. 조작 버튼과 카메라 라벨은 저장하지 않습니다.
5. `Live` 또는 뒤로 가기로 돌아갑니다. 모든 장치와 합성 출력의 종료를 확인한 뒤 기존 Live 프리뷰를 복구합니다.

Single에서는 API 선택 옆의 `PIP`에서 보조 카메라 하나를 선택합니다. Android 11 이상에서 선택 후보가 있으면 사용할 수 있으며, Logical capability는 Physical 후보에만 필요합니다. 목록에 있는 Service 카메라도 실제 동시 출력 구성이 지원되어야 열 수 있습니다.

Camera2 Single에서는 현재 Live 화면과 CameraDevice를 유지합니다. 다른 Service ID는 그 장치만 추가로 열며, Physical ID는 현재 장치에 출력을 추가합니다. 기존 셔터와 Photo·Video 모드로 합성 사진·영상을 저장하고, 보조 화면을 드래그할 수 있습니다.

CameraX에서도 같은 PIP 버튼·셔터·드래그를 사용합니다. CameraX가 광고한 동시 조합의 Service ID만 표시하며 Physical ID는 제공하지 않습니다. Camera2와 후보 목록이 다를 수 있고, 후보가 없으면 PIP 버튼이 비활성화됩니다. 메인 ID는 유지하되 PIP를 켜고 끌 때 단일·동시 세션을 다시 bind합니다. 프리뷰 입력은 각각 720p 이하에서 협상하고, 일반 사진·녹화의 출력 설정 대신 화면용 합성 JPEG·H.264/AAC MP4를 저장합니다. PIP를 끄면 일반 스트림 설정으로 복귀합니다. 목록 스크롤은 엔진·메인 ID별로, 프리뷰 위치는 메인 ID별로 기억합니다.

Multi에서 PIP를 바꾸면 전체 Multi 세션을 닫고 다시 구성합니다. 이미 연 Service ID는 중복해서 열지 않으며, 추가 Service 장치도 최대 장치 수에 포함합니다.

JPEG와 MP4는 `DCIM/HALCamera`, Multi 사진 묶음 JSON은 `Download/HALCamera`에 저장합니다. Single PIP 사진은 합성 JPEG 한 장이며 일반 사진의 YUV·JPEG 쌍이나 RAW·메타데이터 JSON을 저장하지 않습니다. Single PIP 영상은 녹음 권한으로 오디오를 포함하며, Multi 영상은 무음입니다. PIP 사진은 화면용 합성 해상도입니다. Single PIP의 플래시는 Off·Torch만 제공하며 AEB는 사용할 수 없습니다. Multi 사진 결과 안내의 `Details`에서 카메라별 저장 결과를 확인할 수 있습니다. 센서 동기 촬영은 보장하지 않습니다.
### 손떨림 보정을 선택하세요

<details markdown="1" id="detail-aa6a89b2d7" data-search-section>
<summary>손떨림 보정 설정</summary>

1. 촬영·녹화를 끝내고 Live Streams를 엽니다.
2. Stabilization의 Mode에서 모드를 선택합니다. 두 엔진 모두 Auto·Off와 지원되는 보정 모드를 표시합니다. CameraX의 EIS (Video)는 녹화 중에 적용됩니다.
3. `Apply`를 눌러 프리뷰를 재개합니다. Auto는 카메라 요청 템플릿의 기본값으로 복귀합니다.
4. Live 상단의 크기 표시 옆에서 실제 보정 모드를 확인합니다. 보정 표시를 누르면 설정을 다시 열 수 있습니다.

| 표시 | 의미 |
| --- | --- |
| EIS: V | 동영상 보정 모드입니다. |
| EIS: P, V | 프리뷰와 동영상 보정 모드입니다. |
| EIS: ? | 적용 상태를 확인할 수 없습니다. |
| 주황색 요청값·결과값 | 요청과 다른 모드가 1초 이상 보고됐습니다. |
| 표시 없음 | 보정이 정상적으로 미적용된 상태에서는 표시를 숨깁니다. |

이 표시는 카메라의 결과 메타데이터입니다. 실제로 흔들림이 얼마나 줄었는지를 측정한 값은 아닙니다.

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

프리뷰 정보의 `AE Locked`는 노출 잠금, `AF No focus`는 AF 잠금 상태에서 초점을 맞추지 못했음을 뜻합니다. [Callback](callback.md) 그래프를 켜면 이 정보 줄을 숨기고 프레임별 콜백을 표시합니다. 촬영 지연이나 노출 변화가 예상과 다르면 [Troubleshooting](troubleshooting.md#앱과-프레임워크hal을-구분하세요)을 확인하세요.

</details>


**다음 단계:** [Callback](callback.md)에서 촬영 프레임의 콜백과 시각을 확인하세요.
