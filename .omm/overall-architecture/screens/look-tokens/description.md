ui/Look.kt는 docs/design/DESIGN.md의 색상·글꼴·카드·행 토큰을 Android 화면에 적용합니다. 상태 색상은 판정에 사용하며 버튼의 기본 동작 색상과 구분합니다.

text·card·row 생성 함수와 disclosure를 제공합니다. disclosure는 상세 설명을 접고 펼치며 기본값은 접힘입니다. 토글의 최소 높이는 48dp이고 펼침 상태를 접근성 설명에 반영합니다.
