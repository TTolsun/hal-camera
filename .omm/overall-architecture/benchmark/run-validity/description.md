`app/src/main/java/dev/halcamera/benchmark/RunValidity.kt`(규칙 버전 `validity-v2`). `PLAN-BenchMarker-v0.3.md` 5.3에 따라 run 하나에 대해 서로 다른 세 질문에 답합니다. 측정이 유효한가, 비교에 써도 되는가, 기기 간 점수에 넣어도 되는가.

`ValidityFlag` 14개가 각각 셋 중 무엇을 막는지 선언하며, flag는 그 답으로 묶여 있습니다. ABORTED, HARD_FAILURE, PROFILE_UNSUPPORTED, INSUFFICIENT_SAMPLES는 전부 막습니다. run에 프로파일이 약속한 내용이 없기 때문입니다. CADENCE_NOT_FIXED, THERMAL_HIGH, POWER_SAVE_MODE는 비교와 점수를 막습니다. 측정은 맞지만 다른 run과 견줄 수 없습니다. CHARGING, BATTERY_LOW, PROFILE_DRAFT, DEBUGGABLE_BUILD는 점수만 막습니다. 내부 비교에는 쓰되 기기 간 숫자에서는 뺍니다. DEBUGGABLE_BUILD는 debug 빌드가 자기 오버헤드까지 측정하기 때문에 v2에서 추가되었습니다. PREFLIGHT_MISMATCH, THERMAL_CHANGED, LABEL_MISSING은 정보용입니다.

불리언 세 개는 항상 flag 표에서 유도되며 손으로 설정하지 않고, 단계적으로 이어집니다. 비교는 측정을, 점수는 비교를 전제합니다. `fromJsonMap`은 저장된 flag 코드에서 다시 유도하며 저장된 불리언을 신뢰하지 않으므로 표가 바뀌어도 옛 파일에 일관되게 적용됩니다. 이 앱 버전이 모르는 코드는 `unknownFlags`에 그대로 보존하고 fail-closed로 다룹니다. 그 flag가 그것을 쓴 버전에서는 차단 flag였을 수 있으므로 비교와 점수를 막습니다. `ruleVersion`은 모든 run에 저장되어 어느 표가 불리언을 만들었는지 알 수 있습니다.

`RunValidityEvaluator.flags`가 임계값을 갖습니다. 열 상태 MODERATE 이상, 배터리 20% 미만, 관측 프레임 15개 미만, 그리고 launch·still 표본이 프로파일의 기대 수(각 9)에 못 미치는 경우입니다. 열 검사는 시작값이 아니라 run 전체의 최댓값을 쓰며, 그 이유가 코드에 적혀 있습니다. LIGHT로 시작해 SEVERE로 끝난 run은 비교 가능한 run이 아닙니다.
