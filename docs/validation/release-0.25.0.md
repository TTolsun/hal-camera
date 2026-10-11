# 0.25.0 배포 검증

**PIP 사진 세 장 저장과 HAL 출력만 표시하는 Callback을 기기에서 확인했습니다.** 코드에서는 요청 대상 추적·Live 표시·Dual 출력 정의의 중복을 줄였습니다. 2026-10-11에 같은 서명의 0.25.0(versionCode 671)을 기존 앱 위에 설치했습니다.

## 코드 검토에서 바꾼 것

| 반복되던 책임 | 공통 구현 | 유지한 동작 |
| --- | --- | --- |
| Camera2·Dual·Service PIP의 요청 대상 추적 | 순수 Kotlin `RequestStreamRegistry` | 요청 생성 당시 대상을 복사합니다. 늦게 온 콜백은 다음 요청의 상태를 사용하지 않습니다. |
| Live 텍스트와 접근성 설명의 출력 선택 | 순수 Kotlin `LiveStreamReadout` | 꺼진 출력과 합성 크기를 숨기고 PIP 접두어는 한 번만 표시합니다. |
| Dual 요청·Callback·사진 이벤트의 출력 ID | `DualOutputs` | 같은 정의를 재사용하며 카메라별 출력과 실제 HAL 대상의 의미를 유지합니다. |

제조사·칩셋·Service ID별 선택 분기를 추가하지 않았습니다. 엔진 어댑터는 Android API를 사용하고, 공통 표시·추적 정책은 JVM에서 검사합니다. 이번 검토가 앱 전체의 모든 설계를 검증한 것은 아닙니다.

## 기기에서 확인한 결과

Galaxy S25+ **SM-S936N · Android 16/API 36 · Snapdragon SM8750**에서 USB ADB로 확인했습니다.

| 시나리오 | 결과 |
| --- | --- |
| Camera2 Service PIP: 후면 + 전면, JPEG On | 합성·메인 JPEG·보조 JPEG 세 파일을 저장했습니다. Live에는 활성 Preview·JPEG 크기만 표시했습니다. |
| Camera2 Physical PIP: 후면 + 보조 물리 카메라, JPEG On | 합성과 두 JPEG를 저장했습니다. Callback에는 물리 카메라 출력을 표시했습니다. |
| Camera2 YUV 촬영 | 640×480 버퍼를 480×640 JPEG로 저장했습니다. Live에는 YUV 기반 JPEG의 크기를 표시하고 Callback에는 앱 변환 완료 행을 만들지 않았습니다. |
| Camera2 일반 VIDEO | HAL Recording 행에서 도착 시각을 관측하고 정지 후 파일을 저장했습니다. |
| Camera2·CameraX Service PIP VIDEO | 녹화·정지·저장을 확인했습니다. 합성 인코더용 Recording 행은 Callback에 없었습니다. |
| CameraX 일반 VIDEO | Recording은 No callback으로 표시하고 파일을 저장했습니다. 관측하지 못한 버퍼 시각을 만들지 않았습니다. |
| Camera2 Dual: 물리 카메라 두 개 | 사진 두 장을 저장하고 프리뷰가 복구됐습니다. VIDEO에서는 두 HAL Recording 행이 No callback으로 표시됐고 정지 후 녹화 파일을 저장했습니다. |

PIP 선택과 보조 크기 변경의 상세 검증은 [PIP 검증 기록](pip-availability-2026-10-11.md), 앞선 VIDEO 오류 복구 검증은 [VIDEO 검증 기록](video-mode-ready-20261011.md)에 있습니다. 과거 버전의 결과와 이번 APK 확인을 구분합니다.

## 자동 검사

- 앱 JVM **795개**, CTS vendor JVM **12개**가 통과했습니다. 전체 CTS 기기 실행 결과는 아닙니다.
- release APK·debug 기기 테스트 APK 빌드와 Android lint가 통과했습니다. 테스트 APK 빌드만으로 기기 테스트 실행을 주장하지 않습니다.
- 문서 회귀 검사 **33개**와 문서 coverage 검사가 통과했습니다.
- APK의 v2 서명이 유효하며 기존 배포 APK와 인증서가 같았습니다. 배포 파일의 SHA-256은 릴리스 첨부 `SHA256SUMS.txt`에 제공합니다.

## 문서를 읽는 흐름

처음에는 시작·Live·Callback·문제 해결 네 가지를 보여 주고 나머지 도구는 펼쳐서 봅니다. PIP 세 장 비교를 포함한 짧은 실험 세 개로 기능을 익힙니다. 저장 방식은 Live, 관측 범위는 Callback을 원본으로 삼아 반복 설명은 링크로 바꿨습니다. 과거 검증 기록은 삭제하지 않고 접었으며 오래된 CTS 링크도 당시 기록으로 연결했습니다.

짧은 문장·단계·표로 읽는 부담을 줄였습니다. ADHD 당사자를 대상으로 사용성 검사를 수행한 결과는 아닙니다.

## 확인하지 않은 범위

Exynos와 다른 제조사 기기, 모든 출력 크기·코덱 조합, CameraX Dual, 전체 CTS 및 장시간 녹화는 이번 배포 검증에 포함하지 않았습니다. 기기가 지원 여부 조회를 제공하지 않는 조합은 선택할 수 있으며 실제 세션 구성에서 거부될 수 있습니다.
