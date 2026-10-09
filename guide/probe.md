---
title: Probe
---
<h1 lang="en">What the HAL claims.</h1>

**`Lab → Probe`에서 카메라와 찾을 사양을 고르세요.** 카메라가 공개한 지원 사양을 보여 주며, 실제 동작을 검사한 결과는 아닙니다.

## 앱에서 Probe를 여세요

1. Live에서 `Lab → Probe`를 엽니다.
2. 확인할 카메라를 선택합니다. 처음에는 Live에서 고른 카메라가 표시됩니다.
3. 필터에 `JPEG`처럼 찾을 단어를 입력하고 결과를 누릅니다.

**확인할 결과:** 선택한 카메라의 사양과 지원 값이 표시됩니다. 읽지 못한 항목은 실패 목록에 남습니다. 실제 출력 조합이 동작하는지는 [CTS](cts.md)로 따로 확인하세요.

다른 검사를 하려면 Lab으로 돌아갑니다.

읽기 실패는 미지원 판정이 아닙니다. 실패 항목과 내보낸 JSON을 보존하고, 반복되면 [재현 자료](troubleshooting.md#문제-발생-시-수집할-정보)를 함께 기록하세요.

<p class="doc-evidence">아래 화면은 2026년 9월 29일 Galaxy S25+·Android 16에서 HAL CAMERA 0.15.0을 실행해 촬영했습니다. <a href="evidence.html#앱-화면-촬영">촬영 조건과 확인 범위</a>를 함께 확인하세요. 이미지를 누르면 원본이 열립니다.</p>

<figure class="app-screenshot" id="screen-probe">
<a href="assets/screenshots/probe.png" aria-label="Probe에서 조회한 카메라 0의 Identity 사양 원본 보기"><img src="assets/screenshots/probe.png" alt="Probe에서 조회한 카메라 0의 Identity 사양" width="1440" height="3120" loading="lazy" decoding="async"></a>
<figcaption>카메라 0을 선택하고 Device 영역을 펼쳤습니다. 제조사·모델·Android·SoC와 선택 카메라의 LEVEL_3 선언을 확인할 수 있습니다. <a href="assets/screenshots/probe.png">원본 보기</a></figcaption>
</figure>

## 사양 표를 읽으세요

| 확인할 항목 | 확인하는 이유 |
| --- | --- |
| 카메라 목록 | 논리 카메라와 그 안의 물리 카메라를 구분합니다. 사양이 보여도 단독으로 열 수 없는 물리 카메라가 있습니다. |
| 섹션 | 제목을 눌러 접거나 펼칩니다. 출력 형식별 크기는 STREAMS에서 확인합니다. |
| 필터 | 단어가 들어간 줄을 찾습니다. 결과를 누르면 해당 항목으로 이동합니다. |
| 읽지 못한 항목 | 실패 목록과 내보낸 파일에서 누락 내용을 확인합니다. |
| REQUEST · RESULT KEYS / SESSION KEYS | 요청·결과에서 지원하는 key 이름입니다. 실제 촬영값은 아닙니다. |

<details markdown="1" id="probe-fields" data-search-section>
<summary>섹션 이름과 API별 표시 조건</summary>

IDENTITY, CAPABILITIES, SENSOR, LENS, CONTROL, PROCESSING, REQUEST 뒤에 REQUEST · RESULT KEYS가 나옵니다. SESSION KEYS는 API 28 이상, MANDATORY STREAM COMBINATIONS는 API 29 이상에서 표시합니다.

STREAMS는 PRIVATE·JPEG 같은 출력 형식별로 나뉩니다. 이어서 HIGH SPEED VIDEO, REPROCESSING INPUTS, ALL CHARACTERISTICS가 나옵니다. enum 값은 가능한 경우 숫자 대신 상수 이름을 표시합니다.

</details>

## 사양을 내보내세요

`TXT`와 `JSON`은 모든 카메라의 사양을 파일로 공유하고, `Copy`는 현재 카메라만 클립보드에 넣습니다. 이미지 픽셀은 포함되지 않습니다. 같은 기기의 빌드 전후를 비교하거나, 다른 기기의 HAL이 무엇을 공개하는지 나란히 볼 때 JSON을 씁니다. Benchmark 결과 JSON과는 별개 파일이며 자동으로 연결되지 않습니다.

<details>
<summary>구현과 코드 근거</summary>
<p>Live 카메라는 Lab 진입 전에 닫힙니다. 화면 배치 기준은 <a href="https://github.com/TTolsun/hal-camera/blob/main/docs/design/APP-UI.md">APP-UI.md</a>의 “Lab과 독립 화면”에 있습니다.</p>
<p><code>CameraProbeReader</code>가 카메라 사양을 읽고, <code>CameraProbeSnapshot</code> 모델을 거쳐 화면 표·TXT·JSON으로 만듭니다. Reader만 <code>CameraManager</code>를 알며, 모델과 렌더러는 JVM 테스트로 검증합니다.</p>
<p class="doc-evidence">저장소의 <code>app/src/main/java/dev/halcamera/camera/</code>에서 <code>CameraProbe.kt</code>(모델·TXT·JSON 렌더러), <code>CameraProbeReader.kt</code>(CameraManager 읽기)와 <code>app/src/main/java/dev/halcamera/CameraProbeActivity.kt</code>(화면)를 확인하세요. 코드 확인과 기기 검증의 범위는 <a href="evidence.html">Validation</a>에서 구분합니다.</p>
</details>

**다음 단계:** 사양 표에서 본 스트림 조합이 실제로 열리고 판정을 통과하는지 [CTS](cts.md)로 확인하세요.
