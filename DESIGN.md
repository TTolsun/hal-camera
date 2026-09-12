# HAL CAMERA 공통 디자인

Android 앱과 문서 사이트의 공통 시각적 기준은 [HAL CAMERA Editorial 디자인 규칙](docs/design/DESIGN.md)을 따릅니다.

`tools/docgen/project.json`의 `design` 설정은 `custom` 프리셋과 `docs/design/editorial.css`를 연결합니다. 프로젝트 실행기의 `design` 명령은 이 원본으로 `docs/guide/assets/docflow-design.css`를 생성합니다. 생성된 CSS를 직접 편집하지 않습니다.

앱 공용 토큰은 `app/src/main/java/dev/halcamera/ui/Look.kt`, 화면별 조작은 [APP-UI.md](docs/design/APP-UI.md)에서 관리합니다.

한국어 집필 규칙과 근거 검증은 기존 문서 파이프라인을 유지합니다.

custom CSS는 프로젝트 생성기가 원본 경로와 재생성 명령을 주석으로 붙여 생성합니다. `node tools/docgen/common.mjs design --check`로 생성 결과를 확인하며, 이 경로에는 공용 엔진 설치가 필요하지 않습니다.
