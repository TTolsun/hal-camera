WorkbenchActivity는 Live의 Lab 버튼으로 바로 여는 기능 허브입니다. 앱을 실행하면 기존처럼 MainActivity의 프리뷰가 먼저 나옵니다. Lab은 카메라를 열지 않으며 기기 모델과 Android 정보를 표시하고, 상세 대화상자에서 SoC와 OS 빌드를 보여 주며 Live, Probe, CTS, Benchmark, 실행 기록, 갤러리로 이동합니다. API 31 미만에서는 SoC 대신 Build.HARDWARE를 표시합니다. 다른 제조사의 기기에서도 보고된 값을 사용하며 Exynos라고 가정하지 않습니다. Live에서 Lab으로 이동할 때에는 close(done) 뒤에 화면을 열고, Lab의 상단 Live 링크는 기존 프리뷰 화면으로 돌아갑니다.

DeviceIdentity는 Lab 별칭을 로컬 SharedPreferences에 저장하고 Lab의 기기 행에 표시합니다. 별칭은 측정 대상 빌드 라벨이나 benchmark 데이터 계약을 변경하지 않습니다. 기기 정보 대화상자는 별칭 편집과 OS 빌드·fingerprint 복사를 제공하며 하드웨어 serial을 읽지 않습니다. 본문은 실제 창 너비를 기준으로 최대 680dp에 맞추고 시스템 표시줄과 컷아웃을 피합니다.

Lab은 ZIP 기록의 공유·다른 위치에 저장·삭제와 ADB CLI 설정과 앱 정보를 제공합니다. 카메라 다시 연결은 Activity Result로 Live에 전달합니다. 카메라 ID와 엔진을 받아 Probe와 Benchmark에 전달합니다.

Lab은 Apple 참고안에 따라 흰 배경과 밝은 회색의 18dp 그룹 표면을 사용합니다. 작은 Live 복귀 링크, 34sp Lab 제목, Inspection·Results·Settings 그룹을 표시합니다. 기능 행은 최소 56dp이며, 화면 재생성 없이 설정 내용을 다시 그릴 때 기존 스크롤 위치를 유지합니다.

Probe·CTS·Benchmark·Run History와 상세 화면은 LabTheme, Look.titleBar, 밝은 그룹 표면을 공유합니다. Gallery와 About은 Runway 참고안을 적용합니다. Gallery는 어두운 미디어 격자와 흰색 기본 버튼을, About은 기존 로봇 캐릭터와 흰 배경·검은 버튼을 사용합니다. Device Info·ADB CLI와 ZIP 대화상자는 Apple 기반 LabDialog를 사용합니다. 제목은 영어, 보조 설명은 한글이며 About 소개는 사용자 지정 영어 문구를 유지합니다.

Live Streams는 Lab 위에 LiveStreamsActivity를 열며 카메라를 열지 않습니다. 상단·시스템 뒤로가기는 변경을 버리고 Lab으로 돌아갑니다. 저장과 직전 정상 구성 복원도 Lab으로 돌아가며, Lab은 설정 결과를 보관했다가 Live로 복귀할 때 전달합니다. MainActivity는 이 결과를 반영한 뒤 프리뷰를 재개합니다. 화면 재생성 때에도 편집 중 초안과 저장한 결과를 각각 보존합니다.

Run History에는 이전 측정 결과 · 비교 · 내보내기라는 설명을 표시해 새 측정을 시작하는 Benchmark와 구분합니다.
