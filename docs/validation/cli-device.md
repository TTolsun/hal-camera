# CLI 기능 검증 기록

2026-09-12, Windows PC와 Galaxy S25+ SM-S936N / Android 16(API 36)에서 `dev.halcamera.cliprobe` release 시험 앱을 사용했다. 기존 앱과 다른 package를 사용했으며 시험용 debug key로 서명했다. 최종 배포 APK의 서명·설치 검증은 아직 별도로 필요하다.

## 실기기 결과

| 시나리오 | 관측 결과 |
|---|---|
| 초기 비활성화 | `/v1/hello`가 `CLI_DISABLED`를 반환했다. UI의 ADB CLI 허용 스위치를 켠 뒤 명령을 받았다. |
| 사진 촬영 | 요청 `ebe9632a-5807-40a7-b78e-5777c7cc2675`가 성공하고 YUV 변환 JPEG 12,533 bytes와 원본 JPEG 362,310 bytes를 수집했다. 두 파일의 크기·해시 검증을 통과했다. |
| 같은 ID 재제출 | 같은 capture ID `HAL_20260912_225023_413_fc33d2`와 센서 시각·파일 해시가 반환되었다. 새 촬영이 아니라 기존 파일을 다시 받았다. |
| 정상 벤치마크 | 요청 `2aa8cdbb-8f1c-469b-adfb-0f1f5f583c7a`, run `20260912-225123-218`이 성공했고 schema 4 JSON 894,700 bytes를 수집했다. |
| 기존 CSV 도구 호환 | `tools/aggregate.py --eligibility all`이 해당 run 한 개를 읽고 오류 없이 CSV로 변환했다. |
| 잘못된 카메라 | `UNSUPPORTED_CAMERA`, 종료 코드 4를 반환했다. |
| 잘못된 profile | `UNSUPPORTED_PROFILE`, 종료 코드 4를 반환했다. |
| 같은 ID에 다른 camera | `REQUEST_CONFLICT`, 종료 코드 2를 반환했다. |
| 실행 중 다른 요청 | benchmark의 `--no-wait` 접수 직후 capture가 `BUSY`, 종료 코드 4로 거부되었다. |
| 실행 취소 | 요청 `3bb1a03a-3abf-4ce7-9b7d-2946605184c8`이 `cancelled`로 종료되었고 partial JSON 63,473 bytes를 `fetch`로 수집했다. 종료 코드는 130이었다. |
| 프로세스 종료 | 요청 `eb3dde7f-ceae-4698-a070-194f6393b2e3` 실행 중 시험 package를 force-stop했다. 이후 Provider 조회가 `interrupted`, 종료 코드 5를 반환했으며 자동 재실행하지 않았다. |

완료된 benchmark 화면에서 다시 LIVE로 이동할 때 최초 Activity 실행 플래그로는 화면이 전환되지 않았다. CLI의 앱 실행에 CLEAR_TOP과 SINGLE_TOP을 함께 적용한 뒤 잘못된 입력·동시 실행·취소·재시작 시험을 다시 수행했다. UI와 CLI의 전환 경합은 merge 전 리뷰에서 추가 검토한다.

## 자동 검사

- Python 테스트 15개가 통과했다. 인자·기기 선택·프로토콜·대기 종료·파일 무결성·경로 검증을 포함한다.
- `testDebugUnitTest`의 267개 테스트가 실패·오류 없이 통과했다. 신규 CLI 모델·상태 규칙 테스트 6개를 포함한다.
- `lintDebug`가 통과했다.
- 문서 회귀·디자인·동기화 검사 31개가 통과했고 실제 Qwen 호출 검사 1개는 기본 설정대로 생략했다.
- `verify.mjs --check`, `generate.mjs --check`, `design --check`가 통과했다.
- Python wheel `halcamera_cli-0.1.0-py3-none-any.whl`을 로컬에서 빌드했다. 최종 Release asset은 마지막 코드 기준으로 다시 빌드한다.

## 다음 검증

코드리뷰와 경합 수정, 시간 제한·권한 거부·화면 이탈 추가 사례, API 26의 최종 명령 연결, UI 회귀와 측정 영향 비교, 최종 release 서명·설치·CI 결과를 확인해야 한다. 위 결과는 이 항목들의 완료를 대신하지 않는다.
