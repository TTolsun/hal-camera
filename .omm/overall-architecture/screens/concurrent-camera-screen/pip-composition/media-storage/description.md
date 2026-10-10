PipMedia는 두 Live 엔진이 공유하는 순수 사진·녹화 상태 제어기입니다. 촬영과 녹화 시작·종료 작업이 완료될 때까지 close를 기다립니다. PipMediaAdapter는 합성기와 MediaLibrary를 연결하며 장치와 Surface의 해제는 각 세션에 맡깁니다.

Camera2에서 RAW가 켜져 있으면 원본 DNG와 JSON을 먼저 저장하고 같은 파일명 그룹에 합성 JPEG를 추가합니다. 합성 실패 시 이번에 저장한 원본 파일의 삭제를 시도합니다. 저장 전체가 하나의 원자적 작업은 아니며 원본과 합성 프레임의 시각도 같다고 보장하지 않습니다. DNG에는 PIP를 합성하지 않습니다. CameraX는 RAW를 제공하지 않으므로 합성 JPEG만 저장합니다.
