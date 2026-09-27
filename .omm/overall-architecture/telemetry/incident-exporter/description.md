`app/src/main/java/dev/halcamera/telemetry/IncidentExporter.kt` (101 lines). Writes one closed `Incident` to `files/incidents/<id>.zip`, shared out through `FileProvider`.

It writes to a `.partial` file and only then renames it into place, so a crash or a full disk cannot leave a half-written bundle that looks complete. The bundle contains `incident.json` (schema 1 summary), `device.json`, `camera_characteristics.json` for the sessions that actually appear in the events, `events.jsonl`, `capture_requests.jsonl`, `capture_results.jsonl` and a human-readable `incident.md`.

Two habits in this file define how the project reports data. Nanosecond values are written as decimal *strings* so JavaScript tooling cannot round them. And the summary carries an explicit `omitted` map naming what is not in the bundle and why (Perfetto and simpleperf collectors are engineering-mode only, logcat needs external ADB, no preview pixels are persisted), alongside a `measurementNotes` list restating the interpretation limits. `halDroppedFrames` is present and always null, rather than absent, so a consumer cannot mistake an unmeasured quantity for zero.

`device.json`의 `appVersion`은 설치된 패키지의 `versionName`을 사용합니다. 조회에 실패하거나 값이 없으면 `unknown`을 기록합니다.

IncidentActions(ui 패키지)는 Live 화면에서 이 exporter를 부르는 쪽입니다. Mark를 누르면 그 시점의 판독값을 incident id로 보관하고, FlightRecorder가 incident를 닫으면 io 실행기에서 ZIP을 쓴 뒤 Mark 시점의 값을 대화상자로 보여 줍니다. 진단 패널의 기록 목록에서는 ZIP을 공유, 다른 위치에 저장, 삭제할 수 있습니다. 다른 위치에 저장하는 파일 선택기는 Activity에서만 등록할 수 있으므로 MainActivity가 띄웁니다.
