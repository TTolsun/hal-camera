# 작업 상태

2026-09-24에 녹화(3.x) 지표를 구현했습니다([이슈 #122](https://github.com/TTolsun/hal-camera/issues/122)). 벤치마크 시퀀스의 사진 촬영 뒤에 RECORD 단계가 붙어, 9초짜리 녹화를 다섯 번 반복하고 3.1·3.2·3.4·3.6·3.7 다섯 지표를 계산합니다. 3.3은 `MediaRecorder`로 인코더 쪽 프레임 수를 얻을 수 없으므로 `not_measurable`을 유지하고, 3.5는 별도 시나리오로 남겼습니다. 설계와 결정 근거는 [PLAN-Recording-v0.1.md](PLAN-Recording-v0.1.md)에 있습니다.

이 변경으로 profile이 `camera2-standard-v2`가 되었습니다. v1으로 저장된 실행 파일은 계속 읽히고 v1끼리도 계속 비교되지만, v2와는 비교되지 않으므로 기기마다 baseline과 calibration을 다시 만들어야 합니다. run JSON은 schema 5이고 validity flag 표는 `validity-v3`, 회귀 규칙은 `regression-rule-v2`입니다. 점수 체계는 건드리지 않았습니다. RECORD 카테고리의 가중치를 0으로 두었으므로 계산과 `score-v1-draft`라는 이름이 그대로입니다. 녹화 지표를 점수에 넣는 일은 [이슈 #123](https://github.com/TTolsun/hal-camera/issues/123)의 민감도 검증 뒤로 미뤘습니다.

실기기 확인(2026-09-24, Galaxy S25+ `SM-S936N` · Android 16 · `BP4A.251205.006.S936NKSSCCZH2` · versionCode 140 로컬 release 빌드): 카메라 4대에 run을 한 번씩 돌려 RECORD 단계가 끝까지 도는 것을 확인했습니다. 시작 카드는 약 95초를 안내하고 `녹화 9초 5회`를 적으며, 진행 화면은 `6 / 7 Recording`을 표시합니다.

| 카메라 | 3.1 record start | 3.4 steady fps | 3.6 record stop | 3.7 jitter | 3.2 anomalies |
|---|---:|---:|---:|---:|---:|
| 0 (Wide · Rear) | 186 ms | 30.0 fps | 122 ms | 0.0 ms | 0 |
| 1 (Front) | 179 ms | 30.0 fps | 120 ms | 0.0 ms | 0 |
| 3 (Front) | 209 ms | 30.0 fps | 128 ms | 0.0 ms | 0 |
| 2 (UWide · Rear) | 190 ms | 30.0 fps | 124 ms | 0.0 ms | 0 |

3.4는 네 대 모두 정확히 30.0 fps였고 3.2는 0이므로, 고정 cadence 구간 판정이 의도대로 동작합니다. 3.1은 179–209 ms, 3.6은 120–128 ms 범위입니다. 3.7은 0.0 ms로 표시되는데, 센서 timestamp의 간격이 그만큼 규칙적이라는 뜻입니다. 소수점 아래 자릿수는 화면에서 확인할 수 없으므로 원시값은 run JSON의 `raw.record[].jitter_stddev_ms`를 봐야 합니다. 카메라 2는 고정 초점이라 3A에 AF가 없는데, 이는 profile이 허용한 예외입니다.

이번 확인에서 결함을 하나 찾아 고쳤습니다. 결과 화면의 막대는 `ResultPresenter.format`이 아니라 `keyMetric`이라는 별도 경로로 숫자를 만들고 있었고, 그쪽에는 소수점 규칙이 없어서 `Record jitter`가 `0 ms`로 찍혔습니다. 두 경로를 `formatValue` 하나로 합쳤습니다. 단위 테스트는 `format`만 검사하고 있었기 때문에 통과한 상태였습니다.

이 확인은 USB로 연결한 채 수행했으므로 네 run 모두 `CHARGING` flag가 붙어 점수 산정에서는 제외됩니다. 기능 확인이 목적이었고 점수용 자료 수집은 아닙니다.

0.13.0은 벤치마크 기능을 줄였습니다. 반복 측정 비교 화면을 제거했고(다기기 분포 수집을 하지 않기로 결정하면서 여러 실행을 묶어 순열검정을 수행하는 화면과 외부 JSON 가져오기의 용도가 사라졌습니다), 시작 카드의 `Details` 접힘과 Build·Commit·메모 입력 세 칸, 결과·비교 화면의 복사 버튼, Results의 Profile 필터를 함께 없앴습니다. 조작점은 58개에서 35개로 줄었고 벤치마크 비교는 baseline과 이번 실행, 두 개 사이에서만 이루어집니다.

결과 화면은 모든 측정 항목을 카테고리별 막대로 그립니다. 숫자 표를 담고 있던 `All metrics` 접힘을 없앴고, 막대 옆 숫자는 표본의 중앙값입니다. 실행 정보 접힘은 서로 반복되던 문장 일곱 개 대신 라벨·값 아홉 줄이며 validity flag를 코드가 아니라 뜻으로 적습니다. Results 목록의 각 행은 monospace 여섯 줄에서 시각·뱃지·수치 두 줄로 바뀌었고, `Data limit`은 등간격 슬라이더로 고릅니다.

0.13.2는 같은 검토에서 남은 이슈를 정리했습니다. Live 아래의 모든 화면이 제목 왼쪽에 뒤로 가기 아이콘을 두고, Benchmark와 실행 기록의 버튼·메뉴·화면 제목이 한국어이며(지표명·판정 단어·상태 칩은 run JSON과 같은 영어 철자를 유지합니다), `History` 버튼과 그 화면의 제목이 모두 `실행 기록`입니다. 도구 메뉴에서 라디오 버튼을 없앴고, 동영상 저장 안내가 사진과 같은 자리에 뜹니다. 진단 패널의 incident 다이얼로그에 `취소`가 생겼고, 시스템 줄은 `Thermal` 레이블과 CPU 척도 `(1코어=100%)`를 직접 적습니다. `Camera2Engine`의 벤치마크 녹화와 환경 수집을 별도 클래스로 나눈 구조 개선(#155)도 이 판에 들어갔으며, 합친 코드로 벤치마크 RECORD 단계를 기기에서 다시 확인했습니다. [릴리스 노트](releases/0.13.2.md)를 참고하세요.

0.13.1은 앱을 손으로 눌러 가며 검토한 결과를 반영했습니다. 진단 패널의 타임라인 라벨이 겹쳐 `62.767.5`로 읽히던 것, PROBE의 값이 잘리거나 숫자 중간에서 쪼개지던 것, CTS 로그의 잘린 가장자리를 고쳤습니다. 결과 화면의 막대는 같은 묶음에서 단위가 같은 지표끼리 축을 공유하므로 이제 길이가 값의 크기를 나타냅니다. 갤러리 부제가 필터를 따라가고, 전면 카메라 둘을 환산 초점거리로 구별하며, 최근 미디어 썸네일이 동영상임을 표시합니다. 녹화 중 진단 패널에서 녹화를 끝낼 수 있고, CTS와 일시정지 화면은 멈춘 프레임 위에 지금이 어느 상태인지 적습니다. [릴리스 노트](releases/0.13.1.md)를 참고하세요.

`도구` 메뉴와 문서 탭이 모두 Probe · CTS · Benchmark 순서입니다. 화면마다 다르게 부르던 카메라 이름도 `Camera · 0 (Wide · Rear)`와 축약형 `Camera · 0` 두 가지로 통일했고, 표기는 `camera/CameraEndpoint.kt`의 `CameraLabel` 하나가 만듭니다. 아키텍처 문서에 뭉쳐 있던 화면 설명은 각 탭으로 옮겼으며, 문서와 코드를 전수 대조해 어긋난 서술 아홉 곳을 고쳤습니다. [릴리스 노트](releases/0.13.0.md)를 참고하세요.

0.12.0은 Benchmark 화면을 판정 중심으로 다시 구성했습니다. 결과의 첫 줄이 판정이고, 핵심 지표 네 개를 baseline 눈금이 있는 막대로 보여 주며, 비교 화면은 0 기준선 좌우의 변화율 막대로 바뀌었습니다. `Settings`에서 profiling data 보관 개수를 정할 수 있고, 한도를 넘으면 baseline을 제외하고 오래된 것부터 삭제합니다. [릴리스 노트](releases/0.12.0.md)를 참고하세요.

0.11.0은 ADB만으로 프리뷰·촬영·녹화를 제어하는 셸 CLI를 APK에 포함했습니다. [릴리스 노트](releases/0.11.0.md)를 참고하세요.

0.10.1은 HAL 개발자용 UI를 정리했습니다. 프리뷰의 Mark ZIP은 투명 배경으로 유지하고 Benchmark는 도구 메뉴에서 엽니다. 진단 그래프·회귀 지표를 먼저 표시하고 세부 설명은 펼쳐 봅니다. [릴리즈 노트](releases/0.10.1.md)를 참고하세요.

2026-09-19 · HAL CAM 0.10.0([릴리스 0.10.0](releases/0.10.0.md)). CLI에 `probe`·`cts.cases`·`cts.run`을 더하고([PR #107](https://github.com/TTolsun/hal-camera/pull/107)), AOSP 원문 `StillCaptureTest`·`BurstCaptureTest`를 `RecordingTest` 옆에 가져왔으며([PR #108](https://github.com/TTolsun/hal-camera/pull/108), 커스텀 케이스 다섯 개는 [PR #112](https://github.com/TTolsun/hal-camera/pull/112)로 복원), 카메라를 열지 않은 원문 메서드를 SKIP으로 표시하고 어떤 키의 어떤 값 때문인지 붙입니다([PR #113](https://github.com/TTolsun/hal-camera/pull/113)). 0.9.0 이전에 CTS 화면을 케이스 목록으로 바꾸고 FastOnOff·Switching·AllSizeOnOff·StillPreviewCombination·VideoSnapshot 다섯 커스텀 케이스를 더했으며([PR #98](https://github.com/TTolsun/hal-camera/pull/98), Galaxy S25+에서 모두 PASS), 그 위에 AOSP CTS 소스를 그대로 가져와 앱 안의 JUnit으로 실행하는 CTS 원문 경로(`:ctsvendor` 모듈, [PR #100](https://github.com/TTolsun/hal-camera/pull/100))를 추가하고 `testBasicRecording`은 그 경로로 옮겼습니다. Probe에 request·result key 이름 목록 섹션을 더하고([PR #97](https://github.com/TTolsun/hal-camera/pull/97)), 진단 패널에 앱 정보 다이얼로그를 추가했습니다([PR #99](https://github.com/TTolsun/hal-camera/pull/99), [릴리스 0.9.0](releases/0.9.0.md)). 0.8.1의 `도구` 메뉴 배치([PR #82](https://github.com/TTolsun/hal-camera/pull/82))와 CTS 케이스 화면([PR #76](https://github.com/TTolsun/hal-camera/pull/76)), Probe 필터 개선([PR #78](https://github.com/TTolsun/hal-camera/pull/78))을 유지합니다. 0.7.0의 반복 측정 비교([PR #72](https://github.com/TTolsun/hal-camera/pull/72))와 Probe 화면([PR #75](https://github.com/TTolsun/hal-camera/pull/75)), 0.6.0의 PC CLI(0.1.0), 0.5.1의 촬영·갤러리 개선을 유지합니다. [PR #48](https://github.com/TTolsun/hal-camera/pull/48)의 M5a 내부 점수 초안을 포함하며 민감도 검증은 진행 중입니다.

## 지금 어디까지 왔는가

| 마일스톤 | 상태 |
|---|---|
| M1 Data contract | 완료 |
| M2 Measurement correctness | 완료. Galaxy S25+ 실기기 확인 |
| M3 Product conversion | 완료 |
| M4 Developer workflow | 완료. baseline 지정과 vs baseline / vs previous 열을 실기기에서 확인 |
| M5a Internal score | 내부 점수 초안 구현. 2026-09-26 v2 민감도 재검증에서 저조도·발열·점유 경쟁 모두 저하 기준 미달([검증 기록](validation/score-sensitivity-20260926.md)). 점수 설계 검토가 남음 |
| M5b Public endpoint score | 데이터 확보 대기. 여러 제조사·성능군의 5–10개 기기 분포 필요 |
| M6 History / export | 완료. Results 필터, 두 run 비교·삭제, JSON·CSV 내보내기, PC 집계, subject 재사용 (PR #39, 이슈 #10 종료) |

M4의 내용은 계획보다 앞당겨 M3 2단계에서 함께 구현하고 검증했습니다.

[전환 총괄 #4](https://github.com/TTolsun/hal-camera/issues/4)는 2026-09-16에 Galaxy S25+(Qualcomm) 실제 자료로 동일 기기 수정 전후 비교 흐름을 끝까지 확인하고 종료했습니다. 사용자 결정에 따라 Exynos·MediaTek 기기 검증은 이 epic의 완료 조건에서 제외했으며, 다른 vendor 기기가 준비되면 별도 이슈로 진행합니다. [M5 #9](https://github.com/TTolsun/hal-camera/issues/9)는 목표 변경으로 `not_planned` 종료했고 M1–M4와 M6의 개별 이슈도 종료되었습니다.

## M3 이후 완료한 일

- PR #39에서 실행 이력과 CSV 내보내기를 구현했습니다.
- PR #41–#45에서 Live 사진·동영상 저장, 앱 내 갤러리, 촬영 제어와 카메라 선택 UI를 구현하고 개선했습니다. 이 녹화 기능과 아래의 녹화 성능 계측(3.x)은 별도 기능입니다.
- PR #46에서 HAL CAM 0.5.0을 릴리스했습니다.
- PR #47에서 `.omm/`의 Doctor 요소를 제거하고 개발자 가이드와 docgen 근거 검사를 갱신했습니다. 이전 STATUS에 적혀 있던 아키텍처 문서 정리는 완료되었습니다.
- PR #81·#82에서 Live 개발자 진입점을 "현재 세션이 열려 있어야 의미가 있는가"로 나눴습니다. Probe·CTS·Benchmark는 상단 `도구` 메뉴의 독립 화면이고, Readout·그래프·Mark·incident는 `진단` 패널입니다. CTS·Benchmark는 Live 카메라의 `close(done)` 뒤에 열리며(S25+에서 약 260 ms), Probe는 대기 없이 엽니다. 배치 기준은 `docs/design/APP-UI.md`에 있습니다.

## M5 현재 데이터와 구현 (2026-09-12)

Galaxy S25+ (`SM-S936N`, Android SDK 36)에서 무선 ADB로 수집했습니다. 측정 앱은 0.5.0 release(`app.debuggable=false`)이며, 기기 빌드는 `BP4A.251205.006.S936NKSSCCZH2`, profile은 `camera2-standard-v1`, endpoint는 `Camera · 0 (Wide · Rear)`입니다.

| 데이터 | 횟수 | 용도 |
|---|---:|---|
| 최초 수집, 조명 불명확 | 10 | 사용자가 미리 조명을 낮췄다고 정정하여 학습에서 제외했습니다. 원본 메모는 보존했습니다 |
| 사용자가 확인한 저조도 | 3 | 민감도 검증에만 사용했습니다 |
| 사용자가 밝게 켠 뒤 확인한 정상 조건 | 10 | 내부 calibration 학습에 사용했습니다 |

밝은 조건의 10회는 모두 비충전·절전 해제·적격 run이며, 시작 배터리는 29–31%, thermal 시작·최고·종료는 모두 0입니다. 노출 부하 p50은 약 4,850–5,084로, 저조도 3회의 약 99,667과 구분됩니다. `scoring_eligible` 플래그만으로 정상 조명을 추정하지 않습니다.

원본 JSON과 수집 스크립트는 상위 작업 폴더의 `reviews/m5-20260912/`에 있습니다. `runs/`는 밝은 조건, `low-light/`는 저조도, `uncertain-lighting/`는 제외 데이터입니다. 저장소에는 [지표값·원본 해시·계수·비교 결과](data/m5-s25-main-20260912.json)를 보관합니다.

`ScoreComposer`와 `S25PlusScoreDraft`는 16개 지표, 네 카테고리의 동일 가중치, 0–1000 Camera Endpoint Score를 구현합니다. 3A의 가중치는 0입니다. 다른 모델·endpoint·계약, 디버그 빌드, 부적격 환경과 누락 지표에는 점수를 내지 않습니다. 결과 화면은 내부 초안임을 표시하고 JSON의 기존 `scoring_rule_version`, `summary.endpoint_score`, `metrics[].score` 필드에 저장합니다. 규칙은 [SCORING.md](SCORING.md)에 기록했습니다.

밝은 학습 데이터 점수는 786–834점(중앙값 805점), 저조도는 787·830·784점(중앙값 787점)입니다. 범위가 겹치고 저조도 1회가 정상 중앙값보다 높아, 저조도에서 일관되게 점수가 감소한다는 검증은 완료하지 못했습니다. 스트레스 결과에 맞춰 학습 계수나 가중치를 바꾸지 않았습니다.

검증: `assembleDebug assembleRelease testDebugUnitTest lintDebug` 통과, 단위 테스트 261개 성공. 코드 리뷰에서는 점수 계산, 적격 조건 검사, 결과 표시와 JSON 저장 경로를 확인했으며 병합을 막을 결함을 발견하지 못했습니다. M5a는 초안 구현 상태이며 발열·카메라 점유 경쟁 측정과 민감도 재검증이 남았습니다. M5b에 필요한 다기기 분포도 확보하지 못했으므로 #9와 #4는 열린 상태로 유지합니다.

0.5.1-dev release를 기존 앱과 같은 서명으로 설치한 뒤 `20260912-205801-811`을 추가 측정했습니다. 화면의 848점과 네 카테고리 점수, JSON의 `score-v1-draft`·16개 지표 점수·3A의 null을 확인했습니다. 이 실행은 `validation/`에 따로 보관하며 학습에 포함하지 않았습니다. 로컬 공유 수신 앱으로 내보낸 총 24개 JSON은 PC 사본과 SHA-256이 모두 일치했고, 임시 수신 앱은 제거했습니다. HAL CAM의 기존 실행 데이터는 유지했습니다.

## 이전 백업 점검 (2026-09-12)

로컬 `checkpoints/`, `device-backup-2026-09-10-rename/`의 JSON과 `device-backup-2026-09-11-before-release/dev.halcamera-data.tar` 안의 benchmark JSON을 확인했습니다. 경로는 이 저장소의 상위 작업 폴더 기준입니다. TAR는 압축을 풀거나 변경하지 않고 읽었습니다.

- `run_id`로 중복을 제외한 schema 3/4 측정은 13회이며, 기기 모델은 모두 Galaxy S25+ (`SM-S936N`)입니다.
- 현재 `tools/aggregate.py`의 플래그 규칙으로 재평가하면 적격 측정은 3회입니다. 나머지 10회에는 `CHARGING`이 있으며, 그중 7회에는 `PROFILE_DRAFT`도 있습니다.
- 적격 3회는 아래와 같습니다. 모두 `camera2-standard-v1`, thermal 최고값 0, 비충전, 절전 해제 상태입니다. `LABEL_MISSING`은 정보용 플래그이므로 적격 여부를 막지 않습니다.

| run_id | endpoint |
|---|---|
| `20260910-085923-916` | `Camera · 0 (Wide · Rear)` |
| `20260910-210231-192` | `Camera · 3 (Front)` |
| `20260910-212705-261` | `Camera · 2 (UWide · Rear)` |

세 측정은 앱 0.3.0의 `validity-v1` 기록으로 `app.debuggable`이 없습니다. 따라서 플래그상 적격이라는 사실만으로 release 빌드의 정상 조건 학습 데이터라고 확정하지 않습니다. 서로 다른 endpoint의 측정도 같은 endpoint의 반복 분포로 합치지 않습니다.

이 백업 점검 당시에는 연결 기기가 없었고 기존 데이터만으로 M5a를 시작할 수 없었습니다. 이후 무선 연결과 조명 조건 확인을 거쳐 위의 새 데이터를 수집했습니다. 구버전 백업은 새 calibration에 사용하지 않았습니다.

## M5를 이어서 진행하는 순서

1. 2026-09-26 v2 재검증([검증 기록](validation/score-sensitivity-20260926.md))에서 저조도·발열·점유 경쟁 각 6회가 모두 저하 기준을 만족하지 않았습니다. 원인은 척도 하한(중앙값의 15%)과, 측정 직전 기기 상태에 따라 두 갈래로 나뉘는 launch 지표입니다.
2. 이 두 원인을 점수 설계에서 어떻게 다룰지 별도로 검토합니다. 결과에 맞춰 계수를 바꾸지 않으며, 설계를 바꾸면 scoring rule 버전을 올리고 다시 측정합니다.
3. 점수 설계 검토 결과를 보고 M5a 완료 여부를 결정합니다.
4. 제조사와 성능군이 다른 5–10개 기기의 적격 분포로 공개 curve를 확정한 뒤 `score-v1`로 전환합니다. M5a와 M5b의 완료 근거를 확인한 후 #9와 #4를 종료합니다.

## M3에서 한 일

Doctor를 지웠습니다. consumer 홈과 incident 모드, Auto Check와 그 화면, 임계값 엔진과 표, 진단 규칙, health composer, health monitor, v0.2 리포트 작성기, Auto Check baseline store가 사라졌습니다. 모델에 있던 판정 어휘(State, ThresholdBasis, CddApplicability, ConditionEquivalence, HealthLevelV2, CauseLayer, MetricState, Diagnosis, Health)도 함께 지웠습니다.

Live는 측정한 것을 모두 유지하고 판정한 것을 모두 버렸습니다. 건강 배너와 진단 카드 대신 `ui/LiveReadout`이 같은 숫자를 판정 없이 표시합니다. strip에서 경고 색을 뺐고, flight recorder는 더 이상 `health_assessment` 이벤트를 쓰지 않습니다. 하단 버튼은 Mark · 셔터 · Benchmark 세 개이고, MainActivity가 런처가 되었습니다.

살아남은 파일은 이름에 맞는 패키지로 옮겼습니다. `check/`의 엔드포인트 두 개는 `camera/`로, `diagnosis/`의 MetricExtractor와 모델 잔여분은 `metrics/`로 갔습니다. `metrics`는 패키지 그래프의 leaf이며 비교도 표시도 알지 못합니다.

## 실기기 확인 (Galaxy S25+ · SM-S936N)

- 8.2 시작 카드: preflight `device_setup` 경로, CameraX 전환 안내, subject prefill 동작
- 8.3 진행 화면: 6단계, 시간 예산으로 가중한 진행률, 실시간 interval p50 · stall · frames · thermal
- 8.4 결과: eligibility 3단계 머리글, p50 / max 열 규칙, `vs baseline`과 `vs previous` 열
- 7.1: baseline이 없을 때 이전 run 대비 delta만 표시하고 REGRESSED를 붙이지 않음
- 7.2: Open이 +138 %여도 절대 차이 6 ms가 noise floor 10 ms 미만이라 회귀로 판정하지 않음
- `Set as baseline` / `Clear baseline` 전환
- 도구 메뉴(2026-09-16, versionCode 12 로컬 빌드): `도구` 목록 표시, Benchmark·CTS 진입 시 `카메라 세션 종료 중…` 뒤 화면 전환, Probe 즉시 전환, 녹화 중 `도구` 비활성화, 복귀 시 프리뷰 재시작
- 반복 측정 비교 실제 자료(2026-09-16): 2026-09-12의 0.5.0 실행 10회를 JSON으로 가져와 A, 0.8.0 CLI 실행 5회를 B로 선택. 확인 미체크 시 검정 보류, 체크 후 19개 지표 검정·7개 유의차, `regression-rule-v1` 판정과 분리 표시. 이 기능과 설명 문서(`docs/PROFILE-COMPARISON.md`)는 0.13.0에서 제거했습니다
- CTS 케이스 6종(2026-09-17, Galaxy S25+ SM-S936N · Android 16 · versionCode 15 로컬 빌드, 카메라 4대): 기본 녹화 PASS(카메라 0은 8 PASS 1 SKIP, CIF 프로파일 없음), FastOnOff PASS(첫 프레임 중앙값 표준 366.2 ms · fast reopen 366.8 ms), Switching PASS(round 5회 + HIGH 3초 녹화), AllSizeOnOff PASS(카메라 0 크기 26개, 4080x3060까지), VideoSnapshot PASS(4K 25초 드롭 0, 4080x3060 JPEG 3 MB가 350 ms 뒤 도착), StillPreviewCombination PASS(조합 324·300·264·286개, 약 29분). 조합 케이스 실행 중 `중단`은 완료된 68개 조합까지 `중단됨`으로 표시했고, 케이스 화면을 닫고 Live로 돌아오면 프리뷰가 다시 시작됩니다.
- CTS 원문 케이스(2026-09-17, `claude/cts-vendored`): AOSP `android16-release`의 `RecordingTest`를 `:ctsvendor` 모듈로 가져와 앱 안의 JUnit으로 실행하는 경로를 추가하고, 커스텀 `기본 녹화` 케이스는 이 경로로 대체해 제거했습니다. `assembleDebug`·JVM 테스트·lint는 통과했습니다. 실기기(Galaxy S25+, Android 16, versionCode 18 로컬 빌드, 2026-09-17)에서 `testBasicRecording`은 카메라 4대 × CamcorderProfile 7~8개를 3초씩 녹화·검증해 1분 58초 만에 PASS였고(프레임 드롭률 최대 1.1 %), `중단`은 5초 안에 `IllegalStateException: CameraDevice was already closed`로 테스트를 끝내며 `중단됨 · 12초 · 실패 1건`으로 표시됐습니다. 첫 실행에서 `perf-measure=on` 인수 때문에 카메라마다 가장 큰 프로파일 하나만 검증 없이 녹화해 16초 만에 PASS가 나온 문제는 `CameraParameterizedTestCase` 패치로 고쳤습니다. PR #100 merge 뒤 main 빌드(로컬 versionCode 100)로 다시 확인했습니다. `testBasicRecording`은 같은 조건에서 1분 58초에 PASS였고, 중단은 두 경로 모두 2~4초 안에 끝났습니다. 카메라가 열린 채 누르면 닫힌 카메라 때문에 `Wait for a capture start timed out in 3000ms`로, 카메라 0을 닫고 카메라 1을 열기 전에 누르면 `IllegalStateException: stopped by the host`(`Camera2SurfaceViewTestCase.updatePreviewSurface`)로 실패해 `중단됨 · 32초 · 실패 1건`이 표시됐습니다. `RecordingTest`의 나머지 메서드 중 `UiAutomation`·`@TestApi` 때문에 초기화에서 실패하는 목록은 아직 확인하지 않았습니다.
- CTS 체크리스트(2026-09-17, `claude/cts-suite`, Galaxy S25+ · Android 16 · versionCode 102 로컬 빌드): 커스텀 케이스와 CTS 원문 목록을 체크리스트로 바꾸고 체크한 항목을 `CtsSuiteRunActivity`가 차례로 실행합니다. 커스텀 `빠른 켜기·끄기`+`카메라 전환` 큐는 `2개 중 PASS 2 · FAIL 0 · 1분 5초`(33초 + 31초)로 끝났고, 결과 카드를 누르면 카메라별 표가 펼쳐졌습니다. `다시 실행` 뒤 9초에 `중단`을 누르자 첫 항목이 `중단됨 · 9초`, 둘째 항목이 `실행 안 함`으로 남고 머리글에 `중단됨`이 붙었습니다. 원문 `testBasicRecording` 하나를 체크한 큐는 같은 화면에서 `1개 중 PASS 1 · 1분 57초`로 끝나 단일 화면과 같은 시간이었고, 행의 `›`는 기존 단일 케이스 화면을 그대로 열었습니다. 목록으로 돌아가면 체크 상태가 유지됩니다.
- CTS 원문 StillCaptureTest·BurstCaptureTest 전체 실행(2026-09-17, Galaxy S25+ SM-S936N · Android 16 · versionCode 106 로컬 빌드, `halcam cts run` 24개 메서드 한 번에, 요청 `c6891837-3ad9-4cf9-910f-9d95a59c2d21`): PASS 23 · FAIL 1 · 24분 33초. `UiAutomation`·`@TestApi` 때문에 setUp에서 죽는 메서드는 없었습니다. 유일한 FAIL `StillCaptureTest#testAeCompensation`은 HAL 판정입니다: 카메라 0이 노출 시간 288,808,119 ns를 보고하는데 `SENSOR_INFO_EXPOSURE_TIME_RANGE` 상한은 213,334,400 ns이고(5회), 카메라 2는 +2 EV 보정 요청에도 노출 41,621 µs·감도 1500이 그대로였습니다(비율 1.0, 허용 0.8~1.2 대비 기대 2.0). 가장 긴 메서드는 `testStillPreviewCombination` 18분 29초, `testAeRegions` 63초, `testAfRegions` 44초이며, `testHeicUltraHdrCapture`·`testDynamicDepthCapture`·`testYuvBurstWithStillBokeh`·`testHeicExif`는 1초 미만에 끝나 이 기기에서 검사 대상이 없음을 뜻합니다(테스트가 SKIP이 아니라 PASS로 보고). RecordingTest의 나머지 18개 메서드는 아직 돌려 보지 않았습니다.
- CTS 원문 RecordingTest 나머지 18개(2026-09-19, Galaxy S25+ · Android 16 · versionCode 106~108 로컬 빌드, `halcam cts run`): 요청 `29c59174-8c00-4ad9-b22b-010fb3c1947a`에서 PASS 12 뒤 13번째 `testRecordingWithDifferentPreviewSizes`가 "stopped by the host"로 중단되어(폰 화면 조작으로 추정, CLI 취소 아님) 5개가 실행되지 못했고, 남은 6개는 요청 `5bfcf796-67ea-4be5-a2c9-b659957c5a93`에서 PASS 4(`testRecordingWithDifferentPreviewSizes` 32초, `testSupportedVideoSizes` 4분 46초, `testVideoPreviewSurfaceSharing` 16초, `testVideoSnapshot` 9분 52초) · SKIP 2로 끝났습니다. 두 실행을 합치면 `RecordingTest` 19개 모두 실행: PASS 14 · SKIP 5 · setUp 실패 0(SKIP 판정 적용 뒤 기준. `testAbandonedHighSpeedRequest`·`testConstrainedHighSpeedRecording`은 1초 만에 끝나지만 카메라를 열어 PASS로 남습니다). 이 가운데 `testCameraRecorderOrdering`·`testMediaCodecRecording`·`testTimelapseRecording`은 업스트림 본문이 `// TODO. Need implement.`뿐이라 목록에서 제외했습니다(`VendoredCatalog.unimplemented`).
- CTS SKIP 판정(2026-09-19, versionCode 108, 요청 `5bfcf796…`와 재실행): 카메라를 하나도 열지 않은 통과를 SKIP으로 바꾸고 이유를 로그에서 읽은 결과, `testHeicExif`·`testHeicUltraHdrCapture`·`testDynamicDepthCapture`(카메라 0~3 모두 "does not support HEIC / HEIC_ULTRAHDR / dynamic depth, skipping"), `testYuvBurstWithStillBokeh`("Device doesn't support STILL_CAPTURE bokeh"), `testBasic10BitRecordingAV1`·`testSlowMotionRecording`(로그 없이 건너뜀, 카탈로그의 힌트로 표시)이 SKIP으로 기록되었습니다. 세 클래스 40개 메서드의 이 기기 결과는 PASS 33 · FAIL 1 · SKIP 6입니다.

## 아직 하지 않은 것

- 점수(M5)의 민감도 검증과 공개 기준 확정. 내부 초안은 구현했으며 위 검증 결과와 남은 작업을 참고하십시오.
- 녹화(3.x) 지표의 비충전 상태 측정. 기능 확인은 끝났으나 네 run 모두 `CHARGING`이라 점수용 자료가 아닙니다.
- 3.3 encoder drop(`MediaCodec` 전환이 필요함)과 3.5 장시간 drift(10분 녹화 별도 시나리오).
- 여러 제조사 기기에서의 분포 수집.

## 알려진 제약

- 패키지 이름을 `dev.cameradoctor`에서 `dev.halcamera`로 바꾸면서 앱 데이터 경로가 달라졌습니다. 예전 기록은 로컬 백업에 보관되어 있으며 현재 기기의 보관 상태는 이번 점검에서 확인하지 못했습니다.
- USB 충전 중 측정에는 `CHARGING` flag가 붙어 점수에서 제외됩니다. adb 자체가 제외 조건인 것은 아니며, 비충전 상태의 무선 디버깅으로 측정할 수 있습니다.
- 디버그 빌드 측정은 `DEBUGGABLE_BUILD`로 점수에서 제외됩니다. 구버전에서 빌드 종류가 누락된 기록은 학습에 사용하기 전에 출처를 확인해야 합니다.
- Camera2 엔진으로 카메라를 닫을 때 logcat에 `Long monitor contention with owner CD.Camera2 … at CameraDeviceImpl.close() … in CameraDeviceImpl.onDeviceError` 경고가 찍힐 수 있습니다([이슈 #84](https://github.com/TTolsun/hal-camera/issues/84)). 프레임워크 동작이며 앱 코드 원인이 아닙니다. `CameraDeviceImpl.close()`는 `mInterfaceLock`을 잡은 채 cameraserver에 `disconnect()`를 동기 호출하고, cameraserver는 그 사이에 진행 중이던 요청을 flush하면서 `ERROR_CAMERA_REQUEST`/`ERROR_CAMERA_BUFFER`를 binder 스레드의 `onDeviceError`로 보냅니다. 이 콜백은 같은 lock을 기다리다가 `close()`가 끝난 뒤 "already closed"로 버려지므로 앱의 `StateCallback.onError`나 `camera_error` 이벤트는 발생하지 않습니다. `Camera2Engine.close`는 `captureSession.close()` 뒤에 `device.close()`를 호출하는 표준 순서이고, 경고에 적힌 시간(262 ms)은 HAL 닫기 자체의 소요 시간이므로 벤치마크 CLOSE 단계(`close_call` → `closed`)에 별도 지연을 더하지 않습니다. main 스레드는 관여하지 않습니다.
