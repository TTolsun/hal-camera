# Gallery·Lab 디자인 변경 리뷰와 실기기 화면

2026-09-30 Galaxy S25+(SM-S936N), Android 16/API 36, 1440×3120에서 확인했습니다. 앱은 0.16.0(versionCode 593)이며 기존 릴리스 서명으로 업데이트 설치하여 데이터와 설정을 유지했습니다. Gallery·About은 Runway 참고안, Device Info·ADB CLI·ZIP 대화상자는 Apple 공통 구성을 사용합니다.

## 코드 리뷰

세션 변경 커밋 `2347e04`와 최신 main `ef7e066` 통합 후 변경을 리뷰했습니다. 원격 진단 기능은 보존했습니다. Gallery의 MediaStore 조회, 선택 목록, 공유 URI 권한, 시스템 삭제 확인, 확대·재생 로직을 대조했습니다. Lab 설정의 별칭 저장·복사와 CLI 즉시 반영 동작, ZIP 파일 작업의 확인 순서도 검토했습니다.

About은 기존 `R.mipmap.ic_launcher` 캐릭터, 실제 패키지 버전, 이메일과 연속 탭 동작을 유지합니다. Look의 기존 공용 토큰은 변경하지 않았고 새로운 색과 버튼은 Gallery·About에 한정했습니다. Live는 ZIP 버튼의 초기·대기 문구만 영어로 바꿨습니다. 카메라와 측정 로직은 이번 디자인 변경의 대상이 아닙니다.

실기기에서 첫 Gallery 타일에만 둥근 모서리가 적용되지 않는 현상을 발견했습니다. 타일 크기가 바뀔 때 클립 경로를 갱신하고 자식 뷰를 그릴 때 마스크를 적용하여 수정했습니다. 수정 APK 재설치 후 일반·선택 모드의 첫 타일에서도 모서리가 일치함을 확인했습니다. 리뷰 범위에서 남은 머지 차단 사항은 없습니다.

## 기기에서 확인한 범위

- Camera2와 CameraX 프리뷰, Live 제어 펼침, `Save Events · ZIP` 문구를 확인하고 Camera2로 복귀했습니다.
- Gallery의 실제 사진 9장·동영상 9개 조회, 단일 선택과 취소, 사진 상세 진입·복귀를 확인했습니다.
- About의 기존 캐릭터와 실제 버전, 연락처의 표시를 확인했습니다. 메일 전송은 실행하지 않았습니다.
- Device Info의 별칭 입력 영역과 전체 보고서, ADB CLI의 기존 허용 상태를 확인했습니다. 설정값을 바꾸지 않았습니다.
- ZIP 7개 목록, 긴 파일명 줄바꿈, 파일 작업과 별도 삭제 확인창을 확인했습니다. 삭제 확인은 취소했으며 공유·내보내기·삭제를 실행하지 않았습니다.

UI 표시와 탐색 확인이며 카메라 성능·측정 회귀 검증은 아닙니다. 녹화·사진 촬영·ZIP 생성은 이번 촬영에서 실행하지 않았습니다. UIAutomator는 이 환경에서 덤프를 반환하지 않아 ADB 입력과 원본 스크린샷을 직접 대조했습니다.

## 검증

최신 main 통합 후 release 빌드, 앱 JVM 테스트 599개와 lintDebug가 통과했습니다. 모서리 후속 수정은 release 빌드와 기기 재확인으로 검증했습니다. 문서 6단계 검사와 문서 회귀 테스트 23개가 통과했습니다. CI 결과는 PR의 필수 Ubuntu·Windows 검사를 기준으로 확인합니다.

## 스크린샷

ADB screencap PNG 원본을 수정하거나 합성하지 않고 사용합니다. 대화상자와 Live 사진은 세션 디자인 빌드, Gallery 격자·선택은 main 통합 및 모서리 수정 빌드에서 촬영했습니다. 모두 같은 UI·버전이며 각 이미지의 확인 범위를 위에 구분했습니다. 원본은 `guide/assets/screenshots/`이며 문서 엔진이 `docs/assets/screenshots/`로 복사합니다. 과거 날짜의 검증·리뷰 이미지는 이력으로 보존합니다.

| 파일 | 갱신 내용 |
| --- | --- |
| [live.png](../../guide/assets/screenshots/live.png) | 영어 ZIP 버튼과 현재 Live 스트림 표시를 교체했습니다. 디자인 문서의 figures/live-screen.png도 같은 원본입니다. |
| [live-controls.png](../../guide/assets/screenshots/live-controls.png) | 현재 Live 제어 펼침 화면으로 교체했습니다. |
| [engine-camerax.png](../../guide/assets/screenshots/engine-camerax.png) | 현재 CameraX 프리뷰로 교체했습니다. |
| [cli-settings.png](../../guide/assets/screenshots/cli-settings.png) | Apple 스타일 CLI 대화상자로 교체했습니다. |
| [incident-history.png](../../guide/assets/screenshots/incident-history.png) | Apple 스타일 ZIP 목록으로 교체했습니다. |
| [gallery.png](../../guide/assets/screenshots/gallery.png) | Runway 앨범 격자를 추가했습니다. |
| [gallery-selection.png](../../guide/assets/screenshots/gallery-selection.png) | 선택 개수·체크·작업 버튼을 추가했습니다. |
| [gallery-detail.png](../../guide/assets/screenshots/gallery-detail.png) | 사진 상세를 추가했습니다. |
| [about.png](../../guide/assets/screenshots/about.png) | 기존 캐릭터를 유지한 Runway About을 추가했습니다. |
| [device-info.png](../../guide/assets/screenshots/device-info.png) | 기기 정보 대화상자를 추가했습니다. |
| [incident-actions.png](../../guide/assets/screenshots/incident-actions.png) | ZIP 파일 작업을 추가했습니다. |
| [incident-delete.png](../../guide/assets/screenshots/incident-delete.png) | ZIP 삭제 확인을 추가했습니다. |

레이아웃과 조작 규칙은 [APP-UI.md](../design/APP-UI.md)를 확인하세요.
