CameraXEngine의 수명 주기입니다. start()에서 ProcessCameraProvider를 비동기로 받아 Preview, ImageAnalysis, ImageCapture를 LifecycleOwner에 bind하고, cameraState가 OPEN이 되면 Live 준비 완료를 알립니다. active가 거짓이 된 뒤 도착한 provider 콜백과 상태 변화는 무시합니다.

녹화를 시작하면 ImageAnalysis와 ImageCapture를 unbind한 뒤 VideoCapture와 ImageCapture를 함께 bind하며(조합을 거절하면 VideoCapture만), 녹화가 끝나면 되돌린 뒤 `CameraX · LIVE`를 다시 알립니다. 카메라는 열린 채로 세션만 새로 만들어지므로 줌과 Live 제어를 새 세션에 다시 보내고, AE 잠금은 AeRelock으로 다시 수렴시킨 뒤 겁니다. 녹화 중에는 cameraState의 OPEN을 Live 준비 완료로 알리지 않습니다.

close(done)은 analyzer를 먼저 떼고, 대기 중인 사진에 실패를 돌려주고, 진행 중인 녹화를 멈춘 뒤 bind한 use case(녹화 중이면 VideoCapture 포함)를 해제합니다. 그 뒤 cameraState가 CLOSED가 될 때 done을 한 번만 호출합니다. provider나 카메라 정보가 아직 없으면 바로 done을 호출합니다. 이 완료를 받기 전에는 다음 카메라를 열지 않습니다.

터치 초점·노출(FocusMeteringAction)도 이 엔진이 처리하며, 새 요청이 이전 요청을 취소합니다. 자세한 동작은 live-controls/touch-focus와 camerax-engine/controls 요소에 있습니다.
