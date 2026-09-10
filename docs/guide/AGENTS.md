# GitHub Pages 문서 작성

시각적 형식은 저장소 루트의 `DESIGN.md`를 참고합니다. `tools/docgen/project.json`의 `design` 설정으로 Slack·plain·custom 디자인을 선택하고, 공용 실행기의 `design` 명령으로 CSS를 생성합니다. 한국어 집필 규칙은 디자인과 별도로 유지합니다.

이 디렉터리에서 배포하는 모든 페이지와 `_content/` 원고를 작성하거나 수정할 때 `../../tools/docgen/style/README.md`와 해당 문서가 연결하는 두 규칙 원문을 읽고 적용합니다.

마커 블록은 `generate.mjs`로 갱신합니다. 원고는 `_content/`, 구조 근거는 `.omm/`, 사실 정보는 추출기를 통해 수정합니다. 사용자에게 승인받은 편집 범위 안에서 마커 밖의 본문과 `_inputs/` 문구도 수정할 수 있습니다. 기술적 의미와 검증 수준을 유지합니다.
