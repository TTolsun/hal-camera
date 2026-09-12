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

- [ ] `yaml-lite.mjs`가 `elements` 아래의 중첩 매핑(요소 경로 키 → `evidence` 목록)을 읽을 수 있는지 확인한다. 안 되면 최소 확장을 한다.
- [ ] `model.mjs`에 `collectElements(bindings, source)`를 추가한다. 요소 경로, 근거 글롭(상속 포함), 부모 경로를 돌려준다.
- [ ] `verify.mjs --check`에 부분집합 검사를 추가한다. 요소 근거 파일이 관점 `evidence`에 없으면 실패한다.
- [ ] `sync-worker.mjs`의 스캔 루프를 관점 단위에서 요소 단위로 바꾼다. 프롬프트는 요소 필드, 부모 `description`, 요소 근거만 포함한다. 허용 요소는 그 요소 하나다.
- [ ] 기존 파일과 같은 내용의 `updates`는 건너뛴다.
- [ ] `tools/docgen/state/scan.json`을 추가한다. 성공한 요소의 `codeHash`와 `scannedAt`을 기록하고, 다음 실행에서 해시가 같은 요소는 건너뛴다.
- [ ] `sync.mjs`의 `outputPolicy`와 `docs-check.yml`의 상태 파일 일치 검사에 `scan.json`을 추가한다.
- [ ] `_bindings.yaml`에 세 관점의 요소별 근거를 채운다. 시작 표는 아래 3장에 있다.
- [ ] `regression.test.mjs`에 부분집합 위반, 상속, 한도 초과 메시지 검사를 추가한다. `sync-fixture.mjs`에 요소 두 개짜리 fixture를 둔다.

검증: `node --test tools/docgen/regression.test.mjs tools/docgen/sync.test.mjs`. 실제 Ollama로 `node tools/docgen/sync.mjs --scan-only --force`를 실행해서 모든 요소의 입력 크기와 소요 시간을 로그로 남긴다.

### P3. 원고 근거 축소

파일: `tools/docgen/sync-worker.mjs`, `tools/docgen/brief.mjs`, `tools/docgen/sync.test.mjs`

- [ ] 원고 근거 파일 집합을 "기존 원고의 `sources` 인용 파일 ∪ `must_link` 심볼과 파일 이름이 일치하는 파일"로 바꾼다.
- [ ] 집합이 비면 `brief.mjs`로 첫 원고를 쓰라는 메시지와 함께 실패한다.
- [ ] `based_on` 관점의 `.omm` 필드는 지금처럼 `brief.mjs`가 프롬프트에 넣는다. 변경 없음을 확인한다.
- [ ] `sync.test.mjs`에 인용 파일만 제공되는지 검사하는 항목을 추가한다.

검증: 실제 Ollama로 `node tools/docgen/sync.mjs --write-only --force`를 실행해서 원고 6건이 모두 `done_reason: stop`으로 끝나는지 확인한다.

### P4. 실행 환경

파일: `.github/workflows/docs-sync.yml`, 저장소 설정, 개발자 PC

- [ ] Ollama를 Windows 서비스 또는 로그인 작업으로 등록해서 재부팅 후에도 `127.0.0.1:11434`가 응답하게 한다.
- [ ] runner 전용 Windows 사용자 계정을 만든다.
- [ ] Settings > Actions > Runners > New self-hosted runner로 runner를 설치하고, 라벨에 `docgen-qwen`을 추가한 뒤 `svc.cmd install`로 서비스 등록을 한다.
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

## 3. `elements` 시작 표

P2에서 `_bindings.yaml`에 넣을 초기 값이다. 요소 이름은 2026-09-12의 `.omm/` 트리 기준이며, 파일 크기는 같은 날 실측이다. 60,000자를 넘는 조합이 없도록 구성했다.

`overall-architecture`

| 요소 | 근거 | 근거 크기 |
| --- | --- | --- |
| `.` (루트) | `AndroidManifest.xml`, `app/build.gradle.kts`, `settings.gradle.kts` | 약 5,000자 |
| `benchmark` | `benchmark/BenchmarkActivity.kt` | 44,320자 |
| `benchmark/benchmark-runner` | `benchmark/BenchmarkRunner.kt`, `benchmark/ProfileCompatibility.kt`, `benchmark/ThermalTracker.kt` | 32,089자 |
| `benchmark/benchmark-model` | `benchmark/BenchmarkModel.kt`, `benchmark/RunIndex.kt` | 17,835자 |
| `benchmark/benchmark-profile` | `benchmark/BenchmarkProfile.kt`, `benchmark/SubjectPrefs.kt` | 7,559자 |
| `benchmark/benchmark-evaluator` | `benchmark/BenchmarkEvaluator.kt` | 9,435자 |
| `benchmark/run-assembler` | `benchmark/RunAssembler.kt` | 9,751자 |
| `benchmark/run-validity` | `benchmark/RunValidity.kt` | 7,503자 |
| `benchmark/comparison` | `benchmark/RegressionDetector.kt`, `benchmark/BaselineManager.kt`, `benchmark/ReferenceResolver.kt`, `benchmark/ComparePresenter.kt` | 23,780자 |
| `benchmark/regression-rules` | `benchmark/RegressionRules.kt` | 2,270자 |
| `benchmark/build-identity` | `benchmark/BuildIdentity.kt` | 3,756자 |
| `benchmark/metric-info` | `benchmark/MetricInfo.kt` | 2,204자 |
| `benchmark/score-composer` | `benchmark/ScoreComposer.kt`, `benchmark/S25PlusScoreDraft.kt` | 9,215자 |
| `camera-engines` | `camera/CameraEngine.kt`, `camera/CameraEndpoint.kt` | 7,281자 |
| `camera-engines/engine-interface` | `camera/CameraEngine.kt` | 3,806자 |
| `camera-engines/camera2-engine` | `camera/Camera2Engine.kt`, `camera/StillPair.kt`, `camera/YuvPacking.kt` | 30,791자 |
| `camera-engines/camerax-engine` | `camera/CameraXEngine.kt` | 6,418자 |
| `camera-engines/endpoint-model` | `camera/CameraEndpoint.kt` | 3,475자 |
| `camera-engines/endpoint-resolver` | `camera/CameraEndpointResolver.kt` | 3,652자 |
| `platform-camera` | `camera/CameraEndpointResolver.kt`, `camera/MediaLibrary.kt` | 6,853자 |
| `screens/expert-screen` | `MainActivity.kt` | 43,997자 |
| `screens/benchmark-screen` | `benchmark/BenchmarkActivity.kt`, `benchmark/ProgressPresenter.kt`, `benchmark/StartCardPresenter.kt` | 56,614자 (한도에 가깝다. `ResultPresenter.kt`와 `HistoryActivity.kt`는 별도 요소를 만드는 것을 검토한다) |
| `screens/look-tokens` | `ui/Look.kt` | 5,803자 |
| `ui-widgets` | `ui/**/*.kt` | 46,651자 |
| `metrics/metric-extractor` | `metrics/MetricExtractor.kt` | 12,075자 |
| `metrics/metric-model` | `metrics/MetricModel.kt` | 1,365자 |
| `telemetry/flight-recorder` | `telemetry/FlightRecorder.kt`, `telemetry/Telemetry.kt` | 9,805자 |
| `telemetry/capture-callbacks` | `telemetry/Telemetry.kt`, `camera/Camera2Engine.kt` | 33,460자 |
| `telemetry/incident-exporter` | `telemetry/IncidentExporter.kt` | 7,220자 |
| `persistence/benchmark-store` | `benchmark/BenchmarkStore.kt`, `benchmark/BenchmarkCsv.kt` | 6,881자 |
| `persistence/benchmark-report` | `benchmark/BenchmarkReport.kt` | 8,981자 |

`GalleryActivity.kt`(39,552자)와 `ResultPresenter.kt`, `HistoryActivity.kt`는 현재 `.omm` 요소가 없다. 관점 `evidence`에는 포함되므로 최신성 판정에는 들어가지만, 요소를 만들기 전까지는 스캔 대상이 아니다. 요소 생성은 사람이 결정한다.

`data-flow`와 `state-transitions`는 요소가 각각 7개, 3개이고 근거 코드가 `benchmark`, `telemetry`, `metrics`, `camera`, `MainActivity.kt`로 같다. 요소마다 관련 파일 2~3개씩 지정한다. `MainActivity.kt`(43,997자)를 포함하는 요소는 다른 파일을 하나만 더 둔다.

정확한 파일 대응은 P2에서 각 요소의 `description`을 읽고 확정한다. 이 표는 크기 검증용 시작점이다.

## 4. 하지 않는 것

- `verify.mjs --accept` 자동화
- `.omm` 요소 자동 생성과 삭제
- 모델 변경, 클라우드 API 사용
- `DOCGEN_MAX_PROMPT_CHARS` 기본값 상향

## 5. 변경 기록

- 2026-09-12: 초안 작성. 설계 문서와 함께 P0 PR로 등록.
