Telemetry는 Camera2 콜백을 이벤트로 변환하고 FlightRecorder는 메모리 링 버퍼와 incident 창을 관리합니다. IncidentExporter는 이벤트·환경을 ZIP으로 내보냅니다. 라이브 listener는 기록 스레드에서 동기 실행됩니다.
