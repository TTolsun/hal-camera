MainActivity는 LIVE 런처입니다. CameraEngine을 선택하고 카메라 수명주기·권한·incident 내보내기·CPU 샘플링과 관측 수치를 관리합니다. BenchmarkActivity로 벤치마크를 시작합니다. v0.2 HealthMonitor와 소비자용 Home 진입 경로는 제거되었습니다.

엔진과 카메라 선택, 일시정지·재개, 측정 상세를 상단에서 조작합니다. MARK · ZIP 저장과 벤치마크를 주 작업으로 표시하고 사진 촬영·동영상 녹화·갤러리는 보조 행에 둡니다. CameraX에서 촬영·녹화를 요청하면 Camera2로 전환한 뒤 작업합니다. GalleryActivity는 HALCamera 앨범만 조회합니다.
