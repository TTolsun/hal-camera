PreviewTransform은 TextureView의 producer 회전을 중복하지 않으며 화면을 비율 유지·중앙 크롭으로 채웁니다. 가로 화면에서는 화면 회전 값을 함께 적용합니다.

PreviewBufferRelay는 API 33 이상 Live의 PRIVATE 버퍼를 ImageReader로 관측하고 ImageWriter로 TextureView에 전달합니다. 픽셀 CPU 복사 없이 preview_available을 기록하며, CameraDevice 종료 후 리더·라이터를 해제합니다. PIP 중에는 이 원래 표시 경로를 보관하고 같은 CameraDevice의 출력을 합성 입력으로 전환합니다. PIP Off 후 원래 출력을 다시 사용합니다.
