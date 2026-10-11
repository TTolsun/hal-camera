Camera2Engine은 카메라 스레드에서 요청·세션을 관리하고 UI는 메인 스레드로 전달합니다. 기본 크기는 픽셀 예산으로 고르며 명시 크기는 대체하지 않습니다. LiveStreamSupport가 개별 지원, LiveSessionCheck가 출력 조합을 검사하며 조회 불가는 unknown입니다. PHOTO는 Preview FPS와 최소 프레임 시간을 검사하고 VIDEO는 Recording FPS를 사용합니다. PIP 녹화의 카메라 FPS는 템플릿의 Auto이며 합성 인코더만 30fps입니다. 비활성 녹화 값은 PHOTO를 거부하지 않습니다. 실제 FPS는 capture_result에 남으며 Benchmark StreamSpec과 Live 설정은 함께 전달할 수 없습니다.

사진 촬영과 플래시 precapture는 Camera2StillCapture(하위 요소 still-capture)가, Live 녹화는 Camera2LiveRecorder(하위 요소 live-recorder)가 맡습니다. 엔진은 두 클래스에 Host로 카메라·세션과 request 생성을 넘겨주고, 세션 구성·request 조립·Live 제어·줌·close를 직접 처리합니다. 프리뷰 버퍼를 늘어나지 않게 화면에 채우는 변환 행렬은 PreviewTransform.kt의 fitPreview가 만듭니다.

녹화 중 줌은 같은 target의 repeating request를 교체합니다. 연속 입력은 카메라 스레드에서 마지막 배율로 합칩니다. 세션 구성·종료 중에는 요청을 보류하고 다음 세션에 현재 배율을 적용합니다. 닫힌 세션의 요청은 request_skipped로 기록합니다.

Live 제어는 Camera2TouchFocus와 AeRelock이 담당합니다. 엔진은 모든 요청에 현재 제어를 적용하며 녹화 시작 시 같은 세션을 유지합니다. 터치 측광·수렴 대기·수동 제어의 순서는 live-controls/touch-focus 요소에서 설명합니다.

Live 첫 열기의 해제 대기(#230)와 첫 프레임 전 실패 재시도(#224)는 open-recovery 요소에 있습니다.

close는 active를 해제하고 세션과 기기를 닫습니다. finishClose에서 사진 대기와 녹화 정리, reader와 Surface 해제, closed 이벤트, 완료 콜백, 실행기 종료를 처리합니다. API 30 이상은 CONTROL_ZOOM_RATIO를, 이전 버전은 crop 영역을 사용합니다.
콜백 시간축용으로 capture_partial과 preview_presented를 관측하며, callback_streams 이벤트와 세션의 callbackStreams에 현재 출력 구성 및 관측 가능 여부를 기록합니다. PIP VIDEO는 공유 앱 인코더를 Recording (App)·관측 불가로 등록하고 HAL 요청 target에는 넣지 않습니다. 기존 capture_result·image_available의 의미는 유지합니다.

프리뷰 버퍼 관측과 표시 경로는 preview-output 요소에 있습니다.
