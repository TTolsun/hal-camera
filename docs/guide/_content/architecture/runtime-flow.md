---
based_on: [data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/GalleryActivity.kt
  - app/src/main/java/dev/halcamera/ui/GalleryImageView.kt
  - app/src/main/java/dev/halcamera/ui/ExpandingZoomControl.kt
  - app/src/main/java/dev/halcamera/ui/SelectionPopup.kt
  - app/src/main/java/dev/halcamera/ui/ShutterButton.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/BaselineManager.kt
  - app/src/main/java/dev/halcamera/benchmark/RegressionDetector.kt
decisions: []
verifications: []
---

**실행 실패는 러너 결과에서, 측정값의 차이는 이벤트와 계산 규칙에서 확인하세요.** 벤치마크의 입력은 카메라 콜백 이벤트와 러너가 기록한 실행 시각입니다.

### 실행에서 저장까지

1. `BenchmarkActivity`가 선택한 카메라와 profile을 사전 확인합니다.
2. `BenchmarkRunner`가 열기·닫기 반복을 수행합니다. 각 사이클은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE로 진행합니다.
3. 별도 관측 세션에서 WARMUP → OBSERVE → STILL → CLOSE를 진행합니다. profile은 반복 횟수와 관측·촬영 조건을 정합니다.
4. `RunAssembler`가 러너 결과와 이벤트를 결합해 `BenchmarkEvaluator`와 `RunValidityEvaluator`를 호출합니다.
5. `BenchmarkReport`가 실행 JSON을 저장합니다. Activity는 baseline 또는 이전 실행을 찾아 비교 결과를 별도로 계산합니다.

`Telemetry.callback()`은 `capture_started`, `request_observed`, `capture_result`, `capture_failed`, `buffer_lost`를 기록합니다. 콜백의 `alive()`가 거짓이면 이미 닫힌 세션의 늦은 이벤트를 버립니다. `request_observed`는 요청 제출 시각이 아니라 `onCaptureStarted`에서 관측한 요청 내용입니다.

러너는 API 호출과 완료 신호 사이의 시각 차이를 기록합니다. `RunAssembler`는 관측 세션의 프레임 중 관측 시작 이전에 도착한 프레임 수를 워밍업으로 계산합니다. `MetricExtractor`가 이벤트를 표본으로 연결하고, `BenchmarkEvaluator`가 profile에 따라 초기 반복을 제외하고 통계를 계산합니다. 3A 수렴은 관측 세션의 첫 결과부터 계산합니다.

### 저장된 실행의 비교와 내보내기

`RegressionDetector`는 두 실행의 측정 계약·endpoint·validity·환경 조건을 확인합니다. baseline 비교에서만 회귀 판정을 표시하며, 이전 실행이나 임의 선택 실행과의 비교는 변화량과 비교 불가 사유를 표시합니다. 단위가 다르면 각 단위를 유지하고 백분율을 표시하지 않습니다.

RESULTS의 행은 저장된 결과로 연결됩니다. 길게 누르면 baseline, 비교, JSON·CSV 내보내기, 삭제 작업을 선택합니다. 삭제 확인 후 파일을 삭제하고 해당 baseline 포인터를 정리합니다. 측정값이 저장되는 단계와, 화면에서 비교 결과를 다시 계산하는 단계는 서로 다릅니다.

### LIVE에서 선택과 촬영

LIVE 상단에는 엔진 선택, 일시정지·재개, 측정 상세를 둡니다. 하단은 측정값과 그래프, 줌, 촬영 모드, 셔터 행, MARK · ZIP 저장과 벤치마크의 순서로 구성합니다. 셔터 행의 왼쪽에는 갤러리, 오른쪽에는 현재 값을 표시하는 카메라 선택 버튼을 둡니다.

엔진·카메라 버튼을 누르면 현재 항목이 표시된 드롭다운 목록이 해당 버튼에 붙어 열립니다. 사진·동영상 모드, 벤치마크 카메라, RESULTS 필터에도 같은 목록을 사용합니다. 아래 공간이 부족하면 버튼 위에 열립니다. 바깥을 누르거나 뒤로 가면 값을 유지하고 닫으며, 화면을 나갈 때에도 목록을 닫습니다. 모드를 고른 뒤 중앙 셔터를 눌러야 촬영이나 녹화를 시작합니다.

줌 버튼은 하단 프리뷰 영역에서 측정값·그래프 아래, 촬영 모드 위에 현재 배율을 표시합니다. 원의 지름은 40dp이고 터치 영역은 48dp입니다. 버튼을 누르면 배율 목록이 가로로 펼쳐지며, 선택한 배율을 강조합니다. 마지막 조작 후 기본 3초가 지나면 선택한 원형 버튼 하나로 다시 접힙니다. 접근성 설정에 따라 대기 시간을 늘립니다. TalkBack 사용 중에는 선택하기 전까지 자동으로 접지 않으며, 배율을 선택하면 접습니다. 좁은 창에서는 목록을 가로로 스크롤할 수 있습니다.

`ShutterButton`은 사진 모드에서 흰 원을, 동영상 모드에서 흰 테두리와 빨간 원을 표시합니다. 녹화 중에는 빨간 정지 사각형으로 바뀌며, 셔터 위의 모드 위치에는 경과 시간을 표시합니다. 녹화 중에는 엔진·카메라·줌·모드 변경과 일시정지·갤러리·벤치마크를 비활성화합니다. 정지 셔터를 누르면 `저장 중…`을 표시하고, 녹화 종료 처리 동안 셔터를 비활성화해 중복 정지를 막습니다. 앨범 저장 완료는 별도 알림으로 표시합니다.

LIVE에서 사진을 촬영하면 Camera2의 같은 요청에 YUV와 JPEG 출력을 지정합니다. 센서 타임스탬프가 일치하는 버퍼를 연결해 별도 작업 스레드에서 사진 쌍을 저장합니다. 동영상은 프리뷰·인코더 세션으로 전환하고 종료 후 파일을 앨범에 공개합니다. CameraX 상태에서 미디어 작업을 요청하면 Camera2로 전환합니다.

### 갤러리에서 열람과 정리

`GalleryActivity`는 HALCamera 앨범만 조회해 화면 폭에 따라 3–6열의 정사각형 격자로 표시합니다. 썸네일 간격은 2dp이며, YUV·JPEG와 동영상 재생 시간 표시로 항목을 구별합니다. 전체·사진·동영상 필터는 현재 값 버튼에 붙는 선택 목록을 사용합니다.

항목을 누르면 앱 내부 상세 화면을 엽니다. `GalleryImageView`는 사진 확대와 이동을 처리하며, 화면에 맞춘 크기에서는 좌우로 쓸어 항목을 넘길 수 있습니다. 이전·다음 버튼도 제공하고, 동영상은 재생 버튼을 눌러 시작합니다. 파일명·크기·해상도 등은 정보 버튼을 펼쳤을 때 표시합니다. 필터·격자 스크롤 위치·열어 둔 항목을 저장해 화면 복귀와 재생성 시 복원합니다.

선택 버튼이나 길게 누르기로 고른 항목은 함께 공유하거나 삭제할 수 있습니다. 공유 Intent에는 선택한 URI와 읽기 권한만 전달합니다. 삭제는 확인을 거치며 Android 11 이상에서는 `MediaStore.createDeleteRequest()`의 시스템 확인을 사용합니다.
