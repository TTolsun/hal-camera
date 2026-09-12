---
based_on: [data-flow]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/cli/CliProvider.kt
  - app/src/main/java/dev/halcamera/cli/CommandCoordinator.kt
  - tools/halcam/halcam/cli.py
  - tools/halcam/halcam/download.py
  - app/src/main/java/dev/halcamera/MainActivity.kt
  - app/src/main/java/dev/halcamera/GalleryActivity.kt
  - app/src/main/java/dev/halcamera/ui/GalleryImageView.kt
  - app/src/main/java/dev/halcamera/ui/IconButton.kt
  - app/src/main/java/dev/halcamera/ui/ExpandingZoomControl.kt
  - app/src/main/java/dev/halcamera/ui/RecentMediaButton.kt
  - app/src/main/java/dev/halcamera/camera/RecentMediaThumbnail.kt
  - app/src/main/java/dev/halcamera/ui/SelectionPopup.kt
  - app/src/main/java/dev/halcamera/ui/ShutterButton.kt
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/ScoreComposer.kt
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
4. `RunAssembler`가 러너 결과와 이벤트를 결합해 `BenchmarkEvaluator`와 `RunValidityEvaluator`를 호출합니다. `ScoreComposer`는 calibration의 적용 범위와 적격 조건에 맞는 run에만 내부 점수를 채웁니다.
5. `BenchmarkReport`가 실행 JSON을 저장합니다. Activity는 baseline 또는 이전 실행을 찾아 비교 결과를 별도로 계산합니다.

`Telemetry.callback()`은 `capture_started`, `request_observed`, `capture_result`, `capture_failed`, `buffer_lost`를 기록합니다. 콜백의 `alive()`가 거짓이면 이미 닫힌 세션의 늦은 이벤트를 버립니다. `request_observed`는 요청 제출 시각이 아니라 `onCaptureStarted`에서 관측한 요청 내용입니다.

러너는 API 호출과 완료 신호 사이의 시각 차이를 기록합니다. `RunAssembler`는 관측 세션의 프레임 중 관측 시작 이전에 도착한 프레임 수를 워밍업으로 계산합니다. `MetricExtractor`가 이벤트를 표본으로 연결하고, `BenchmarkEvaluator`가 profile에 따라 초기 반복을 제외하고 통계를 계산합니다. 3A 수렴은 관측 세션의 첫 결과부터 계산합니다.

### 저장된 실행의 비교와 내보내기

`RegressionDetector`는 두 실행의 측정 계약·endpoint·validity·환경 조건을 확인합니다. baseline 비교에서만 회귀 판정을 표시하며, 이전 실행이나 임의 선택 실행과의 비교는 변화량과 비교 불가 사유를 표시합니다. 단위가 다르면 각 단위를 유지하고 백분율을 표시하지 않습니다.

RESULTS의 행은 저장된 결과로 연결됩니다. 길게 누르면 baseline, 비교, JSON·CSV 내보내기, 삭제 작업을 선택합니다. 삭제 확인 후 파일을 삭제하고 해당 baseline 포인터를 정리합니다. 측정값이 저장되는 단계와, 화면에서 비교 결과를 다시 계산하는 단계는 서로 다릅니다.

벤치마크 닫기와 결과·이력 복귀에는 기존 위치의 48dp 아이콘을 사용합니다. 접근성 이름과 길게 누르기 설명에 복귀 대상을 표시하며, 실행·비교·내보내기·필터의 구체적인 작업 이름은 글씨로 유지합니다.

### LIVE에서 선택과 촬영

LIVE 상단에는 배경·테두리 없는 현재 API 버튼과 `Benchmark` 메뉴를 두고, 두 메뉴 사이에 `Camera2 · LIVE`처럼 엔진과 상태를 같은 줄로 표시합니다. 하단은 핵심 측정값 2줄, 줌, 셔터 행, 사진·동영상 모드 순서입니다. MARK·벤치마크·일시정지·그래프는 측정 상세에서 제공합니다. 셔터 행의 왼쪽에는 최근 촬영물 썸네일, 오른쪽에는 카메라 선택 목록을 여는 아이콘을 둡니다.

일시정지·재개·갤러리·측정 패널 닫기처럼 익숙한 동작은 `IconButton`을 사용합니다. 아이콘은 24dp, 터치 영역은 48dp 이상이며 한국어 접근성 이름과 길게 누르기 설명을 제공합니다. API·카메라 ID·모드·줌·필터·선택과 MARK·벤치마크·측정 상세는 현재 값이나 동작의 의미를 확인할 수 있도록 글씨를 유지합니다.

API 버튼은 누를 때마다 Camera2와 CameraX를 전환합니다. 사진·동영상은 각각의 이름을 한 번 눌러 선택합니다. 카메라 버튼, 벤치마크 카메라, RESULTS 필터는 현재 항목이 표시된 목록을 사용합니다. 아래 공간이 부족하면 버튼 위에 열립니다. 바깥을 누르거나 뒤로 가면 값을 유지하고 닫으며, 화면을 나갈 때에도 목록을 닫습니다. 모드를 고른 뒤 중앙 셔터를 눌러야 촬영이나 녹화를 시작합니다.

줌은 현재 배율만 표시하다가 누르면 지원 배율로 펼쳐집니다. 선택 후 3초 동안 추가 조작이 없으면 선택한 배율을 유지한 채 접힙니다. 선택된 흰 원의 지름은 32dp이고 터치 영역은 48dp입니다. 펼침은 260ms, 접힘은 220ms 동안 폭과 투명도·크기가 부드럽게 바뀝니다. 좁은 창에서는 가로 스크롤을 제공하고, 드래그 중에는 자동 접기를 미룹니다. 시스템 접근성 시간 제한을 반영하며 TalkBack에서는 자동 접기 없이 선택을 기다린 뒤 접힙니다. 시스템 애니메이션 비활성화 설정도 따릅니다.

`ShutterButton`은 사진 모드에서 흰 원을, 동영상 모드에서 흰 테두리와 빨간 원을 표시합니다. 녹화 중에는 빨간 정지 사각형으로 바뀌며, 셔터 아래의 모드 위치에는 경과 시간을 표시합니다. 녹화 중에는 엔진·카메라·줌·모드 변경과 일시정지·갤러리·벤치마크를 비활성화합니다. 정지 셔터를 누르면 `저장 중…`을 표시하고, 녹화 종료 처리 동안 셔터를 비활성화해 중복 정지를 막습니다. 앨범 저장 완료는 별도 알림으로 표시합니다.

LIVE에서 사진을 촬영하면 Camera2의 같은 요청에 YUV와 JPEG 출력을 지정합니다. 센서 타임스탬프가 일치하는 버퍼를 연결해 별도 작업 스레드에서 사진 쌍을 저장합니다. 동영상은 프리뷰·인코더 세션으로 전환하고 종료 후 파일을 앨범에 공개합니다. CameraX 상태에서 미디어 작업을 요청하면 Camera2로 전환합니다.

### 갤러리에서 열람과 정리

촬영 화면의 최근 썸네일은 MediaStore에서 HALCamera의 저장 완료 항목만 조회합니다. 파일 저장과 화면 복귀 시 백그라운드에서 갱신하며 항목이 없거나 읽기에 실패하면 갤러리 아이콘을 표시합니다.

`GalleryActivity`는 HALCamera 앨범만 조회해 화면 폭에 따라 3–6열의 정사각형 격자로 표시합니다. 썸네일 간격은 2dp이며, YUV·JPEG와 동영상 재생 시간 표시로 항목을 구별합니다. 전체·사진·동영상 필터는 현재 값 버튼에 붙는 선택 목록을 사용합니다.

항목을 누르면 앱 내부 상세 화면을 엽니다. `GalleryImageView`는 사진 확대와 이동을 처리하며, 화면에 맞춘 크기에서는 좌우로 쓸어 항목을 넘길 수 있습니다. 이전·다음 아이콘도 제공하고, 동영상은 재생 아이콘을 눌러 시작합니다. 돋보기의 +·−와 접근성 설명은 버튼 클릭뿐 아니라 두 손가락·두 번 누르기로 바뀐 실제 확대 상태에도 맞춰 갱신합니다. 파일명·크기·해상도 등은 정보 아이콘을 펼쳤을 때 표시하며, 아이콘의 선택 상태와 설명도 함께 갱신합니다. 필터·격자 스크롤 위치·열어 둔 항목을 저장해 화면 복귀와 재생성 시 복원합니다.

선택 버튼이나 길게 누르기로 고른 항목은 공유·삭제 아이콘으로 함께 처리합니다. 공유 Intent에는 선택한 URI와 읽기 권한만 전달합니다. 삭제는 확인을 거치며 Android 11 이상에서는 `MediaStore.createDeleteRequest()`의 시스템 확인을 사용합니다.

갤러리 상단은 16dp 좌우 여백을 사용하고 제목·버튼 사이를 12dp 띄웁니다. 사진 상세의 탐색 버튼 사이에는 16dp, 공유·삭제 사이에는 24dp 간격을 둡니다. 탐색과 공유·삭제 행의 터치 영역 사이에는 패딩과 행 간격을 합쳐 28dp 여백을 확보합니다. 터치 영역은 48dp 이상을 유지합니다. 선택 체크 표시는 24dp 크기로 썸네일 가장자리에서 8dp 안쪽에 둡니다.

선택 모드에서는 상단 제목에 선택 개수를 크게 표시하고, 전체 선택 체크박스로 현재 필터의 모든 항목을 고르거나 해제합니다. 화면 밖의 항목도 포함합니다. 개별 항목을 해제하면 전체 선택 상태와 개수가 즉시 바뀝니다. 필터를 바꾸거나 선택을 취소하면 기존 선택을 해제합니다. 항목이 없거나 삭제 확인이 진행 중일 때에는 전체 선택을 비활성화합니다. 삭제는 기존 시스템 확인을 거칩니다. 2,000개를 넘는 선택은 2,000개 이하로 나누어 순서대로 시스템 확인을 요청하며, 중간에 취소하면 남은 요청을 중단합니다.

### CLI 요청과 결과 수집

CLI 명령은 ADB와 `CliProvider`를 거쳐 `CommandCoordinator`에 접수됩니다. 요청 ID와 내용을 먼저 저장하고 `LiveController` 또는 `BenchmarkController`가 화면의 카메라·벤치마크 동작을 실행합니다. 사진은 두 이미지의 저장 완료, 벤치마크는 report 파일 쓰기 완료 후 artifact를 등록합니다. 명령 접수와 실제 완료는 서로 다른 상태입니다.

PC는 요청 상태를 조회하고 완료된 artifact의 크기와 SHA-256을 확인합니다. 같은 요청 ID와 같은 내용은 기존 결과를 반환하며 새로운 촬영을 시작하지 않습니다. 원본 파일이 삭제되거나 전송이 끊긴 경우에는 요청 ID로 상태를 확인한 다음 파일 수집을 재시도합니다.
