# 저장 책임 분리와 Live 정보 정렬 검증

2026-10-11에 PR #261–#269의 변경을 검토하고 저장 책임·중복 처리·사용하지 않는 분기·구현체 의존을 수정했습니다.

## 변경과 코드 리뷰

- DeviceCompositor의 GL 그리기와 픽셀 읽기는 유지하고, JPEG 압축과 녹화 종료·파일 저장을 CompositorMedia의 직렬 작업 스레드로 분리했습니다. close는 앞서 접수한 미디어 작업이 끝난 뒤 완료됩니다.
- MediaLibrary가 MediaTransaction을 사용하여 파일 쓰기·공개·실패 시 삭제를 공통 처리합니다. 일반 사진 묶음의 롤백과 Multi의 카메라별 부분 성공을 유지합니다.
- ConcurrentCameraActivity의 사용하지 않는 Single PIP 진입 경로와 전체 화면·인셋 배치 분기를 제거했습니다. Multi의 카메라별 분할 화면은 유지합니다.
- MainActivity는 PipCamera.pipSources로 후보를 조회합니다. CameraXEngine으로 형변환하여 후보를 가져오던 분기를 제거했습니다.
- Live 정보는 FPS·EXP·AE·AF를 같은 폭의 네 칸에 왼쪽 정렬합니다. ISO와 EV는 표시하지 않습니다. 상태 문구가 바뀌어도 칸 위치와 글자 크기를 다시 계산하지 않습니다.

변경 파일과 호출부를 대조하여 저장 실패 시 소유 파일 삭제, GL Surface 분리 이후 녹화기 해제, 종료 콜백의 호출 스레드, 제거한 경로의 남은 참조를 검토했습니다. 문서의 코드 근거도 대조했습니다. 이 기록은 Codex의 자체 리뷰이며 독립된 사람의 승인을 뜻하지 않습니다.

## 로컬 검증

- JDK 17에서 `testDebugUnitTest lintDebug assembleRelease`가 통과했습니다. JVM 테스트는 745개이며 실패와 오류는 없습니다.
- 새 저장 트랜잭션 테스트는 쓰기 실패, 공개 중 실패, 삭제 중 추가 실패, 전체 쓰기 이후 공개 순서를 검증합니다.
- 분할 화면은 1–6개 출력에서 빈틈 없이 전체 높이를 채우는지 검증합니다. Live 문자열 테스트는 누락 값, EXP 표시, AE·AF 상태별 예약 문구를 검증합니다.

## 실기기 검증

Galaxy S25+ (SM-S936N), Android 16 / API 36에서 release APK를 업데이트 설치하여 확인했습니다. 앱 데이터는 삭제하지 않았습니다.

| 시나리오 | 결과 |
|---|---|
| Camera2 일반 HAL JPEG 촬영 | JPEG와 메타데이터를 저장했습니다. |
| Camera2 Logical 0 + Physical 6 PIP, DNG 활성화 | 합성 JPEG, 원본 DNG, JSON을 저장했습니다. DNG 크기는 24,999,544바이트였습니다. |
| Camera2 Physical PIP 동영상 | 오디오 녹화와 중지 후 MP4 저장을 확인했습니다. |
| CameraX Service 0 + Service 1 PIP | 합성 사진과 오디오 동영상을 저장했습니다. MP4는 2,450,940바이트이며 `is_pending=0`이었습니다. |
| Multi · P, Service 0 + Service 1 | 두 카메라의 사진을 저장하고 완료 안내를 확인했습니다. |
| Multi · V, Service 0 + Service 1 | 두 MP4를 각각 저장했습니다. 크기는 1,711,134바이트와 11,990,258바이트이며 모두 `is_pending=0`이었습니다. |
| Multi 녹화 중 홈 화면 이동 | 두 파일의 저장 완료 후 앱에 복귀하여 프리뷰를 다시 열었습니다. |
| Live 정보 정렬 | 네 칸의 중심 x 좌표가 216, 552, 888, 1224px로 동일 간격이었습니다. AF Focused/Unfocused와 AE OK/Locked에서도 각 열의 x 좌표가 유지됐습니다. 활성 설정 행의 표시 여부는 별도로 높이에 반영됩니다. |

Camera2·CameraX는 같은 Live 정보 뷰를 사용합니다. 이번 화면 검증은 해당 기기의 기본 글꼴 설정에서 진행했습니다. 다른 기기·대형 글꼴·장시간 녹화는 실기기 검증 범위에 포함하지 않았으며, 성능 개선율을 측정한 결과는 아닙니다.

![같은 폭으로 정렬한 FPS, EXP, AE, AF](assets/solid-live-readout.png)
