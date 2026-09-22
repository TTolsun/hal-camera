benchmark/domain/RegressionDetector.kt, BaselineManager.kt, ReferenceResolver.kt, StoreRunCatalog.kt가 비교 조건, 기준 실행 선택, 기준 실행 파일 읽기를 담당합니다. 비교는 저장된 판정을 읽지 않고 측정값 두 개에서 다시 계산합니다. BenchmarkActivity.finishRun과 toggleBaseline이 baseline 또는 이전 실행을 고른 뒤 RegressionDetector.compare를 호출하며, 결과·비교 화면의 표시 모델은 result-presentation 요소가 만듭니다.

비교 대상은 baseline과 이번 실행, 두 개뿐입니다. 여러 실행을 묶어 통계 검정을 수행하던 반복 측정 비교 화면은 0.13.0에서 제거했습니다. 다기기 분포 수집을 하지 않기로 결정하면서 그 화면의 용도가 사라졌기 때문입니다.
