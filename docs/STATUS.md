# 작업 상태

**0.29.0은 CameraX Multi와 Multi V 마이크 녹음을 추가한 릴리스입니다.** 설치는 [최신 릴리스](https://github.com/TTolsun/hal-camera/releases/latest), 변경 내용은 [릴리스 노트](releases/0.29.0.md), 촬영 방법은 [Live 가이드](../guide/live.md)를 확인하세요.

## 이번 버전에서 확인할 것

- Multi P·V에서 카메라 ID를 끌어 배치를 바꾸고 Split에서 카메라 조합을 고릅니다.
- 갤러리에서 장치별 사진·영상과 녹화 중 사진을 확인합니다.
- 프리뷰의 카메라 ID를 선택하고 Callback에서 해당 카메라의 실제 HAL 출력을 확인합니다.

## 검증 범위

Galaxy S25+ · Android 16의 데이터 보존 업데이트와 코드·기기 검증은 [0.29.0 검증 기록](validation/release-0.29.0.md)에 모았습니다. 다른 제조사, 세 카메라 이상, 전체 CTS와 장시간 녹화는 이번 기기 검증에 포함하지 않았습니다.

Baseline 기반 회귀 비교는 유지합니다. 내부 점수 초안은 2026-10-08에 제거했으며 새 run의 점수 필드는 호환을 위해 null로 남습니다. [점수 초안 보관 문서](archive/SCORING-v1-draft.md)를 참고하세요.

## 다음 검증

1. 다른 제조사 기기에서 출력·PIP 조합을 확인합니다.
2. 비충전 상태에서 녹화 성능을 측정합니다.
3. 인코더 drop과 10분 녹화 drift 측정을 별도 시나리오로 검증합니다.

과거 버전의 변경 목록은 [릴리스 기록](releases/), 마일스톤·측정 수치·당시 제약은 [이전 작업 기록](archive/STATUS-before-0.24.0.md)에 보관했습니다.
