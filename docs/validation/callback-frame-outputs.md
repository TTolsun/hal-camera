# Dual 사진 프레임과 CameraX Video 준비 실패 검증

2026-11-27에 Galaxy S25+ (SM-S936N, Android 17)를 무선 ADB로 연결했습니다. Depth/Bokeh 제거 커밋 `14139860`을 기반으로 수정한 디버그 APK를 별도 패키지에 설치하고, `callback_frames` 기기 검사를 세 차례 실행했습니다. 세 실행이 모두 통과했습니다.

| 검사 | 기기에서 확인한 결과 |
| --- | --- |
| Camera2 Dual, Hold 0초 | 촬영 전에는 두 Preview 행이 보였습니다. 셔터를 누르자 사진 프레임에 두 photo 행이 표시됐고, 이후 더 큰 프레임 번호와 두 Preview 행이 돌아왔습니다. |
| Camera2 Dual, Hold 5초 | 프리뷰 세션에서 다음 Shutter가 들어온 후에도 그래프는 촬영한 프레임 번호를 유지했습니다. 두 photo 행에 각각 도착값이 표시됐습니다. 만료 직전에도 같은 사진 프레임이었고, 만료 후에는 두 Preview 행이 표시됐습니다. |
| CameraX Video 준비 실패 | 기본 Preview·YUV·JPEG를 바인딩한 뒤 고정 코덱 H264를 지정해 CameraX의 Auto 코덱 검증에서 실패시켰습니다. 오류 이벤트와 함께 기본 출력 세 행이 표시됐고 YUV 행에 실제 도착값이 기록됐습니다. Recording 행은 생기지 않았습니다. |

검사는 `CliStoreInstrumentation`에서 `-e callback_frames true`로 실행합니다. 물리 카메라 두 개로 동시에 YUV 사진을 찍을 수 있는 기기가 필요합니다. 한 번 실행하면 Hold 설정별로 사진을 두 장씩 저장하고 원래 Hold 설정을 복구합니다. CameraX 검증은 영상 유스케이스를 바인딩하기 전의 준비 실패 경로를 대상으로 합니다.
