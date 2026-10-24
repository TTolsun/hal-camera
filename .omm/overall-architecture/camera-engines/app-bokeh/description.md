App Bokeh는 일반 Camera2의 Preview와 JPEG 두 출력을 사용한다. MainAppBokehBridge가 JPEG 촬영·저장을 마친 뒤 AppBokehEditor에 URI를 전달한다. 제조사 확장과 분석용 YUV, 센서 Depth 스트림을 사용하지 않는다.

AppBokehProcessor는 원본을 EXIF 방향에 맞추고 긴 변을 최대 1600픽셀로 제한한다. MiDaS v2.1 모델은 RGB 사진 한 장에서 256×256 Float 상대 역깊이를 추론한다. RelativeDepth와 DepthBokeh는 Android 의존성 없이 깊이 정규화·선택 위치 표본·배경 혼합과 blur를 계산한다. 결과는 JPEG로 재인코딩하며 별도 깊이 PNG와 모델·원본·선택 위치·강도 메타데이터를 저장한다. 원본은 수정하지 않는다.

편집기의 worker가 추론·렌더·저장을 처리한다. 변경 세대가 지난 렌더는 표시하지 않고 화면 종료 후에는 UI를 갱신하지 않는다. 화면 복귀는 보관한 원본 URI로 편집을 다시 시작하며 이전 강도·선택 위치는 초기화한다. 상대 깊이는 실제 거리나 센서 DEPTH16이 아니다. Benchmark와 incident ZIP에는 이미지 픽셀을 넣지 않는다.

편집기는 Live 선택창과 같은 제목·닫기 아이콘, 흰색 선택 테두리와 접근성 선택 상태를 사용한다. 이미지와 저장 버튼은 Gallery의 Look 토큰을 재사용한다. 강도 조절은 Bokeh 결과로 돌아가며 Save Bokeh는 비교 화면과 관계없이 보케 복사본을 저장한다. 슬라이더는 최소 48dp 높이를 제공한다.
