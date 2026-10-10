ConcurrentCameraActivity는 기존 Dual을 대체하는 Multi와 Single PIP의 공통 화면입니다. Multi는 2개 이상의 CameraDevice를 각각 열고 기본 세로 분할로 표시합니다. Live의 Multi · P는 사진 촬영, Multi · V는 영상 녹화로 진입하며 선택한 모드의 촬영 버튼만 표시합니다. 모드는 Intent extra로 전달되어 Activity 재생성에도 유지됩니다. Single PIP는 지정한 Logical 장치 하나만 엽니다. 각 Logical 장치의 PIP 버튼에서 선택한 Physical 출력은 해당 장치의 메인 프리뷰 위에 합성되며 그 화면 밖으로 이동할 수 없습니다. 비논리 장치의 PIP 버튼은 비활성화합니다.

Live Streams의 최대 장치 수는 기본 All available이며 Apply에서 저장합니다. 광고된 동시 조합의 부분집합 중 상한 이내의 모든 조합을 만들고 큰 조합부터 제시합니다. 독립 ID 쌍은 미광고 조합도 사전 검사 결과를 확인할 수 있습니다. 모든 장치를 연 뒤에 세션을 구성하며, 선택한 각 Physical 입력까지 실제 프레임이 도착해야 촬영·녹화를 활성화합니다. 출력 구성이 거부되면 실패를 표시하며 Physical 선택을 자동 대체하지 않습니다.

ConcurrentLifecycle은 장치별 open·streaming·closing·closed 상태를 관리합니다. 종료 중 늦게 도착하는 open 콜백과 모든 onClosed를 기다린 뒤 compositor를 해제하고 ConcurrentLease를 넘깁니다. Live는 자신의 close(done) 이후 이 화면을 열고, 돌아오면 원래 엔진의 프리뷰를 복구합니다. Benchmark 경로와 프로파일은 바꾸지 않습니다.

사진은 장치마다 저장합니다. PIP를 끈 장치는 Image.timestamp와 CaptureResult.SENSOR_TIMESTAMP가 같은 JPEG를 저장합니다. PIP를 켠 장치는 DeviceCompositor가 화면과 같은 장면을 JPEG로 읽어 저장합니다. 이 파일의 센서 촬영 시각은 null이며 sourceSurfaceTimestampsNs에 입력 시각을 구분합니다. ConcurrentPhotoStore는 공통 group ID, 장치 ID, Physical ID, 합성 여부·크기, 시각 기준과 성공·실패를 JSON에 기록합니다. 사진 제한 시간은 10초이며 만료 시 미완료 장치를 실패로 기록하고 모두 닫습니다.

Record와 Stop은 장치마다 독립 MP4를 생성합니다. PIP가 켜져 있으면 해당 장치 안의 합성 장면이 저장되고 Multi 화면 전체를 하나로 합치지 않습니다. 화면 종료 시 정상 녹화는 저장을 마친 뒤 해제하며, 장치·인코더 오류 시 진행 중 녹화를 취소합니다. 사진의 부분 실패는 성공한 파일을 지우지 않으며 JSON 저장 실패는 해당 사진 묶음을 롤백합니다. 사진·영상의 센서 동기는 보장하지 않습니다.