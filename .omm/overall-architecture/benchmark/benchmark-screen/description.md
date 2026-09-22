BenchmarkActivity가 시작 카드·진행·결과·비교 네 화면을 상태 기계로 전환합니다. 시작 카드는 기능 설명 한 문장, 발열·배터리 칩, 측정 대상 빌드 이름 한 칸을 표시합니다. profile ID와 preflight 판정을 보여 주던 Details 접힘과 Commit·메모 입력 칸은 제거했습니다. 실행할 수 없는 조건은 blockedReason 한 줄로 드러납니다. SEVERE 발열로 시작이 막히면 5초마다 preflight를 다시 실행해 식으면 START가 자동으로 돌아옵니다. PROBE 진입 버튼은 이 화면에 없으며 LIVE 화면에서만 엽니다.

결과 화면은 판정 한 줄(`N metrics degraded`·`No degradation`·`First run`)을 먼저 표시하고, 핵심 지표 네 개를 ui/MetricBars의 MeterView 막대(baseline 눈금 포함)로 그립니다. 전체 지표 표와 실행 정보·flag·파일 경로는 `All metrics`·`Run info` 접힘 아래에 있습니다. 비교 화면은 DeltaBarView로 0 기준선 좌우에 변화율 막대를 그리고, 백분율이 없는 행은 글줄로 남깁니다. 라벨·버튼·지표명·판정 단어는 영어(판정은 `degraded`), 설명·안내·오류 문장은 한국어입니다.

Settings의 `Profiling data limit`은 BenchmarkPrefs에 저장하고, 저장 직후와 설정 변경 시 RunRetention.toDelete가 한도를 넘는 실행 파일을 오래된 것부터 고릅니다. baseline으로 지정된 실행은 삭제 대상에서 제외하며 기본값은 Unlimited입니다.
