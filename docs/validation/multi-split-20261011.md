# Multi Split UI 검증 기록

Multi · P와 Multi · V는 Photo·Video의 상단·하단 배치를 사용하고 PIP 위치에 Split 설정을 제공합니다. Split은 프리뷰 배치만 정하며 장치별 파일에 다른 장치 영상을 합성하지 않습니다. Manual은 Multi 제어에서 제외합니다.

## 로컬 검증

| 검사 | 결과 |
| --- | --- |
| `assembleRelease` | 기존 release 서명으로 빌드했습니다. |
| `testDebugUnitTest` | app JVM 테스트 754개가 통과했습니다. |
| `lintDebug` | 오류 없이 통과했습니다. 기존 경고는 남아 있습니다. |
| `npm test --prefix tools/docgen` | 문서 회귀 검사 33개가 통과했습니다. |
| `docflow.mjs check --build` | 문서 검사 6단계와 사이트 생성이 통과했습니다. |

`ConcurrentPlanTest`는 위아래·좌우·격자 배치가 홀수 크기의 프리뷰 영역을 겹침과 빈틈 없이 나누는지 검사합니다. 카메라 회전에서 정한 출력 크기와 전체 프레임 맞춤도 검사합니다. 이 JVM 검사를 실제 인코더·JPEG 저장 검증으로 해석하지 않습니다.

## 기기 관찰

- 기기는 Galaxy S25+ (`SM-S936N`)이며 Android 16을 사용했습니다.
- 기존 앱을 삭제하지 않고 같은 서명의 초기 구현 APK를 업데이트했습니다.
- Camera2에서 독립 Camera ID 0·1의 프리뷰가 위아래로 표시됐습니다. 갤러리·원형 셔터·네 모드·Save Events · ZIP과 Split 버튼의 배치를 확인했습니다.
- Split을 누르면 상단 설정창에 Layout, Maximum camera devices, 카메라 조합과 Apply가 표시됐습니다.
- 이후 무선 ADB 연결이 끊겨 최종 APK의 화면별 순서 선택, 좌우·격자 적용, 실제 JPEG·MP4 저장과 재생은 기기에서 확인하지 못했습니다. UI 관찰은 초기 구현에 한정합니다.

## 남은 기기 확인

1. Split의 각 배치를 적용하고 장치 순서를 바꿉니다. 닫기와 뒤로 가기는 적용 전 설정을 유지해야 합니다.
2. Multi · P에서 셔터를 누르고 카메라별 JPEG와 묶음 JSON을 확인합니다. JPEG에 다른 카메라 영상과 UI가 포함되지 않아야 합니다.
3. Multi · V에서 녹화를 시작·종료하고 장치별 MP4를 재생합니다. 배치를 바꿔도 해당 장치의 저장 크기와 전체 화각이 유지돼야 합니다.
4. 촬영·녹화 중에는 Split 변경이 차단돼야 합니다. Photo·Video로 돌아오면 모든 Multi 장치를 닫은 뒤 Live 프리뷰가 시작돼야 합니다.

사용 절차는 [Live의 Multi 안내](../../guide/live.md#여러-카메라를-함께-촬영하세요)를 확인하세요.
