CameraXLiveRecorder는 CameraXEngine의 Live 동영상 녹화를 맡으며, Camera2LiveRecorder에 대응합니다. CameraX Recorder가 캐시 폴더의 임시 MP4에 기록하고, 녹화가 끝나면 MediaLibrary.saveVideo로 DCIM/HALCamera에 공개한 뒤 임시 파일을 지웁니다.

Camera2처럼 프리뷰와 인코더 두 스트림만으로 녹화합니다. 녹화를 시작하면 ImageAnalysis와 ImageCapture를 unbind하고 VideoCapture를 bind하므로 세션이 새로 만들어집니다. 녹화가 끝나면 반대로 되돌립니다. 네 use case를 한꺼번에 bind하면 스트림 조합을 CameraX의 stream sharing에 맡기게 되는데, Camera2 경로에는 그런 단계가 없어서 두 엔진의 조건이 달라지기 때문입니다. 녹화 중에는 사진 촬영을 거절합니다.

요청 조건은 Recorder가 허용하는 범위에서 Camera2와 맞춥니다. 해상도는 FHD를 기준으로 하고 없으면 더 낮은 품질을 고르므로 1920×1080 이하의 가장 큰 크기가 됩니다. 30fps, 10Mbps를 요청하고, 방향 정보는 녹화 시작 시점의 화면 회전을 씁니다. 코덱과 오디오 형식은 기기의 encoder profile을 따르므로, H.264와 44.1kHz AAC로 고정한 Camera2와 다를 수 있습니다. 오디오를 요청했는데 RECORD_AUDIO 권한이 없으면 소리 없이 녹화하지 않고 실패로 처리합니다.

VideoRecordEvent.Start가 오면 REC 상태를 알리고, Finalize가 오면 결과를 판정합니다. 첫 Status 이벤트가 오면 AF 잠금과 길게 누른 AE 지점을 한 번 더 보냅니다. CameraX는 bind가 끝나고 Start가 온 뒤에도 동영상 surface가 실제로 켜질 때 repeating 요청을 다시 구성하는데, 그 전에 보낸 FocusMeteringAction은 사라지기 때문입니다. Galaxy S25+에서는 이 재전송이 없으면 AF 잠금이 켜져 있어도 녹화 내내 AF 상태가 Idle이었습니다. ERROR_NONE과, 카메라가 닫혀서 멈춘 ERROR_SOURCE_INACTIVE는 재생 가능한 파일로 보고 저장합니다. 그 밖의 오류나 빈 파일은 "녹화가 너무 짧거나 실패하여 동영상을 저장하지 못했습니다"로 알립니다. 카메라를 닫을 때 녹화 중이면 녹화를 멈추고, Finalize 이벤트가 평소처럼 저장을 수행한 뒤 엔진에 실행기를 종료해도 된다고 알립니다.
