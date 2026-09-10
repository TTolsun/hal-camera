설계의 형태를 결정한 판단이 세 가지 있고, 모두 계획 문서에 근거가 남아 있습니다.

**preflight에서 카메라를 열지 않습니다.** 열어서 확인하면 측정되지 않은 open이 하나 생기고, 그러면 warm reopen이라는 조건의 정의 자체가 흐려집니다. 그래서 API 35 이상에서 `CameraManager.getCameraDeviceSetup(...).isSessionConfigurationSupported(...)`로 열지 않고 정확히 질의하고, 그 외에는 hardware level별 보장 조합 표로 정적 판정합니다. 어느 경로로 판정했는지를 `compatibility.method`에 남기는 이유는, 나중에 기기 사이를 비교할 때 그 차이가 중요하기 때문입니다.

**자동 fallback이 없습니다.** 판정 결과는 SUPPORTED와 UNSUPPORTED 둘뿐이고 DEGRADED를 두지 않습니다. 조건을 만족하지 못하는 기기에서 앱이 해상도를 낮춰 같은 프로파일 ID로 저장하는 일은 없습니다. 720p로 재려면 `camera2-standard-720p-v1`처럼 별도 ID를 정의해야 하며 이는 v0.3 범위 밖입니다. 같은 ID가 서로 다른 조건을 뜻하게 되면 비교 계약이 무너지기 때문입니다.

**프레임 예산은 기록만 하고 판정에 쓰지 않습니다.** `getOutputMinFrameDuration`이 33.33 ms를 넘어도 UNSUPPORTED로 만들지 않고 `compatibility.frame_budget_ok`에 남기기만 합니다. 실제로 30 fps가 나오는지는 실행 시점의 `CADENCE_NOT_FIXED`가 판단합니다. 30 fps가 안 나오는 것이 성능 차이라면 그것은 벤치마크가 잡아내야 할 결과이지 compatibility 단계에서 숨길 일이 아니기 때문입니다.
