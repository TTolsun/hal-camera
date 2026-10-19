# PROBE와 LIVE Depth 검증

2026-10-20에 PROBE의 Depth 사양 표시와 LIVE의 DEPTH16 보기 코드를 검증했습니다. 실제 Depth 센서에서 프레임을 받은 결과는 포함하지 않습니다.

## 자동 검증

- `testDebugUnitTest`: 앱 JVM 테스트 907개가 통과했습니다. Depth 신뢰도 코드, 거리 0, stride·crop·버퍼 위치, 잘린 plane, 표시 표본 제한, 지원 여부와 Unknown 구분, TXT·JSON 내보내기와 Depth 스트림 표시를 포함합니다.
- `lintDebug`: 오류와 경고가 없습니다.
- `assembleDebug`, `assembleDebugAndroidTest`: APK를 생성했습니다.
- 문서 회귀 검사 37개가 모두 통과했습니다. 새 요소 수 112개, 입력 크기 한도, dry-run, 앱 UI 규칙 사본 일치와 배포 링크를 포함합니다.
- 사용자 요청에 따라 코드를 대조하고 원본 최신성 15개 항목의 검토 기록을 `codex-user-authorized-depth-review`로 갱신했습니다. 기존 최신 항목의 검토자는 보존했습니다. 전체 `docflow check --build` 6단계와 coverage·생성 일치·사이트 산출물 일치 검사를 통과했습니다.

## 에뮬레이터 확인

기기는 `sdk_gphone64_x86_64`이며 Android 16 / API 36입니다. 기존 앱 데이터를 삭제하지 않고 서명이 일치하는 로컬 빌드로 갱신했습니다.

1. LIVE에서 `Depth`를 눌러 미지원 카메라의 이유와 비활성화된 해상도 항목을 확인했습니다.
2. `Lab → Probe`에서 `Depth`를 검색해 지원 여부·해상도·컬러 동시 요청 가능 여부가 검색 결과에 포함되는지 확인했습니다.
3. `depth_checks=true` instrumentation으로 합성 DEPTH16을 해석하고, 중앙 거리 1.000 m·유효 표본 비율·맵 표시와 접근성 설명을 확인했습니다. 검증용 testApplicationId는 `dev.halcamera.depthchecks`로 지정했습니다. 결과는 `DEPTH_MAP_CHECKS_OK`입니다.

| 화면 | 확인 자료 |
| --- | --- |
| PROBE 검색 | [Depth 사양](probe-depth.png) |
| LIVE 미지원 안내 | [Depth unavailable](depth-unavailable.png) |
| 합성 데이터 렌더링 | [Depth map synthetic](depth-map-synthetic.png) |

## 변경사항 리뷰

Depth 지원 선언과 Unknown 처리, confidence 비트·stride·crop 해석, UI 전달 대기 제한, 세션 실패와 close(done) 순서, 늦은 프레임 차단, Photo·Video·CLI 전환 및 픽셀 저장 경계를 대조했습니다. 코드 리뷰에서 남은 기능 수정 사항은 없습니다. 문서에는 Depth의 별도 엔진·촬영 비활성화·줌 제한이 일부 빠져 있어 엔진 비교·데이터 흐름·수명주기·줌 원본에 반영했습니다.

이 검토는 사용자 요청에 따른 Codex의 코드 대조 기록입니다. 실제 Depth 센서 실측이나 별도 사람의 독립 코드 리뷰를 뜻하지 않습니다.

## SM-S936N 실기기 확인

2026-10-20에 Samsung SM-S936N, Android 16 / API 36에서 앱 0.30.0(691)을 기존 데이터 삭제 없이 갱신했습니다. 아래 결과는 에뮬레이터 결과와 별개입니다.

PROBE 요청 `0be1d193-a8ac-4ee0-8c88-f9bec825c01b`는 `succeeded`로 끝났으며 메타데이터 읽기 오류가 없었습니다. JSON 367,246바이트와 TXT 261,858바이트를 PC로 회수하고 manifest의 SHA-256과 일치하는지 확인했습니다. [Depth 항목 발췌와 원본 해시](sm-s936n-depth-summary.json)를 보존했습니다.

| 카메라 ID | 구분 | DEPTH_OUTPUT | DEPTH16 해상도 | 컬러 동시 요청 |
| --- | --- | --- | --- | --- |
| 0 | 후면 Wide, 공개 ID | 미지원 | 없음 | 해당 없음 |
| 1 | 전면 25 mm, 공개 ID | 미지원 | 없음 | 해당 없음 |
| 2 | 후면 UWide, 공개 ID | 미지원 | 없음 | 해당 없음 |
| 3 | 전면 30 mm, 공개 ID | 미지원 | 없음 | 해당 없음 |
| 5 | 카메라 0의 물리 Wide | 미지원 | 없음 | 해당 없음 |
| 6 | 카메라 0의 물리 Tele | 미지원 | 없음 | 해당 없음 |

이 기기에서 앱에 공개된 Camera2 Depth 스트림은 없습니다. 실제 Depth 센서의 프레임 수신이나 컬러·Depth 조합 검증 결과로 해석하면 안 됩니다.

실기기에서 `depth_checks=true` instrumentation도 `DEPTH_MAP_CHECKS_OK`로 통과했습니다. 합성 DEPTH16의 stride 해석, 중앙 거리 1.000 m, Android View 렌더링 및 접근성 설명을 확인한 결과이며 센서 출력 검증은 아닙니다.

잠금 해제 후 실기기 UI에서 다음을 확인했습니다.

1. 공개 카메라 0·1·2·3 모두 컬러 프리뷰가 준비됐고, `Depth unavailable`과 `This camera does not advertise DEPTH_OUTPUT.` 안내가 표시됐습니다. 미지원 항목을 눌러도 Depth 세션을 시작하지 않았습니다.
2. PROBE에서 `Depth` 검색 시 지원 여부·해상도·컬러 동시 요청·LIVE 안내가 검색됐으며, 검색 결과를 눌러 실제 Depth 사양 섹션으로 이동했습니다.
3. Video에서 `Depth → Off · Return to Photo`를 선택해도 Video에 남는 문제를 발견했습니다. Video에서 Off를 현재 선택으로 표시하지 않도록 고치고, 같은 Depth 크기라는 이유로 Photo 복귀를 건너뛰지 않도록 수정했습니다.
4. 수정한 APK를 재설치한 뒤 `Video → Depth → Off → Photo`를 반복해 Photo 선택과 `Live, preview running`을 확인했습니다. 홈 이동 후 복귀에서도 프리뷰가 다시 준비됐습니다.
5. 회전 설정을 가로로 바꿔도 LIVE는 manifest의 기존 `portrait` 설정대로 세로 화면을 유지했습니다. 검증 후 기기의 원래 `lock 0` 설정을 복원했습니다. 실제 Depth 맵의 센서 방향 보정 검증은 아닙니다.

수정 후 앱 JVM 테스트 907개와 `lintDebug`(오류·경고 없음), `assembleDebug`가 통과했습니다. 최종 앱 프로세스 로그에서 앱 충돌이나 카메라 세션 실패는 관측하지 않았습니다. `ashmem`의 pinning 폐기 안내와 Adreno/QSPM·`libpenguin.so` 관련 플랫폼 로그는 있었습니다.

## 실제 기기에서 남은 확인

DEPTH_OUTPUT 지원 기기에서 해상도별 세션 구성과 지속 수신, 컬러 보기와의 반복 전환, 백그라운드 복귀와 회전, 카메라 해제 완료를 확인해야 합니다. Depth 전용 세션이므로 컬러와 Depth를 같은 요청으로 받는 조합이나 Bokeh 합성은 이번 검증 범위에 포함하지 않습니다.

사용 방법은 [Live 가이드](../../../guide/live.md#depth-맵을-보세요)를 참고하세요.
