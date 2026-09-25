benchmark/BenchmarkActivity.kt는 Live에서 진입하는 벤치마크 실행 화면입니다. ProfileCompatibilityChecker로 사전 확인하고 StreamSpec과 ThermalTracker를 준비해 BenchmarkRunner를 실행합니다. finishRun은 RunAssembler와 BenchmarkReport로 결과를 저장하며 표본 수·flag·저장 경로를 표시합니다. finishRun은 io 실행기에서 baseline 또는 이전 실행을 선택하고 비교한 후 RESULT 화면으로 전환합니다. renderResult는 ResultPresenter, renderCompare는 ComparePresenter를 호출합니다. MainActivity가 선택 카메라 ID와 엔진 이름을 전달하며 시작 카드는 StartCardPresenter, 실행 진행률은 ProgressPresenter로 구성합니다.

카메라는 역할과 ID가 있는 목록에서 직접 선택합니다. 측정 대상 빌드 이름은 카드의 입력 한 칸으로 적으며, 이전 실행의 값이 미리 채워집니다. 결과는 판정을 먼저 표시하고, 모든 측정 항목을 카테고리별 막대로 그립니다. 실행 정보만 접힘 아래에 두고 결과를 밖으로 옮기는 경로는 Export 하나입니다. HistoryActivity에서 연 실행은 저장된 기기 정보를 표시하고 새 실행 버튼을 숨깁니다.

단순 닫기와 결과·이력 복귀는 다른 화면과 같이 `Look.titleBar`가 만드는 제목 왼쪽의 48dp 뒤로 가기 IconButton으로 표시합니다. 실행 중에는 이 아이콘이 없고 `Abort`로만 중단합니다. 한국어 접근성 이름과 길게 누르기 설명으로 동작과 복귀 대상을 알립니다. 실행·비교·내보내기·필터처럼 구체적인 작업 이름은 글씨를 유지합니다.
