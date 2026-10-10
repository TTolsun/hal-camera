Camera2Engine은 전용 HandlerThread에서 Camera2 요청과 세션을 관리하고 UI 상태는 메인 스레드로 전달합니다. Live 기본값은 preview·YUV·JPEG 크기를 픽셀 예산에 맞춰 선택합니다. LiveStreamSettings를 전달하면 정확한 preview 크기, 선택한 YUV·JPEG 출력과 FPS 범위를 사용하며 자동 크기 대체는 하지 않습니다. LiveStreamSupport가 개별 지원을 검사하고 LiveSessionCheck는 Android 10 이상에서 출력 조합을 조회합니다. 조회 미지원은 unknown으로 기록하고 실제 세션 구성으로 확인합니다. 최소 프레임 시간이 FPS 하한을 초과하면 거부하며 결과 FPS 범위는 capture_result에 남습니다. BenchmarkActivity의 StreamSpec과 Live 설정은 동시에 전달할 수 없습니다.

사진 촬영과 플래시 precapture는 Camera2StillCapture(하위 요소 still-capture)가, Live 녹화는 Camera2LiveRecorder(하위 요소 live-recorder)가 맡습니다. 엔진은 두 클래스에 Host로 카메라·세션과 request 생성을 넘겨주고, 세션 구성·request 조립·Live 제어·줌·close를 직접 처리합니다. 프리뷰 버퍼를 늘어나지 않게 화면에 채우는 변환 행렬은 PreviewTransform.kt의 fitPreview가 만듭니다.

녹화 중에도 줌은 받습니다. 줌을 바꾸면 녹화 request를 같은 preview·인코더 target으로 다시 만들어 repeating request만 교체합니다. 줌 입력은 카메라 스레드에 한 번만 예약하므로, 빠르게 연속으로 조작하면 실행 시점의 마지막 배율 하나로 합쳐집니다. 녹화 세션이 구성 중이거나 종료 중이면 요청을 보내지 않고, 다음 세션이 구성될 때 현재 배율을 읽어 적용합니다. 정지와 겹쳐 닫힌 세션에 보낸 요청은 카메라 오류가 아니라 request_skipped 이벤트로 기록합니다.

프리뷰 터치는 Camera2TouchFocus가 순서를 관리하고 엔진이 모든 LIVE 요청에 영역을 적용합니다. AF 잠금은 단발 START/CANCEL trigger를 사용합니다. 줌·제어 변경은 카메라 스레드에서 최신 입력으로 합칩니다. 새 세션의 AE 잠금은 AeRelock이 CONVERGED·FLASH_REQUIRED 결과 2개 또는 2초 제한을 기다린 뒤 적용합니다. 결과 키가 없는 기기는 대기를 생략하고, 요청 실패 시 수렴 대기를 이어갑니다. 재잠금 노출 차이가 1/3 EV를 넘으면 알립니다. 수동 노출에서는 AE 잠금·EV를 해제합니다. VIDEO 녹화 시작은 같은 세션을 유지하므로 AE·AF를 다시 잠그지 않습니다. 모드 전환·정지 후 세션 재구성에서는 현재 제어를 복원합니다. 터치 측광의 세부 순서는 live-controls/touch-focus 요소에 있습니다.

Live 첫 열기의 해제 대기(#230)와 첫 프레임 전 실패 재시도(#224)는 open-recovery 요소에 있습니다.

close는 active를 해제하고 세션과 기기를 닫습니다. finishClose에서 사진 대기와 녹화 정리, reader와 Surface 해제, closed 이벤트, 완료 콜백, 실행기 종료를 처리합니다. API 30 이상은 CONTROL_ZOOM_RATIO를, 이전 버전은 crop 영역을 사용합니다.
콜백 시간축용으로 capture_partial과 preview_presented를 관측하며, callback_streams 이벤트와 세션의 callbackStreams에 현재 출력 구성 및 관측 가능 여부를 기록합니다. 기존 capture_result·image_available의 의미는 유지합니다.

프리뷰 버퍼 관측과 표시 경로는 preview-output 요소에 있습니다.
