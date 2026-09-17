# 개발자 가이드 문서 작성

시각적 형식은 저장소 루트의 `DESIGN.md`를 참고합니다. `tools/docgen/project.json`의 `design` 설정으로 Slack·plain·custom 디자인을 선택하고, 공용 실행기의 `design` 명령으로 CSS를 생성합니다. 한국어 집필 규칙은 디자인과 별도로 유지합니다.

이 디렉터리에서 배포하는 모든 페이지와 `_content/` 원고를 작성하거나 수정할 때 공용 엔진의 `style/README.md`(설치 경로 `tools/docgen/node_modules/@ttolsun/omm-doc-workflow/style/`)와 해당 문서가 연결하는 두 규칙 원문을 읽고 적용합니다.

이 디렉터리는 원고이며 배포 산출물은 `docs/`입니다. 원고를 바꾼 뒤 `node tools/docgen/docflow.mjs site build`로 `docs/`를 다시 생성하여 함께 커밋합니다. `docs/*.html`을 직접 편집하지 않습니다.

마커 블록은 `node tools/docgen/docflow.mjs generate`로 갱신합니다. 원고는 `_content/`, 구조 근거는 `.omm/`, 사실 정보는 추출기를 통해 수정합니다. 사용자에게 승인받은 편집 범위 안에서 마커 밖의 본문과 `_inputs/` 문구도 수정할 수 있습니다. 기술적 의미와 검증 수준을 유지합니다.

새 화면(`*Activity.kt`)이나 `dev/halcamera/` 바로 아래 패키지를 추가하면 먼저 `.omm/`에 요소 디렉터리를 만들고 `_bindings.yaml`의 `elements:`에 evidence를 적습니다. `node tools/docgen/docflow.mjs coverage --check`가 담당 요소 없는 화면·패키지를 누락으로 판정하며, 문서화하지 않을 파일은 `coverage.ignore`에 사유와 함께 적습니다. 절차는 `../../tools/docgen/README.md`의 "화면·패키지 추가"에 있습니다.
