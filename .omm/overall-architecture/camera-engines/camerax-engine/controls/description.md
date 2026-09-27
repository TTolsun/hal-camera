CameraXControls는 CameraXEngine의 Live 제어(EV, AE/AF 잠금, 플래시)와 터치 측광을 맡습니다. 규칙은 Camera2Engine과 같고 호출하는 API만 다릅니다.

- EV는 `CameraControl.setExposureCompensationIndex`, 토치는 `enableTorch`, 사진의 플래시는 ImageCapture의 flashMode입니다. 바뀐 값만 보냅니다.
- CameraX에는 AE 잠금 API가 없으므로 CONTROL_AE_LOCK을 Camera2CameraControl로 repeating 요청에 넣습니다. interop 옵션은 CameraX의 기본값보다 우선하기 때문에 이 키 하나만 씁니다. AE 모드를 interop으로 넣으면 CameraX의 플래시 처리가 깨집니다.
- CameraX는 FocusMeteringAction을 하나만 유지하고, 새 action은 이전 것을 취소합니다. 그래서 AF 잠금, 탭한 AF 지점, 길게 누른 AE 지점을 하나의 action으로 합칩니다. submitMetering은 지금 유지 중인 것들로 action을 다시 만들고, 아무것도 없으면 취소해 AF·AE를 전체 화면으로 되돌립니다. 탭한 지점이 없을 때의 AF 잠금은 화면 전체를 덮는 metering point를 씁니다. Camera2에서 AF 영역 없이 trigger를 보내는 것과 같은 의미입니다.
- CameraX의 자동 취소는 쓰지 않습니다. 탭한 AF 지점은 결과가 나온 뒤 5초(TouchMeter.HOLD_MS)가 지나면 이 클래스가 직접 끝냅니다. AF 잠금 중에는 사각형만 사라지고 그 지점의 잠금은 유지됩니다. Camera2와 같은 동작입니다.
- 길게 누르기는 action의 future가 끝난 뒤부터 TouchExposureWatch로 AE 상태를 셉니다. 그 전의 결과는 이전 영역을 담고 있기 때문입니다. 수렴하면 METERED를 알리고, FocusRing이 AE 잠금을 겁니다. 2초 안에 수렴하지 않으면 그 시점에 측광이 끝난 것으로 봅니다.
- 녹화 시작·정지로 세션이 바뀌면 탭한 AF 지점은 버리고, AE 잠금은 AeRelock으로 다시 수렴시킨 뒤 잠급니다. AF 잠금, 길게 누른 AE 지점, EV, 토치도 새 세션에 다시 보냅니다.

Camera2와 다른 점이 하나 있습니다. Camera2는 AE 잠금 중인 플래시 사진에서 precapture를 건너뛰지만, CameraX는 ImageCapture가 자기 순서대로 precapture를 수행합니다. 공개 호출은 main thread에서 오고 onResult는 카메라 콜백 스레드에서 오므로 상태는 lock으로 보호합니다. CameraX의 제어 호출은 스레드에 안전합니다.
