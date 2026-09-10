# docgen — 개발자 가이드 생성 파이프라인

`docs/guide/`의 개발자 가이드를 코드 근거와 검토 기록에 맞춰 유지합니다. 결정론적 생성은 Node만 필요하며, 자동 동기화는 로컬 Ollama의 `qwen3.5:4b`와 OMM CLI 0.2.0을 사용합니다.

## 흐름

공용 시스템을 사용할 때는 별도로 설치한 엔진 경로를 `DOCFLOW_ENGINE_ROOT`에 지정하고 `node tools/docgen/common.mjs sync`를 실행합니다. `project.json`은 이 앱의 코드 범위·추출기·Qwen·디자인을 연결합니다. 공용 엔진 0.1.0을 사용하며, 비공개 엔진 소스를 이 공개 저장소에 복사하지 않습니다. 기존 명령과 CI 검사는 독립적으로 사용할 수 있습니다.

시각적 형식은 루트 `DESIGN.md`에 따라 Slack 디자인을 적용합니다. `project.json`의 `design.preset`을 `plain`으로 바꾸거나 `custom`과 `stylesheet`를 지정한 후 `node tools/docgen/common.mjs design`을 실행하면 모든 Pages 페이지의 형식이 바뀝니다. 생성 CSS는 `docs/guide/assets/docflow-design.css`이며, 한국어 집필 규칙과 원고 검토 해시에는 영향을 주지 않습니다.

```
코드 ─ extract.mjs ─────────────→ state/facts.json           (결정론, 매번 재계산)
코드 ─ omm-scan (LLM) ──────────→ .omm/<perspective>/        (구조, 근거, 제약)
.omm + facts + brief + _inputs ─ 집필 (LLM) → docs/guide/_content/<page>/<id>.md
위 원본 전부 ─ verify.mjs ──────→ state/evidence.json        (최신성, 검토 기록)
위 원본 전부 ─ generate.mjs ────→ docs/guide/<page>.md 의 마커 블록
```

`sync.mjs`가 임시 복사본을 만들고 `sync-worker.mjs`가 `qwen.mjs`를 통해 모델을 호출합니다. 모델은 JSON 수정안만 반환합니다. 파일이나 셸 도구는 제공하지 않으며, 스크립트가 검증한 결과만 반영합니다. 생성기는 글을 쓰지 않고 코드를 읽지 않습니다.

## 쓰기 권한

| 영역 | 작성 주체 |
| --- | --- |
| `.omm/**` | omm-scan (LLM) |
| `docs/guide/_content/**` | 집필 (LLM), 사람이 검토 |
| `tools/docgen/state/facts.json` | `extract.mjs` |
| `tools/docgen/state/evidence.json` | `verify.mjs` (검토 기록은 사람이 `--accept` 로) |
| `docs/guide/*.md` 의 마커 블록 내부 | `generate.mjs` |
| 마커 바깥 산문, front matter, `_bindings.yaml`, `_inputs/**`, `_config.yml` | 사람 |

## 명령

```bash
node tools/docgen/extract.mjs            # 사실 추출
node tools/docgen/verify.mjs             # 최신성 상태 표
node tools/docgen/verify.mjs --check     # CI: 최신이 아니면 실패
node tools/docgen/verify.mjs --accept    # 검토 완료를 기록 (사람이 실행)
node tools/docgen/generate.mjs           # 페이지에 반영
node tools/docgen/generate.mjs --check   # CI: 디스크와 다르면 실패
node tools/docgen/brief.mjs <page> <id>  # 집필 프롬프트 출력
node tools/docgen/sync.mjs [--dry-run]   # 낡은 원본을 LLM 으로 재생성
node tools/docgen/sync.mjs --recover    # 중단된 파일 반영을 실행 전 상태로 복구
node tools/docgen/selftest.mjs           # 산문 보존, 재현성, 최신성 검사
```

## 최신성 상태

| 상태 | 뜻 |
| --- | --- |
| 최신 | 검토 이후 관련 코드도 원본도 바뀌지 않음 |
| 관련 소스 변경됨 — 재검토 필요 | 근거 파일이 바뀜. `sync.mjs` 로 재생성하거나 직접 고친 뒤 `--accept` |
| 원본이 갱신됨 — 검토 대기 | `.omm/` 이나 원고가 바뀌었고 아직 사람이 검토하지 않음 |
| 검증 정보 없음 | 한 번도 `--accept` 하지 않음 |

## 근거 수준

원고와 블록마다 `confidence` 를 둡니다. 생성기가 사람 입력과 대조합니다.

- `code`: 코드를 읽어 확인한 동작
- `device`: 실제 기기에서 검증한 동작. `_inputs/device-verification.yaml` 의 `V-xxx` 기록이 있어야 합니다.
- `intent`: 설계 의도나 추정. 이유는 `_inputs/decisions.md` 의 `D-xxx` 에 있는 것만 인용합니다.

## 페이지 추가

1. `docs/guide/_bindings.yaml` 의 `pages` 에 페이지와 블록을 정의합니다.
2. 페이지 파일에 산문과 마커(`<!-- omm:begin id=… -->` / `<!-- omm:end id=… -->`)를 둡니다.
3. `content` 블록이면 `sync.mjs` 나 `brief.mjs` 로 원고를 만듭니다.
4. `generate.mjs` 를 실행하고 검토한 뒤 `verify.mjs --accept` 로 기록합니다.

## 알려진 제약

- Google Drive 가상 파일 시스템에서는 `npm install`이 0바이트 파일을 만들 수 있습니다. 이 경우 아래 설명처럼 OMM을 로컬 디스크에 설치합니다. 결정론적 생성기의 YAML은 `yaml-lite.mjs`의 부분집합만 지원합니다(앵커, 여러 줄 스칼라, 인라인 매핑 없음).
- `.omm/` 바로 아래의 디렉터리는 모두 omm CLI 가 perspective 로 인식합니다. 그래서 추출 결과와 검증 상태는 `tools/docgen/state/` 에 둡니다.

## 검증 규칙과 PR 검토

- 해시 입력의 CRLF는 LF로 정규화합니다. 코드 근거는 텍스트 파일만 지정합니다.
- 원고 최신성에는 구조의 모든 하위 필드, 원고, 바인딩의 집필 지침, facts와 두 사람 입력 파일의 내용이 포함됩니다. 집필 프롬프트가 전체 입력 파일을 읽으므로 인용 ID가 같아도 파일 내용 변경은 재검토 대상입니다.
- sync --dry-run은 상태 파일을 쓰지 않으며, 변경된 코드에서 다시 최신성을 계산합니다. 실제 스캔 후에는 원고 의존성을 다시 계산합니다.
- node --test tools/docgen/regression.test.mjs는 임시 복사본에서 줄바꿈·입력 변경·dry-run·마커 오류를 검사합니다. CI는 Windows와 Ubuntu에서 실행합니다.
- 검토 기록은 node tools/docgen/verify.mjs --accept --reviewer=이름 형식으로 작성자를 명시할 수 있습니다. Codex의 코드 대조 기록은 사람의 승인이나 기기 실측을 뜻하지 않습니다. 자동 sync는 accept를 실행하지 않습니다.
- Pages PR 빌드는 Jekyll 산출물을 확인하며 배포는 main push에서만 수행합니다. 레이아웃은 Mermaid 11.12.0을 CDN에서 불러와 구조도를 표시합니다.

## 배포 문서의 문체

모든 배포 페이지와 원고에는 [공통 집필 규칙](style/README.md)을 적용합니다. 두 스킬의 원문과 라이선스를 저장소에 고정해 두었으며, `brief.mjs`는 원문 전체를 전달합니다. 규칙이 바뀌면 원고 해시도 바뀌므로 재검토가 필요합니다.

## 로컬 Qwen 실행

1. `ollama list`에서 `qwen3.5:4b` 설치 여부를 확인합니다. Ollama 서버가 실행 중이어야 합니다.
2. 일반 디스크의 작업본에서는 `npm ci --prefix tools/docgen --ignore-scripts`로 OMM을 설치합니다.
3. `node tools/docgen/sync.mjs --dry-run`으로 대상을 확인한 뒤 `node tools/docgen/sync.mjs`를 실행합니다.
4. diff를 코드와 대조한 뒤 검토 기록과 생성 페이지를 함께 갱신합니다.

Google Drive 작업본에서는 PowerShell에서 OMM을 로컬 디스크에 설치합니다.

```powershell
$docgenTools = Join-Path $env:LOCALAPPDATA 'HALCamera/docgen-tools'
npm install --prefix $docgenTools --ignore-scripts oh-my-mermaid@0.2.0
$env:DOCGEN_OMM_CLI = Join-Path $docgenTools 'node_modules/oh-my-mermaid/dist/cli.js'
node tools/docgen/sync.mjs
```

Windows에서는 위 로컬 설치 경로를 자동으로 찾습니다. 다른 위치에 설치했을 때만 `DOCGEN_OMM_CLI`를 지정하면 됩니다.

클라우드 API 키나 Claude 로그인이 필요하지 않습니다. `DOCGEN_OLLAMA_URL`은 기본값 `http://127.0.0.1:11434`이며 루프백 주소만 허용합니다. 다른 로컬 Qwen을 사용하려면 `DOCGEN_QWEN_MODEL`을 지정합니다. 서버가 꺼졌거나 모델이 없으면 실패하며 다른 제공자로 전환하지 않습니다.

각 모델 호출은 기본 300초 후 중단됩니다(`DOCGEN_LLM_TIMEOUT_MS`). 입력은 기본 60,000자, 컨텍스트는 32,768토큰입니다(`DOCGEN_MAX_PROMPT_CHARS`, `DOCGEN_QWEN_CONTEXT`). 입력을 잘라 보내지 않으므로 넓은 근거 범위가 한도를 넘으면 실패합니다. 큰 모델이나 컨텍스트를 쓰기 전에 메모리 여유를 확인하고, 가능하면 바인딩의 근거 범위를 나눕니다. 현재 스캐너는 기존 OMM 요소의 필드를 갱신하며 새 요소 생성·삭제는 지원하지 않습니다.

현재 저장소의 세 관점은 코드만 약 31만~41만 자입니다. 따라서 기본 설정에서 해당 관점의 전체 재스캔은 입력 한도로 중단됩니다. 작은 범위의 실행·복구 검증은 완료했지만, 이 저장소 전체의 무인 갱신은 근거 분할을 구현하기 전까지 준비되지 않은 상태입니다. 한도를 올리는 것만으로 정확성이 확보되지는 않습니다.

## 실패와 복구

스캔, OMM 검증, 원고 계약, 페이지 생성 중 하나라도 실패하면 임시 복사본을 폐기하고 종료 코드 1을 반환합니다. 두 번째 원고가 실패해도 첫 번째 원고만 반영하지 않습니다. 실행 도중 원본이 바뀌면 결과를 반영하지 않습니다.

파일 반영 직전에 `state/sync-transaction/journal.json`에 이전 내용과 새 내용을 저장합니다. 처리 가능한 쓰기 오류는 즉시 되돌립니다. 프로세스가 강제로 종료되어 기록이 남으면 실행 중인 작업이 없는지 확인한 뒤 `node tools/docgen/sync.mjs --recover`를 실행합니다. 복구 후 같은 동기화 명령을 다시 실행하면 됩니다. 복구 대상에 후속 편집이 있으면 해당 내용을 덮어쓰지 않고 중단합니다. 실행 잠금과 복구 기록은 커밋하지 않습니다. 이 절차는 프로세스 중단에 대한 복구이며 전원 장애에 대한 저장장치 내구성을 보장하지 않습니다.

검토 기록의 `accepted`는 자동 동기화가 변경하지 않습니다. 형식 검증과 OMM 검사는 내용의 정확성을 보장하지 않으므로, PR에서 코드를 대조해야 합니다.

## 자동 실행과 검사 범위

로컬 실행 명령은 Windows 작업 스케줄러에서도 동일합니다. 이번 변경은 작업 스케줄러 등록을 생성하지 않습니다. GitHub의 일반 실행기는 로컬 PC의 Ollama에 접근할 수 없어 기존 클라우드 일일 실행을 제거했습니다. 선택적으로 `docgen-qwen` 레이블을 가진 신뢰할 수 있는 self-hosted runner를 등록하고 저장소 변수 `DOCGEN_LOCAL_RUNNER_ENABLED=true`를 설정하면 main의 `docs-sync`를 수동 실행할 수 있습니다. 이 runner는 PR 트리거로 실행하지 않습니다. PR 검사와 Pages 배포는 기존 GitHub 실행기를 사용합니다.

```powershell
node --test tools/docgen/regression.test.mjs tools/docgen/sync.test.mjs
$env:DOCGEN_REAL_QWEN = '1'
node --test --test-name-pattern='real installed Qwen' tools/docgen/sync.test.mjs
```

기본 CI는 모의 Ollama 응답과 실제 OMM CLI로 오류·복구를 검사합니다. 두 번째 명령은 설치된 Qwen을 실제 호출하므로 명시적으로 실행합니다. 두 검사 모두 임시 테스트 프로젝트를 사용하며 제품 코드나 배포 문서를 테스트용으로 바꾸지 않습니다.
