cts/onoff는 custom#FastOnOff입니다. FastOnOffRunner가 카메라마다 표준 패스(열기 → 프리뷰 세션 → 첫 프레임 → 닫기)와 빠른 패스(onOpened 직후 세션 없이 닫기 → 다시 열어 표준 패스)를 5회씩 번갈아 수행하고, FastOnOffRules가 첫 결과의 SENSOR_TIMESTAMP 존재·양수, 프레임 번호, 타임스탬프 소스가 REALTIME이면 열기~완료 창 안인지, 닫기 시간 준수를 판정합니다. 마지막 compare 행은 두 방식의 첫 프레임 중앙값과 차이를 적고, 한쪽이 한 번도 첫 프레임에 이르지 못했을 때만 FAIL입니다.

cts/switching은 custom#Switching입니다. SwitchingRunner가 runCameras를 재정의해 컬러 출력과 프리뷰 크기가 있는 카메라를 차례로 5회 순회하며 공유 패스를 수행하고, 끝에 카메라마다 가장 큰 CamcorderProfile로 3초 녹화합니다. SwitchingRules가 순회 순서·round_n 단계 id·녹화 프로파일 선택·건너뛰기 사유(숫자 ID 아님, 프로파일 없음)·축소한 validateRecording(파일·트랙·크기·길이 오차 20 %)을 담습니다.

cts/sizes는 custom#AllSizeOnOff입니다. AllSizeOnOffRules가 SurfaceHolder 출력 크기 전부를 큰 것부터 한 번씩 계획하고(1080p 상한 없음), AllSizeOnOffRunner가 크기마다 공유 패스를 수행해 크기 문자열을 단계 id로 씁니다.
