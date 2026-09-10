Event에는 앱 관측 시각, 세션, 종류, 선택적 프레임 번호·센서 시각과 값이 담깁니다. Telemetry는 원시 메타데이터와 함께 FrameTracker에서 계산한 intervalMs·resultFps·observedResultGap도 기록하므로 이벤트의 모든 값이 원시값인 것은 아닙니다.

프레임 번호는 started/result/request 연결에, 센서 타임스탬프는 이미지 연결에 사용합니다. request_observed는 onCaptureStarted에서 관측한 요청이며 제출 시각은 러너의 mark와 별개입니다. 설계 의도는 사람의 결정 기록을 근거로 작성합니다.
