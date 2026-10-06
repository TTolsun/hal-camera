# 0.20.0 병합 전 코드 리뷰

사용자의 전체 리뷰·병합·릴리스 요청에 따라 Codex가 `origin/main` 대비 CLI와 진행 화면 변경을 검토했습니다. 이 기록은 독립된 사람의 승인이나 새로운 기기 성능 평가를 의미하지 않습니다.

## 검토 범위와 결과

- `CliCommand`, `AdbArguments`, `CliJson`, ADB 스크립트와 Python 클라이언트의 명령·인자 계약을 대조했습니다. 벤치마크는 Camera2 표준 v2만 허용하고 Live 엔진·스트림 옵션을 거부합니다.
- `LiveController`와 MainActivity의 인계 경로에서 close(done) 뒤에 BenchmarkActivity를 열고, 요청 ID와 foreground를 다시 확인하는 것을 확인했습니다. `BenchmarkController`는 기존 preflight·Runner·저장 경로를 사용합니다.
- 취소·타임아웃·화면 이탈과 저장 완료 경로를 대조했습니다. 성공 여부와 파일 회수는 별개이며 실패·중단 보고서도 artifact로 보존할 수 있습니다. 실기기 시나리오는 기존 [CLI 검증 기록](cli-benchmark-20261006.md)에 있습니다.
- 녹화 프레임 표시는 현재 세션과 회차 태그를 필터링하고 회차별로 초기화합니다. 단계별 타이머와 촬영 순서는 표시용이며 보고서·점수 계산을 바꾸지 않습니다.
- 리뷰에서 발견한 오래된 CLI 허용 경로(`Tools > Settings`)를 `Lab > ADB CLI`로 수정했습니다. 구조 문서의 벤치마크 CLI 미지원 설명, 진행 단계 수, Activity와 CLI의 의존 설명도 현재 코드에 맞췄습니다.

검토한 변경에서 추가로 병합을 막을 코드 결함은 발견하지 못했습니다. 기기별 스트림 조합과 큰 글꼴 화면은 검증 범위 밖입니다.

## 검증

0.20.0/versionCode 629에서 JVM 테스트 639개, Android lint, 서명 release 빌드와 계측 테스트 APK 빌드를 통과했습니다. 계측 테스트 APK는 빌드만 했으며 기존 release 앱 위에 debug 앱을 설치하지 않았습니다. 문서 회귀 테스트 23개와 문서 검사 6단계를 통과했습니다.

문서 근거 변경을 `verify --changes`로 확인하고 관련 원본을 코드와 대조했습니다. 사용자의 리뷰·병합 지시에 따라 검토자는 `Codex-user-requested-release-review`로 기록했습니다. 예전 기기 관찰 기록은 새로 측정한 값으로 바꾸지 않았습니다.

Galaxy S25+ (`SM-S936N`)·Android 16에 기존 릴리스 인증서의 APK를 삭제 없이 업데이트했으며 `doctor`가 앱 버전 0.20.0과 준비 완료를 반환했습니다. 최종 배포 결과와 CI는 릴리스와 PR 기록에서 확인합니다.

배포 변경 사항은 [0.20.0 릴리스 기록](../releases/0.20.0.md)에 있습니다.
