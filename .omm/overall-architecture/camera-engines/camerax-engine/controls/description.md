CameraXControls는 CameraXEngine의 Live 제어(EV, AE/AF 잠금, 플래시)와 터치 측광을 맡습니다. 규칙은 Camera2Engine과 같고 호출하는 API만 다릅니다.

- EV는 `CameraControl.setExposureCompensationIndex`, 토치는 `enableTorch`, 사진의 플래시는 ImageCapture의 flashMode입니다. 바뀐 값만 보냅니다.
- CameraX에는 AE 잠금 API가 없으므로 CONTROL_AE_LOCK을 Camera2CameraControl로 repeating 요청에 넣습니다. interop 옵션은 CameraX의 기본값보다 우선하기 때문에 이 키 하나만 씁니다. AE 모드를 interop으로 넣으면 CameraX의 플래시 처리가 깨집니다.
- CameraX는 FocusMeteringAction을 하나만 유지하고, 새 action은 이전 것을 취소합니다. 그래서 AF 잠금, 탭한 AF 지점, 길게 누른 AE 지점을 하나의 action으로 합칩니다. submitMetering은 지금 유지 중인 것들로 action을 다시 만듭니다. 유지할 것이 없으면 화면 전체에 AE만 측광하는 action을 보내 영역을 되돌리고, 이전 action이 잠근 AF는 interop 옵션에 한 번 실은 CONTROL_AF_TRIGGER_CANCEL로 풉니다.
- cancelFocusAndMetering은 쓰지 않습니다. CameraX 1.6의 camera-pipe에서 이 호출은 unlock3A(ae = true)이고, 그래프의 3A 상태에 aeLock = false를 남깁니다. 3A 상태는 요청 옵션보다 우선하므로, 한 번 취소하면 같은 세션에서는 interop으로 건 AE 잠금이 더 이상 적용되지 않았습니다(Galaxy S25+, 요청의 aeLock이 OFF로 고정됨). AF가 들어간 action은 lock3A(aeLockBehavior = null)이고, AE만 있는 action은 영역만 갱신하므로 AE 잠금에 영향을 주지 않습니다. 탭한 지점이 없을 때의 AF 잠금은 화면 전체를 덮는 metering point를 씁니다. Camera2에서 AF 영역 없이 trigger를 보내는 것과 같은 의미입니다.
- CameraX의 자동 취소는 쓰지 않습니다. 탭한 AF 지점은 결과가 나온 뒤 5초(TouchMeter.HOLD_MS)가 지나면 이 클래스가 직접 끝냅니다. AF 잠금 중에는 사각형만 사라지고 그 지점의 잠금은 유지됩니다. Camera2와 같은 동작입니다.
- 길게 누르기는 action의 future가 끝난 뒤부터 TouchExposureWatch로 AE 상태를 셉니다. 그 전의 결과는 이전 영역을 담고 있기 때문입니다. 수렴하면 METERED를 알리고, FocusRing이 AE 잠금을 겁니다. 2초 안에 수렴하지 않으면 그 시점에 측광이 끝난 것으로 봅니다.
- 녹화 시작·정지로 세션이 바뀌면 탭한 AF 지점은 버리고, AE 잠금은 AeRelock으로 다시 수렴시킨 뒤 잠급니다. AF 잠금, 길게 누른 AE 지점, EV, 토치도 새 세션에 다시 보냅니다.

- 탭한 AF 지점의 결과는 그 지점을 담은 action 가운데 먼저 끝난 것이 알립니다. 스캔 중에 action을 다시 보내면 이전 action이 취소되기 때문입니다. 3초(TouchMeter.SCAN_TIMEOUT_MS) 안에 결과가 없으면 Camera2처럼 실패로 처리합니다.
- 세션을 다시 만든 직후에는 이전 세션의 잠긴 결과가 CameraX 실행기에서 늦게 도착합니다. 그래서 AeRelock이 기다리는 동안에는 CONTROL_AE_LOCK을 끈 요청의 결과만 셉니다. 이전 세션의 LOCKED 상태를 새 세션의 수렴으로 착각하면 수렴 전 노출을 잠그게 되기 때문입니다.
- 닫힌 엔진의 지연 작업(탭 유지 시간 종료, AE 재잠금 timeout)은 아무것도 보내지 않습니다. 같은 카메라를 다시 열면 새 엔진이 같은 CameraControl을 쓰기 때문입니다.

Camera2와 다른 점이 둘 있습니다. 첫째, AF 잠금도 FocusMeteringAction이므로 AF 잠금 중에 길게 누르거나 AE 잠금을 풀어 action을 다시 보내면 AF가 한 번 더 스캔합니다. action에서 AF를 빼면 CameraX가 AF 잠금을 풀기 때문에 피할 수 없습니다. 둘째, Camera2는 AE 잠금 중인 플래시 사진에서 precapture를 건너뛰고 잠긴 노출로 촬영하지만, CameraX는 ImageCapture가 자기 순서대로 precapture를 수행하고 플래시에 맞춰 노출을 다시 정합니다. Galaxy S25+에서 AE 잠금(ISO 161, 8.33ms)과 플래시 On으로 찍은 사진은 플래시가 터졌고 1/1169초, ISO 25로 저장되었습니다. 플래시가 없는 사진에는 잠긴 노출과 EV가 그대로 적용됩니다. 공개 호출은 main thread에서 오고 onResult는 카메라 콜백 스레드에서 오므로 상태는 lock으로 보호합니다. CameraX의 제어 호출은 스레드에 안전합니다.
