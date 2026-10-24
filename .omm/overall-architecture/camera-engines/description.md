CameraEngine 구현은 Camera2·CameraX·Depth·Portrait로 나뉩니다. Camera2는 Live 기본 크기와 Benchmark의 명시적 StreamSpec을 구분합니다. Depth는 맵 보기, Portrait는 제조사 Bokeh JPEG 촬영입니다. 콜백과 close(done)는 러너에 연결됩니다.
StreamConfiguration의 Callback 목록은 실제 카메라 출력만 포함하며 앱 변환·합성·GPU 출력은 제외합니다.

App Bokeh는 일반 JPEG 저장 후 앱에서 깊이를 추정하고 배경을 흐립니다. 구현은 app-bokeh 요소에 설명합니다.
