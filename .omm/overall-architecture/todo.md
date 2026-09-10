`docs/PLAN-BenchMarker-v0.3.md`의 마일스톤을 계획된 순서대로 정리하면 다음과 같습니다.

1. M2 `BenchmarkRunner`: warm-reopen 실행 루프, 관측 창, 연속 촬영을 구동해서 `BenchmarkEvaluator`가 실제 입력을 받도록 합니다. 이 과정에서 Galaxy S25+가 1080p YUV 스트림을 감당하는지도 확인되며, 그 결과에 따라 프로파일 ID에서 `-draft` 접미사를 뗄 수 있습니다.
2. M3 결과 UI, 그리고 `diagnosis/`에서 공용 헬퍼를 분리한 뒤의 Doctor 코드 삭제.
3. M4 baseline, compare, regression: `RegressionRules`를 적용하고 각 지표의 `baseline_ref`, `reference_ref`, `delta_pct`, `regression`을 채웁니다.
4. M5 Score. 의도적으로 미뤄 둔 항목입니다. profile v1 실행이 충분히 쌓이기 전에는 점수를 계산하지도 표시하지도 않습니다.
5. M6 실행 이력과 빌드 관리.

독립적으로 처리할 수 있는 작은 항목도 있습니다. `IncidentExporter`의 하드코딩된 `appVersion`을 고치고, `2.7`(촬영 중 프리뷰 stall)에 무조건적인 `NOT_RUN` 대신 실제 계산 근거를 부여하는 일입니다.
