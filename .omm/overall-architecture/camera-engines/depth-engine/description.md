DepthCameraEngine은 LIVE의 DEPTH16 전용 세션을 소유합니다. 공개 ID의 DEPTH_OUTPUT과 선택한 해상도를 재검사하고 ImageReader 하나만 구성합니다. 이전 엔진이 놓은 카메라는 AvailabilityCallback 또는 최대 2초의 대기 뒤 엽니다. 컬러 출력이 없으므로 DEPTH_DEPTH_IS_EXCLUSIVE인 카메라도 대상으로 삼을 수 있습니다. MediaCapture는 구현하지 않으며 촬영과 녹화를 제공하지 않습니다.

ImageReader는 acquireLatestImage로 버퍼를 읽고 use로 즉시 돌려줍니다. DepthFrame은 crop·rowStride·pixelStride와 little-endian 16비트 표본을 해석합니다. 하위 13비트는 mm 거리이며 상위 3비트는 신뢰도입니다. 신뢰도 코드 0은 100%, 1은 0%입니다. 거리가 0이거나 신뢰도가 50% 미만이면 검게 표시하고, 나머지는 고정된 0–8191 mm 범위에서 가까울수록 밝게 표시합니다. 긴 변은 최대 640픽셀로 표본을 추출하고 UI 전달 대기는 한 프레임으로 제한하며 최대 초당 10회 갱신합니다.

DepthMapView는 센서·디스플레이 회전과 전면 반전을 적용해 전체 맵을 fit으로 표시합니다. 중앙 거리와 표시 표본의 유효 비율을 함께 보여줍니다. 표시용 픽셀은 저장하지 않으며 Telemetry에는 크기·시각·형식과 Depth 콜백만 기록합니다.

세션 거절·버퍼 오류·카메라 오류는 상태 메시지로 보고하고 장치를 닫습니다. repeating request 제출 후 8초 이내 첫 표시용 프레임이 없으면 실패로 처리합니다. close(done)는 열기 대기 중인 장치도 onOpened에서 닫고 onClosed 이후 자원을 회수한 뒤 UI 스레드에서 완료를 전달합니다. 이미 해제된 엔진의 close도 완료를 전달합니다.
