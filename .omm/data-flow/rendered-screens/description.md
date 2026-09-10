HomeActivity의 RunSummary.load와 저장 결과를 여는 CheckActivity는 파일에서 측정·진단 결과를 읽습니다. CheckActivity는 방금 끝난 실행의 fromEvaluations와 기존 파일의 fromFile을 같은 표시 모델로 연결합니다.

BenchmarkActivity.finishRun은 RunAssembler가 만든 실행을 저장한 뒤 메모리의 Result와 BenchmarkRun으로 표본 수·flag 요약을 표시합니다. 모든 화면이 파일만을 단일 입력으로 사용한다는 설명은 정확하지 않습니다. 별도 ResultPresenter 비교 로직은 아직 이 Activity에 연결되지 않았습니다.
