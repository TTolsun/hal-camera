# Camera Doctor

**중간 산출물 / checkpoint-001 · 2026-09-08**

현재 우선 검토할 파일은 [1차 MVP 지표 정의서](docs/METRICS.md)입니다.

1차 계획: **Camera2 단일 엔진 · First preview · Shot-to-shot · Recording performance · 반복 통계 · run JSON export**.
사용자 요청에 따라 전체 구현을 진행하지 않고 이 시점의 작업을 공유합니다.

## 현재 코드와 계획의 관계

`app/`은 처음 제안된 Live Inspector/Flight Recorder 아이디어를 탐색하며 작성한 초기 코드입니다. CameraX/Camera2 전환, metadata callback, scope, incident ZIP 코드가 들어 있지만, **새로운 1차 성능 측정 MVP 구현은 아닙니다**. 다음 단계에서 재사용 여부를 결정할 초안으로 보존합니다.

| 항목 | 상태 |
|---|---|
| 지표 정의서 | v0.1 검토안 작성, CTS 원본 대조 |
| 초기 Android 프로젝트 | Kotlin / Gradle Wrapper / APK 컴파일 완료 |
| Flight Recorder 단위 테스트 | 7개 통과 |
| Android Lint | **실패: 오류 5개, 경고 24개** |
| 실제 카메라 장치 테스트 | 미실행 |
| 새 계획의 3개 시나리오·반복 루프 | 미구현 |
| Google Drive | 체크포인트 소스·문서·검증 보고서 관리 |

APK가 생성되었다는 사실은 실제 카메라 동작 검증이나 MVP 완성을 의미하지 않습니다. 현재 검증 결과와 남은 항목은 [STATUS](docs/STATUS.md)에 기록했습니다.

## 빌드

Android Studio에서 이 폴더를 엽니다. JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13을 사용합니다.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

현재 체크포인트에서는 마지막 lint 단계가 실패합니다. `local.properties`는 PC별 SDK 경로로 생성하며 Git에 포함하지 않습니다. 툴체인 다운로드·빌드 캐시도 저장소에 포함하지 않습니다.

## 관리 방식

- GitHub 비공개 저장소: 소스와 정의서 버전 관리.
- [Google Drive CameraDoctor](https://drive.google.com/drive/folders/1JghtHY74UGHjZ3I2LeO7hUFh-TRgu0mP): 체크포인트 ZIP, 문서, 테스트 결과 보관.
- 다른 PC에서 작업할 때 GitHub를 clone하거나 Drive의 source ZIP을 내려받아 로컬 Android Studio에서 엽니다.
- Drive 웹에서 Android 앱을 컴파일·실행하는 환경은 구성하지 않았습니다. 자동 동기화나 자동 배포도 아직 설정하지 않았습니다.

향후 완성된 결과물은 `releases/`, 검토 중인 결과물은 `checkpoints/`에 저장합니다.
