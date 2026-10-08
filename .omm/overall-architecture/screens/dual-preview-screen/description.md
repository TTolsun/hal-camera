DualPreviewActivity는 Lab의 Inspection 그룹에서 여는 Camera2 전용 화면으로, 한 논리 멀티 카메라의 후면 물리 카메라 2개를 나란히 보여 줍니다(#171). Live는 Lab을 열기 전에 카메라를 닫으므로 이 화면이 보이는 동안 논리 카메라를 소유하고, onStop에서 닫습니다. Live로 돌아가면 Live가 자기 프리뷰를 다시 엽니다. CameraX에서는 지원하지 않으며 화면에 그렇게 표시합니다. Benchmark profile과 측정 계약은 사용하지 않습니다.

DualPreviewPlanner는 Android 의존성이 없는 선택 규칙입니다. LOGICAL_MULTI_CAMERA 능력과 물리 카메라 2개 이상을 가진 후면 논리 카메라만 후보로 삼고, 기본 조합은 Wide+Tele, 다음은 Wide+UWide, 둘 다 없으면 처음 두 물리 카메라입니다. 크기는 두 물리 카메라의 SurfaceTexture 출력 크기 중 공통이며 1080p 이하인 것만 쓰고 4:3, 16:9, 나머지 순서로 큰 것부터 최대 4개를 후보로 둡니다. API 28 미만, 논리 카메라 아님, 물리 카메라 부족, 같은 렌즈 두 번, 공통 크기 없음은 사유를 표시하고 시작하지 않습니다.

DualPreviewSession은 공개 논리 ID만 열고 각 출력을 OutputConfiguration.setPhysicalCameraId로 물리 카메라에 연결합니다. 물리 ID를 직접 열 수 있다고 가정하지 않습니다. API 29 이상에서는 isSessionConfigurationSupported로 먼저 묻고, 거부되거나 구성이 실패하면 다음 크기 후보로 넘어가며 모두 실패하면 시도한 크기를 표시합니다. 열기 직후의 사용 중·연결 끊김은 CameraOpenRetry로 다시 엽니다. 조합을 바꾸거나 화면을 떠나면 장치 전체를 닫은 뒤 다시 열어, 이전 조합의 버퍼가 다음 조합의 화면에 섞이지 않게 합니다.

각 출력 아래에는 물리 ID와 렌즈, 실제 스트림 크기, 화면에 그려진 프레임 수와 평균 FPS, 그 물리 ID의 결과 메타데이터 수와 누락 수, 마지막 SENSOR_TIMESTAMP를 표시합니다. 프레임 수와 메타데이터 수는 따로 셉니다. 두 물리 결과가 모두 있을 때만 A−B 타임스탬프 차이를 표시하며, LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE(APPROXIMATE·CALIBRATED·미보고)을 함께 보여 주되 동기화가 보장된다고 가정하지 않습니다. 보고서 복사는 이 값을 텍스트로 클립보드에 넣습니다. 물리 카메라별 영상·사진 저장은 이 화면에 없습니다.
