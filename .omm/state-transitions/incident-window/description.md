`telemetry/FlightRecorder.kt`. 세 상태의 타임라인 (idle, pending, closed) 이 체크 시퀀스에 독립적입니다.

`trigger(id)` 는 `preNs`(10 초) 보다 새로운 모든 이벤트를 스냅샷으로 가져와 pending 상태로 이동하고, `postNs`(5 초) 후까지 도착한 이벤트를 계속 추가합니다. `finish()`는 post-window 가 열려있을 때 null 을 반환하며, `forceReason` 를 제공하지 않는 한 종료되지 않습니다. 이는 사용자가 화면을 떠날 때에도 완료되지 않은 bundle 을 생성하는 방식입니다.

하나의 incident 만 pending 상태일 수 있으며, 두 번째 `trigger()` 호출은 false 를 반환하여 기존 incident 을 대체하지 않습니다. `remainingNs()` 는 UI 가 post-window 를 카운트다운하도록 허용합니다. incident 의 이벤트 목록은 `maxEvents` 의 두 배로 제한되며, 이를 초과하면 `truncated` 이 true 로 설정되어 short bundle 이 truncated 으로 식별됩니다.