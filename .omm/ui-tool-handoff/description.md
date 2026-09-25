Live 화면의 `도구` 메뉴는 `Probe`·`CTS`·`Benchmark`를 이 순서로 보여 줍니다. `Probe`는 `CameraCharacteristics`만 읽고 카메라를 열지 않으므로 `CameraProbeActivity`를 바로 시작하며, Live 카메라는 화면이 가려질 때 `onStop`이 평소처럼 닫습니다.

`CTS`와 `Benchmark`는 자기 카메라를 열기 때문에 `openAfterClose`를 거칩니다. 진행 중인 incident 기록을 마무리하고 진단 패널을 닫은 뒤, `closing`을 켜고 `카메라 세션 종료 중…`을 표시합니다. Live 엔진의 `close(done)` 콜백을 받은 뒤에야 다음 화면을 시작하며, `closing`이 켜져 있는 동안에는 메뉴를 다시 열 수 없습니다. 녹화 중, 녹화 저장 중, 세션 종료 중에는 `도구` 버튼이 비활성화됩니다. Live로 돌아오면 `onStart`가 카메라를 다시 엽니다.
