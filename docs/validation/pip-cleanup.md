# PIP 공통 처리 정리 검증

2026-10-10에 PR #262–#267의 구조 리뷰에서 지적한 네 항목을 정리하고 검증했습니다. UI 배치와 기능 계약은 유지합니다.

## 구조 리뷰

- Camera2와 CameraX의 PIP 사진 저장·녹화 상태를 `PipMedia`와 Android 어댑터로 통합했습니다. 장치·Surface 수명 주기는 각 세션에 남겼습니다. 녹화 시작·종료 중 close가 호출되면 저장 결과를 전달한 뒤 합성기를 해제합니다.
- `PipOutputs`의 설명자를 실제 세션 출력과 Callback 메타데이터가 공유합니다. Physical 이름은 `OutputDescriptor`에서만 만들고 합성 사진은 요청 대상과 분리합니다.
- 사용하지 않는 Hold 토글·잔여 시간 API와 Manual 관측 키를 제거했습니다. Hold 활성 여부는 시간 값에서 유도합니다.
- Single과 기존 Dual의 FPS·ISO·노출·AE·AF 문자열은 순수 `LiveMeasurementText`를 공유합니다.

공통 상태 제어는 Android를 참조하지 않고 함수로 입출력을 받습니다. 엔진별 카메라 연결을 억지로 공통 상속 구조에 넣지 않았습니다. Codex가 변경 전체를 자체 리뷰했으며 독립된 사람의 리뷰를 뜻하지 않습니다.

## 자동 검증

앱 JVM 테스트 741개, Android lint, release 빌드가 통과했습니다. 추가한 11개 테스트는 close와 촬영·녹화 경합, 실패 후 상태 복구, 실제 출력과 Callback 정의 일치, 공통 표시 형식을 검증합니다. 문서 테스트 33개와 문서 생성·사이트 검사가 통과했습니다.

## 실기기 검증

Galaxy S25+ (SM-S936N), Android 16 / API 36에서 서명된 release APK를 기존 데이터 위에 설치했습니다.

| 시나리오 | 관찰 결과 |
| --- | --- |
| Camera2 Service 0 + Physical 6, Photo | PIP 사진 저장과 `Meta (Phy)`, `Preview (Phy)`, `YUV`, `Jpeg` 행을 확인했습니다. |
| CameraX Service 0 + Service 1, Photo | 메인 `Preview`, `YUV`, 합성 `Jpeg` 행과 사진 저장을 확인했습니다. 추가 Service 결과는 메인 행에 섞이지 않았습니다. |
| 두 엔진의 PIP Video | 시작·정지 후 Saved video와 갤러리 썸네일을 확인했습니다. 녹화 중 Home으로 이탈한 뒤 복귀해도 프리뷰가 재개됐습니다. |
| Hold와 모드 전환 | 5초 설정으로 사진 프레임이 고정됐고 엔진·Photo/Video 전환 후에도 시간이 유지됐습니다. PIP와 Callback 표시 상태는 모드 전환 시 초기화됐습니다. 검증 후 시간은 0초로 복원했습니다. |
| 저장·UI | 새 JPEG 3개와 MP4 4개가 MediaStore에 0보다 큰 크기로 저장됐습니다. FPS부터 AF까지 한 줄 표시와 글자색만 사용하는 강조를 확인했습니다. |

검증 중 앱 충돌 기록은 없었습니다. 초기 UI 조회에서 Android CLI instrumentation 서버가 종료되어 한 차례 재시도했습니다. 화면은 로컬에서 확인했으며 촬영 영상과 스크린샷은 공개 저장소에 포함하지 않습니다. 영상 파일의 디코딩 품질과 다른 기기의 동시 조합은 이번 검증 범위가 아닙니다.

관련 동작 계약은 [Engine Comparison](../../guide/engine.md)을 참고하세요.
