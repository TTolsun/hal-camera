`app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt`(111줄). 링 버퍼, incident 창, `FrameTracker`입니다.

링은 두 번 제한됩니다. 시간(`retentionNs`, 기본 30초)과 개수(`maxEvents`, 18,000)입니다. 개수 상한으로 밀려난 이벤트는 `capacityEvictions`에 세어 export되므로, 잘린 번들은 조용히 짧은 것이 아니라 잘린 것으로 보입니다. `BenchmarkActivity`는 기본값을 180초와 60,000개로, 트리거 전후 창은 0으로 바꿉니다. run 하나가 incident 크기의 버퍼보다 길고 incident 창은 쓰지 않기 때문입니다. `clear()`는 보존 중인 이벤트를 모두 버리며, run을 시작할 때 호출해 이전 run의 이벤트가 새 run 파일에 섞이지 않게 합니다. 수집 중인 incident는 자기 사본을 갖고 있으므로 영향을 받지 않습니다.

incident는 트리거 전후의 창입니다. `trigger(id)`는 링에서 `preNs`(10초)보다 새로운 이벤트를 스냅샷한 뒤 `postNs`(5초)가 지날 때까지 계속 붙입니다. `finish()`는 닫힌 `Incident`를 돌려주거나, 이후 창이 아직 열려 있으면 `forceReason`이 없는 한 null을 돌려줍니다. 대기 중인 incident는 한 번에 하나뿐이며 그 동안 `trigger`는 false를 반환합니다.

스레딩 결정 둘이 핵심입니다. `store()`는 `@Synchronized`이지만 `record()`는 `listener`를 lock **밖에서** 호출하므로 라이브 listener가 `snapshot()`이나 `record()`를 불러도 교착하지 않습니다. 그리고 listener는 기록 스레드에서 동기 실행되므로 문서가 가볍게 유지하라고 요구합니다. `BenchmarkActivity`는 listener에서 메인 스레드로 post한 뒤 `onEvent`에서 러너 신호로 바꿉니다.

`FrameTracker`는 연속된 결과에서 프레임별 통계를 유도합니다. 센서 타임스탬프에서 `intervalMs`와 `fps`를, 프레임 번호에서 `resultGap`을 구합니다. 주석이 한계를 명시합니다. gap은 관측된 결과 간격이지 HAL 드롭이나 프리뷰 드롭 횟수가 아닙니다.
