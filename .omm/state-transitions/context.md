기존 루틴의 동작을 바꾸지 않고 현재 코드를 기술합니다. Auto Check의 엔드포인트 실패 처리와 BenchmarkRunner의 반복 사이클 실패 한도는 서로 다른 계약입니다. 두 러너 모두 Driver·Scheduler·clock을 주입받고 Listener로 결과를 전달합니다. 설계 선택의 이유는 사람의 결정 기록이 있을 때만 문서에 추가합니다.
