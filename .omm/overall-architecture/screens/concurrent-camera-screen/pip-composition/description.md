PIP는 메인 화면 위에 선택한 보조 카메라 하나를 합성합니다. Android 11 이상에서 PhysicalPipPicker는 부모 장치의 Physical ID와 현재 ID를 제외한 CameraManager의 Service ID를 구분하여 보여 줍니다. 전면 카메라도 포함하며 선택은 하나만 허용합니다. Physical 후보에는 Logical capability가 필요하지만 Service 후보는 부모의 Logical capability와 무관합니다. 목록에 표시되더라도 실제 동시 세션 구성이 거부될 수 있습니다. 두 엔진의 Live는 LivePipController로 현재 화면에 합성 TextureView를 표시하며 다른 Activity로 이동하지 않습니다.

Camera2Engine.setPip는 현재 CameraDevice를 그대로 유지하며 캡처 세션의 출력만 변경합니다. LivePipSession은 별도 Service 장치만 추가 open하고 Physical은 부모 장치의 OutputConfiguration에 연결합니다. Off와 보조 카메라 교체는 메인 출력을 복원한 후 보조 장치·합성 Surface를 해제합니다. CameraXPipSession은 CameraX가 광고한 Service ID 동시 조합만 두 Preview로 bind합니다. 메인 ID를 유지하지만 단일·동시 모드 전환에는 unbindAll과 재bind가 필요합니다. 두 SurfaceRequest 반환과 CameraState.CLOSED를 기다린 뒤 합성 입력을 해제합니다. 입력은 720p 이하에서 협상하며 Physical 후보는 제공하지 않습니다.

DeviceCompositor는 동일한 GPU draw로 프리뷰, 별도 pbuffer의 JPEG, MediaRecorder 영상을 구성합니다. TextureView가 native buffer 크기를 변경할 수 있어 EGL 대상 크기로 viewport를 설정합니다. producer 변환 행렬을 적용하고 센서 회전을 중복하지 않습니다. PIP JPEG는 화면용 합성 해상도이며 센서 최대 해상도가 아닙니다. Live는 오디오를 포함할 수 있고 Multi는 무음이며, 인코더 PTS는 MediaRecorder 오디오와 맞는 System.nanoTime 시계를 사용합니다. 센서 timestamp나 측정용 elapsedRealtimeNanos와 비교하지 않습니다.

PipRouting은 Physical 입력을 부모 장치로, Service 입력을 독립 장치로 연결하며 동일 Service ID는 한 번만 엽니다. Multi에서 PIP를 바꾸면 전체 동시 세션을 닫고 다시 구성합니다. Multi는 패널별 합성 파일을 저장하고 전체 분할 화면을 하나로 저장하지 않습니다. PipScene 좌표와 드래그는 부모 영역을 벗어나지 않도록 제한합니다. Live에서도 기존 셔터·모드·줌·측정 정보를 유지하고 조작부는 저장 영상에 포함하지 않습니다.
PIP 선택창은 Single과 Multi가 공유하는 LiveChoiceSheet이며 화면 위에서 내려옵니다. Live의 카메라 선택창도 같은 스타일을 쓰지만 아래에서 올라옵니다. 제목은 20sp, 항목은 14sp, 보조 줄은 10sp이며 알파 120의 배경을 사용합니다. Physical·Service 종류와 ID를 14sp 주 표시로, 카메라 방향과 렌즈 이름을 10sp 보조 줄로 표시합니다. PIP 선택 목록은 엔진·부모 Camera ID별로 마지막 스크롤 위치를 저장하여 다시 열 때 복원하며, 저장된 위치가 없으면 현재 선택 항목을 보여 줍니다. 선택된 항목은 테두리와 체크를 사용합니다. 같은 항목을 다시 누르면 장치를 재구성하지 않으며 닫기와 바깥 터치는 기존 선택을 유지합니다. Live와 Multi의 PIP 버튼은 체크 문자 대신 노란색 계열 글자색만으로 표시하며 다시 누르면 선택창을 엽니다. Live Camera 목록도 Service · ID를 주 표시로 사용합니다. Camera 목록과 PIP의 각 종류 안에서 ID를 숫자 오름차순으로 정렬하며 선택 인덱스도 정렬된 목록을 기준으로 전달합니다. PIP 선택창은 위쪽 컨트롤만 숨기고 아래쪽은 흐리게 유지합니다. 카메라 선택창은 반대로 처리하며 모달 창이 열린 동안 배경 버튼은 조작할 수 없습니다. 닫으면 원래 상태를 복원합니다. Camera2 전환 확인, Manual 입력과 Multi 상세창도 같은 스타일을 사용합니다.

Single의 Service PIP는 장치를 열기 전에 메인·추가 프리뷰의 동시 세션 지원 여부를 검사합니다. Multi의 Service PIP도 현재 기본 장치와 추가 Service ID의 중복을 제거한 개수를 최대 장치 수 설정과 비교합니다.

Single PIP는 합성 JPEG를 저장합니다. Camera2에서 RAW가 켜져 있으면 메인 카메라의 DNG와 해당 원본의 JSON을 먼저 저장한 뒤 합성 JPEG를 추가합니다. 합성 실패 시 이번 원본 파일의 삭제를 시도합니다. 원본과 합성 프리뷰는 같은 순간을 보장하지 않으며 DNG 자체에는 PIP를 합성하지 않습니다. RAW 출력도 동시 세션 지원 검사에 포함하며 거부되면 조합 실패를 표시합니다. PIP 녹화 중 사진은 지원하지 않습니다. PIP 합성 사진은 프리뷰를 저장하므로 일반 사진용 Flash Auto·On과 AEB를 사용하지 않으며 Off·Torch를 제공합니다. 같은 모드의 Live로 복귀하면 선택한 PIP를 복원합니다. 모드·엔진 전환과 Multi 진입에서는 PIP와 스트림 설정을 초기화합니다.

PipPositionStore는 Single과 Multi를 구분하여 부모 Camera ID별 정규화 좌표를 저장합니다. 같은 모드의 보조 카메라 선택, PIP Off·On과 화면 재생성 후에는 같은 부모의 위치를 복원합니다. Photo·Video·엔진 전환이나 Multi 신규 진입에서는 해당 범위의 위치를 지웁니다. 합성기는 첫 프레임부터 복원 좌표를 사용하고 프리뷰 영역 밖의 좌표는 경계로 제한합니다. 저장값이 유효하지 않으면 기본 좌표를 사용합니다.

두 Live 엔진의 사진 저장과 녹화 수명 주기는 PipMedia와 PipMediaAdapter를 공유합니다. 순수 상태 제어기는 촬영·녹화 시작·녹화 종료가 완료될 때까지 close를 기다리며, Android 어댑터가 합성기와 MediaLibrary를 연결합니다. 장치와 Surface의 해제는 각 세션이 담당합니다. PipOutputs가 정한 출력 설명자를 실제 세션 구성과 Callback 메타데이터에서 함께 사용합니다. 합성 JPEG는 카메라 요청 대상과 분리하여 관측 출력으로만 추가합니다.

Live 화면은 PipCamera.pipSources로 현재 엔진의 지원 후보를 조회합니다. Camera2와 CameraX의 후보 탐색은 각 엔진에 남기며 화면에서 구체 엔진으로 캐스팅하지 않습니다.
