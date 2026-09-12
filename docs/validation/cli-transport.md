# CLI transport 검증

2026-09-12, 이슈 #56의 M0 실험 결과다. 실제 촬영·벤치마크 검증은 이후 단계에서 추가한다.

## 환경

- 별도 시험 package `dev.halcamera.cliprobe`를 release variant로 빌드했다. 디버깅 가능 플래그를 사용하지 않고 시험용 debug key로 서명했다. 기존 `dev.halcamera` 앱은 교체하지 않았다.
- Galaxy S25+ SM-S936N, Android 16 / API 36에서 USB와 무선 ADB를 각각 사용했다.
- API 26 Google APIs x86_64 에뮬레이터를 Windows WHPX로 실행했다. shell UID는 2000이었다.

## 결과

| 확인 사항 | API 36 USB | API 36 무선 | API 26 |
|---|---|---|---|
| release Provider call + base64url 응답 | 통과 | 후속 통합 검사에서 확인 | 통과 |
| 65,536 bytes 바이너리 수신 | 통과 | 통과 | 통과 |
| 기대 데이터와 byte 단위 일치 | 통과 | 통과 | 통과 |
| 일반 앱의 call·query·openFile 접근 거부 | 통과 | 호출자 UID 검사는 USB와 동일 | 통과 |
| DUMP를 별도로 부여한 일반 앱도 거부 | 통과 | 호출자 UID 검사는 USB와 동일 | 통과 |

기대 데이터는 byte `0..255`를 256회 반복했다. 수신 파일의 SHA-256은 세 연결에서 모두 `7daca2095d0438260fa849183dfc67faa459fdf4936e1bc91eec6b281b27e4c2`였다.

`content call` 응답은 두 API에서 `Result: Bundle[{halcam_v1=<base64url>}]` 형태였다. 잘못된 method·URI는 예외 텍스트로 반환되었다. 따라서 ADB 프로세스 종료 코드만으로 성공을 판정하지 않고, 명령 응답과 다운로드 무결성을 별도로 검사한다.

보안 시험용 별도 앱 `dev.halcamera.clisecurityprobe`에서 call·query·openFile을 각각 시도했다. 세 진입점 모두 `SecurityException`으로 거부되었다. DUMP 권한을 부여한 후에도 거부되어 shell UID 검사의 필요성을 확인했다.

## 남은 검증

CLI 허용 스위치의 기본 비활성화, 프로토콜 오류 상세 분류, 파일 크기 제한, 취소·중복 요청, 실제 사진·벤치마크, 최종 배포 서명 검증은 production 구현 이후 다시 수행한다. 시험 전용 probe method와 데이터 경로는 배포 코드에서 제거한다.
