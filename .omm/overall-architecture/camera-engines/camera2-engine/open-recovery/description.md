Live에서 첫 프레임 전에 열기가 끊김·사용 중·최대 개수 초과·기기 또는 서비스 오류로 실패하면 CameraOpenRetry가 정한 200~2000ms 간격으로 최대 5번 다시 엽니다(#224). 다음 열기는 실패한 기기의 onClosed 뒤에 예약하고 open_retry 이벤트를 남깁니다. 최종 실패는 streamsFailed로 바로 알려 CLI 요청이 타임아웃까지 기다리지 않습니다. 벤치마크와 첫 프레임 이후의 오류는 다시 열지 않습니다.

엔진이나 카메라를 바꾼 뒤 Live의 첫 열기는 이전 엔진이 닫은 카메라를 CameraManager.AvailabilityCallback이 사용 가능으로 알릴 때까지 최대 2초 기다립니다(#230). CameraX의 close(done)이 카메라 서비스의 실제 해제보다 약 1초 먼저 오기 때문입니다. 대기는 CameraReleaseWait가 한 번만 끝내고 release_wait·release_waited 이벤트를 남기며, 이후 실패는 위 재시도가 맡습니다.
