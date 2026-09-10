# HAL Camera

**중간 산출물 / checkpoint-001 · 2026-09-08**

현재 우선 검토할 파일은 사용자 정의표를 반영한 [1차 MVP 지표 정의서 v0.2](docs/METRICS.md)입니다. [원문 대비 검토 내용](docs/METRICS-REVIEW.md)도 함께 보존했습니다. v0.2는 문서 수정안이며 기존 프리뷰 APK의 기능 변경이 아닙니다.

1차 계획: **Camera2 단일 엔진 · First preview · Shot-to-shot · Recording performance · 반복 통계 · run JSON export**.
사용자 요청에 따라 전체 구현을 진행하지 않고 **실제 카메라 프리뷰 코드와 설치용 APK가 있는 체크포인트**를 공유합니다. 실기기 구동 확인은 아직 하지 않았습니다.

## 현재 코드와 계획의 관계

`app/`은 처음 제안된 Live Inspector/Flight Recorder 아이디어를 탐색하며 작성한 초기 코드입니다. CameraX/Camera2 전환, metadata callback, scope, incident ZIP 코드가 들어 있지만, **새로운 1차 성능 측정 MVP 구현은 아닙니다**. 다음 단계에서 재사용 여부를 결정할 초안으로 보존합니다.

| 항목 | 상태 |
|---|---|
| 지표 정의서 | v0.2 검토안, 사용자 지표 ID·MVP 분류 유지, MediaRecorder 기본 |
| 초기 Android 프로젝트 | Kotlin / Gradle Wrapper / APK 컴파일 완료 |
| Flight Recorder 단위 테스트 | 7개 통과 |
| Android Lint | 통과: 오류 0개 (경고는 보고서 참조) |
| 실제 카메라 장치 테스트 | 미실행 |
| 새 계획의 3개 시나리오·반복 루프 | 미구현 |
| Google Drive | 체크포인트 소스·문서·검증 보고서 관리 |

APK가 생성되었다는 사실은 실제 카메라 동작 검증이나 MVP 완성을 의미하지 않습니다. 현재 검증 결과와 남은 항목은 [STATUS](docs/STATUS.md)에 기록했습니다.

## 빌드

Android Studio에서 이 폴더를 엽니다. JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13을 사용합니다.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

현재 체크포인트에서 위 세 작업은 모두 통과했습니다. `local.properties`는 PC별 SDK 경로로 생성하며 Git에 포함하지 않습니다. 툴체인 다운로드·빌드 캐시도 저장소에 포함하지 않습니다.

## 설치 후 확인

1. Google Drive 체크포인트의 `HALCamera-checkpoint-001-preview.apk`를 기기에 내려받아 설치합니다.
2. HAL Camera 실행 → 카메라 권한 허용 → 실제 프리뷰 확인.
3. Camera2 버튼을 눌러 직접 Camera2 프리뷰도 확인합니다.
4. 실패 시 화면 오류 문구와 기기 모델·Android 버전을 기록합니다.

이 APK는 debug 서명이며, 카메라 실기기 테스트·녹화 성능 시나리오 검증을 마친 릴리스가 아닙니다.

## 관리 방식

- GitHub 비공개 저장소: 소스와 정의서 버전 관리.
- [Google Drive HALCamera](https://drive.google.com/drive/folders/1JghtHY74UGHjZ3I2LeO7hUFh-TRgu0mP): 체크포인트 ZIP, 문서, 테스트 결과 보관.
- 다른 PC에서 작업할 때 GitHub를 clone하거나 Drive의 source ZIP을 내려받아 로컬 Android Studio에서 엽니다.
- Drive 웹에서 Android 앱을 컴파일·실행하는 환경은 구성하지 않았습니다. 자동 동기화나 자동 배포도 아직 설정하지 않았습니다.

향후 완성된 결과물은 `releases/`, 검토 중인 결과물은 `checkpoints/`에 저장합니다.
