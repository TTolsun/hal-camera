# HAL CAMERA 공통 디자인

Android 앱과 문서 사이트의 공통 시각적 기준은 [HAL CAMERA Editorial 디자인 규칙](docs/design/DESIGN.md)을 따릅니다.

`tools/docgen/project.json`의 `design` 설정은 `custom` 프리셋과 `docs/design/editorial.css`를 연결합니다. 공용 엔진의 `design` 명령(`node tools/docgen/docflow.mjs design`)은 이 원본으로 `guide/assets/docflow-design.css`를 생성합니다. 생성된 CSS를 직접 편집하지 않습니다. 사이트 빌드(`node tools/docgen/docflow.mjs site build`)가 이 파일을 `docs/assets/`로 복사합니다.

앱 공용 토큰은 `app/src/main/java/dev/halcamera/ui/Look.kt`, 화면별 조작은 [APP-UI.md](docs/design/APP-UI.md)에서 관리합니다.

한국어 집필 규칙과 근거 검증은 기존 문서 파이프라인을 유지합니다.

custom CSS는 공용 엔진이 원본 스타일시트를 그대로 복사하여 생성합니다. `node tools/docgen/docflow.mjs design --check`로 생성 결과를 확인합니다.
