# 작업 상태

2026-09-11 · M3 완료 시점.

## 지금 어디까지 왔는가

| 마일스톤 | 상태 |
|---|---|
| M1 Data contract | 완료 |
| M2 Measurement correctness | 완료. Galaxy S25+ 실기기 확인 |
| M3 Product conversion | 완료 |
| M4 Developer workflow | 완료. baseline 지정과 vs baseline / vs previous 열을 실기기에서 확인 |
| M5a Internal score | 미착수. scoring-eligible run 10회가 시작 조건 |
| M6 History / export | 미착수 |

M4의 내용은 계획보다 앞당겨 M3 2단계에서 함께 구현하고 검증했습니다.

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

- 점수(M5). curve 학습에 쓸 정상 조건 scoring-eligible run이 아직 부족합니다.
- 이력 화면과 CSV export(M6).
- 녹화(3.x) 지표 전체.
- 여러 제조사 기기에서의 분포 수집.
- `docs/guide/architecture.md`와 `.omm/` 아키텍처 문서는 M3에서 지운 클래스를 아직 설명하고 있습니다.

## 알려진 제약

- M2 이전의 run JSON은 기기에 남아 있지 않습니다. 패키지 이름을 `dev.cameradoctor`에서 `dev.halcamera`로 바꾸면서 앱 데이터 경로가 달라졌기 때문입니다.
- adb로 측정할 때는 USB가 연결되어 있어 모든 run에 `CHARGING` flag가 붙습니다. 비교에는 쓰이지만 점수에서는 제외됩니다.
