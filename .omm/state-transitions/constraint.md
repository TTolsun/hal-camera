- `AutoCheckRunner` 인스턴스 하나는 검사 한 번만 실행합니다. `start()`가 `step == IDLE`을 단언하며, 클래스를 재사용하지 않습니다.
- OBSERVE를 제외한 모든 단계에 시간 제한이 걸립니다. open, configure, first frame, close는 각각 3초이고 촬영은 5초입니다. OBSERVE는 고정된 10초 구간이며 시간 제한이 아닙니다.
- 현재 세션과 다른 세션 ID를 가진 신호는 무시합니다. 이전 엔드포인트의 늦은 콜백이 현재 엔드포인트의 표시를 오염시키는 것을 막는 장치입니다.
- Auto Check의 엔드포인트 실패는 기본적으로 전체 실행을 중단시키지 않습니다. `failedStep`을 기록하고 해당 엔드포인트는 CLOSE로 넘어가며 `next()`가 이어집니다. 재시도는 없습니다.
- `close(done)`은 콜백을 정확히 한 번, 카메라를 반납한 뒤에만 호출해야 합니다. `next()`가 그 콜백에서 시작되기 때문입니다.
- `FlightRecorder` 하나에 대기 중인 incident는 최대 하나입니다. 하나가 열려 있는 동안 `trigger()`는 false를 반환합니다. `finish()`는 이후 구간이 지나기 전에는 null을 반환하며, 이유를 주어 강제할 때만 예외입니다.
- `ThresholdEngine`의 판정 순서는 고정되어 있습니다. hard failure가 먼저이고, 다음이 샘플 자체의 unknown 이유, 다음이 값 없음, 마지막이 `worstOf(absolute, relative)`입니다.
- validity 불리언 세 개는 단계적으로 이어지며 항상 flag 표에서 유도합니다. 저장된 실행을 읽을 때에도 직접 대입하지 않습니다.

- BenchmarkRunner는 기본 연속 실패 3회, 관측 세션 실패 또는 명시적 abort에서 조기 종료합니다.
