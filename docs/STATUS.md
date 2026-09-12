# 작업 상태

2026-09-12 · HAL CAM 0.5.1. 촬영 화면과 갤러리의 전체 선택·간격 개선을 반영했습니다. [PR #48](https://github.com/TTolsun/hal-camera/pull/48)의 M5a 내부 점수 초안을 포함하며 민감도 검증은 진행 중입니다.

## 지금 어디까지 왔는가

| 마일스톤 | 상태 |
|---|---|
| M1 Data contract | 완료 |
| M2 Measurement correctness | 완료. Galaxy S25+ 실기기 확인 |
| M3 Product conversion | 완료 |
| M4 Developer workflow | 완료. baseline 지정과 vs baseline / vs previous 열을 실기기에서 확인 |
| M5a Internal score | 내부 점수 초안 구현. 밝은 조건의 release 적격 run 10회로 calibration을 만들고 저조도 3회에 별도 적용. 저조도 효과의 일관성, 발열·점유 경쟁 검증은 미완료 |
| M5b Public endpoint score | 데이터 확보 대기. 여러 제조사·성능군의 5–10개 기기 분포 필요 |
| M6 History / export | 완료. RESULTS 필터, 두 run 비교·삭제, JSON·CSV 내보내기, PC 집계, subject 재사용 (PR #39, 이슈 #10 종료) |

M4의 내용은 계획보다 앞당겨 M3 2단계에서 함께 구현하고 검증했습니다.

GitHub의 미완료 이슈는 [M5 #9](https://github.com/TTolsun/hal-camera/issues/9)와 이를 포함하는 [전환 총괄 #4](https://github.com/TTolsun/hal-camera/issues/4)입니다. M1–M4와 M6의 개별 이슈는 종료되었습니다.

## M3 이후 완료한 일

- PR #39에서 실행 이력과 CSV 내보내기를 구현했습니다.
- PR #41–#45에서 LIVE 사진·동영상 저장, 앱 내 갤러리, 촬영 제어와 카메라 선택 UI를 구현하고 개선했습니다. 이 녹화 기능과 아래의 녹화 성능 계측(3.x)은 별도 기능입니다.
- PR #46에서 HAL CAM 0.5.0을 릴리스했습니다.
- PR #47에서 `.omm/`의 Doctor 요소를 제거하고 개발자 가이드와 docgen 근거 검사를 갱신했습니다. 이전 STATUS에 적혀 있던 아키텍처 문서 정리는 완료되었습니다.

## M5 현재 데이터와 구현 (2026-09-12)

Galaxy S25+ (`SM-S936N`, Android SDK 36)에서 무선 ADB로 수집했습니다. 측정 앱은 0.5.0 release(`app.debuggable=false`)이며, 기기 빌드는 `BP4A.251205.006.S936NKSSCCZH2`, profile은 `camera2-standard-v1`, endpoint는 후면 메인 `0`입니다.

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

| run_id | endpoint | 역할 |
|---|---|---|
| `20260910-085923-916` | `0` | 후면 메인 |
| `20260910-210231-192` | `3` | 전면 |
| `20260910-212705-261` | `2` | 초광각 |

세 측정은 앱 0.3.0의 `validity-v1` 기록으로 `app.debuggable`이 없습니다. 따라서 플래그상 적격이라는 사실만으로 release 빌드의 정상 조건 학습 데이터라고 확정하지 않습니다. 서로 다른 endpoint의 측정도 같은 endpoint의 반복 분포로 합치지 않습니다.

이 백업 점검 당시에는 연결 기기가 없었고 기존 데이터만으로 M5a를 시작할 수 없었습니다. 이후 무선 연결과 조명 조건 확인을 거쳐 위의 새 데이터를 수집했습니다. 구버전 백업은 새 calibration에 사용하지 않았습니다.

## M5를 이어서 진행하는 순서

1. 밝은 조건과 저조도를 추가 반복해 점수 차이가 정상 실행 간 변동보다 큰지 확인합니다. 이번 저조도 3회만으로 일관된 저하를 주장하지 않습니다.
2. 발열·카메라 점유 경쟁 조건의 run을 각각 3회 이상 확보해 민감도를 검증합니다. 측정 자체가 실패하면 임의 점수 대신 계산 불가로 기록합니다. 스트레스 데이터는 curve 학습에 넣지 않습니다.
3. 코드 리뷰는 완료했으며, 추가 실기기 결과로 민감도를 확인한 뒤 M5a 완료 여부를 결정합니다.
4. 제조사와 성능군이 다른 5–10개 기기의 적격 분포로 공개 curve를 확정한 뒤 `score-v1`로 전환합니다. M5a와 M5b의 완료 근거를 확인한 후 #9와 #4를 종료합니다.

## M3에서 한 일

Doctor를 지웠습니다. consumer 홈과 incident 모드, Auto Check와 그 화면, 임계값 엔진과 표, 진단 규칙, health composer, health monitor, v0.2 리포트 작성기, Auto Check baseline store가 사라졌습니다. 모델에 있던 판정 어휘(State, ThresholdBasis, CddApplicability, ConditionEquivalence, HealthLevelV2, CauseLayer, MetricState, Diagnosis, Health)도 함께 지웠습니다.

LIVE는 측정한 것을 모두 유지하고 판정한 것을 모두 버렸습니다. 건강 배너와 진단 카드 대신 `ui/LiveReadout`이 같은 숫자를 판정 없이 표시합니다. strip에서 경고 색을 뺐고, flight recorder는 더 이상 `health_assessment` 이벤트를 쓰지 않습니다. 하단 버튼은 MARK · SHUTTER · BENCHMARK 세 개이고, MainActivity가 런처가 되었습니다.

살아남은 파일은 이름에 맞는 패키지로 옮겼습니다. `check/`의 엔드포인트 두 개는 `camera/`로, `diagnosis/`의 MetricExtractor와 모델 잔여분은 `metrics/`로 갔습니다. `metrics`는 패키지 그래프의 leaf이며 비교도 표시도 알지 못합니다.

## 실기기 확인 (Galaxy S25+ · SM-S936N)

- 8.2 시작 카드: preflight `device_setup` 경로, CameraX 전환 안내, subject prefill 동작
- 8.3 진행 화면: 6단계, 시간 예산으로 가중한 진행률, 실시간 interval p50 · stall · frames · thermal
- 8.4 결과: eligibility 3단계 머리글, p50 / max 열 규칙, `vs baseline`과 `vs previous` 열
- 7.1: baseline이 없을 때 이전 run 대비 delta만 표시하고 REGRESSED를 붙이지 않음
- 7.2: Open이 +138 %여도 절대 차이 6 ms가 noise floor 10 ms 미만이라 회귀로 판정하지 않음
- `SET AS BASELINE` / `CLEAR BASELINE` 전환

## 아직 하지 않은 것

- 점수(M5)의 민감도 검증과 공개 기준 확정. 내부 초안은 구현했으며 위 검증 결과와 남은 작업을 참고하십시오.
- 녹화(3.x) 지표 전체.
- 여러 제조사 기기에서의 분포 수집.

## 알려진 제약

- 패키지 이름을 `dev.cameradoctor`에서 `dev.halcamera`로 바꾸면서 앱 데이터 경로가 달라졌습니다. 예전 기록은 로컬 백업에 보관되어 있으며 현재 기기의 보관 상태는 이번 점검에서 확인하지 못했습니다.
- USB 충전 중 측정에는 `CHARGING` flag가 붙어 점수에서 제외됩니다. adb 자체가 제외 조건인 것은 아니며, 비충전 상태의 무선 디버깅으로 측정할 수 있습니다.
- 디버그 빌드 측정은 `DEBUGGABLE_BUILD`로 점수에서 제외됩니다. 구버전에서 빌드 종류가 누락된 기록은 학습에 사용하기 전에 출처를 확인해야 합니다.
