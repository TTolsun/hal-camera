PipMedia는 두 Live 엔진이 공유하는 순수 사진·녹화 상태 제어기입니다. 촬영과 녹화 시작·종료 작업이 완료될 때까지 close를 기다립니다. PipMediaAdapter는 합성기와 MediaLibrary를 연결하며 장치와 Surface의 해제는 각 세션에 맡깁니다.

Camera2에서 RAW가 켜져 있으면 원본 DNG와 JSON을 먼저 저장하고 같은 파일명 그룹에 합성 JPEG를 추가합니다. 합성 실패 시 이번에 저장한 원본 파일의 삭제를 시도합니다. 저장 전체가 하나의 원자적 작업은 아니며 원본과 합성 프레임의 시각도 같다고 보장하지 않습니다. DNG에는 PIP를 합성하지 않습니다. CameraX는 RAW를 제공하지 않으므로 합성 JPEG만 저장합니다.

DeviceCompositor는 GL 그리기와 픽셀 읽기를 담당합니다. CompositorMedia는 별도 직렬 작업 스레드에서 픽셀 변환·JPEG 압축과 저장 콜백을 실행하며, 종료 전에 접수된 작업을 모두 마칩니다. CompositorRecording은 녹화기 설정·해제를 담당합니다. EGL 인코더 Surface를 분리한 뒤 녹화 종료와 파일 저장을 미디어 스레드로 넘깁니다.

MediaLibrary는 MediaTransaction을 통해 파일 생성 이후의 쓰기·공개·실패 시 삭제를 공통으로 처리합니다. 일반 사진·DNG·메타데이터는 한 묶음으로 공개하며, Multi는 카메라별 단일 트랜잭션으로 부분 성공을 유지합니다. 그룹 보고서 저장이 실패하면 해당 그룹에서 저장한 파일들의 삭제를 시도합니다.
