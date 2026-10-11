# 작업 상태

**0.25.0의 변경 내용과 검증 범위입니다.** 설치할 정식 APK는 [최신 릴리스](https://github.com/TTolsun/hal-camera/releases/latest), 변경 내용은 [0.25.0 릴리스 노트](releases/0.25.0.md), 사용법은 [Live 가이드](../guide/live.md)를 확인하세요.

## 이번 버전에서 확인할 것

- JPEG On의 PIP 사진은 합성과 두 카메라 원본을 저장합니다. 보조 크기는 Live Streams에서 선택합니다.
- Callback은 카메라 출력만 표시합니다. 앱 합성 녹화에는 별도 HAL Recording 행이 없습니다.
- 코드 중복과 문서의 반복 설명을 정리합니다. 자세한 변경은 릴리스 노트에 모읍니다.

## 검증 범위

Galaxy S25+ · Android 16에서 확인한 조합과 자동 검사 결과는 [0.25.0 검증 기록](validation/release-0.25.0.md)에 있습니다. 다른 제조사 기기, 전체 CTS, 장시간 녹화는 이번 검증에 포함하지 않았습니다.

Baseline 기반 회귀 비교는 유지합니다. 내부 점수 초안은 2026-10-08에 제거했으며 새 run의 점수 필드는 호환을 위해 null로 남습니다. [점수 초안 보관 문서](archive/SCORING-v1-draft.md)를 참고하세요.

## 다음 검증

1. 다른 제조사 기기에서 출력·PIP 조합을 확인합니다.
2. 비충전 상태에서 녹화 성능을 측정합니다.
3. 인코더 drop과 10분 녹화 drift 측정을 별도 시나리오로 검증합니다.

과거 버전의 변경 목록은 [릴리스 기록](releases/), 마일스톤·측정 수치·당시 제약은 [이전 작업 기록](archive/STATUS-before-0.24.0.md)에 보관했습니다.
