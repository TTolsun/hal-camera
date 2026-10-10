MainActivity는 Live 런처입니다. CameraEngine을 선택하고 카메라 수명주기, 권한, 촬영 모드와 버튼 상태를 관리합니다. LiveReadings가 실시간 측정값을 갱신하고 IncidentActions가 이벤트 ZIP 저장과 공유를 담당합니다. Benchmark는 Lab에서 엽니다.

프리뷰의 제어·측정값·촬영 버튼 배치는 docs/design/APP-UI.md를 따릅니다. 아이콘 터치 영역은 48dp 이상이며 색·서체는 Look을 사용합니다.

Live 표시는 프리뷰·capture result가 1.5초 동안 없으면 회색으로 바뀌며 시스템 애니메이션이 꺼지면 깜빡이지 않습니다. FPS·ISO·노출·AE·AF와 추가 측정값을 표시하고 Callback을 열면 ResultCallbackGraph로 대체합니다. 펼침 컨트롤은 Live·크기 표시와 같은 자리에서 교차 페이드합니다. 측정값·그래프 규칙은 diagnostics-panel 요소를 따릅니다.

ExpandingZoomControl은 현재 배율을 눌러 후보를 펼치고 선택 후 접습니다. 핀치는 엔진과 줌 숫자를 함께 갱신하며 터치 측광과 구분합니다. 카메라 선택은 ID 목록을 열며 취소하면 기존 선택을 유지합니다. 상단 촬영 제어에서 플래시, AE·AF 잠금, EV를 조절합니다. 프리뷰 터치 측광은 현재 엔진에 전달합니다.

모드 선택만으로 촬영하지 않습니다. Photo 셔터는 선택한 YUV·JPEG·RAW 출력을 저장합니다. Video 셔터는 오디오 권한 처리 후 녹화를 시작하고 정지 시 저장합니다. 녹화 중 셔터는 정지 사각형으로 바뀌고 모드 행은 녹화 시간으로 대체됩니다. 녹화 중에는 카메라·엔진·모드 변경을 막습니다. Gallery는 최근 저장 썸네일에서 엽니다.

연사·AEB 촬영 중에도 셔터를 정지 사각형으로 표시하며 셔터 위 한 줄에서 저장 장수와 HDR 합성 상태를 확인합니다. 촬영 중에는 카메라·엔진·모드뿐 아니라 EV·수동 제어·줌·터치 측광도 잠급니다. 촬영 순서와 중단 처리는 LiveBurst, 진행·결과 표시는 CaptureFeedback이 담당합니다.

Multi · P와 Multi · V는 같은 장치 조합 목록을 열며 Live의 close(done) 이후 ConcurrentCameraActivity를 엽니다. CameraX에서는 Camera2 경로로 전환을 확인합니다. Photo·Video·엔진 전환은 PIP·위치·스트림·제어를 초기화하고 재구성합니다. Camera2에서 PIP 선택만 바꿀 때는 현재 Live CameraDevice를 유지합니다. Live의 비동기 재오픈과 권한 대기 중에는 중복 진입을 막습니다.

LiveIndicator의 크기 표시를 누르면 Live Streams를 엽니다. 설정과 직전 정상 구성은 카메라·엔진별로 유지하며, 적용 또는 취소 후 Live로 돌아옵니다. Lab도 Activity Result로 설정 변경을 전달합니다. Benchmark profile은 Live 설정과 분리합니다. 종료 완료 전에 다른 카메라를 열지 않으며, CLI 명령 중에는 UI의 충돌하는 조작을 비활성화합니다.

일반 사진·PIP 사진·동영상 저장 완료 안내는 3초 뒤 숨기며 새 안내가 오면 이전 타이머를 취소합니다. 활성 Live의 동영상 저장 실패도 notice 경로로 전달해 다음 상태 변화까지 표시하며, Save Events · ZIP으로 진단을 남기는 다음 행동을 안내합니다. 카메라가 이미 닫힌 경우에는 기존 토스트 경로를 유지합니다. 진단 ZIP 완료 창에는 앱 내부 저장 여부와 Lab → ZIP Archives에서 다시 찾는 경로를 표시합니다.

API 옆 PIP는 보조 ID 하나를 고릅니다. LivePipController는 기존 조작부와 측정값을 유지하고 합성 TextureView를 더합니다. Camera2는 장치를 유지하고 CameraX는 지원 Service 조합을 재bind합니다. 합성·위치·저장 계약은 concurrent-camera-screen/pip-composition을 따르며 결과 안내는 컨트롤 위에 표시합니다.
