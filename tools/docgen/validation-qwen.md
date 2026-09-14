# 로컬 Qwen 실행 및 실패 복구 검증

최신 요소별 스캔 결과를 먼저 기록하고, 이전 검증 기록을 뒤에 보존합니다.

## 2026-09-13 P2 요소별 Qwen 스캔 검증

2026-09-13에 [#53](https://github.com/TTolsun/hal-camera/issues/53)의 세 관점, 47개 요소를 실제 로컬 Qwen으로 스캔했습니다. 실행은 종료 코드 0으로 완료됐으며 모든 입력이 60,000자 이하였습니다.

### 실행 조건

- 앱과 기존 OMM 입력은 main `2a4aa99`, 스캔 구현과 바인딩은 `3107073`을 사용했습니다.
- Windows에서 Node.js 24.18.0, Ollama 0.34.0, `oh-my-mermaid` 0.2.0을 사용했습니다.
- 모델은 `qwen3.5:4b`이며 digest는 `2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd`입니다.
- 컨텍스트는 49,152토큰, 출력 한도는 8,192토큰, temperature는 0이며 thinking은 껐습니다. 입력을 잘라 보내지 않았습니다.
- 별도 저장소 복사본에서 다음 명령을 실행했습니다. 결과 문서와 스캔 캐시는 이 복사본에만 반영했습니다.

```powershell
$env:DOCGEN_QWEN_CONTEXT = '49152'
node tools/docgen/sync.mjs --scan-only --force
```

### 결과

| 관점 | 요소 수 | 최소 입력 | 최대 입력 | 요소 처리 시간 합계 |
| --- | ---: | ---: | ---: | ---: |
| overall-architecture | 35 | 2,859자 | 58,434자 | 300.9초 |
| data-flow | 8 | 15,494자 | 46,914자 | 38.5초 |
| state-transitions | 4 | 7,214자 | 57,234자 | 22.4초 |

추출·검증·생성·트랜잭션 반영을 포함한 전체 시간은 **363.611초**였습니다. 입력 크기 중앙값은 15,494자였습니다. 요소 처리 시간에는 모델 호출과 해당 요소의 쓰기·검증이 포함됩니다. 아래 수치는 실제 호출 직전 로그에서 얻었으므로 부모 설명이 갱신되기 전의 계획 표와 다를 수 있습니다.

성공한 `scan.json` 기록 47개의 경로와 근거 해시를 실제 바인딩·코드와 대조했고 모두 일치했습니다. 기존 `evidence.json`의 `accepted` 기록도 모두 유지됐습니다. 복사본에서 갱신된 원본과 원고의 상태는 검토 대기로 남았습니다.

이어서 동일한 복사본에서 Ollama 주소를 접속할 수 없는 `http://127.0.0.1:1`로 지정하고 `node tools/docgen/sync.mjs --scan-only`를 실행했습니다. **47개 요소를 모두 건너뛰었으며**, 모델 호출과 파일 변경 없이 **1.210초** 만에 종료 코드 0으로 완료됐습니다.

### 회귀 검사와 해석 범위

기본 테스트는 Windows 로컬에서 71개가 통과했으며 선택 검사 3개는 제외했습니다. `3107073`의 [docs-check 실행](https://github.com/TTolsun/hal-camera/actions/runs/34761481389)은 Windows·Ubuntu 모두 통과했습니다. 이 검사는 상속·부분집합·입력 한도·요소 격리·부분 근거 변경·캐시·동일 내용 무쓰기·실패 및 중단 복구를 포함합니다.

첫 전체 실행은 incident-exporter가 변경하지 않을 필드를 빈 문자열로 반환하여 중단됐고 원본 변경은 0개였습니다. 이후 응답 스키마에 `minLength: 1`을 추가하고 변경이 없으면 빈 배열을 반환하도록 프롬프트를 명시했습니다. 빈 문자열과 공백 응답을 거부하면서 모든 파일을 보존하는 회귀 검사도 추가했습니다. Windows CI에서 발견한 LF 전용 테스트 치환도 LF·CRLF 양쪽을 검사하도록 수정했습니다.

실측은 앞선 실행 뒤 모델이 로드된 상태에서 수행한 한 번의 결과입니다. 전체 363.611초는 단일 모델 응답이 300초를 넘었다는 증거가 아닙니다. 생성 내용의 정확성을 승인하거나 모든 무인 갱신을 검증한 결과도 아닙니다. 넓은 개요 요소는 일부 진입점과 계약 파일을 근거로 사용합니다. 원고 근거 축소와 runner 자동 실행은 후속 이슈에 남아 있습니다. 이 PR의 `scan.json`은 검증 복사본의 미검토 결과를 재사용하지 않도록 빈 상태로 둡니다.

### 요소별 실행 로그 요약

| 요소 | 입력 문자 수 | 처리 시간 |
| --- | ---: | ---: |
| `overall-architecture` | 27074 | 41.2초 |
| `overall-architecture/benchmark` | 55970 | 6.5초 |
| `overall-architecture/benchmark/benchmark-evaluator` | 12098 | 5.9초 |
| `overall-architecture/benchmark/benchmark-model` | 18878 | 3.3초 |
| `overall-architecture/benchmark/benchmark-profile` | 10320 | 4.3초 |
| `overall-architecture/benchmark/benchmark-runner` | 32767 | 3.8초 |
| `overall-architecture/benchmark/build-identity` | 6075 | 4.9초 |
| `overall-architecture/benchmark/comparison` | 42336 | 4.4초 |
| `overall-architecture/benchmark/metric-info` | 4750 | 1.3초 |
| `overall-architecture/benchmark/regression-rules` | 4613 | 3.9초 |
| `overall-architecture/benchmark/run-assembler` | 18267 | 3.0초 |
| `overall-architecture/benchmark/run-validity` | 9623 | 7.2초 |
| `overall-architecture/benchmark/score-composer` | 10487 | 5.3초 |
| `overall-architecture/camera-engines` | 11739 | 10.0초 |
| `overall-architecture/camera-engines/camera2-engine` | 36768 | 32.3초 |
| `overall-architecture/camera-engines/camerax-engine` | 8462 | 21.6초 |
| `overall-architecture/camera-engines/endpoint-model` | 5317 | 31.1초 |
| `overall-architecture/camera-engines/endpoint-resolver` | 5103 | 19.2초 |
| `overall-architecture/camera-engines/engine-interface` | 6025 | 17.9초 |
| `overall-architecture/metrics` | 15876 | 12.5초 |
| `overall-architecture/metrics/metric-extractor` | 14543 | 2.9초 |
| `overall-architecture/metrics/metric-model` | 2859 | 2.1초 |
| `overall-architecture/persistence` | 25361 | 3.8초 |
| `overall-architecture/persistence/benchmark-report` | 10068 | 2.4초 |
| `overall-architecture/persistence/benchmark-store` | 8061 | 2.3초 |
| `overall-architecture/platform-camera` | 6087 | 1.6초 |
| `overall-architecture/screens` | 10088 | 2.0초 |
| `overall-architecture/screens/benchmark-screen` | 58434 | 6.8초 |
| `overall-architecture/screens/expert-screen` | 54185 | 14.1초 |
| `overall-architecture/screens/look-tokens` | 7334 | 3.0초 |
| `overall-architecture/telemetry` | 20064 | 2.8초 |
| `overall-architecture/telemetry/capture-callbacks` | 6765 | 3.8초 |
| `overall-architecture/telemetry/flight-recorder` | 11755 | 2.3초 |
| `overall-architecture/telemetry/incident-exporter` | 9334 | 4.4초 |
| `overall-architecture/ui-widgets` | 50817 | 7.0초 |
| `data-flow` | 46914 | 5.1초 |
| `data-flow/benchmark-metrics` | 30206 | 4.1초 |
| `data-flow/event-record` | 15494 | 4.9초 |
| `data-flow/frame-observations` | 18893 | 5.6초 |
| `data-flow/framework-callbacks` | 42780 | 6.1초 |
| `data-flow/rendered-screens` | 45433 | 4.8초 |
| `data-flow/run-json` | 28684 | 3.9초 |
| `data-flow/runner-marks` | 33754 | 4.0초 |
| `state-transitions` | 57234 | 10.2초 |
| `state-transitions/engine-lifecycle` | 56031 | 5.7초 |
| `state-transitions/incident-window` | 7214 | 3.8초 |
| `state-transitions/validity-gate` | 20231 | 2.7초 |

## 2026-09-11 동기화와 복구 검증

검증일: 2026-09-11 KST. Windows에서 Ollama 0.33.3, `qwen3.5:4b`(로컬 모델 ID `2a654d98e6fb`), Node 24.18.0, OMM 0.2.0을 사용했습니다. 제품 코드는 테스트를 위해 변경하지 않았습니다.

### 실제 모델 실행

임시 프로젝트의 `Probe.OBSERVE_MS`를 `10000L`에서 `12000L`로 변경했습니다. 먼저 실제 Ollama 요청에 1ms 제한을 적용해 실패시켰으며, 실행 전후 모든 원본 파일의 바이트가 같았습니다. 기본 제한으로 재실행한 결과는 다음과 같습니다.

- Qwen이 부모와 자식 OMM 설명을 모두 12000ms로 갱신했습니다.
- OMM CLI로 수정한 필드를 검증했습니다.
- Qwen이 작성한 원고에서 생성된 페이지에도 12000ms가 표시되었습니다.
- 7개 결과 파일을 반영했으며, 기존 검토 기록은 유지했습니다.
- 재실행 전체는 약 8초였습니다. 모델 로딩과 PC 상태에 따라 달라집니다.

최초 실행에서는 자식 설명만 바뀌고 부모에 10000ms가 남아 내용 검사가 실패했습니다. 부모와 자식의 반복된 사실을 모두 대조하도록 프롬프트를 보완한 뒤 통과했습니다. 이 사례는 형식 검사만으로 문서 정확성을 보장할 수 없음을 보여 줍니다. 자동 실행은 검토를 승인하지 않습니다.

실제 저장소에서도 `sync.mjs`를 실행했습니다. 모든 원본이 최신이어서 모델 호출 없이 0개 파일 변경으로 종료했습니다. 이는 변경 감지 경로의 검증이며, 전체 제품 코드를 재집필한 결과는 아닙니다.

### 오류 주입 검사

`sync.test.mjs`는 모의 Ollama HTTP 서버와 실제 OMM CLI를 사용합니다. 다음 실패에서 원본 보존 또는 복구를 검사합니다.

| 검사 | 확인한 결과 |
| --- | --- |
| HTTP 오류, 시간 초과, JSON 오류, 출력 잘림 | 종료 코드 1이며 원본 바이트가 같습니다. |
| 허용 범위 밖 OMM 경로, 잘못된 다이어그램 | 수정안을 반영하지 않습니다. |
| 빈 원고, 제공하지 않은 코드 인용, 두 번째 원고 실패 | 앞 단계의 성공분도 원본에 반영하지 않습니다. |
| 페이지 마커 훼손 | 부분 페이지 생성 결과를 반영하지 않습니다. |
| 실행 중 원본 편집 | 동기화를 중단하고 사용자 편집을 보존합니다. |
| 반영 도중 쓰기 오류 | 이미 반영한 파일을 실행 전 바이트로 되돌립니다. |
| 반영 중단 기록과 남은 잠금 | `sync.mjs --recover`가 이전 내용을 복원합니다. |
| 복구 대상에 후속 편집 존재 | 복구 전에 모든 대상을 확인하고 덮어쓰지 않습니다. |
| 실행 중 잠금, 원격 URL, 입력 한도 초과 | 추가 작업을 거부합니다. |
| 모든 원본 최신 | Ollama 서버가 없어도 모델 요청 없이 완료합니다. |

전원 장애, 파일 시스템 자체 손상, 실제 GitHub self-hosted runner, Windows 작업 스케줄러 등록은 이 검사 범위에 포함하지 않았습니다. 기존 문서 회귀 검사 9개는 별도로 실행합니다.

### 운영 전에 남은 작업

공용 엔진 0.1.0을 별도로 설치하고 `common.mjs sync`로 이 저장소를 실행했습니다. 기존 근거·원고·페이지가 일치하며 0개 파일 변경으로 완료했습니다. 공용 엔진에서는 별도 C++ 코드 경로를 읽는 실제 Qwen 실행도 통과했습니다. Slack 디자인은 네 페이지를 1360px·390px 화면에서 확인했으며, 그림 확대가 정상 동작했습니다.

현재 관점별 코드 근거는 약 31만~41만 자여서 기본 입력 한도 60,000자를 초과합니다. 작은 검증 프로젝트에서 동기화와 복구는 확인했지만, 전체 저장소를 Qwen으로 무인 갱신하는 기능은 아직 준비되지 않았습니다.

다음 작업은 **근거를 파일·요소 단위로 나누고, 변경 전후의 실제 코드와 대조하는 검사 사례를 추가하는 것**입니다. 이때 서로 다른 파일의 계약과 부모·자식 설명이 일치하는지도 검사해야 합니다. 해당 단계가 통과한 뒤 로컬 정기 실행을 등록합니다.
# P3 manuscript evidence validation · 2026-09-14

Windows / Node.js 24.18.0 / local `qwen3.5:4b`, `num_ctx=49152`, default 60,000-character input limit and 8,192-token output budget were used. The current bindings contain five manuscripts, rather than the six anticipated in issue #54.

Selecting existing citations and exact `must_link` filenames alone still produced roughly 111,000–318,000 characters per manuscript. The implementation therefore feeds all selected code through bounded evidence-summary calls, then retains the complete existing brief for the final writer. The user approved this extension while keeping manuscript blocks and the input limit unchanged.

Review follow-up: source offsets refer to the LF-normalized UTF-16 string, not on-disk bytes; CRLF carriage returns are intentionally removed. Perspective-wide freshness remains a conservative review alarm as specified by the existing pipeline, while the writer receives selected code and the OMM context refreshed by the scanner. A regression test verifies uncited code changes reflected in that context reach the writer. This does not assert that every uncited implementation detail is supplied to the writer; accepted review records remain unchanged.

The completed `sync.mjs --write-only --force` run took **287.968 seconds**. All **25 calls** (20 evidence summaries and five writers) completed with `done_reason: stop`. Inputs ranged from **7,974 to 56,484 characters**.

| Manuscript | Summary calls | Final writer input | Writer duration |
| --- | ---: | ---: | ---: |
| architecture/overview | 4 | 37,809 | 7.6 s |
| architecture/module-roles | 4 | 32,788 | 12.7 s |
| architecture/runtime-flow | 6 | 37,472 | 22.3 s |
| architecture/constraints | 2 | 29,926 | 11.5 s |
| troubleshooting/layer-isolation | 4 | 35,995 | 13.2 s |

The disposable validation checkout received eight output files: five manuscripts, two generated pages, and observed evidence state. Every `accepted` record and every `.omm` file matched the original. Generated prose remains a review candidate and was not copied into the product documentation. A loopback recording proxy forwarded the requests and streamed responses unchanged to the installed Ollama service; it recorded prompt lengths and final completion packets. Local artifacts are in `reviews/issue54-real-write-1789385725373/` in the parent workspace.

Earlier trials exposed overlong summaries, unsupported source citations, and repeated Markdown sections reaching the output limit. Each trial failed without publishing any output. The final writer uses fixed question fields and a source enum; the program assembles front matter from the binding and existing human evidence metadata. This prevents the model from adding device-verification claims to metadata. Semantic accuracy still requires review against code.

Final local deterministic validation: **81 passed**, with three existing opt-in tests skipped. Coverage includes exact filename selection, `must_link` freshness, complete LF-normalized large-source reconstruction from LF and CRLF files, empty and oversized summaries, citation rejection after summarization, structured answer completeness, preservation of human metadata, and existing transaction recovery. Design generation, extraction, freshness, generated-page equality, and whitespace checks pass. No runtime dependency was added.

## P1 long single-manuscript validation · 2026-09-14

The installed Qwen weights were copied to a temporary local model alias with `num_gpu=0` and `num_thread=2` to make a single request exceed 300 seconds. A disposable fixture requested a table of 20 Kotlin constants using the P3 structured writer. With context 32,768, default output budget 8,192, total timeout 1,800,000 ms and an explicit stress-test idle timeout of 600,000 ms, `sync.mjs --write-only` completed successfully. The one model call took **300.9 seconds**, used **15,629 input characters**, emitted **915 tokens**, and ended with `done_reason: stop`. The whole pipeline took **301.422 seconds** and published three fixture files. Review status remained stale. The temporary model alias and fixture were removed; the report and candidate are retained in the parent workspace at `reviews/issue52-long-real-1789387890145/`.

This confirms transport and transaction completion, not semantic approval. The generated candidate still contains inaccurate ordinal wording and an unsupported relationship between constants; it is not product documentation. Earlier CPU-only trials exposed an invalid source citation after a 1,209.3-second completed response, and a first-response preparation time over 300 seconds that hit fetch's header deadline. The latter motivated the native HTTP follow-up: configured total and idle timers now cover header waiting without an additional fetch deadline. Ordinary GPU execution retains the default 120-second idle timeout.

## P4 runner 및 P5 선택 갱신 검증 · 2026-09-14

실제 Windows 서비스 runner의 [전체 실행 34843006334](https://github.com/TTolsun/hal-camera/actions/runs/34843006334)은 약 11분 5초에 동기화를 완료했습니다. 스캔 47회·근거 요약 20회·집필 5회 모두 `done_reason: stop`이었고 최대 입력은 58,434자였습니다. 자동 생성한 [PR #66](https://github.com/TTolsun/hal-camera/pull/66)은 코드 대조에서 발견한 오류와 누락을 수정한 뒤 검토 기록·CI를 거쳐 머지했습니다. [Pages 실행 34845194647](https://github.com/TTolsun/hal-camera/actions/runs/34845194647)과 실제 HTML에서도 반영을 확인했습니다.

저장소 변수를 실제 삭제한 상태의 다음 main push는 [실행 34842964171](https://github.com/TTolsun/hal-camera/actions/runs/34842964171)에서 작업을 건너뛰었습니다. 이후 `DOCGEN_LOCAL_RUNNER_ENABLED=true`를 복구했습니다. runner는 비관리자 전용 계정으로 실행되며 Ollama는 사용자 로그인 시작 항목을 사용합니다. 재부팅과 로그인 전 실행은 검증하지 않았습니다.

첫 선택 검증은 `RunAssembler.kt`의 schema 설명만 수정한 `d2249fa`에서 실행했습니다. [실행 34845763411](https://github.com/TTolsun/hal-camera/actions/runs/34845763411)은 예상 요소 4개를 스캔하고 43개를 생략했지만, overview의 세 번째 근거 요약이 4,000자 한도를 넘어서 실패했습니다. 완료 이유는 `stop`이었으므로 출력 종료 여부와 길이 계약은 별도로 검사해야 합니다. 이 실패는 원본 문서와 스캔 기록을 보존했고 PR을 생성하지 않았습니다.

[PR #70](https://github.com/TTolsun/hal-camera/pull/70)에서 길이가 초과된 요약만 같은 원본 코드로 한 번 더 짧게 요청하도록 수정했습니다. 초과 응답을 자르거나 다음 입력에 넣지 않으며, 재시도 후에도 한도를 넘으면 전체 트랜잭션이 실패합니다. 로컬 결정론적 검사 86개가 통과했고 선택 검사 4개는 생략했습니다. Windows·Ubuntu CI와 Pages 빌드도 통과했습니다.

두 번째 검증은 `RunAssembler.kt`의 `observedFrames` KDoc 한 줄만 추가한 `143e026`에서 수행했습니다. [실행 34846817274](https://github.com/TTolsun/hal-camera/actions/runs/34846817274)은 13:02:41 UTC에 생성되어 13:08:18 UTC에 [PR #71](https://github.com/TTolsun/hal-camera/pull/71)을 열었습니다. 5분 37초로 45분 기준을 충족했습니다. 입력 한도 60,000자, 컨텍스트 49,152, 출력 8,192토큰, 전체 제한 1,800초, 유휴 제한 120초를 사용했습니다.

| 검증 항목 | 실측 결과 |
| --- | --- |
| 재스캔 요소 | `overall-architecture/benchmark/run-assembler`, `data-flow`, `data-flow/benchmark-metrics`, `data-flow/runner-marks`의 4개 |
| 나머지 스캔 기록 | 43개가 변경 전과 동일 |
| 원고 | 기존 관점 최신성 연결에 따른 5건 |
| 모델 호출 | 스캔 4회 + 근거 요약 20회 + 집필 5회 = 29회, 모두 `stop` |
| 입력 크기 | 최소 7,974자, 평균 41,387.7자, 최대 58,722자 |
| 모델 호출 시간 합계 | 311.0초 |
| 자동 출력 파일 | 17개, 구조 내용 변경은 위 4개 요소에 한정 |
| 자동 검토 기록 | 기존 `accepted` 8개 모두 보존 |
| 요약 재시도 | 이번 성공 실행에서는 발생하지 않음. 재시도 경로는 모의 응답으로 검증 |

이 수치는 자동 생성 직후 커밋 `9e01f94`를 `143e026`과 비교한 결과입니다. 실행 로그와 원본 diff는 작업 공간의 `reviews/issue51-success/`, `reviews/issue51-generated-original.patch`에 보존했습니다. 내용 검토에서는 `BenchmarkRun`이 계산한다는 잘못된 주어, `ScoreComposer`의 근거 없는 최소 10회 조건, LIVE 경로의 `LiveController` 오연결, 세션 필터와 이력 필터의 혼동, 기존 CLI·미디어 설명 누락을 발견했습니다. 잘못된 재작성은 이전 검토본으로 복원하고, 코드로 확인한 관측 프레임 수·validity 입력·직렬화 경계만 구조 설명과 실행 흐름 원고에 추가했습니다. 최종 변경 파일 수와 원고 수는 이 검토로 줄어듭니다. 자동 생성 성공은 내용의 정확성 승인과 다릅니다.
