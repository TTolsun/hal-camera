ui/는 공통 시각 설정 Look, Live 수치를 계산하는 LiveReadout, 그리고 IconButton, ShutterButton, ExpandingZoomControl, RecentMediaButton, SelectionPopup, GalleryImageView를 포함합니다. ExpandingZoomControl은 현재 배율에서 목록을 펼치고 선택 후 자동으로 접으며 RecentMediaButton은 최근 앨범 항목을 표시합니다. LiveReadout은 M3에서 삭제된 HealthMonitor를 대체하며, MainActivity가 넘긴 이벤트 목록에서 판정 없이 관측값(간격, partial, buffer, stall 횟수, 3A 상태)을 읽습니다. 최근 1.5초 안의 마지막 프레임이 현재값이고, 그보다 오래된 프레임이 15개 이상 모이면 그 p50이 기준선입니다.

앱과 문서 사이트는 docs/design/DESIGN.md의 HAL-CAMERA-Editorial을 공통 기준으로 사용하며 Look은 Android의 밝은 화면과 어두운 화면에 대응하는 토큰을 제공합니다.

Look.disclosure는 세부 설명을 접고 펼치며 접근성 상태를 제공합니다. 결과와 비교의 숫자를 글자 행으로 그리던 MetricRows는 비교를 막대와 눈금 하나로 합치면서 없앴습니다. 계산은 domain presenter에 남깁니다.

콜백 그래프(ResultCallbackGraph·ResultCallbackSeries)와 Live 화면이 함께 쓰는 라벨·버튼 생성기(CameraWidgets)는 screens/expert-screen/diagnostics-panel 요소가, ShutterButton과 ExpandingZoomControl의 상세는 capture-controls 하위 요소가 설명합니다.

LabDialog는 Lab의 Device Info·ADB CLI와 ZIP 목록·파일 작업·삭제 확인에 쓰는 스크롤 가능한 Apple 기반 대화상자입니다. Gallery 전용 Look 토큰과 galleryButton은 Runway의 단색 대비와 알약형 버튼을 제공합니다. AboutSheet는 기존 로봇 리소스와 앱 버전·연락처 동작을 유지하며 흰 표면과 검은 버튼을 사용합니다. Live에서 표시하는 incident 저장 결과는 기존 대화상자를 유지합니다.
