# ADB 전용 CLI 검증

검증일: 2026-09-20. 대상 기기는 Samsung SM-S936N, Android 16입니다. 같은 로컬 release 서명으로 `adb install -r` 업데이트하여 기존 앱 데이터를 유지했습니다. USB ADB로 아래 항목을 확인했습니다. 무선 ADB에서는 기존 앱의 연결·조회까지 확인했으나 연결 주소가 바뀌고 연결이 거부되어 전체 기능 검증은 USB로 진행했습니다.

## 확인한 기능

| 항목 | 결과 |
| --- | --- |
| APK의 `/v1/shell`에서 스크립트 준비 | PC 추가 런타임 없이 `content read`로 준비했습니다. |
| `preview`, `preview stop` | 첫 프레임 준비와 카메라 종료가 각각 완료되었습니다. |
| `capture` | 480×640 YUV 변환 JPEG, 1920×1080 카메라 JPEG을 저장하고 PC에 회수했습니다. Pillow로 두 파일의 JPEG 구조를 확인했습니다. |
| `record start --no-audio`, `record stop` | MP4 저장·등록·회수를 완료했습니다. 컨테이너 길이 14.8453초, 영상 트랙만 존재했습니다. |
| `record start`, `record stop` | MP4 길이 20.6417초, 영상과 음성 트랙을 확인했습니다. |
| 녹화 중 `capture` | 새 요청을 제출하지 않고 실행 중임을 안내했습니다. |
| `record start --timeout 5` | `EXECUTION_TIMEOUT`으로 완료되고 앱의 `busy`가 false로 복구되었습니다. 저장된 MP4는 `fetch UUID`로 회수할 수 있었습니다. |
| `record start --no-wait` 직후 `record stop` | 짧아 저장할 수 없는 녹화를 `RECORDING_FAILED`로 구분하고 `busy`를 해제했습니다. |
| 녹화 중 `cancel` | `cancelled`로 완료되었으며 저장된 MP4가 결과에 등록되었습니다. |
| `probe` | 카메라 6개의 사양 JSON·TXT를 오류 없이 저장하고 기기 다운로드 폴더에 준비했습니다. |
| 직접 `benchmark.run` 호출 | `INVALID_ARGUMENT`으로 거부되었습니다. |
| 기존 Python `preview` | Base64 요청·응답 프로토콜을 통한 실행도 완료되었습니다. |

사진 2장과 무음 MP4는 `adb pull`로 PC에 복사한 뒤 SHA-256이 앱의 등록값과 같은지 확인했습니다. 소리 포함 MP4도 회수하여 해시와 트랙 구성을 확인했습니다. 영상 디코딩·전체 재생 품질과 Android 8–15의 기기별 동작은 이번 검증 범위에 포함하지 않았습니다. CTS suite 전체 재실행도 포함하지 않았습니다.

## 자동 검사

- Android JVM 테스트: 399개 통과.
- Python 클라이언트·APK 셸 스크립트 테스트: 35개 통과. 인자 검증, 중복 작업 차단, 실제 시작 대기, 녹화 종료, 오류 반환, 바이너리·크기·해시 검증, 시간 초과 파일 복구를 포함합니다.
- Android lint 및 서명된 release APK 빌드: 통과.
- 문서 엔진 회귀 테스트: 18개 통과. 담당 요소 검사, 생성 결과 일치와 사이트 산출물 검사도 통과했습니다.
- 최초 구현 당시 문서 최신성 검사는 검토 기록 미갱신으로 실패했습니다. 이후 사용자의 리뷰·머지 요청에 따라 Codex가 코드와 원고를 대조하고 위임 검토자 이름으로 기록을 갱신했습니다. 독립적인 사람의 코드 리뷰를 뜻하지 않습니다.

현재 사용법은 [CLI 가이드](../../guide/cli.md)를 참고하세요.
