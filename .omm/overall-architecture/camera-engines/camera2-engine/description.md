Camera2Engine은 전용 HandlerThread에서 Camera2 요청과 세션을 관리하고 UI 상태는 메인 스레드로 전달합니다. LIVE에서는 preview·YUV·JPEG 크기를 픽셀 예산에 맞춰 선택합니다. BenchmarkActivity가 StreamSpec을 전달하면 정확한 profile 크기와 FPS 범위를 사용합니다.

LIVE 사진은 하나의 요청에서 YUV와 JPEG을 대상으로 지정합니다. StillPair가 센서 타임스탬프로 두 버퍼를 연결하고 YuvPacking이 평면 stride와 crop을 고려해 NV21을 만듭니다. 별도 mediaIo 실행기가 YUV 변환과 MediaLibrary 저장을 처리합니다. 벤치마크 still은 기존 JPEG 측정 경로를 유지하며 앨범에 저장하지 않습니다.

녹화는 MediaRecorder의 영상·마이크 입력을 사용합니다. 프리뷰와 인코더 출력으로 세션을 구성하고 종료 시 임시 MP4를 앨범에 저장합니다. 실패하거나 너무 짧아 stop에 실패한 파일은 폐기합니다. 녹화 중에는 촬영·줌 요청을 받지 않습니다.

close는 active를 해제하고 세션과 기기를 닫습니다. finishClose에서 녹화 정리, reader와 Surface 해제, closed 이벤트, 완료 콜백, 실행기 종료를 처리합니다. API 30 이상은 CONTROL_ZOOM_RATIO를, 이전 버전은 crop 영역을 사용합니다.