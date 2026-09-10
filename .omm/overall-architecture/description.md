HAL Camera는 단일 모듈 Android 앱입니다(`:app`, applicationId `dev.halcamera`, minSdk 26, compileSdk 36). 기기의 카메라가 어떻게 동작하는지 측정하고, 측정 결과를 기기 내부 저장소에 JSON 실행 파일로 남깁니다.

전체 구조는 한 방향 파이프라인입니다. 카메라 엔진이 Camera2 또는 CameraX를 구동하면서 프레임워크 콜백을 모두 Telemetry 파사드로 보고합니다. Telemetry는 이것을 `Event` 레코드로 만들어 `FlightRecorder` 링 버퍼에 기록합니다. 러너가 정해진 순서대로 카메라를 구동한 뒤, 엔드포인트별 계측값과 기록된 이벤트를 평가 계층에 넘기면 평가 계층이 이를 지표로 변환합니다. 평가가 끝난 실행 결과는 내부 저장소에 기록되고, 화면은 그 파일을 다시 읽습니다. 어떤 화면도 값을 다시 계산하지 않습니다.

평가 계층이 두 개 공존하는 이유는 제품 정의가 작업 도중에 바뀌었기 때문입니다. `diagnosis/`는 v0.2 건강 판정 계층이며(PASS / WARN / FAIL, NORMAL / WARNING / ISSUE), 현재 출시된 Auto Check 화면이 여전히 이것을 사용합니다. `benchmark/`는 v0.3 Camera BenchMarker 데이터 계약이고(측정 전용, IMPROVED / STABLE / REGRESSED / UNKNOWN), 지금은 M1 마일스톤까지만 구현되어 있습니다. 즉 데이터 모델, 프로파일, regression 규칙표, validity 표, run JSON schema 3만 있고, 이것을 채울 러너(M2)는 아직 없습니다.

서버도 데이터베이스도 외부 서비스도 없습니다. 앱 바깥에 있는 것은 Android 카메라 스택 자체뿐입니다.
