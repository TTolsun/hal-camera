# docgen — 개발자 가이드 생성 파이프라인

`docs/guide/` 의 개발자 가이드를 코드에서 유래한 내용과 사람이 쓴 산문으로 나누어 유지합니다. 외부 의존성이 없어 `node` 만 있으면 어디서든 같은 결과가 나옵니다.

## 흐름

```
코드 ─ extract.mjs ─────────────→ state/facts.json           (결정론, 매번 재계산)
코드 ─ omm-scan (LLM) ──────────→ .omm/<perspective>/        (구조, 근거, 제약)
.omm + facts + brief + _inputs ─ 집필 (LLM) → docs/guide/_content/<page>/<id>.md
위 원본 전부 ─ verify.mjs ──────→ state/evidence.json        (최신성, 검토 기록)
위 원본 전부 ─ generate.mjs ────→ docs/guide/<page>.md 의 마커 블록
```

LLM 을 호출하는 곳은 `sync.mjs` 하나입니다. 생성기는 글을 쓰지 않고, 코드를 읽지 않고, git 을 호출하지 않습니다.

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

- Google Drive 가상 파일 시스템 위의 작업본에서는 `npm install` 이 0바이트 파일을 만듭니다. 그래서 의존성을 두지 않으며, YAML 은 `yaml-lite.mjs` 의 부분집합만 지원합니다(앵커, 여러 줄 스칼라, 인라인 매핑 없음).
- `.omm/` 바로 아래의 디렉터리는 모두 omm CLI 가 perspective 로 인식합니다. 그래서 추출 결과와 검증 상태는 `tools/docgen/state/` 에 둡니다.

## 검증 규칙과 PR 검토

- 해시 입력의 CRLF는 LF로 정규화합니다. 코드 근거는 텍스트 파일만 지정합니다.
- 원고 최신성에는 구조의 모든 하위 필드, 원고, 바인딩의 집필 지침, facts와 두 사람 입력 파일의 내용이 포함됩니다. 집필 프롬프트가 전체 입력 파일을 읽으므로 인용 ID가 같아도 파일 내용 변경은 재검토 대상입니다.
- sync --dry-run은 상태 파일을 쓰지 않으며, 변경된 코드에서 다시 최신성을 계산합니다. 실제 스캔 후에는 원고 의존성을 다시 계산합니다.
- node --test tools/docgen/regression.test.mjs는 임시 복사본에서 줄바꿈·입력 변경·dry-run·마커 오류를 검사합니다. CI는 Windows와 Ubuntu에서 실행합니다.
- 검토 기록은 node tools/docgen/verify.mjs --accept --reviewer=이름 형식으로 작성자를 명시할 수 있습니다. Codex의 코드 대조 기록은 사람의 승인이나 기기 실측을 뜻하지 않습니다. 자동 sync는 accept를 실행하지 않습니다.
- Pages PR 빌드는 Jekyll 산출물을 확인하며 배포는 main push에서만 수행합니다. 레이아웃은 Mermaid 11.12.0을 CDN에서 불러와 구조도를 표시합니다.
