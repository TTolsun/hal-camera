camera/는 CameraEngine 인터페이스와 Camera2Engine·CameraXEngine을 제공합니다. Camera2Engine은 기본 크기 선택 경로와 벤치마크용 명시적 StreamSpec 경로를 구분합니다. 세션별 콜백과 close(done) 완료 통지가 러너에 연결됩니다.
StreamConfiguration은 실제 카메라 target과 Callback 메타데이터를 함께 관리합니다. appJpeg와 합성 녹화는 앱 출력 설명자만 추가하며 HAL target을 만들지 않습니다. 합성 Recording은 `Recording (App)`·관측 불가로 표시하고 Metadata 수치로 녹화 버퍼 시각을 대신하지 않습니다.
