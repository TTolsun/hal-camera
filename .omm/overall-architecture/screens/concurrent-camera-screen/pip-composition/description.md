PIP는 메인 화면 위에 선택한 보조 카메라 하나를 합성합니다. Android 11 이상에서 PhysicalPipPicker는 부모 장치의 Physical ID와 현재 ID를 제외한 CameraManager의 Service ID를 구분하여 보여 줍니다. 전면 카메라도 포함하며 선택은 하나만 허용합니다. Physical 후보에는 Logical capability가 필요하지만 Service 후보는 부모의 Logical capability와 무관합니다. 목록에 표시되더라도 실제 동시 세션 구성이 거부될 수 있습니다. Camera2 Live는 LivePipController로 현재 화면에 합성 TextureView를 표시하며 다른 Activity로 이동하지 않습니다.

Camera2Engine.setPip는 현재 CameraDevice를 그대로 유지하며 캡처 세션의 출력만 변경합니다. LivePipSession은 별도 Service 장치만 추가 open하고 Physical은 부모 장치의 OutputConfiguration에 연결합니다. Off와 보조 카메라 교체는 메인 출력을 복원한 후 보조 장치·합성 Surface를 해제합니다. CameraX에서는 명시적으로 Camera2로 전환하므로 기존 CameraX 장치 유지는 지원하지 않습니다.

DeviceCompositor는 동일한 GPU draw로 프리뷰, 별도 pbuffer의 JPEG, MediaRecorder 영상을 구성합니다. TextureView가 native buffer 크기를 변경할 수 있어 EGL 대상 크기로 viewport를 설정합니다. producer 변환 행렬을 적용하고 센서 회전을 중복하지 않습니다. PIP JPEG는 화면용 합성 해상도이며 센서 최대 해상도가 아닙니다. Live는 오디오를 포함할 수 있고 Multi는 무음이며, 인코더 PTS는 MediaRecorder 오디오와 맞는 System.nanoTime 시계를 사용합니다. 센서 timestamp나 측정용 elapsedRealtimeNanos와 비교하지 않습니다.

PipRouting은 Physical 입력을 부모 장치로, Service 입력을 독립 장치로 연결하며 동일 Service ID는 한 번만 엽니다. Multi에서 PIP를 바꾸면 전체 동시 세션을 닫고 다시 구성합니다. Multi는 패널별 합성 파일을 저장하고 전체 분할 화면을 하나로 저장하지 않습니다. PipScene 좌표와 드래그는 부모 영역을 벗어나지 않도록 제한합니다. Live에서도 기존 셔터·모드·줌·측정 정보를 유지하고 조작부는 저장 영상에 포함하지 않습니다.
PIP 선택창은 Single과 Multi가 공유하는 LiveChoiceSheet이며 화면 위에서 내려옵니다. Live의 카메라 선택창도 같은 스타일을 쓰지만 아래에서 올라옵니다. 제목은 20sp, 항목은 14sp, 보조 줄은 10sp이며 알파 120의 배경을 사용합니다. 카메라 방향과 렌즈 이름을 주 표시로, Physical·Service 종류와 ID를 보조 줄로 표시합니다. 선택된 항목은 테두리와 체크를 사용합니다. 같은 항목을 다시 누르면 장치를 재구성하지 않으며 닫기와 바깥 터치는 기존 선택을 유지합니다. PIP 선택창은 위쪽 컨트롤만 숨기고 아래쪽은 흐리게 유지합니다. 카메라 선택창은 반대로 처리하며 모달 창이 열린 동안 배경 버튼은 조작할 수 없습니다. 닫으면 원래 상태를 복원합니다. Camera2 전환 확인, Manual 입력과 Multi 상세창도 같은 스타일을 사용합니다.

Single의 Service PIP는 장치를 열기 전에 메인·추가 프리뷰의 동시 세션 지원 여부를 검사합니다. Multi의 Service PIP도 현재 기본 장치와 추가 Service ID의 중복을 제거한 개수를 최대 장치 수 설정과 비교합니다.

Single PIP 사진은 합성 JPEG 한 장만 저장하며 일반 사진의 YUV·JPEG 쌍과 RAW·메타데이터 JSON은 만들지 않습니다. PIP 녹화 중 사진은 지원하지 않습니다. PIP 합성 사진은 프리뷰를 저장하므로 일반 사진용 Flash Auto·On과 AEB를 사용하지 않으며 Off·Torch를 제공합니다. 같은 카메라의 Live로 복귀하면 선택한 PIP를 복원합니다.
