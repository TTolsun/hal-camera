# docgen 무인 동기화 설계 (로컬 Qwen runner)

- 작성일: 2026-09-12
- 상태: 초안. 실행 계획은 `docs/PLAN-docgen-autosync.md`에 있습니다.
- 코드 기준: `main` `38b9b5c` (2026-09-12)
- 대상 파이프라인: `tools/docgen/` (README의 흐름도 참고)

## 0. 한 문장

`main`에 코드가 push되면 개발자 PC의 self-hosted runner가 로컬 Qwen으로 `.omm/` 구조와 `_content/` 원고를 갱신하고 PR을 연다. 사람은 PR을 검토하고 `verify.mjs --accept`로 기록한 뒤 merge한다. merge 이후 배포는 기존 `pages.yml`이 처리한다.

## 1. 현재 상태와 문제

배포와 검사는 이미 자동이다.

| 단계 | 담당 | 상태 |
| --- | --- | --- |
| 코드와 문서 불일치 검사 | `docs-check.yml` | 동작 중. PR과 `main` push에서 실행된다. |
| `docs/guide` 배포 | `pages.yml` | 동작 중. `main` push에서 GitHub Pages로 배포된다. |
| 구조와 원고 재작성 | `docs-sync.yml` + `sync.mjs` | 잠들어 있다. `workflow_dispatch` 전용이고 runner가 없다. |

`sync.mjs`를 이 저장소 전체에 실행하면 실패한다. 2026-09-12에 실측한 원인은 다음과 같다.

| 원인 | 실측값 | 현상 |
| --- | --- | --- |
| 관점 하나의 스캔 입력 | 22만~40만 자 (근거 코드 전체 + `.omm` 필드 전체) | 기본 한도 60,000자에서 즉시 중단 |
| 한도를 올린 경우 | `DOCGEN_QWEN_CONTEXT=163840`, VRAM 5.4GB + RAM | 생성 3.7 tok/s, 관점 하나에 수십 분 |
| Node `fetch` | 헤더 대기 300초 (undici 기본값) | `DOCGEN_LLM_TIMEOUT_MS`와 무관하게 중단 |
| 출력 한도 | `num_predict: 8192` 고정 | 원고 응답이 `done_reason: length`로 잘림 |
| 스캐너 기능 | 기존 요소의 필드 수정만 가능 | 새 화면이나 모듈 추가를 반영하지 못함 |
| 결과 품질 | 큰 입력에서 거의 같은 텍스트 반환 | 변경이 있어도 갱신되지 않음 |

핵심 원인은 스캔 단위가 관점(perspective) 전체라는 점이다. `overall-architecture` 관점은 코드 약 45만 자와 `.omm` 필드 약 4만 6천 자를 한 번에 보낸다. 4B 모델은 이 입력을 처리할 수 없다.

## 2. 목표와 비목표

목표

1. `main`에 `app/**`가 바뀌면 사람의 개입 없이 문서 갱신 PR이 열린다.
2. 모델 호출 하나의 입력이 항상 60,000자 이하이고, 전체 실행이 45분 안에 끝난다.
3. 기존 검증 규칙(`verify.mjs`의 최신성 판정, 원고 계약, 트랜잭션 복구)을 그대로 유지한다.
4. 클라우드 API 키 없이 로컬 Ollama만 사용한다.

비목표

1. `verify.mjs --accept` 자동화. 검토 기록은 사람이 남긴다.
2. `.omm` 요소의 자동 생성과 삭제. 새 모듈이 생기면 사람이 `omm` CLI로 요소를 만들고, 그 뒤부터 자동 갱신 대상이 된다.
3. 모델 교체. `qwen3.5:4b`와 RTX 5070 Laptop 8GB를 기준으로 설계한다.

## 3. 변경 개요

네 가지를 바꾼다. 앞의 세 가지는 `tools/docgen/` 코드 변경이고, 마지막은 실행 환경 변경이다.

```text
                    (A) 근거 분할              (B) 원고 근거 축소
코드 ──┬── 요소별 evidence ──→ 요소 단위 스캔 ──→ .omm/<perspective>/<element>/
       │                                            │
       └── 인용 파일 + must_link 파일 ──→ 원고 집필 ─┘──→ docs/guide/_content/<page>/<id>.md
                       │
             (C) 스트리밍 전송: qwen.mjs
                       │
             (D) self-hosted runner: docs-sync.yml (push main) ──→ PR
```

### A. 근거 분할: 스캔 단위를 관점에서 요소로 바꾼다

`_bindings.yaml`의 `sources.<name>`에 요소별 근거를 선언한다.

```yaml
sources:
  overall-architecture:
    kind: omm
    evidence:                      # 최신성 판정용. 기존과 같다.
      - app/src/main/java/dev/halcamera/**/*.kt
      - app/src/main/AndroidManifest.xml
      - app/build.gradle.kts
      - settings.gradle.kts
    elements:                      # 스캔 단위. 새로 추가한다.
      benchmark/run-assembler:
        evidence:
          - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
      screens/expert-screen:
        evidence:
          - app/src/main/java/dev/halcamera/MainActivity.kt
      ui-widgets:
        evidence:
          - app/src/main/java/dev/halcamera/ui/**/*.kt
```

규칙

1. 스캔 단위는 요소 하나다. 프롬프트에는 그 요소의 필드 파일, 부모 요소의 `description`(문맥용, 짧다), 그 요소의 근거 파일만 들어간다.
2. `elements`에 없는 요소는 부모의 근거를 물려받는다. 관점 루트 요소는 `evidence` 전체를 물려받으므로, 루트 요소에도 `elements`에 별도 항목(`.`)을 두어 진입 파일만 지정한다.
3. 요소 근거는 관점 `evidence`의 부분집합이어야 한다. `verify.mjs --check`가 이를 검사해서 CI에서 실패시킨다.
4. 프롬프트가 `DOCGEN_MAX_PROMPT_CHARS`를 넘으면 요소 이름과 크기를 출력하고 실패한다. 입력을 자르지 않는 기존 원칙을 유지한다. 해결은 사람이 `elements`를 더 나누는 것이다.
5. `verify.mjs`의 `omm:<source>` 키와 `codeHash`는 바뀌지 않는다. 최신성 판정은 관점 단위, 스캔은 요소 단위다.

재스캔 대상 선정

- `tools/docgen/state/scan.json`에 요소별 `{ codeHash, scannedAt }`을 기록한다. `sync.mjs`가 성공한 요소만 기록하며, PR에 함께 커밋한다.
- 요소의 현재 근거 해시가 `scan.json`과 다르거나 기록이 없으면 재스캔한다. `--force`는 stale 관점의 모든 요소를 재스캔한다.
- `sync.mjs`의 `outputPolicy` 허용 목록에 `scan.json`을 추가한다.

크기 근거 (2026-09-12 기준)

| 요소 | `.omm` 필드 | 근거 파일 | 합계 |
| --- | --- | --- | --- |
| `screens/expert-screen` | 4,209자 | `MainActivity.kt` 43,997자 | 약 48,000자 |
| `benchmark` (루트) | 2,165자 | `BenchmarkActivity.kt` 44,320자 | 약 47,000자 |
| `ui-widgets` | 약 1,000자 | `ui/**` 46,651자 | 약 48,000자 |
| `benchmark/run-assembler` | 약 1,300자 | `RunAssembler.kt` 9,751자 | 약 11,000자 |

가장 큰 단위도 60,000자 아래다. 요소는 약 40개이며, 대부분 5,000~20,000자다. 한 번의 `main` push는 보통 요소 1~5개만 건드린다.

### B. 원고 근거 축소: 집필 프롬프트에 코드 전체를 넣지 않는다

지금은 `based_on` 관점의 근거 글롭 전체를 원고 프롬프트에 넣는다. `overview` 블록은 `overall-architecture`와 `data-flow`를 참조하므로 코드 전체가 들어간다.

바꾼 뒤의 근거 파일 집합은 다음 셋의 합집합이다.

1. 기존 원고 front matter의 `sources`에 인용된 파일
2. `brief.must_link`의 심볼과 파일 이름이 일치하는 파일 (`RunAssembler` → `RunAssembler.kt`)
3. `based_on` 관점의 `.omm` 필드 전체 (이미 요약된 구조이므로 코드 대신 이것이 주 근거다)

원고가 아직 없고 `must_link`도 파일과 매칭되지 않으면 실패하고, 사람이 `brief.mjs`로 첫 원고를 쓰도록 안내한다. "인용한 파일은 제공된 파일의 부분집합"이라는 기존 계약 검사는 그대로 둔다.

### C. 전송: 스트리밍으로 헤더 대기 문제를 없앤다

`qwen.mjs`를 `stream: true`로 바꾼다. Ollama는 스트리밍이면 첫 토큰과 함께 헤더를 즉시 보내므로 undici의 300초 헤더 대기에 걸리지 않는다. 외부 의존성을 추가하지 않는다.

- 응답 본문은 NDJSON이다. 각 줄의 `message.content`를 이어 붙이고, 마지막 줄의 `done`, `done_reason`, `eval_count`로 완료를 판정한다. 판정 규칙은 지금과 같다.
- 시간 제한을 둘로 나눈다. `DOCGEN_LLM_TIMEOUT_MS`는 호출 전체 예산이며 기본값을 300,000에서 1,800,000으로 올린다. `DOCGEN_LLM_IDLE_MS`(기본 120,000)는 마지막 청크 이후 무응답 한도다.
- `num_predict`는 `DOCGEN_QWEN_NUM_PREDICT`(기본 8192)로 조정할 수 있게 한다. B의 축소로 원고가 한도 안에 들어오는 것이 목표이며, 한도 초과는 지금처럼 실패로 처리한다.
- `sync.test.mjs`의 mock 서버를 스트리밍 형식으로 바꾼다. 검사 항목(`tools` 없음, `truncate: false`, 모델 이름)은 유지한다.

### D. 실행 환경: self-hosted runner와 workflow 트리거

- 개발자 PC(Windows 11, RTX 5070 Laptop 8GB)에 GitHub self-hosted runner를 Windows 서비스로 설치한다. 라벨은 `self-hosted`, `docgen-qwen`이다.
- Ollama도 서비스로 실행되어 로그인 없이 `127.0.0.1:11434`에 응답해야 한다.
- 저장소 변수 `DOCGEN_LOCAL_RUNNER_ENABLED=true`를 둔다. 이 변수를 지우면 workflow가 즉시 멈춘다.
- `docs-sync.yml` 트리거를 `workflow_dispatch`에서 `push: main` + `paths: [app/**, .omm/**, docs/guide/_bindings.yaml, tools/docgen/**]`로 바꾼다. `workflow_dispatch`는 남긴다.
- 환경 변수는 `DOCGEN_QWEN_CONTEXT=49152`로 둔다. 입력 60,000자는 약 25,000~30,000토큰이고, 출력 8,192토큰을 더해도 49,152 안에 들어온다. 이 크기의 KV 캐시는 8GB VRAM 안에 들어가므로 RAM으로 넘치지 않는다.
- PR 생성은 기존 `peter-evans/create-pull-request` 단계를 그대로 쓴다. 브랜치 `docs/omm-sync`가 열려 있으면 갱신된다.

보안 원칙

1. 공개 저장소의 self-hosted runner는 fork PR의 workflow를 실행할 수 있다. `docs-sync.yml`은 `main` push에서만 실행되고 `pull_request` 트리거가 없으므로 fork 코드는 이 runner에 도달하지 않는다. 다른 workflow가 `docgen-qwen` 라벨을 쓰지 않도록 리뷰에서 확인한다.
2. 저장소 Settings > Actions에서 "Require approval for all outside collaborators"를 유지한다.
3. runner는 별도 Windows 사용자 계정으로 실행하고, 그 계정에는 저장소 clone 외 권한을 주지 않는다. runner에 비밀 값을 두지 않는다.
4. `qwen.mjs`의 루프백 주소 제한과 모델 이름 제한은 유지한다.

## 4. 실행 흐름 (변경 후)

```text
main push (app/** 변경)
  │
  ▼
docs-sync.yml (self-hosted, docgen-qwen)
  1. checkout, npm ci --prefix tools/docgen
  2. node tools/docgen/sync.mjs
     ├ extract.mjs            facts.json 재계산
     ├ verify.mjs             stale 관점 목록
     ├ 요소 선정               stale 관점 안에서 scan.json과 해시가 다른 요소
     ├ 요소별 스캔 (A, C)      요소 하나 = 모델 호출 하나
     ├ 원고 집필 (B, C)        stale 원고만
     ├ verify.mjs, generate.mjs
     └ 트랜잭션 반영           .omm, _content, docs/guide, state/{facts,evidence,scan}.json
  3. create-pull-request      브랜치 docs/omm-sync
  │
  ▼
사람: PR에서 코드와 대조 → verify.mjs --accept --reviewer=이름 → generate.mjs → 커밋 → merge
  │
  ▼
pages.yml → GitHub Pages 배포
```

## 5. 남는 수동 단계

| 단계 | 이유 |
| --- | --- |
| `verify.mjs --accept` | 검토 기록은 사람이 코드를 대조했다는 뜻이다. 자동화하면 배지가 의미를 잃는다. |
| `.omm` 요소 생성과 삭제 | 스캐너는 필드 수정만 한다. 구조 변경은 사람이 결정한다. |
| `elements` 근거 유지 | 파일이 옮겨지거나 새 파일이 생기면 사람이 `_bindings.yaml`을 고친다. CI가 부분집합 규칙 위반을 알려 준다. |
| PR 검토와 merge | 형식 검증은 내용의 정확성을 보장하지 않는다. |

## 6. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 요소 근거가 60,000자를 넘는 파일이 생긴다 (`MainActivity.kt`가 커지는 경우) | 실패 메시지에 요소와 크기를 출력한다. 사람이 파일을 나누거나 요소를 나눈다. 한도 상향은 마지막 수단이다. |
| PC가 꺼져 있어 workflow가 대기한다 | GitHub는 24시간까지 대기한다. `concurrency` 그룹이 중복 실행을 막는다. 대기가 길어지면 다음 push가 이어서 처리한다. |
| 모델이 변경 없는 필드까지 되돌려 준다 | 기존 파일과 같은 내용의 `updates`는 반영하지 않고 건너뛴다. |
| Codex 등 다른 에이전트가 같은 브랜치를 편집한다 | runner는 독립된 clone에서 실행된다. 개발자의 공유 작업본과 충돌하지 않는다. |
| `scan.json`이 PR에서 빠진다 | `docs-check.yml`의 상태 파일 일치 검사에 `scan.json`을 추가한다. |

## 7. 수용 기준

1. `main`에 `RunAssembler.kt`만 바꾸는 commit을 push하면, 45분 안에 `docs/omm-sync` PR이 열리고 `benchmark/run-assembler` 요소와 이를 참조하는 원고만 바뀐다.
2. 실행 로그의 모든 모델 호출 입력이 60,000자 이하이고 `done_reason: stop`이다.
3. 그 PR에서 `verify.mjs --accept` 후 `docs-check`가 통과한다.
4. `node --test tools/docgen/regression.test.mjs tools/docgen/sync.test.mjs`가 Windows와 Ubuntu에서 통과한다.
5. `DOCGEN_LOCAL_RUNNER_ENABLED`를 지우면 다음 push에서 workflow가 실행되지 않는다.
