# 벤치마크 단계 설명 검증

벤치마크 진행 화면의 제목 아래에 현재 작업을 설명하는 짧은 보조 문구를 추가했습니다. 기존 Look 토큰의 작은 글자와 보조 색상을 사용해 단계 제목·상황 설명·진행률의 위계를 유지합니다.

- 카메라 열기, 프리뷰 구성, 첫 프레임 대기, 회차 종료에는 현재 작업과 경과 시간을 표시합니다.
- 워밍업과 프레임 관측에는 경과 시간과 예정 시간을 함께 표시합니다.
- 3A 단계에는 초점·노출 확인 문구를 표시합니다.
- 사진 촬영에는 `Capturing 3/10`처럼 실제 촬영 순서와 경과 시간을 표시합니다.
- 녹화 준비·시작·진행·저장 설명도 같은 보조 줄에 표시합니다. 실행 결과를 저장할 때는 `Saving results`를 표시합니다.

경과 시간은 대기 상태를 설명하는 화면용 정보입니다. 측정 시퀀스, 보고서, 점수 계산은 변경하지 않았습니다.

## 자동 검사

`testDebugUnitTest`, `lintDebug`, `assembleRelease`를 통과했습니다. 단계별 설명과 Runner가 전달하는 사진 촬영 순서·총 횟수를 JVM 테스트로 확인했습니다.

`node tools/docgen/docflow.mjs check --build`는 기존 CLI 변경에서 남은 원본 최신성 재검토 14개 항목 때문에 실패했습니다. 검토 승인 기록을 자동 갱신하지 않았습니다.

## 실기기 검사

Galaxy S25+ (`SM-S936N`)·Android 16에 기존 서명의 0.19.0 APK를 `adb install -r`로 업데이트했습니다. 후면 카메라 0에서 `benchmark run --camera 0 --no-wait`를 실행했습니다.

| 화면 | 확인 내용 |
| --- | --- |
| [카메라 준비](assets/benchmark-step-detail-20261006/open.png) | Camera Open 4/10 아래에 `Preparing preview · 0.0 s`가 표시됩니다. |
| [사진 촬영](assets/benchmark-step-detail-20261006/still.png) | Still Capture 아래에 `Capturing 9/10 · 0.0 s`가 표시됩니다. |
| [녹화 준비](assets/benchmark-step-detail-20261006/recording.png) | Recording 3/5 아래에 `Preparing recorder`가 표시됩니다. |

세 화면 모두 설명이 카드 안에 잘리지 않고 표시되는 것을 확인했습니다. 워밍업·관측 문구는 JVM 테스트로 확인했으며 별도 화면 캡처는 하지 않았습니다. 다른 기기와 큰 글꼴 설정은 검증하지 않았습니다.

요청 `9f2c4693-9a39-4323-9f37-f231348e2777`은 succeeded로 완료됐으며 run `20261006-090438-543`의 schema 5 보고서를 회수했습니다. [요청 결과](assets/benchmark-step-detail-20261006/request.json)에 파일 정보가 있습니다. 검증한 APK와 원본 보고서는 작업 폴더의 `releases/cli-benchmark-validation/`에 보관했습니다.
