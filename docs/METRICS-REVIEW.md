# 지표 정의표 v0.2 검토 기록

2026-09-08 · 문서 갱신. 앱 소스와 checkpoint-001 APK는 변경하지 않음.

입력은 [사용자 원문](inputs/user-metrics-2026-09-08.md)이며 원문 파일은 바이트 그대로 보존했다. [수정안](METRICS.md)은 아직 확정본이 아니다.

## 수용한 방향

Camera2 단일 엔진, MediaRecorder 기본, encoder 측정은 후속, 지표 ID 및 MVP/다음/이후 구분, 조건별 지표 이름, `not_measurable`, run JSON, PC 분석을 유지했다. 기존 v0.1의 MediaCodec 중심 녹화 제안은 이 초안에서 대체했다.

## 정의 충돌 수정안

| 항목 | 원문 | 검토안 / 근거 |
|---|---|---|
| 1.3 | sensor 이름, 종료는 callback 진입 | `first_frame_started_callback`. 콜백 진입과 인자로 전달되는 노출 timestamp는 별개 |
| 1.5 | app launch 이름, 시작은 Activity onCreate | `activity_create_to_open`. 프로세스 시작과 Activity 재생성을 분리 |
| 2.2/2.4 | capture→image에 precapture 포함 | still 제출 기준은 precapture를 포함하지 않음. 필요하면 trigger→image를 별도 정의 |
| 2.3 | 이미지/결과 차이 = JPEG 인코딩 | 전달·스케줄링이 포함된 callback 차이이며 JPEG 내부 시간으로 단정 불가 |
| 2.5 | 10장, warm-up 제외 후 9개 간격 | 10장은 9개 간격. 첫 간격 제외 후 8개. 유효 9개는 11장 필요 |
| 3.1 | recording start latency | 첫 encoded frame과 구분한 camera callback 대리 지표. 시작 전 제출 요청 제외 정책 필요 |
| 3.2 | 간격 초과 = HAL drop | 관측 간격 이상으로 명명. 노출·요청 cadence·결과 누락을 원인 판정과 분리 |
| 3.3 | count 차이 = encoder drop | 향후 타임스탬프/경계/drain 검증 필요. MVP는 측정 불가 유지 |
| 3.6 | 파일 크기가 더 이상 변하지 않는 시각 | 관측 가능한 stop 정상 반환으로 종료. 파일 완전성 검사는 별도 |
| CTS | launch/multiple을 직접 대응 | YUV 대리 endpoint와 queue-empty 정책 차이를 명시 |

정정 근거와 공식 링크는 METRICS.md의 S1–S5에 연결했다. 기본값·복귀 기준 등 제품 정책은 검토 제안으로 표시했다.

## 기존 프리뷰와의 관계

현재 Camera2Engine의 preview는 TextureView이고 `onSurfaceTextureUpdated`에도 측정값을 넣지 않는다. 1.4의 `OnFrameAvailable` 계측과 반복 runner는 아직 없다. 따라서 기존 APK가 이 표의 launch 숫자를 제공한다고 보고하지 않는다.

## 이번 문서 검증

- 원문 보존 파일과 첨부 입력의 바이트 일치.
- 사용자 표의 21개 지표 ID와 MVP/다음/이후 분류 유지.
- JSON 예시 파싱, 설명용 표본의 p50/p95/min/max/n 계산 확인.
- 10장/11장에 따른 간격 수 산술 확인.
- APK SHA-256이 checkpoint-001 manifest와 동일함을 확인.

문서만 변경했으므로 Android 앱을 재빌드하거나 테스트를 반복하지 않았다. 기존 build/test/lint 결과는 checkpoint-001에 대한 결과다.
