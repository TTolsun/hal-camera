ConcurrentCameraActivity는 Live의 Multi · P와 Multi · V에서 여는 독립 장치 촬영 화면입니다. Photo·Video와 같은 상단 제어, 크기 표시, 측정값·줌, 갤러리·64dp 셔터·카메라 선택, 네 모드와 Save Events · ZIP을 사용합니다. Photo·Video의 PIP 위치에는 Split 버튼을 배치하며 Manual 버튼과 입력창은 제공하지 않습니다. Multi는 기존 Camera2 경로를 사용합니다.

Split 설정창은 LiveChoiceSheet.Edge.TOP으로 위에서 내려옵니다. 한 창에서 위아래·좌우·격자 배치, 촬영할 카메라 수, 독립 CameraDevice 조합과 순서를 정합니다. Apply 전까지는 초안이며 닫기와 뒤로 가기는 변경을 버립니다. 카메라 수는 기기가 광고한 동시 조합의 크기에서 선택합니다. 기기가 광고한 동시 장치 수와 조합을 선택하며 광고되지 않은 조합은 비활성화합니다. 실제 세션 지원 검사도 적용 전에 수행합니다. 배치만 바꾸면 세션을 유지하며 장치·순서·촬영 모드를 바꾸면 close(done) 뒤 재구성합니다.

장치 이름을 누르면 해당 장치의 줌·AF·AE·EV 제어, 측정값과 Callback을 표시합니다. 같은 세션 안에서 장치를 전환해도 각 장치의 요청값을 보존합니다. Multi는 Manual과 AEB를 제공하지 않으며 플래시는 Off·Torch를 사용합니다. ConcurrentSession은 ManualControls와 bracket을 제거한 요청을 적용합니다. Telemetry는 장치별 세션 키에 카메라 결과와 프리뷰·JPEG 이벤트를 기록하며 이벤트 ZIP에는 이미지 픽셀을 넣지 않습니다.

ConcurrentLifecycle은 장치별 open·streaming·closing·closed 상태를 관리합니다. 모든 장치를 연 뒤 세션을 구성하고 모든 출력의 실제 프레임 도착 이후 셔터를 활성화합니다. 출력 구성이 거부되면 실패와 Retry를 표시합니다. 종료 중 늦게 도착하는 open 콜백과 모든 onClosed를 기다린 뒤 compositor를 해제하고 ConcurrentLease를 넘깁니다. Live는 자신의 close(done) 이후 이 화면을 열고 돌아오면 원래 엔진의 프리뷰를 복구합니다. Benchmark 경로와 프로파일은 바꾸지 않습니다.

사진은 각 장치의 Image.timestamp와 CaptureResult.SENSOR_TIMESTAMP를 연결한 온전한 JPEG를 저장합니다. Multi는 Physical·Service PIP를 합성하지 않습니다. ConcurrentPhotoStore는 공통 group ID, 장치 ID, 크기, 시각 기준과 성공·실패를 JSON에 기록합니다. 사진 제한 시간은 10초이며 만료 시 미완료 장치를 실패로 기록하고 모두 닫습니다. 부분 실패는 성공한 파일을 유지하며 JSON 저장 실패는 해당 사진 묶음을 롤백합니다.

영상은 장치별 독립 무음 MP4를 저장합니다. 인코더 크기는 해당 장치의 프리뷰 스트림과 센서 회전에서 정하며 Split 패널 크기와 무관합니다. preserveFullFrame은 EGL 대상 크기에 전체 입력을 맞추어 표시하며 화면 배치를 영상 크롭으로 사용하지 않습니다. 화면 전체·다른 장치·UI를 저장 영상에 합성하지 않습니다. 정상 종료 시 녹화를 저장한 뒤 해제하며 장치·인코더 오류 시 진행 중 녹화를 취소합니다. 사진·영상의 센서 동기는 보장하지 않습니다.
