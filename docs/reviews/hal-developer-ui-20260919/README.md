# HAL 개발자 UI 검토 반영

2026-09-19에 Camera HAL 개발자의 관측·재현·비교 흐름을 기준으로 반영했습니다.

- Live 하단에 투명한 Mark ZIP 버튼을 고정했습니다. 상단·하단에 은은한 그라데이션을 적용하고 촬영 조작부부터 Mark까지 하단 음영을 연결해 색 경계를 없앴습니다. Benchmark는 메인 프리뷰에 표시하지 않고 도구 메뉴에서 엽니다. 진단 패널은 가용 높이의 60%를 사용하며 상단 프리뷰를 유지합니다. 그래프를 먼저 표시하고 상세 수치와 CLI 설정은 펼쳐 봅니다.
- 결과 화면은 회귀 지표를 먼저 표시합니다. 숫자 행은 가로 스크롤 없이 줄바꿈하며 max/p95와 delta, 판정 사유를 유지합니다. 전체 지표·실행 정보와 복사 기능을 제공합니다.
- Results에 두 실행 비교 버튼과 행별 작업 메뉴를 추가했습니다. 기준 선택 상태는 테두리로 표시합니다.
- CTS·Probe·Benchmark 시작 문구를 줄였습니다. CTS 공식 결과와의 구분, 중단 시 FAIL 처리, 측정 범위와 비교 조건은 유지했습니다.
- 반복 측정 비교에는 묶음별 기기·빌드·실행 수 요약을 추가했습니다. 기기 ID 안내는 펼쳐 보며 예외 경고는 해당 조건에서 표시합니다.

## 검증

- `assembleDebug testDebugUnitTest lintDebug`가 통과했습니다. 앱 테스트 395개와 CTS 모듈 테스트 12개가 통과했으며, 앱 lint 오류는 없고 경고 109개가 있습니다.
- 문서 최신성 8개를 코드와 대조하고 `.omm/`, 원고, 생성 가이드와 사이트를 갱신했습니다. `node tools/docgen/docflow.mjs check`의 6단계와 문서 도구 회귀 테스트 18개가 통과했습니다.
- 검토 기록의 reviewer는 `Codex (user-delegated code review)`입니다. 사용자의 진행 지시에 따른 코드 대조이며, 별도의 사람 검토나 추가 기기 실측을 뜻하지 않습니다.
- 결과 행 렌더러 `MetricRows`를 저장소 배치 규칙에 맞춰 `ui/`로 옮긴 뒤 빌드·단위 테스트·lint를 다시 통과했습니다.
- Galaxy S25+ (SM-S936N), Android 16에서 별도 패키지 `dev.halcamera.uireview`로 확인했습니다. 기존 `dev.halcamera`와 그 데이터는 변경하지 않았습니다.
- 실제 Benchmark 1회 완료, baseline 지정, 결과·이력 비교 진입, 두 실행 선택, 반복 측정 A 묶음 요약, Live·진단 패널 및 CTS·Probe 진입 화면을 확인했습니다.
- 회귀 강조는 실측 JSON의 Open 값을 100 ms 증가시킨 **합성 UI 샘플**로 확인했습니다. 샘플 이름은 `UI fixture - synthetic regression`이며 실제 성능 결과가 아닙니다. 임의 기준 비교에서는 delta만 표시되고 회귀 판정은 표시되지 않음을 확인했습니다.
- 화면 크기·글꼴 배율 전체 조합, TalkBack, CTS 테스트 자체의 재실행은 이번 검증 범위에 포함하지 않았습니다.

## 0.10.1 릴리즈 검증

- 서명된 release APK(versionCode 112)를 Galaxy S25+의 기존 dev.halcamera 위에 `adb install -r`로 설치했습니다. 서명 인증서가 기존 앱과 일치하며 최초 설치일은 2026-09-16으로 유지됩니다.
- 설치 후 0.10.1·112를 확인했습니다. 프리뷰에는 투명한 Mark ZIP만 표시되고 Benchmark는 도구 메뉴에서 접근합니다.
- `assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug lintRelease`가 통과했습니다. 앱·CTS 모듈 JVM 테스트 407개, PC CLI 테스트 23개, 문서 회귀 테스트 18개가 통과했습니다. Debug·Release 앱 lint는 각각 오류 0개, 경고 109개입니다.

## 화면

최종 릴리즈 화면은 [Live](live-release.png)와 [도구 메뉴](tools-release.png)에서 확인합니다. 아래 캡처는 최초 UI 검증 당시 화면으로, 이후 반영한 Mark 배경 투명화와 Benchmark의 도구 메뉴 이동 전입니다.

| 화면 | 기록 |
| --- | --- |
| 프리뷰와 진단 그래프 | [diagnostics-final.png](diagnostics-final.png) |
| 실제 Benchmark 결과 | [benchmark-result.png](benchmark-result.png) |
| 합성 샘플 회귀 강조 | [regression.png](regression.png) |
| CTS 선택 | [cts.png](cts.png) |
| Probe | [probe.png](probe.png) |
| 반복 측정 비교 | [profile-comparison.png](profile-comparison.png) |
