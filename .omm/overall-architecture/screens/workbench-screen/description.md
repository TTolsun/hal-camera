WorkbenchActivity는 Live의 도구 메뉴에서 여는 선택적인 작업실입니다. 앱을 실행하면 기존처럼 MainActivity의 프리뷰가 먼저 나옵니다. 작업실은 카메라를 열지 않으며 기기 모델, Android 버전, SoC와 OS 빌드를 보여 주고 Live, Probe, CTS, Benchmark, 실행 기록, 갤러리로 이동합니다. API 31 미만에서는 SoC 대신 Build.HARDWARE를 표시합니다. 다른 제조사의 기기에서도 보고된 값을 사용하며 Exynos라고 가정하지 않습니다. Live에서 작업실로 이동할 때에는 close(done) 뒤에 화면을 열고, 작업실의 Live 버튼은 기존 프리뷰 화면으로 돌아갑니다.

DeviceIdentity는 작업실 별칭을 로컬 SharedPreferences에 저장하고 공통 제목 줄에도 표시합니다. 별칭은 측정 대상 빌드 라벨이나 benchmark 데이터 계약을 변경하지 않습니다. 기기 정보 대화상자는 별칭 편집과 OS 빌드·fingerprint 복사를 제공하며 하드웨어 serial을 읽지 않습니다. 본문은 실제 창 너비를 기준으로 최대 840dp에 맞추고 시스템 표시줄과 컷아웃을 피합니다.
