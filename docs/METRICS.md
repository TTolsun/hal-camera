# 현재 벤치마크 지표와 판정 계약

이 문서는 현재 코드가 계산하는 측정값을 정의합니다. 사용 절차는 [Benchmark 가이드](../guide/benchmark.md), 기기에서 확인한 범위는 [Validation](../guide/evidence.md)에서 관리합니다. 과거의 MVP 계획·미구현 제안·JSON 예시는 [v0.2 설계 초안](archive/METRICS-v0.2-draft.md)에 보존합니다. 현재 앱은 종합 점수를 산정하지 않습니다.

## 0. 공통 규약

### 0.1 시계

앱의 호출·콜백 시각은 `elapsedRealtimeNanos`입니다. 센서 시각과 앱 시각의 직접 차이는 `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`일 때만 해석합니다. `UNKNOWN`에서도 센서 시각끼리의 간격은 계산할 수 있습니다. 콜백 지연은 앱이 관측한 시간이며 HAL 내부 처리 시간이나 화면 표시 시각을 뜻하지 않습니다.

### 0.2 반복과 통계

백분위수는 정렬한 표본에서 `ceil(p × n)`번째 값을 선택하는 nearest-rank 방식입니다. n=9이면 p95는 최댓값입니다. 지터는 모집단 표준편차 `sqrt(sum((x - mean)^2) / n)`이며 단위는 ms입니다. 없는 값을 0으로 대체하지 않습니다. 값과 통계가 남아 있어도 실행의 validity·비교 적격성은 별도로 확인해야 합니다.

표준 profile은 launch 10회와 still 10회를 수행하고 각각 첫 표본을 제외합니다. 정상 완료 시 유효 표본은 각각 9개이며, 2.5의 제출 간격은 9개 중 첫 간격을 제외한 8개입니다. 관측 세션은 3초 warm-up 뒤 10초 동안 측정합니다. H.1~H.5와 H.10은 warm-up 프레임을 제외하고, H.6~H.8은 관측 세션의 첫 result부터 계산합니다. H.1~H.4와 H.10은 해당 표본이 15개 미만이면 대표값을 내지 않습니다. 실패한 launch 사이클은 일반 launch 통계에서 제외하고 누락 표본을 자동 재시도로 채우지 않습니다.

`camera2-standard-v2`는 녹화 5회(회당 9초)를 추가하고 첫 회를 제외합니다. 나머지 정상 완료 사이클의 값을 집계합니다. profile별 계약은 [BenchmarkProfile.kt](../app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkProfile.kt), 집계는 [BenchmarkEvaluator.kt](../app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkEvaluator.kt)가 정의합니다.

### 0.3 환경과 유효성

실행에는 기기·앱·subject 식별자, thermal 시작·최고·종료 상태, 절전 모드와 충전 상태를 기록합니다. 측정 가능 여부와 비교 적격성은 [RunValidity.kt](../app/src/main/java/dev/halcamera/benchmark/domain/RunValidity.kt)에서 유도합니다. 알 수 없는 flag는 비교에서 제외합니다. 과거 설계 초안의 CPU governor 고정 등은 현재 앱이 자동으로 보장하는 조건이 아닙니다.

### 0.4 시나리오 조건 축

표준 profile은 Camera2, preview/YUV/JPEG 1920×1080, FPS [30,30], ZSL off, precapture trigger off, AF CONTINUOUS_PICTURE를 사용합니다. v2의 녹화 조건은 1920×1080, H.264, 10 Mbps, 30 fps, 무음입니다. stream 크기는 profile 그대로 사용하고 자동 fallback을 하지 않습니다. 지원 여부는 preflight에서 확인합니다. profile 계약과 endpoint가 다른 실행은 같은 조건의 회귀 비교로 취급하지 않습니다.

### 0.5 산출물

현재 보고서는 schema 5 JSON이며 schema 3·4도 읽습니다. 측정값, 원시 표본, profile, 환경, validity를 저장하고 이미지 픽셀은 넣지 않습니다. `endpoint_score`는 null이며 과거 점수 계약은 [보관된 점수 초안](archive/SCORING-v1-draft.md)에만 남아 있습니다. 파일 경계는 [BenchmarkReportCodec.kt](../app/src/main/java/dev/halcamera/benchmark/domain/BenchmarkReportCodec.kt), 이벤트 조립은 [RunAssembler.kt](../app/src/main/java/dev/halcamera/benchmark/domain/RunAssembler.kt)가 담당합니다.

## 1. First preview time

일반 launch 대표값은 첫 사이클과 실패 사이클을 제외한 p50입니다. warm reopen 시퀀스이며 검증된 cold start를 뜻하지 않습니다.

| ID | 현재 계산하는 구간·값 |
| --- | --- |
| 1.1 | `openCamera` 호출 → `onOpened` |
| 1.9 | 1.1에서 제외된 첫 open 표본. cold start라고 단정하지 않습니다. |
| 1.10 | 1.1의 유효 표본 중 최댓값 |
| 1.2 | session 생성 호출 → `onConfigured` |
| 1.3 | repeating 제출 → 첫 `onCaptureStarted` |
| 1.8 | repeating 제출 → 첫 YUV `onImageAvailable` |
| 1.6 | `openCamera` 호출 → 첫 YUV `onImageAvailable` |
| 1.7 | close 호출 → `onClosed` |

1.6과 1.8은 YUV 수신을 끝점으로 삼습니다. 화면에 프레임이 표시된 시각이 아닙니다. 과거 초안의 1.4(SurfaceTexture)와 1.5는 현재 Benchmark 지표 목록에 없습니다. 1.9·1.10의 파생 규칙은 [LaunchTail.kt](../app/src/main/java/dev/halcamera/benchmark/domain/LaunchTail.kt)에 있습니다.

## H. 프리뷰 관측 지표

계산 근거는 [MetricExtractor.kt](../app/src/main/java/dev/halcamera/metrics/MetricExtractor.kt)입니다. Benchmark의 대표값은 아래와 같으며 LIVE의 순간 관측 집계와 구분합니다.

| ID | 정의 | 단위·대표값 |
| --- | --- | --- |
| H.1 | 연속 sensor timestamp 간격 | ms, p50 |
| H.2 | 같은 프레임 간격의 긴 꼬리 | ms, p95 |
| H.3 | 같은 프레임의 `onCaptureStarted` 수신 → 최종 `onCaptureCompleted` 수신 | ms, p50. 이름이 Partial이어도 partial result 도착 시간이 아닙니다. |
| H.4 | `onCaptureStarted` 수신 → 같은 sensor timestamp의 첫 이미지 수신 | ms, p50 |
| H.5 | stall 조건에 해당하는 관측 프레임 수 | count |
| H.6 | 관측 세션 첫 result → 처음 AE 안정 상태를 보고한 result | ms |
| H.7 | 관측 세션 첫 result → 처음 AF 초점 상태를 보고한 result | ms |
| H.8 | 관측 세션 첫 result → 처음 AWB 안정 상태를 보고한 result | ms |
| H.9 | 관측 구간의 `capture_failed` + `buffer_lost` 이벤트 수 | count. 같은 프레임의 두 이벤트도 각각 셉니다. |
| H.10 | H.1과 같은 유효 간격의 모집단 표준편차 | ms |

H.5는 간격이 해당 프레임의 `SENSOR_FRAME_DURATION × 1.5`보다 길 때 셉니다. 별도의 기준 간격이 주어지면 그 기준의 1.5배도 넘어야 합니다. own duration이 없으면 제공된 기준 간격으로만 판단하며, 둘 다 없으면 stall로 세지 않습니다. 현재 Benchmark 조립기는 별도 기준 간격을 전달하지 않습니다. 이 개수만으로 프레임 드롭을 확정하지 않습니다.

3A 안정 상태는 AE CONVERGED/LOCKED/FLASH_REQUIRED(2/3/4), AF PASSIVE_FOCUSED/FOCUSED_LOCKED(2/4), AWB CONVERGED/LOCKED(2/3)입니다. AF OFF/EDOF는 초점 수렴 대상으로 보지 않으며 지원 표본이 없으면 unsupported입니다. 관측 끝까지 수렴하지 않으면 첫 result부터 마지막 result까지의 시간과 timeout을 남깁니다. H.6~H.8은 참고값이며 회귀 판정에는 사용하지 않습니다.

## 2. Shot-to-shot

| ID | 현재 계산하는 구간·값 |
| --- | --- |
| 2.2 | still capture 제출 → 이미지 수신, 유효 표본 p50 |
| 2.3 | still capture 제출 → 최종 capture result 수신, 유효 표본 p50 |
| 2.5 | 연속 still 제출 시각의 간격, 첫 간격 제외 후 p50 |
| 2.7 | 현재 evaluator가 `NOT_RUN`으로 반환하는 예약 지표. 0 stall을 뜻하지 않습니다. |

### 2.5 제출 간격

10장을 제출하면 간격은 9개이고, `excludeFirst=true`이면 첫 간격을 제외한 8개를 집계합니다. JPEG 저장 완료나 센서 노출 간격이 아닙니다. 2.1·2.4·2.6은 과거 초안의 제안이며 현재 Benchmark 지표 목록에 없습니다.

## 3. Recording performance

녹화 단계가 있는 profile만 3.x를 보고합니다. stream 조합이 거부되면 unsupported이며, 실행되지 않았거나 표본이 없을 때 0으로 채우지 않습니다. 정상 완료한 비-warm-up 사이클이 3개 미만이면 대표값은 insufficient samples입니다.

| ID | 현재 계산하는 구간·값 |
| --- | --- |
| 3.1 | recorder start 호출 → 녹화 request의 첫 `onCaptureStarted`, 사이클별 값의 p50 |
| 3.2 | 고정 cadence에서 기준 간격의 1.5배를 넘은 간격 수를 유효 사이클 전체에서 합산 |
| 3.4 | 사이클별 완전한 1초 창의 result 개수 p50을 구한 뒤 사이클 간 p50, fps |
| 3.6 | recorder stop 호출 → 정상 반환, 사이클별 값의 p50 |
| 3.7 | 사이클별 sensor 간격의 모집단 표준편차를 구한 뒤 사이클 간 p50, ms |

[RecordMetrics.kt](../app/src/main/java/dev/halcamera/metrics/RecordMetrics.kt)는 session과 request tag로 녹화 사이클을 분리합니다. 센서 시각이 증가하고 frame 번호가 연속인 인접 result만 간격 표본에 넣습니다. 누락·역순 표본은 제외 개수로 남기며 카메라 드롭으로 단정하지 않습니다.

3.2는 간격 시작 프레임의 own duration이 profile 기준 `1000/fps`에서 ±5% 이내인 구간만 비교합니다. 비교할 구간이 없으면 cadence changed 또는 not measurable입니다. 3.4는 첫 녹화 프레임에서 3초를 건너뛰고 겹치지 않는 `[start, start+1초)` 창을 세며 마지막 불완전 창을 버립니다. 이 3초 제외는 3.2·3.7에 적용하지 않습니다. result 빈도는 인코더 출력 FPS가 아닙니다.

### 3.7 지터

표준편차는 0.2의 모집단 공식을 사용합니다. 원시 사이클에는 간격의 p95도 남기지만 최종 3.7의 대표값은 사이클별 표준편차의 p50입니다. 3.3(encoder drop)·3.5(장시간 녹화)는 현재 Benchmark 지표 목록에 없습니다.

## 4. 회귀 판정

규칙 원본은 [RegressionRules.kt](../app/src/main/java/dev/halcamera/benchmark/domain/RegressionRules.kt)의 `regression-rule-v3`, 적용은 [RegressionDetector.kt](../app/src/main/java/dev/halcamera/benchmark/domain/RegressionDetector.kt)입니다. 임계값을 변경하면 코드·이 문서·비교 테스트를 함께 갱신합니다.

| 지표 | 악화율 임계값 | 절대 변화 하한 |
| --- | --- | --- |
| 1.1·1.9·1.10·1.3·1.8·1.6·2.2·2.3·2.5·3.1 | 15% | 10 ms |
| 1.2·1.7·H.3·H.4 | 15% | 5 ms |
| H.1·H.2 | 10% | 2 ms |
| H.10·3.7 | 20% | 1 ms |
| 3.6 | 15% | 20 ms |
| 3.4 | 5% 감소 | 1 fps 감소 |
| H.5·2.7·3.2 | 개수 증가만 사용 | 2개 |
| H.9 | 개수 증가만 사용 | 1개 |
| H.6·H.7·H.8 | 참고 전용, 판정 제외 | 규칙표의 30%·200 ms는 판정에 적용하지 않음 |

시간 지표는 증가, 3.4는 감소가 악화입니다. 비율과 절대 변화 하한을 모두 만족해야 회귀이며 개수 지표는 절대 증가량만 봅니다. 값이 없는 예약 지표 2.7에 규칙이 있다고 해서 판정이 생기지는 않습니다. 기준값이 0이면 비율 대신 절대 변화 하한을 적용합니다.

명시한 baseline 집합의 최악값보다 임계값 이상 나빠져야 REGRESSED이며, 최선값보다 충분히 좋아지면 IMPROVED입니다. 기준이 없으면 UNKNOWN(no_baseline)입니다. 두 실행을 직접 비교할 때에는 사용자가 먼저 선택한 실행을 비교 기준으로 삼으며 저장된 baseline 지정은 바꾸지 않습니다.

계약·endpoint·비교 적격성이 맞아야 판정합니다. thermal 최고 상태 차이가 2단계 이상이거나 절전 모드가 다르면 판정을 막고, 충전 상태 차이는 경고로 표시합니다. 3A 노출 조건 차이는 참고값 해석에 남깁니다. 데이터가 없거나 조건이 맞지 않는 UNKNOWN을 정상(STABLE)으로 해석하지 마세요.
