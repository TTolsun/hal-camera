---
based_on: [data-flow, state-transitions]
confidence: code
sources:
  - app/src/main/java/dev/halcamera/telemetry/Telemetry.kt
  - app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt
  - app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt
  - app/src/main/java/dev/halcamera/benchmark/RunAssembler.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt
  - app/src/main/java/dev/halcamera/benchmark/BenchmarkActivity.kt
  - app/src/main/java/dev/halcamera/benchmark/HistoryActivity.kt
decisions: []
verifications: []
---

**먼저 앱의 필터와 계산 규칙을 확인한 뒤 원시 이벤트를 대조하세요.** 프레임워크에서 받은 값과 앱이 계산한 지표를 구분해야 원인 계층을 좁힐 수 있습니다.

1. `unknownReason`, 세션 ID, 관측 창과 표본 수를 확인합니다. `INSUFFICIENT_SAMPLES`는 측정 대상의 고장을 뜻하지 않습니다.
2. `capture_failed.reason`, `buffer_lost`, `capture_result`의 센서 시각과 `frameDurationNs`를 대조합니다. `request_observed`는 요청 제출 시각이 아닙니다.
3. 센서 타임스탬프가 REALTIME 소스일 때만 앱 시각과 직접 비교합니다. 다른 시계의 값을 빼면 지연을 해석할 수 없습니다.
4. 앱 기록만으로 프레임워크와 HAL 중 원인을 확정하지 않습니다. 시스템 트레이스와 카메라 서비스 로그로 추가 확인해야 합니다.

### 표본과 보존 규칙

| 규칙 | 확인할 코드와 영향 |
| --- | --- |
| 닫힌 세션의 콜백을 버립니다. | `Telemetry.callback`의 `alive()`를 확인합니다. |
| 관측 세션과 시간 창을 선택합니다. | `RunAssembler.observe()`가 관측 시작 이전 결과를 워밍업으로 제외합니다. 고정 5프레임 규칙은 사용하지 않습니다. |
| 표본이 부족하면 값이 비어 있습니다. | `BenchmarkEvaluator`의 관측 통계는 해당 지표 표본 수가 15개 미만이면 `null`을 반환합니다. 3A 수렴은 워밍업 전 프레임도 사용합니다. |
| 오래된 이벤트를 제거합니다. | `FlightRecorder` 기본 설정은 30초·18,000개이며 `BenchmarkActivity`는 180초·60,000개로 구성합니다. `capacityEvictions`를 확인합니다. |
| listener는 동기로 실행합니다. | `FlightRecorder.listener`에 무거운 처리를 추가하면 관측 경로에 영향을 줄 수 있습니다. |

`intervalMs`, `resultFps`, `observedResultGap`은 관측값으로 계산한 수치입니다. HAL 내부의 처리 시간이나 화면에 표시된 프레임 수를 직접 측정한 값이 아닙니다.

### 이력과 CSV가 예상과 다를 때

RESULTS는 기본적으로 비교 가능한 실행을 표시합니다. 중단된 실행을 찾으려면 상태 필터를 `전체`로 바꾸고 profile·camera 필터도 확인합니다. PC의 `tools/aggregate.py`는 기본적으로 점수 산정 가능한 실행만 내보내므로, 앱과 같은 범위를 보려면 `--eligibility comparison_eligible`을 사용합니다.

손상된 JSON은 목록과 PC 집계에서 별도로 알립니다. CSV 출력 장치의 오류는 입력 파일 오류와 구분하며 작업을 실패로 종료합니다. baseline 파일을 읽을 수 없으면 baseline 변경과 삭제를 중단합니다. 기기 화면·공유·삭제 동작의 실제 검증 기록은 이 문서에서 주장하지 않습니다.

사진·동영상 저장 실패는 벤치마크 비교와 구분해 확인합니다. 사진은 같은 센서 타임스탬프의 YUV·JPEG 버퍼가 모두 있어야 저장됩니다. 녹화 중에는 사진 촬영과 줌을 비활성화하며, 녹화 종료 후 사진용 프리뷰로 복귀합니다. 비교 화면에서 뒤로 가기를 누르면 결과 화면으로 돌아갑니다.
