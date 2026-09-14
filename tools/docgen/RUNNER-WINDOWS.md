# Windows 문서 동기화 runner

관리자 PowerShell에서 `tools/docgen/install-runner.ps1`을 실행하면 `hal-docgen` 일반 사용자와 `C:\ProgramData\HALCamera\docgen-runner`의 GitHub runner 서비스를 만듭니다. PowerShell 7, Git for Windows, Git Credential Manager의 저장소 관리 권한, 로컬 Ollama의 `qwen3.5:4b`가 필요합니다.

1. Ollama가 로그인 시 실행되고 `http://127.0.0.1:11434/api/tags`에 모델이 표시되는지 확인합니다. Windows 설치본의 시작 프로그램을 사용하거나 로그인 작업을 등록합니다.
2. 관리자 PowerShell에서 `& .\tools\docgen\install-runner.ps1`을 실행합니다. 설치기는 공식 runner 2.337.0의 SHA-256을 검사하며 같은 계정·폴더·runner가 있으면 덮어쓰지 않습니다. Windows 서비스 등록은 `config.cmd --runasservice`로 처리합니다.
3. GitHub Settings → Actions에서 외부 기여자 전체의 실행 승인을 요구하도록 설정합니다. 워크플로 기본 권한은 읽기로 유지하고, Actions의 PR 생성은 허용합니다. `docs-sync.yml` 작업에만 contents·pull-requests 쓰기 권한이 있습니다.
4. runner가 `halcamera-docgen-windows`라는 이름과 `docgen-qwen` 라벨로 온라인인지 확인한 뒤 저장소 Actions 변수 `DOCGEN_LOCAL_RUNNER_ENABLED=true`를 설정합니다.
5. Actions → docs-sync → Run workflow에서 `main`, `force=true`로 실행합니다. 성공 후 `docs/omm-sync` PR의 근거와 원고를 검토합니다. 일반 코드 push에는 `force=false`가 적용되어 변경된 근거만 스캔합니다.

`main`의 `app/**`, `.omm/**`, 바인딩, 문서 도구와 동기화 workflow 변경이 자동 실행 대상입니다. 작업은 저장소·브랜치·이벤트 종류·허용 변수를 모두 검사합니다. `pull_request` 트리거는 없으며 다른 workflow에 `docgen-qwen` 라벨을 사용하지 않습니다.

중지하려면 `DOCGEN_LOCAL_RUNNER_ENABLED` 변수를 지우거나 `false`로 설정합니다. 이후 push·수동 실행은 작업 시작 전에 건너뜁니다. 이미 시작한 작업은 Actions에서 별도로 취소해야 합니다. 다시 켜기 전에는 실행 중인 작업과 `.sync-lock`·복구 기록을 확인합니다.

서비스 계정은 관리자 그룹에 추가하지 않으며 runner 폴더에만 별도 파일 권한을 줍니다. GitHub 등록 토큰과 서비스 암호는 설치 프로세스 메모리와 runner의 비밀 마스킹 환경 입력으로 전달하고 스크립트·로그·작업 폴더에 평문 저장하지 않습니다. runner 자체 인증 파일은 공식 설치기가 관리합니다. 프로젝트의 서명 키나 API 키는 이 계정에 복사하지 않습니다.

설치가 중간에 실패하면 기존 계정이나 폴더를 자동 삭제하지 않습니다. 설치 로그, GitHub의 runner 등록 상태와 Windows 서비스를 확인한 뒤 해당 설치만 복구합니다. 관리자 권한 없이 설치 스크립트를 실행하면 변경 전에 실패합니다.

자동 동기화는 검토 기록을 승인하지 않습니다. PR 검토 후 `verify.mjs --accept --reviewer=이름`과 `generate.mjs`를 실행합니다. 구조 스캔·원고 계약·파일 반영 중 하나라도 실패하면 기존 파일을 보존하며 복구 절차는 [README](README.md#실패와-복구)에 있습니다.

공식 기준: [Windows runner 서비스 설치](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/configure-the-application?platform=windows), [외부 기여자 승인 정책](https://docs.github.com/en/rest/actions/permissions#set-fork-pr-contributor-approval-permissions-for-a-repository), [Ollama 시작 설정](https://docs.ollama.com/faq).
