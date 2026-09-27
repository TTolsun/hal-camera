# 앱 기능별 실제 화면 촬영

- 촬영: 2026-09-27, Galaxy S25+ (SM-S936N), Android 16 / API 36.
- 앱: 배포된 0.15.0, versionCode 543. 앱을 다시 빌드하거나 설치하지 않았습니다.
- 도구: Android CLI screen capture와 ADB 화면 입력. 캡처한 PNG를 모두 눈으로 확인했습니다.
- 파일: guide/assets/screenshots 원본 16장, 1440×3120. 사이트 빌드로 docs/assets/screenshots에 복사합니다. 이미지를 수정하거나 측정값을 합성하지 않았습니다.

## 실제로 실행한 범위

Live의 Camera2 프리뷰, 제어 줄 펼침, CameraX 전환 후 프리뷰 갱신, 사진 저장 안내와 Callback의 YUV·JPEG 자동 고정, 녹화 중 Preview·Recording 값을 확인했습니다. 사진 두 장 저장 안내를 확인했으며 파일의 픽셀이나 인코딩은 검사하지 않았습니다. 녹화를 정상 종료했습니다.

Probe에서 카메라 0의 Identity를 펼쳤습니다. 커스텀 CTS의 빠른 켜기·끄기 한 개를 실행했으며 34초에 PASS 1·FAIL 0으로 완료됐습니다. 원문 CTS 목록은 열어 보았고 원문 테스트는 실행하지 않았습니다.

Benchmark는 카메라 0과 기본 camera2-standard-v2 프로필로 실행했습니다. 실행 이름은 docs-screenshots-v0.15.0입니다. 완료 후 결과와 실행 기록을 확인했습니다. 기존 baseline은 변경하지 않았습니다. 결과 화면에는 1 metric degraded와 충전·노출 조건 차이 경고가 표시됐습니다. 문서 화면 확보를 위한 실행이므로 이를 통제된 성능 비교나 앱 버전 회귀의 증거로 사용하지 않습니다.

CLI 설정과 ZIP 기록은 조회만 했습니다. CLI 허용은 이미 켜져 있었으며 변경하지 않았습니다. ZIP 생성·공유나 CLI 명령 실행은 이 촬영 범위에 포함하지 않습니다.

## 화면과 설명

| 원본 | 확인한 장면 |
| --- | --- |
| [live.png](../../guide/assets/screenshots/live.png) | Camera2의 사진 모드입니다. 하단에서 실시간 정보, 줌, 셔터와 최근 썸네일을 확인할 수 있습니다. |
| [live-controls.png](../../guide/assets/screenshots/live-controls.png) | 상단 화살표로 제어 줄을 펼쳤습니다. Flash·AF·AE·EV를 조작할 수 있습니다. |
| [engine-camerax.png](../../guide/assets/screenshots/engine-camerax.png) | CameraX로 전환한 뒤 프리뷰와 실시간 정보가 갱신되는 화면입니다. 상단의 엔진 이름으로 현재 경로를 확인합니다. |
| [probe.png](../../guide/assets/screenshots/probe.png) | 기기 정보 영역을 접고 카메라 0의 Identity를 펼쳤습니다. LEVEL_3 선언과 논리 멀티카메라 정보를 읽을 수 있습니다. |
| [cts-custom.png](../../guide/assets/screenshots/cts-custom.png) | 커스텀 케이스에서 빠른 켜기·끄기 한 개를 선택했습니다. 하단 실행 버튼은 선택한 항목 수를 표시합니다. |
| [cts-vendored.png](../../guide/assets/screenshots/cts-vendored.png) | AOSP 원문 메서드 목록을 연 화면입니다. 이 장면에서는 항목을 선택하거나 원문 테스트를 실행하지 않았습니다. |
| [cts-running.png](../../guide/assets/screenshots/cts-running.png) | 빠른 켜기·끄기를 실행 중인 화면입니다. 카메라 프리뷰와 현재 회차, 단계별 판정을 함께 표시합니다. |
| [cts-result.png](../../guide/assets/screenshots/cts-result.png) | 이번 실행은 34초에 PASS로 끝났습니다. 이 결과는 해당 기기의 앱 내 검사 한 건이며 공식 CTS 인증 결과가 아닙니다. |
| [benchmark-setup.png](../../guide/assets/screenshots/benchmark-setup.png) | 카메라 0에서 실행하기 전의 조건입니다. 이번 실행에는 docs-screenshots-v0.15.0이라는 이름을 붙였습니다. |
| [benchmark-running.png](../../guide/assets/screenshots/benchmark-running.png) | 카메라 열기 반복 측정이 진행 중입니다. 현재 단계와 회차, 진행률을 확인할 수 있습니다. |
| [benchmark-result.png](../../guide/assets/screenshots/benchmark-result.png) | 실행 완료 후 기존 baseline과 비교한 화면입니다. 충전 상태와 노출 조건 차이가 표시되므로, 이 장면을 앱 버전 간 성능 저하의 증거로 해석하지 않습니다. |
| [benchmark-history.png](../../guide/assets/screenshots/benchmark-history.png) | 방금 실행한 docs-screenshots-v0.15.0이 목록에 저장됐습니다. 기존 baseline은 별도 묶음에 남아 있습니다. |
| [callback-recording.png](../../guide/assets/screenshots/callback-recording.png) | Camera2로 녹화하면서 Preview·Recording 값을 확인한 장면입니다. Real-time Frame에서는 시간 버튼이 숨겨지고 프레임이 계속 갱신됩니다. |
| [callback-event.png](../../guide/assets/screenshots/callback-event.png) | 고정 시간이 10초인 상태에서 사진을 촬영했습니다. 일시정지 표시와 같은 프레임의 YUV 1·JPEG 값을 확인할 수 있습니다. |
| [cli-settings.png](../../guide/assets/screenshots/cli-settings.png) | 도구 → 설정 · 앱 정보 → ADB CLI 설정을 연 화면입니다. 촬영 기기는 이미 허용된 상태였으며 설정을 변경하지 않았습니다. |
| [incident-history.png](../../guide/assets/screenshots/incident-history.png) | 도구 → ZIP 기록에서 기기에 저장된 incident 목록을 열었습니다. 기존 기록을 조회한 화면이며 이 촬영에서 ZIP 생성이나 공유를 실행하지 않았습니다. |

## 문서 검증

문서 회귀 테스트 23개와 로컬 docflow 검사 6단계가 통과했습니다. 8개 문서에서 16개 이미지의 원본 링크를 눌러 1440×3120 PNG가 열리는지 확인하고 뒤로 가기로 복귀했습니다. 원본과 배포 파일 16쌍의 SHA-256도 일치했습니다.

375×812 모바일 뷰포트에서 이미지가 본문 폭에 맞게 줄어들고 페이지 전체에 가로 넘침이 없는지 확인했습니다. 데스크톱에서는 최대 400px 폭을 유지합니다. 모든 이미지에 대체 텍스트, 원본 링크, 캡션, 고유 앵커가 있으며 아래쪽 이미지는 지연 로딩합니다. CI 상태 검사는 커밋 후 별도로 실행합니다.
