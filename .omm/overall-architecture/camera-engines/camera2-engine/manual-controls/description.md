ManualControls는 Live 수동 노출·초점·WB의 순수 값 모델입니다. ISO와 노출 시간은 한 쌍이며 초점과 WB는 독립적으로 선택합니다. ManualSupport는 직접 입력을 거부할 조건과 모드 전환 시 지원 범위로 정규화하는 규칙을 분리합니다. 수동 WB는 수동 노출과 유효한 4개 gains·9개 matrix 값을 요구합니다.

ManualControlRequests는 CameraCharacteristics의 capability와 요청 키·범위를 확인하고 수동 값을 CaptureRequest에 적용합니다. 수동 노출은 AE OFF와 ISO·노출·frame duration, 수동 초점은 AF OFF와 diopter, 수동 WB는 AWB OFF와 gains·transform을 사용합니다. 프리뷰·사진·녹화·녹화 중 사진의 공통 경로이며 Benchmark에서는 호출하지 않습니다.
