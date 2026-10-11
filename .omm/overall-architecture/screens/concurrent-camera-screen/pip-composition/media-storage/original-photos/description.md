PIP 사진은 합성 JPEG와 선택된 두 카메라의 원본을 구분합니다. JPEG가 켜져 있고 RAW가 꺼져 있으면 JPEG 이미지 세 장을 저장합니다. JPEG Off에서도 프리뷰 합성 사진 한 장은 저장합니다. JPEG 소스가 YUV이면 각 카메라의 YUV 원본을 JPEG로 변환하며, Native JPEG를 선택하면 실제 촬영 JPEG 버퍼를 저장합니다. 원본 JSON과 선택한 메인 RAW/DNG는 별도 파일로 유지합니다.

PipPhotoReader는 보조 Camera2의 ImageReader를 소유합니다. JPEG 또는 YUV 버퍼를 해당 촬영 결과의 센서 timestamp에 맞추고 저장 전에 Image를 닫습니다. Physical 원본은 부모 장치에 보조 출력만 대상으로 별도 요청하여 해당 센서의 JPEG 방향을 적용합니다. 메인 요청과 보조 요청의 대상을 분리하며 Physical CaptureResult를 사용하고 다른 센서의 노출값을 빌려 오지 않습니다. 지연·실패·버퍼 손실과 종료를 실패 결과로 전달하고 시간 제한을 둡니다.

PipCameraXPhoto는 보조 ImageCapture 또는 ImageAnalysis를 사용하며 일반 CameraXStillCapture의 저장 경로를 재사용합니다. 두 엔진의 원본 묶음은 capturePipBatch로 모든 결과를 기다린 뒤 완료합니다. 일부 원본만 성공하면 새로 저장된 원본의 삭제를 시도합니다. 합성 실패 시에도 이번 원본 파일의 삭제를 시도합니다. 저장 전체가 하나의 원자적 작업은 아닙니다.

합성 프리뷰와 두 센서 촬영은 같은 시각을 보장하지 않습니다. 원본마다 센서 timestamp와 결과 메타데이터를 기록하며 합성 완료는 별도 앱 이벤트로 남깁니다. 합성 완료를 HAL JPEG 요청이나 카메라 Callback 행으로 등록하지 않습니다.

PipServiceTelemetry는 보조 Service의 프레임 번호·센서 시각·요청 대상을 독립된 세션에 기록합니다. Callback 화면은 Service 세션을 카메라 ID로 전환하며 시간축을 섞지 않습니다. Physical 원본은 부모의 출력 설명자와 Physical timestamp로 연결합니다.
