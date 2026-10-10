상단의 `Callback`는 프리뷰 위에 한 프레임의 콜백 도착 시각을 표시합니다. Start와 Metadata를 기본 행으로 두고 구성된 출력 스트림을 추가합니다. Partial은 표시하지 않습니다. 이전 프레임의 onCaptureStarted 수신 시각을 모든 행의 공통 기준으로 사용합니다. 첫 Start는 0ms이며 이후 Start는 직전 Start와의 간격입니다. Metadata와 출력도 같은 기준에서 도착 위치를 점으로 그립니다. 시간축은 표시된 값에 맞춰 자동으로 확장하며 작은 범위가 3초간 유지되면 축소합니다. 자동 고정 중에는 늦은 결과에 필요한 확장만 허용합니다. 시간축 범위 설정은 없습니다. 콜백 없음·요청 대상 아님·시작 시각 없음은 구분합니다. 그래프를 켜면 FPS·EXP·AE·AF의 실시간 정보를 숨기며, 그래프를 닫으면 다시 표시합니다. 촬영 조작부는 유지합니다.

ResultCallbackSeries는 같은 세션과 프레임 번호의 capture_started를 capture_result와 연결합니다. 출력 버퍼와 프리뷰 표시는 센서 타임스탬프가 같은 시작 이벤트로 연결합니다. 센서 시각은 동등성 키로만 쓰고 지연은 앱의 elapsedRealtimeNanos끼리 계산합니다. 새 출력 구성의 시각 이전 표본은 제외합니다. 결과를 받지 못했거나 시작 표본이 없으면 0으로 만들지 않습니다.

Camera2의 Android 13 이상에서 PreviewBufferRelay는 PRIVATE 프리뷰 버퍼가 앱에 도착한 시각을 기록한 뒤 ImageWriter로 TextureView에 넘깁니다. 화면 갱신 시각과 구분하며 HAL 내부 완료 시각을 직접 측정하는 것은 아닙니다. Android 12 이하에서는 Preview 버퍼를 직접 관측하지 못합니다. YUV·JPEG는 ImageReader 수신을 관측합니다. Camera2의 Android 13 이상에서는 RecordingBufferRelay가 PRIVATE 녹화 버퍼를 수신한 시각을 Recording 행에 표시하고 ImageWriter로 MediaRecorder에 전달합니다. 버퍼 수신 시각이며 인코딩 완료나 파일 저장 시각은 아닙니다. Android 12 이하와 CameraX에서는 녹화 버퍼를 직접 관측하지 않으므로 콜백 없음으로 표시합니다. 일반 CameraX 프리뷰에서는 Preview 버퍼가 노출되지 않으며 YUV 분석과 still 이미지는 관측합니다. JPEG 행은 result 메타데이터가 아니라 JPEG 이미지 수신 시각입니다. Start는 onCaptureStarted, Metadata는 onCaptureCompleted의 실제 수신 시각입니다.

MainActivity는 그래프를 표시한 동안만 그래프 표본을 갱신합니다. 녹화 정지와 경과 시간은 원래 촬영 조작부에 남습니다. 카메라 선택은 하단 카메라 아이콘에서, 카메라 다시 연결과 ADB CLI·앱 정보는 Lab에서 실행합니다. ZIP 기록은 Lab에 있으며 Mark와 incident 수집은 유지합니다.


`Hold` 옆 시간은 `0s → 1s → 3s → 5s → 10s`로 순환하며 기본값은 0초입니다. 0초는 실시간 갱신이며, 나머지는 JPEG나 PIP 합성 사진이 도착한 프레임을 지정 시간 동안 고정합니다. 시간 변경은 현재 고정을 해제하고 다음 촬영부터 적용합니다. 모드·엔진 변경과 앱 재실행에도 선택한 시간은 유지합니다. 고정 중 같은 프레임의 늦은 콜백은 채우지만 다른 프레임을 섞지 않습니다. 새 촬영은 고정 시간을 다시 시작합니다. 그래프 재진입과 카메라·출력 재구성은 과거 촬영을 재생하지 않습니다. 평상시 수치는 100ms 주기로 최신 결과가 모인 프레임을 표시합니다. 특정 출력이 250ms 이상 도착하지 않으면 새 프레임으로 진행하며 해당 행은 수신 대기로 표시합니다.

Telemetry는 각 Shutter 이벤트에 직전 Shutter 수신 시각과 첫 프레임 여부를 기록합니다. 기록 창에서 이전 이벤트가 사라지거나 그래프를 나중에 열어도 기준을 유지합니다. 출력 구성을 바꾸면 첫 Start부터 다시 시작합니다. 기준이 없는 과거 표본은 0으로 만들지 않습니다. 축과 수치는 ms로 표시하며 실제 음수가 있다면 부호를 보존합니다.

행 목록은 StreamConfiguration이 세션 구성에 사용한 출력 목록에서 전달합니다. 버퍼는 출력 고유 ID와 센서 타임스탬프로 연결합니다. YUV가 여러 개여도 고유 ID로 값을 구분하며 표시 이름은 모두 YUV입니다. 표시 이름을 식별 키로 쓰지 않습니다.

LiveReadings는 프리뷰의 수치와 활성 설정과 앱의 CPU·PSS·Thermal 표본을 관리하며, ResultCallbackGraph는 콜백 그래프와 자동 고정만 관리합니다.

그래프는 상단의 가로 눈금과 0ms 기준선만 남기고, 도착점과 분리한 오른쪽 고정 열에 ms 값을 정렬합니다. 글자·눈금·도착점의 어두운 외곽선은 밝은 프리뷰에서도 대비를 유지합니다. 실제 자동 고정 중에만 프레임 번호 앞에 일시정지 표시를 그리며, 대기 중에도 그 자리의 폭을 유지합니다. 라벨은 시스템 글꼴, 숫자는 고정폭 글꼴이며 행 간격은 24sp입니다. 배경 박스와 불필요한 세로 격자는 두지 않습니다. 행 수가 달라질 때만 배치를 다시 요청하며, 표시 값과 축이 같으면 다시 그리지 않습니다. 하드웨어 가속을 사용하고 MainActivity의 100ms 주기마다 표본을 계산합니다. CPU·PSS·Thermal 표본의 시스템 조회는 I/O 작업 스레드에서 실행하여 프리뷰의 UI 스레드를 막지 않습니다.

LiveMeasurementView는 FPS·EXP·AE·AF를 각 항목의 최대 문구 폭을 예약한 네 칸에 왼쪽 정렬합니다. 칸 사이 간격은 8dp이며 전체 묶음은 화면 중앙에 배치합니다. 기본 글자 크기는 12sp이며 화면 폭과 가장 긴 상태 문구를 기준으로 한 번 맞춥니다. AE·AF 문구는 Search, Scan, Focus처럼 간결하게 표시합니다. AE·AF 상태가 바뀌어도 각 칸의 시작 위치·폭·글자 크기는 바뀌지 않습니다. 칸을 넘는 예외적인 값은 말줄임으로 처리하여 겹치지 않습니다. EXP는 관측 노출 시간이며 1초 미만은 ms, 그 이상은 s로 표시합니다. ISO와 EV는 측정 행에서 제외합니다. 아래에는 Flash, AE/AF Lock, AEB, 수동 모드·초점·WB 중 활성 설정만 표시하며 Manual 선택창과 원본 텔레메트리는 유지합니다.

PIP에서는 합성기에 입력된 각 SurfaceTexture의 프레임 수신을 관측합니다. 메인 Service ID의 TotalCaptureResult에 포함된 Physical 결과는 센서별 `Meta (Phy)` 행으로 표시하며 별도 HAL 콜백인 것처럼 취급하지 않습니다. Physical 프리뷰는 해당 센서 타임스탬프와 요청 프레임을 연결합니다. 별도 Service ID의 결과는 메인 Service ID 그래프에 섞지 않습니다. PIP의 `Jpeg` 행은 합성 이미지 생성 시각이며 HAL JPEG 수신과 구분합니다.

Physical 출력은 종류에 따라 `Preview (Phy)`, `YUV (Phy)`로 표시하며 일반 YUV 행은 번호 없이 `YUV`로 표시합니다. 같은 이름의 출력도 내부 ID로 구분합니다.

Camera2와 CameraX 모두 PIP를 켤 때 메인 Service의 기존 YUV 분석 출력을 유지합니다. Callback에도 동일한 YUV 출력 ID를 전달하며, 추가된 서비스의 결과와 섞지 않습니다. 지원하지 않는 조합은 실패로 처리하고 YUV를 임의로 끄지 않습니다.

LiveMeasurementText는 Single과 기존 Dual 화면의 FPS·EXP·AE·AF 문자열을 같은 형식으로 만듭니다. Physical 출력 이름은 OutputDescriptor에서 만들며 그래프에서 다시 조합하지 않습니다. Hold 여부는 저장된 holdSeconds에서 유도합니다.
