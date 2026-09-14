# docgen 무인 동기화 계획

- 작성일: 2026-09-12
- 상태: 초안. 설계는 `docs/design/DOCGEN-AUTOSYNC.md`에 있다. 이 문서는 순서, 작업 단위, 완료 조건만 다룬다.
- 코드 기준: `main` `38b9b5c` (2026-09-12)
- 추적: GitHub 이슈 라벨 `docgen-autosync`. epic 이슈 하나와 단계별 이슈로 관리한다.

## 0. 목표 한 문장

`main`에 코드가 push되면 개발자 PC의 self-hosted runner가 로컬 Qwen으로 문서 갱신 PR을 연다. 사람은 검토와 `verify.mjs --accept`만 한다.

## 1. 단계와 순서

단계는 의존 순서대로 진행한다. 각 단계는 PR 하나이며, 앞 단계가 merge된 뒤 다음 단계를 시작한다. 예상 시간은 구현하는 사람이나 에이전트 기준이고, 검토 시간은 포함하지 않는다.

| 단계 | 내용 | 예상 시간 | 완료 조건 |
| --- | --- | --- | --- |
| P0 | 설계와 계획 문서 (이 문서) | 완료 | 이 PR merge |
| P1 | 전송 계층: `qwen.mjs` 스트리밍, 유휴 시간 제한, 출력 한도 설정 | 2시간 | `sync.test.mjs` 통과, 실제 Ollama로 원고 1건 생성 |
| P2 | 근거 분할: `_bindings.yaml` `elements`, 요소 단위 스캔, `scan.json`, CI 부분집합 검사 | 1일 | 관점 3개 모두 요소별 스캔이 60,000자 이하로 실행됨 |
| P3 | 원고 근거 축소: 인용 파일 + `must_link` 파일만 제공 | 3시간 | 원고 6건 모두 `done_reason: stop`으로 생성됨 |
| P4 | 실행 환경: runner 설치, `docs-sync.yml` 트리거 변경, 저장소 변수 | 1시간 + 첫 실행 대기 | `main` push로 PR이 자동 생성됨 |
| P5 | 검증과 마무리: 수용 기준 확인, README 갱신 | 2시간 | 설계 문서 7장의 기준 5개 모두 확인 |

P1은 P2보다 먼저 한다. P2의 요소별 스캔을 실제 모델로 검증하려면 300초 헤더 대기 문제가 먼저 없어져야 하기 때문이다.

## 2. 단계별 작업

### P1. 전송 계층

파일: `tools/docgen/qwen.mjs`, `tools/docgen/sync.test.mjs`, `tools/docgen/README.md`

- [ ] `stream: true`로 요청하고 NDJSON 본문을 줄 단위로 읽어 `message.content`를 이어 붙인다.
- [ ] 마지막 청크의 `done`, `done_reason`, `eval_count`로 완료를 판정한다. 판정 규칙은 기존과 같다.
- [ ] `DOCGEN_LLM_TIMEOUT_MS` 기본값을 1,800,000으로 올리고, `DOCGEN_LLM_IDLE_MS`(기본 120,000)를 추가한다. 유휴 한도를 넘으면 요청을 중단하고 실패한다.
- [ ] `num_predict`를 `DOCGEN_QWEN_NUM_PREDICT`(기본 8192)로 읽는다.
- [ ] `sync.test.mjs`의 mock 서버를 스트리밍 응답으로 바꾸고, 유휴 시간 초과 검사를 하나 추가한다.
- [ ] README의 "로컬 Qwen 실행" 절에서 시간 제한 설명을 갱신한다.

검증: `node --test tools/docgen/sync.test.mjs`. 실제 Ollama로 `node tools/docgen/sync.mjs --write-only`를 원고 하나에 실행해서 300초를 넘겨도 완료되는지 확인한다.

### P2. 근거 분할

파일: `docs/guide/_bindings.yaml`, `tools/docgen/model.mjs`, `tools/docgen/sync-worker.mjs`, `tools/docgen/sync.mjs`, `tools/docgen/verify.mjs`, `tools/docgen/regression.test.mjs`, `tools/docgen/sync-fixture.mjs`, `.github/workflows/docs-check.yml`

- [x] `yaml-lite.mjs`가 `elements` 아래의 중첩 매핑(요소 경로 키 → `evidence` 목록)을 읽을 수 있는지 확인한다. 안 되면 최소 확장을 한다.
- [x] `model.mjs`에 `collectElements(bindings, source)`를 추가한다. 요소 경로, 근거 글롭(상속 포함), 부모 경로를 돌려준다.
- [x] `verify.mjs --check`에 부분집합 검사를 추가한다. 요소 근거 파일이 관점 `evidence`에 없으면 실패한다.
- [x] `sync-worker.mjs`의 스캔 루프를 관점 단위에서 요소 단위로 바꾼다. 프롬프트는 요소 필드, 부모 `description`, 요소 근거만 포함한다. 허용 요소는 그 요소 하나다.
- [x] 기존 파일과 같은 내용의 `updates`는 건너뛴다.
- [x] `tools/docgen/state/scan.json`을 추가한다. 성공한 요소의 `codeHash`와 `scannedAt`을 기록하고, 다음 실행에서 해시가 같은 요소는 건너뛴다.
- [x] `sync.mjs`의 `outputPolicy`와 `docs-check.yml`의 상태 파일 일치 검사에 `scan.json`을 추가한다.
- [x] `_bindings.yaml`에 세 관점의 요소별 근거를 채운다. 확정 표는 아래 3장에 있다.
- [x] `regression.test.mjs`에 부분집합 위반, 상속, 한도 초과 메시지 검사를 추가한다. `sync-fixture.mjs`에 요소 두 개짜리 fixture를 둔다.

검증: `node --test tools/docgen/regression.test.mjs tools/docgen/sync.test.mjs`. 실제 Ollama로 `node tools/docgen/sync.mjs --scan-only --force`를 실행해서 모든 요소의 입력 크기와 소요 시간을 로그로 남긴다.

2026-09-13에 47개 요소의 실제 스캔이 363.611초에 완료됐고 최대 입력은 58,434자였습니다. 캐시 재실행은 모델 호출과 파일 변경 없이 완료됐습니다. 세부 조건과 요소별 실측은 [검증 기록](../tools/docgen/validation-qwen.md)에 있습니다.

### P3. 원고 근거 축소

파일: `tools/docgen/sync-worker.mjs`, `tools/docgen/brief.mjs`, `tools/docgen/sync.test.mjs`

- [x] 원고 근거 파일 집합을 "기존 원고의 `sources` 인용 파일 ∪ `must_link` 심볼과 파일 이름이 일치하는 파일"로 바꾼다.
- [x] 집합이 비면 `brief.mjs`로 첫 원고를 쓰라는 메시지와 함께 실패한다.
- [x] `based_on` 관점의 `.omm` 필드는 지금처럼 `brief.mjs`가 프롬프트에 넣는다. 변경 없음을 확인한다.
- [x] `sync.test.mjs`에 인용 파일만 제공되는지 검사하는 항목을 추가한다.

검증: 2026-09-14 현재 원고 5건을 실제 Ollama의 `node tools/docgen/sync.mjs --write-only --force`로 287.968초에 생성했습니다. 근거 요약 20회와 집필 5회가 모두 `done_reason: stop`으로 종료되었고 최대 입력은 56,484자였습니다. 파일 선별 후에도 입력이 11만~32만 자여서 사용자 확인에 따라 근거 분할 요약을 추가했습니다. 반복 출력은 질문별 JSON 응답과 프로그램의 front matter 조립으로 해결했습니다. [검증 기록](../tools/docgen/validation-qwen.md)에 조건과 한계를 남겼습니다.

### P4. 실행 환경

파일: `.github/workflows/docs-sync.yml`, 저장소 설정, 개발자 PC

- [ ] Ollama를 Windows 서비스 또는 로그인 작업으로 등록해서 재부팅 후에도 `127.0.0.1:11434`가 응답하게 한다.
- [ ] runner 전용 Windows 사용자 계정을 만든다.
- [ ] Settings > Actions > Runners > New self-hosted runner로 runner를 설치하고, 라벨에 `docgen-qwen`을 추가한 뒤 `config.cmd --runasservice`로 서비스 등록을 한다.
- [ ] Settings > Variables에 `DOCGEN_LOCAL_RUNNER_ENABLED=true`를 추가한다.
- [ ] Settings > Actions에서 "Require approval for all outside collaborators"가 켜져 있는지 확인한다.
- [ ] `docs-sync.yml`에 `push: main` + `paths` 트리거를 추가하고, `DOCGEN_QWEN_CONTEXT=49152`, `DOCGEN_LLM_TIMEOUT_MS=1800000`을 env에 둔다. `workflow_dispatch`는 남긴다.
- [ ] `workflow_dispatch`로 한 번 수동 실행해서 runner가 잡히고 PR이 열리는지 확인한다.

검증: `gh run list --workflow docs-sync`에서 성공 기록, `docs/omm-sync` PR 존재.

### P5. 검증과 마무리

- [ ] `main`에 `RunAssembler.kt`만 바꾸는 작은 commit을 push하고, 설계 문서 7장의 수용 기준 5개를 확인해서 epic 이슈에 기록한다.
- [ ] `tools/docgen/README.md`의 "알려진 제약"과 "로컬 Qwen 실행" 절을 갱신한다. "전체 무인 갱신은 준비되지 않았다"는 문장을 실측 결과로 바꾼다.
- [ ] `tools/docgen/validation-qwen.md`에 실행 로그 요약(요소 수, 입력 크기 분포, 총 소요 시간)을 추가한다.
- [ ] epic 이슈를 닫는다.

## 3. `elements` 확정 표

2026-09-13의 main `2a4aa99`를 기준으로 각 요소의 description과 실제 코드 경로를 대조했습니다. 관점의 evidence 범위는 유지하고 47개 요소 모두에 명시적인 근거를 지정했습니다. 아래 크기는 기존 필드·부모 설명·코드·지시문을 합친 요청 전 문자 수이며, 부모 설명이 갱신되면 실제 호출 크기는 달라질 수 있습니다. 스캐너는 매 호출마다 60,000자 한도를 다시 검사합니다.

경로는 별도 표시가 없으면 `app/src/main/java/dev/halcamera/` 기준입니다. 크기가 큰 개요 요소는 진입점과 계약 파일을 사용하며 모든 관련 구현을 포함하지 않습니다. 예를 들어 `screens/expert-screen`의 GalleryActivity 상세는 이번 근거에 포함하지 않고, 근거가 없는 내용을 추정해 고치지 않도록 합니다. 새 요소 생성은 후속 수동 결정으로 남깁니다.

### overall-architecture

| 요소 | 근거 파일/글롭 | 프롬프트 문자 수 |
| --- | --- | ---: |
| `.` | `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `settings.gradle.kts`, `cli/CommandCoordinator.kt`, `camera/LiveController.kt`, `benchmark/BenchmarkController.kt` | 27074 |
| `benchmark` | `benchmark/BenchmarkActivity.kt`, `benchmark/RunIndex.kt`, `benchmark/BenchmarkCsv.kt`, `benchmark/BenchmarkController.kt` | 55925 |
| `benchmark/benchmark-evaluator` | `benchmark/BenchmarkEvaluator.kt` | 12090 |
| `benchmark/benchmark-model` | `benchmark/BenchmarkModel.kt`, `benchmark/RunIndex.kt` | 18870 |
| `benchmark/benchmark-profile` | `benchmark/BenchmarkProfile.kt`, `benchmark/SubjectPrefs.kt` | 10312 |
| `benchmark/benchmark-runner` | `benchmark/BenchmarkRunner.kt`, `benchmark/ProfileCompatibility.kt`, `benchmark/ThermalTracker.kt` | 32759 |
| `benchmark/build-identity` | `benchmark/BuildIdentity.kt` | 6067 |
| `benchmark/comparison` | `benchmark/RegressionDetector.kt`, `benchmark/BaselineManager.kt`, `benchmark/ReferenceResolver.kt`, `benchmark/ResultPresenter.kt`, `benchmark/ComparePresenter.kt` | 42328 |
| `benchmark/metric-info` | `benchmark/MetricInfo.kt`, `metrics/MetricModel.kt` | 4742 |
| `benchmark/regression-rules` | `benchmark/RegressionRules.kt` | 4605 |
| `benchmark/run-assembler` | `benchmark/RunAssembler.kt`, `benchmark/ScoreComposer.kt` | 18259 |
| `benchmark/run-validity` | `benchmark/RunValidity.kt` | 9615 |
| `benchmark/score-composer` | `benchmark/ScoreComposer.kt`, `benchmark/S25PlusScoreDraft.kt` | 10479 |
| `camera-engines` | `camera/CameraEngine.kt`, `camera/CameraEndpoint.kt` | 11694 |
| `camera-engines/camera2-engine` | `camera/Camera2Engine.kt`, `camera/StillPair.kt`, `camera/YuvPacking.kt`, `camera/MediaLibrary.kt` | 36768 |
| `camera-engines/camerax-engine` | `camera/CameraXEngine.kt` | 8462 |
| `camera-engines/endpoint-model` | `camera/CameraEndpoint.kt` | 5317 |
| `camera-engines/endpoint-resolver` | `camera/CameraEndpointResolver.kt` | 5103 |
| `camera-engines/engine-interface` | `camera/CameraEngine.kt` | 6025 |
| `metrics` | `metrics/MetricExtractor.kt`, `metrics/MetricModel.kt` | 15831 |
| `metrics/metric-extractor` | `metrics/MetricExtractor.kt` | 14527 |
| `metrics/metric-model` | `metrics/MetricModel.kt` | 2843 |
| `persistence` | `benchmark/BenchmarkReport.kt`, `benchmark/BenchmarkStore.kt`, `benchmark/BaselineManager.kt`, `benchmark/SubjectPrefs.kt`, `camera/MediaLibrary.kt` | 25316 |
| `persistence/benchmark-report` | `benchmark/BenchmarkReport.kt` | 10055 |
| `persistence/benchmark-store` | `benchmark/BenchmarkStore.kt`, `benchmark/BenchmarkCsv.kt` | 8048 |
| `platform-camera` | `camera/CameraEngine.kt` | 6042 |
| `screens` | `app/src/main/AndroidManifest.xml`, `camera/LiveController.kt`, `benchmark/BenchmarkController.kt` | 10043 |
| `screens/benchmark-screen` | `benchmark/BenchmarkActivity.kt`, `benchmark/ProgressPresenter.kt`, `benchmark/StartCardPresenter.kt` | 58426 |
| `screens/expert-screen` | `MainActivity.kt`, `camera/RecentMediaThumbnail.kt` | 54177 |
| `screens/look-tokens` | `ui/Look.kt` | 7326 |
| `telemetry` | `telemetry/Telemetry.kt`, `telemetry/FlightRecorder.kt`, `telemetry/IncidentExporter.kt` | 20019 |
| `telemetry/capture-callbacks` | `telemetry/Telemetry.kt` | 6760 |
| `telemetry/flight-recorder` | `telemetry/FlightRecorder.kt`, `telemetry/Telemetry.kt` | 11750 |
| `telemetry/incident-exporter` | `telemetry/IncidentExporter.kt` | 9329 |
| `ui-widgets` | `ui/**/*.kt` | 50772 |

### data-flow

| 요소 | 근거 파일/글롭 | 프롬프트 문자 수 |
| --- | --- | ---: |
| `.` | `benchmark/RunAssembler.kt`, `telemetry/Telemetry.kt`, `benchmark/BenchmarkReport.kt`, `cli/CommandCoordinator.kt`, `camera/MediaLibrary.kt`, `camera/RecentMediaThumbnail.kt` | 46914 |
| `benchmark-metrics` | `benchmark/RunAssembler.kt`, `benchmark/BenchmarkEvaluator.kt`, `benchmark/RegressionDetector.kt` | 30763 |
| `event-record` | `telemetry/FlightRecorder.kt`, `telemetry/Telemetry.kt`, `camera/CameraEngine.kt` | 16051 |
| `frame-observations` | `metrics/MetricExtractor.kt`, `telemetry/FlightRecorder.kt` | 19450 |
| `framework-callbacks` | `camera/Camera2Engine.kt`, `camera/CameraXEngine.kt`, `telemetry/Telemetry.kt` | 43337 |
| `rendered-screens` | `benchmark/ResultPresenter.kt`, `benchmark/ComparePresenter.kt`, `benchmark/HistoryActivity.kt`, `camera/LiveController.kt`, `benchmark/BenchmarkController.kt` | 45990 |
| `run-json` | `benchmark/BenchmarkReport.kt`, `benchmark/BenchmarkModel.kt`, `camera/MediaLibrary.kt` | 29241 |
| `runner-marks` | `benchmark/BenchmarkRunner.kt`, `benchmark/RunAssembler.kt`, `telemetry/Telemetry.kt` | 34311 |

### state-transitions

| 요소 | 근거 파일/글롭 | 프롬프트 문자 수 |
| --- | --- | ---: |
| `.` | `benchmark/BenchmarkRunner.kt`, `cli/CommandStore.kt`, `cli/CommandCoordinator.kt`, `camera/RecentMediaThumbnail.kt`, `benchmark/HistoryActivity.kt` | 57234 |
| `engine-lifecycle` | `camera/Camera2Engine.kt`, `camera/CameraXEngine.kt`, `benchmark/BenchmarkRunner.kt` | 56021 |
| `incident-window` | `telemetry/FlightRecorder.kt` | 7204 |
| `validity-gate` | `benchmark/RunValidity.kt`, `benchmark/RegressionDetector.kt` | 20221 |

## 4. 하지 않는 것

- `verify.mjs --accept` 자동화
- `.omm` 요소 자동 생성과 삭제
- 모델 변경, 클라우드 API 사용
- `DOCGEN_MAX_PROMPT_CHARS` 기본값 상향

## 5. 변경 기록

- 2026-09-12: 초안 작성. 설계 문서와 함께 P0 PR로 등록.

- 2026-09-13: P2의 47개 요소별 근거를 현재 코드에 맞춰 확정했습니다.
