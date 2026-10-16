BenchmarkProfile은 실행 조건을 담는 불변 값이며 전체 내용과 ID가 실행 JSON에 저장됩니다. 엔진, 스트림 크기·형식, FPS, ZSL·트리거·AF, 실행 횟수, 워밍업·관측 시간과 첫 표본 제외 여부가 비교 계약에 포함됩니다.

현재 표준 CAMERA2_STANDARD_V2는 V1의 Camera2·1920x1080 스트림·[30,30] FPS·warm reopen·10회 launch·3초 warmup·10초 observe·10회 still 조건을 유지하고 녹화를 추가합니다. 녹화는 1920x1080 H.264, 10 Mbps, 30 FPS, 회당 9초, 5회이며 오디오는 없습니다. 첫 표본 제외 후 launch와 still은 각 9개, shot-to-shot은 8개입니다.

CAMERA2_STANDARD_V1도 canonical 정의로 보존하며 이전 결과를 읽는 데 사용합니다. V1과 V2의 ID와 조건이 다르므로 서로 비교하지 않습니다. 알려진 canonical ID의 조건이 바뀐 파일과 녹화 조건 일부만 있는 파일은 읽기를 거부합니다. 이 코드 검토는 기기 실측을 의미하지 않습니다.
