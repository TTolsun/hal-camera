---
title: HAL Camera 개발자 가이드
---
# HAL Camera 개발자 가이드

Camera HAL 개발자가 앱의 관측값을 해석하고 측정 기능을 유지보수하기 위한 가이드입니다. 앱은 Camera2/CameraX 호출과 콜백을 기록하며, Auto Check의 건강 판정과 벤치마크의 측정·비교 경로를 제공합니다.

1. [개발 환경 및 빠른 시작](getting-started.md)
2. [아키텍처 및 코드 구조](architecture.md)
3. [디버깅 및 문제 해결](troubleshooting.md)

현재는 아키텍처와 디버깅 두 장을 중심으로 작성한 파일럿입니다. 각 페이지의 근거와 검토 상태를 함께 확인하세요. 앱이 기록한 콜백만으로 HAL 내부 처리 시간이나 근본 원인을 확정할 수는 없습니다.
