---
title: Callback
---
<h1 lang="en">One frame.<br>Every callback.</h1>

**사진 한 장을 찍고, 이미지와 메타데이터가 어느 순서로 도착하는지 보세요.** Live의 `Callback`은 앱이 받은 콜백을 같은 시간축에 표시합니다. 점은 도착 시각이고 오른쪽 숫자는 ms 값입니다.

## 촬영 프레임을 고정하세요

1. Live에서 `Callback`을 켭니다.
2. `Hold` 옆 시간 버튼을 눌러 `3s`를 고릅니다.
3. 사진을 한 장 찍습니다.
4. 프레임 번호 앞에 일시정지 표시가 나타나면 이미지와 Metadata의 위치를 비교합니다.

**사진이 먼저일까요, 메타데이터가 먼저일까요?** 더 왼쪽에 있는 점이 앱에 먼저 도착했습니다. 순서는 엔진·출력·기기에 따라 달라질 수 있습니다. 고정되는 것은 그래프이며 카메라와 녹화는 계속 동작합니다.

| 표시·조작 | 의미 |
| --- | --- |
| `Frame #1527` | 현재 보고 있는 카메라 프레임 번호입니다. 촬영 버튼을 누른 횟수가 아닙니다. |
| `Hold 0s` | 기본값입니다. 프레임을 계속 갱신합니다. |
| 시간 버튼 | `0s → 1s → 3s → 5s → 10s`로 순환합니다. 0초가 아니면 촬영 출력이 도착한 프레임을 그 시간 동안 유지합니다. |
| 시간 변경 | 현재 고정을 풀고 다음 촬영부터 새 시간을 적용합니다. 모드·엔진 변경과 앱 재실행 후에도 설정은 유지합니다. |

고정 중 같은 프레임의 늦은 결과도 채워집니다. `Callback`을 다시 누르면 그래프가 닫히고 FPS·EXP·AE·AF 정보가 돌아옵니다. 촬영 버튼은 계속 사용할 수 있습니다.

## 시간 기준을 확인하세요

**0ms는 이전 프레임의 Shutter 콜백을 앱에서 받은 시점입니다.** 촬영 요청을 보낸 시점이나 현재 프레임의 시작이 아닙니다.

<figure class="doc-visual" aria-labelledby="callback-example-title">
<p id="callback-example-title"><strong>90ms라고 쓰여 있어도 현재 프레임에서 90ms 걸린 것은 아닙니다.</strong> · 설명용 예시</p>
<div class="doc-timeline" aria-hidden="true">
<div><span>이전 Shutter</span><span class="doc-time-track"><i style="--at:0%"></i></span><b>0 ms</b></div>
<div><span>현재 Shutter</span><span class="doc-time-track"><i style="--at:33%"></i></span><b>33 ms</b></div>
<div><span>Metadata</span><span class="doc-time-track"><i style="--at:90%"></i></span><b>90 ms</b></div>
<div><span>Preview</span><span class="doc-time-track"><i style="--at:95%"></i></span><b>95 ms</b></div>
</div>
<figcaption>실측이 아닌 계산 예시입니다. 현재 Shutter가 33ms이므로 Metadata까지는 90 − 33 = <strong>57ms</strong>, Preview까지는 95 − 33 = <strong>62ms</strong>입니다.</figcaption>
</figure>

실제 그래프에서는 해당 프레임의 Shutter 값을 빼세요. 첫 프레임의 Shutter만 0ms이며, 그다음부터는 직전 프레임과의 콜백 간격입니다. 센서 시각과 앱 시각을 구분하는 이유는 [Timestamp](glossary.md#timestamp)에 있습니다.

<details markdown="1" id="callback-axis" data-search-section>
<summary>시간축과 갱신 규칙</summary>

시간축은 값에 맞춰 늘어납니다. 작은 범위가 3초 동안 유지되면 줄어들며, 고정 중에는 늦은 결과를 담기 위한 확장만 합니다. 범위를 직접 설정할 필요는 없습니다.

평상시에는 100ms 주기로 최신 결과가 모인 프레임을 표시합니다. 특정 출력이 250ms 이상 도착하지 않으면 다음 프레임으로 진행합니다. 그래프 재진입이나 출력 재구성 때 이전 촬영을 다시 고정하지 않습니다.

</details>

## 스트림별 도착 시각을 읽으세요

**이미지 도착과 파일 저장 완료는 다릅니다.** 그래프는 현재 메인 Service ID의 결과와 구성된 출력을 보여 줍니다.

| 행 | 관측하는 시점 |
| --- | --- |
| Shutter · Metadata | 각각 `onCaptureStarted`, 최종 `onCaptureCompleted`를 받은 시점입니다. Partial은 표시하지 않습니다. |
| Preview · Recording | 일반 Camera2 경로의 Android 13 이상에서 PRIVATE 버퍼를 화면·인코더에 전달하기 전의 시점입니다. 실제 화면 표시 시각은 아닙니다. |
| YUV · JPEG · RAW | 해당 이미지가 도착한 시점입니다. YUV가 여러 개여도 내부 스트림 ID로 구분합니다. |
| Meta (Phy) · Preview (Phy) · YUV (Phy) | 메인 Logical Camera에 속한 Physical 결과·출력입니다. 구성한 종류에 맞는 행을 표시합니다. |
| Jpeg | YUV 앱 변환 또는 PIP 합성 JPEG가 준비된 시점입니다. HAL JPEG의 도착 행과 구분합니다. |

### PIP를 켜면 무엇이 추가되나요?

메인 Logical Camera의 Physical PIP는 Physical 메타데이터와 각 프리뷰 입력을 함께 표시합니다. 메인 YUV 스트림을 켜 두었다면 YUV도 유지합니다. 다른 Service ID를 PIP로 열었을 때에는 그 장치의 결과를 메인 프레임의 결과처럼 섞지 않습니다.

<details markdown="1" id="callback-engine-limits" data-search-section>
<summary>Camera2·CameraX·기존 Dual의 관측 차이</summary>

일반 Camera2 경로의 Android 12 이하와 일반 CameraX 경로는 Preview·Recording 버퍼를 직접 관측하지 못하므로 `No callback`을 표시합니다. PIP에서는 합성기에 들어오는 프리뷰 입력을 별도로 기록합니다.

CameraX의 YUV는 ImageAnalysis 수신, 카메라 JPEG는 ImageCapture 수신입니다. JPEG 출처로 YUV를 선택하면 촬영 뒤 받은 analysis 프레임을 앱에서 변환합니다. YUV와 카메라 JPEG 두 장을 짝지어 저장하지 않습니다.

CLI 호환용 Dual의 Main/Sub display는 화면 갱신 시점이며 Preview 버퍼 수신과 다릅니다. Camera2의 Main/Sub photo는 두 센서 이미지 수신입니다. 이 차이를 센서 동기화 성능으로 해석하지 마세요.

이미지와 프레임은 센서 타임스탬프를 대조하여 연결합니다. 그래프 값은 앱 수신 시각이며 HAL 내부 처리 완료 시각을 직접 측정한 값은 아닙니다.

</details>

## 값이 없는 이유를 확인하세요

| 표시 | 다음 행동 |
| --- | --- |
| Awaiting data | 아직 결과가 없습니다. 프리뷰가 진행되는지 확인합니다. |
| Not requested | 현재 프레임이 그 출력을 요청하지 않았습니다. 사진 출력을 보려면 사진을 찍습니다. |
| Awaiting capture | 요청 대상 목록을 모르는 프레임에서 촬영 출력을 기다립니다. 사진을 찍고 확인합니다. |
| No callback | 현재 경로에서 관측할 수 없습니다. 위의 엔진별 조건을 확인합니다. |
| No start time | 출력은 받았지만 기준 시각을 연결하지 못했습니다. 다음 프레임에서도 반복되는지 확인합니다. |

문제가 반복되면 [진단 ZIP과 재현 조건](troubleshooting.md#문제-발생-시-수집할-정보)을 저장하세요.

<details markdown="1" id="callback-hold-flow" data-search-section>
<summary>이전 화면과 검증 근거</summary>

아래는 2026-09-27의 0.15.0 촬영본입니다. 현재의 `Frame`·`Hold 0s` 조작과 버튼 이름이 다릅니다. 당시 관측 결과를 보존한 자료이며 현재 사용법은 이 페이지의 절차를 따릅니다.

<div id="screen-callback-recording"><a id="detail-fb127fe169"></a>
<a href="assets/screenshots/callback-recording.png">이전 녹화 Callback 화면 보기</a></div>
<div id="screen-callback-event"><a id="detail-8d0a8ea66f"></a>
<a href="assets/screenshots/callback-event.png">이전 촬영 프레임 고정 화면 보기</a></div>

현재 코드는 `ui/ResultCallbackGraph.kt`(버튼), `ResultCallbackTimeline.kt`(고정·프레임 선택), `ResultCallbackSeries.kt`(결과 연결), `camera/StreamConfiguration.kt`·`PipOutputs.kt`(출력 이름)에서 확인합니다. [Validation](evidence.md)에는 코드 확인과 기기 관찰을 구분해 기록합니다.

</details>

**다음 단계:** 같은 조건을 반복 측정하려면 [Benchmark](benchmark.md)를 여세요.
