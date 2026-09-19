ui/는 ScopeView, StripView, TimelineView와 공통 시각 설정 Look, Live 수치를 계산하는 LiveReadout, 그리고 IconButton, ShutterButton, ExpandingZoomControl, RecentMediaButton, SelectionPopup, GalleryImageView를 포함합니다. ExpandingZoomControl은 현재 배율에서 목록을 펼치고 선택 후 자동으로 접으며 RecentMediaButton은 최근 앨범 항목을 표시합니다. 커스텀 View는 Canvas에 관측 데이터를 그립니다. LiveReadout은 M3에서 삭제된 HealthMonitor를 대체하며, MainActivity가 넘긴 이벤트 목록에서 판정 없이 관측값(간격, partial, buffer, stall 횟수, 3A 상태)을 읽습니다. 최근 1.5초 안의 마지막 프레임이 현재값이고, 그보다 오래된 프레임이 15개 이상 모이면 그 p50이 기준선입니다.

앱과 문서 사이트는 docs/design/DESIGN.md의 HAL-CAMERA-Editorial을 공통 기준으로 사용하며 Look은 Android의 밝은 화면과 어두운 화면에 대응하는 토큰을 제공합니다.

Look.disclosure는 세부 설명을 접고 펼치며 접근성 상태를 제공합니다. MetricRows는 ResultRow·CompareRow를 화면 폭에 맞춰 줄바꿈하는 행으로 표시합니다. 값·max/p95·변화량·판정 사유를 유지하고, 판정은 색과 글씨로 구별합니다. 계산은 domain presenter에 남깁니다.
