MainActivity가 Live 런처이며 BenchmarkActivity가 실행과 결과를, HistoryActivity가 이력·임의 비교·내보내기를 제공합니다. HomeActivity와 CheckActivity는 M3에서 제거되었습니다. GalleryActivity는 Live나 작업실에서 여는 앨범 화면입니다. WorkbenchActivity는 도구 메뉴에서 선택적으로 열며 기기 정보와 검사 도구 진입점을 모읍니다. 앱의 런처는 계속 MainActivity입니다.

벤치마크 관련 화면은 BenchmarkActivity와 HistoryActivity 둘뿐입니다. 여러 실행을 묶어 통계 검정을 수행하던 ProfileComparisonActivity와 외부 JSON 가져오기는 0.13.0에서 제거했습니다.
