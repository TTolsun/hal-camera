CameraXEngine의 수명 주기입니다. start()에서 ProcessCameraProvider를 비동기로 받아 Preview, ImageAnalysis, ImageCapture를 LifecycleOwner에 bind하고, cameraState가 OPEN이 되면 Live 준비 완료를 알립니다. active가 거짓이 된 뒤 도착한 provider 콜백과 상태 변화는 무시합니다.

close(done)은 analyzer를 먼저 떼고 bind한 use case를 해제한 뒤, cameraState가 CLOSED가 될 때 done을 한 번만 호출합니다. provider나 카메라 정보가 아직 없으면 바로 done을 호출합니다. 이 완료를 받기 전에는 다음 카메라를 열지 않습니다.

터치 초점·노출(FocusMeteringAction)도 이 엔진이 처리하며, 새 요청이 이전 요청을 취소합니다. 자세한 동작은 live-controls/touch-focus 요소에 있습니다.
