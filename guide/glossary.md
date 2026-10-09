---
title: Glossary
---
<h1 lang="en">Find the meaning.</h1>

**모르는 용어만 골라 읽으세요.** HALCamera의 화면과 결과 파일에서 쓰는 뜻을 정리했습니다. 위의 **문서 찾기**에서도 영어 이름이나 한국어 설명으로 검색할 수 있습니다. 설명을 읽은 뒤 브라우저의 뒤로 가기로 원래 문서에 돌아가세요.

자주 찾는 용어: [Baseline](#baseline) · [Validity](#validity) · [Profile](#profile) · [AEB](#aeb) · [Timestamp](#timestamp)

| 찾는 내용 | 바로 가기 |
| --- | --- |
| 카메라와 출력 조건 | [카메라와 출력](#camera-streams) |
| 사진과 노출 | [사진과 노출](#capture-exposure) |
| 측정값과 판정 | [측정값과 판정](#measurements-verdicts) |
| 시각과 결과 파일 | [시각과 결과 파일](#timing-files) |

<h2 id="camera-streams" >카메라와 출력</h2>

<h3 id="engine">Engine · 카메라를 구동하는 방식</h3>

Live에서 선택하는 Camera2 또는 CameraX입니다. 같은 카메라라도 요청 구성과 관찰 가능한 콜백이 다릅니다. 결과를 비교할 때 엔진도 함께 기록하세요. [차이 확인](engine.md)

<h3 id="endpoint">Endpoint · 검사할 카메라 대상</h3>

어떤 논리 카메라와 물리 카메라를 사용할지 구분하는 대상입니다. Probe에 사양이 표시되는 물리 카메라라도 앱에서 단독으로 열 수 있다고 보장하지는 않습니다. [카메라 목록 확인](probe.md#사양-표를-읽으세요)

<h3 id="stream">Stream · 이미지가 나오는 출력 경로</h3>

Preview·YUV·JPEG·Recording 같은 출력입니다. 각 출력은 크기와 형식 등의 조건을 가집니다. 개별 크기를 지원해도 여러 출력을 함께 사용하는 조합은 실패할 수 있습니다. [출력 설정](live.md#live-스트림을-설정하세요)

<h3 id="profile">Profile · 벤치마크 실행 조건</h3>

출력 크기, 반복 횟수와 관측·촬영·녹화 조건을 묶은 설정입니다. Live의 스트림 설정과 별개입니다. 서로 다른 profile의 결과를 같은 조건의 측정으로 비교하지 마세요. [비교 조건](benchmark.md#비교-기준을-고르세요)

<h2 id="capture-exposure" >사진과 노출</h2>

<h3 id="yuv">YUV · 밝기와 색 정보로 구성한 이미지</h3>

HALCamera는 YUV 이미지를 JPEG로 변환해 저장할 수 있습니다. Camera2는 NV21 형식 저장도 지원합니다. 따라서 파일 이름에 YUV가 있어도 JPEG 파일일 수 있습니다. [저장 포맷](engine.md#yuv-저장-포맷)

<h3 id="raw">RAW / DNG · 센서 원본 계열 출력과 저장 형식</h3>

RAW는 센서 원본 계열 이미지 데이터이며, DNG는 이를 저장하는 파일 형식입니다. HALCamera에서는 RAW를 지원하는 카메라의 Camera2 엔진으로 DNG를 저장합니다. [엔진별 지원](engine.md#두-엔진의-차이)

<h3 id="3a">3A · 자동 노출·초점·화이트밸런스</h3>

AE는 자동 노출, AF는 자동 초점, AWB는 자동 화이트밸런스입니다. 수렴은 각 자동 제어가 안정된 상태로 가는 과정입니다. 장면 영향을 크게 받으므로 Benchmark의 3A는 변화량만 표시하고 저하 판정에 넣지 않습니다.

<h3 id="ev">EV · 노출 보정 단위</h3>

자동 노출을 기준보다 밝게 또는 어둡게 요청할 때 쓰는 단위입니다. 요청한 EV와 실제 노출 변화는 다를 수 있습니다. 센서 한계나 AE 동작의 영향을 보려면 저장된 노출 시간과 ISO도 확인하세요. [AEB 사용법](live.md#aeb)

<h3 id="aeb">AEB · 노출을 바꿔 연속 촬영</h3>

Auto Exposure Bracketing의 약자입니다. HALCamera에서는 현재 EV, 2 EV 어둡게, 2 EV 밝게 사진 3장을 요청합니다. 지원 범위 끝에서는 EV가 제한됩니다. 일반 연사와 조작이 다릅니다. [촬영 방법](live.md#aeb)

<h3 id="hdr">HDR · 이 앱에서는 AEB 사진의 노출 융합</h3>

`_AEB_HDR.jpg`는 AEB 원본 세 장을 앱이 합친 이미지입니다. 이 파일명만으로 센서의 HDR 모드나 Ultra HDR 형식이라고 해석하지 마세요. 합성 조건과 성공·생략·실패 안내는 [AEB 결과 표](live.md#aeb)에서 확인하세요.

<h2 id="measurements-verdicts" >측정값과 판정</h2>

<h3 id="baseline">Baseline · 저하 판정의 기준</h3>

성능 변화를 판단하기 위해 명시적으로 지정한 실행 또는 실행 집합입니다. baseline 없이 이전 실행을 자동으로 비교할 때에는 변화량만 표시합니다. `두 실행 비교`에서는 먼저 고른 실행을 그 비교의 기준으로 씁니다. [기준 선택](benchmark.md#비교-기준을-고르세요)

<h3 id="validity">Validity · 측정 결과를 사용할 수 있는 조건</h3>

표본 수와 실행 상태 등을 바탕으로 측정·비교·점수에 사용할 수 있는지 구분합니다. 작업이 성공해 JSON이 저장됐다는 뜻과는 다릅니다. 결과가 무효라면 성능 저하로 판단하기 전에 실행 정보의 사유를 확인하세요. [결과 해석](benchmark.md#판정과-막대를-읽으세요)

<h3 id="warmup">Warmup · 본 관측 전 준비 구간</h3>

Benchmark는 관측 시작 전의 프레임을 프리뷰 관측 통계에서 제외합니다. 워밍업과 관측 조건이 다른 실행을 같은 표본으로 취급하지 마세요. 지표별 표본 규칙은 [실행 조건](benchmark.md#실행-조건을-확인하세요)에서 확인하세요.

<h3 id="percentile">p50 / p95 · 반복 측정값의 분포</h3>

p50은 중앙값이며, p95는 큰 값 쪽의 95백분위수입니다. 평균과는 다릅니다. 표본이 적으면 p95가 사실상 최댓값이 될 수 있으므로 표본 수도 함께 읽으세요. [녹화 표본 예시](benchmark.md#실행-조건을-확인하세요)

<h3 id="verdict">Verdict · 검사 결과에 대한 판정</h3>

측정값 자체와 그 값을 규칙에 대입한 판정은 다릅니다. CTS의 PASS·FAIL·SKIP과 Benchmark의 성능 저하 판정도 서로 다른 규칙을 사용합니다. 앱의 CTS 결과는 공식 CTS 인증 판정을 대신하지 않습니다. [CTS 판정](cts.md#판정을-읽으세요)

<h2 id="timing-files" >시각과 결과 파일</h2>

<h3 id="callback">Callback · 앱이 받는 처리 알림</h3>

촬영 시작, 결과 메타데이터, 이미지 도착 등을 앱에 알리는 호출입니다. 콜백을 받은 시각은 HAL 내부 처리가 일어난 시각과 같지 않습니다. JPEG 도착도 파일 저장 완료를 뜻하지 않습니다. [관측 지점](callback.md#스트림별-도착-시각을-읽으세요)

<h3 id="timestamp">Timestamp · 어떤 시계로 기록한 시각</h3>

앱 시각과 센서 시각을 구분해야 합니다. 센서의 timestamp source가 REALTIME일 때만 앱의 `elapsedRealtimeNanos` 시각과 직접 비교합니다. Callback 그래프의 0 ms는 이전 Shutter 콜백 수신 시각입니다. [그래프 시간 기준](callback.md#시간-기준을-확인하세요)

<h3 id="metadata">Metadata · 이미지와 함께 기록되는 정보</h3>

노출 시간, ISO, 센서 시각처럼 촬영 조건과 결과를 설명하는 값입니다. 요청한 설정과 결과 메타데이터를 대조하면 실제 적용 여부를 확인하는 데 도움이 됩니다. 메타데이터 자체는 이미지 픽셀이 아닙니다.

<h3 id="request-id">Request ID · 자동화 작업의 식별자</h3>

CLI가 접수한 작업을 다시 조회하거나 파일을 받을 때 쓰는 ID입니다. 연결이 끊겼다면 이 ID로 기존 작업부터 확인하세요. 다시 촬영할 필요 없이 파일만 회수할 수도 있습니다. [완료 확인](cli.md#요청과-완료를-구분하세요)

<h3 id="artifact">Artifact / Incident ZIP · 작업 파일과 진단 기록</h3>

artifact는 CLI 작업이 만든 사진·보고서 같은 결과 파일입니다. incident ZIP은 문제 분석용 이벤트·메타데이터 묶음이며 이미지 픽셀을 담지 않습니다. 사진 파일과 진단 ZIP을 구분해서 공유하세요. [로그 수집](troubleshooting.md#로그와-진단-자료)

**다음 단계:** 처음 사용하는 기능이라면 [Quickstart](getting-started.md)에서 작업을 선택하세요.
