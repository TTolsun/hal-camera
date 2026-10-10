PIP는 하나의 Logical CameraDevice에서 메인 논리 출력과 선택한 Physical 출력을 함께 구성합니다. PhysicalPipPicker는 LOGICAL_MULTI_CAMERA capability와 그 장치의 physicalCameraIds를 조회합니다. Single의 API 선택 옆 PIP와 Multi의 각 장치 PIP는 해당 장치에 속한 ID만 선택합니다. CameraX에서 진입하면 Camera2 전환을 명시적으로 확인합니다.

DeviceCompositor는 장치마다 EGL 문맥과 OES 입력 텍스처를 갖습니다. 동일한 draw 경로로 화면 Surface, JPEG readback, MediaRecorder 인코더에 Logical 메인과 Physical 보조 영상을 그립니다. producer의 SurfaceTexture 변환 행렬을 적용하고 별도 센서 회전을 중복 적용하지 않습니다. 프리뷰에는 저장 장면 전체가 들어가도록 fit-center 변환을 씁니다. PIP JPEG는 합성 프리뷰 해상도이며 센서 최대 해상도 사진이 아닙니다.

PipScene의 좌표는 부모 Logical 장면을 기준으로 정규화합니다. 드래그와 Move 버튼은 보조 영상의 전체 사각형을 0~1 경계 안으로 제한합니다. 다른 Logical 화면으로 옮길 수 없습니다. 녹화 중 위치 변경도 같은 렌더러에 전달됩니다. 화면의 버튼·카메라 ID 등 조작부는 저장 영상에 넣지 않습니다.

각 Logical 장치에 별도 JPEG·MP4를 저장합니다. PIP를 켠 장치의 파일에는 그 장치 안의 합성 장면만 포함합니다. 녹화는 무음 H.264 MP4이고 앱 단조 시계로 인코더 타임스탬프를 생성하며 출력 제출을 최대 30fps로 제한합니다. 실제 프레임율·센서 동기는 보장하지 않습니다. CameraDevice가 종료된 뒤 compositor 입력 Surface를 해제하고 세션 lease를 넘깁니다.
