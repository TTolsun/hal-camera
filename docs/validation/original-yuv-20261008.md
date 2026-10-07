# YUV 원본 추가 저장 검증

#177의 첫 단계인 Camera2 YUV 원본 추가 저장을 검증했습니다. RAW/DNG는 이번 변경에 포함하지 않으며 이 기록으로 #177 전체를 완료 처리하지 않습니다.

## 환경

- 기기는 Samsung Galaxy S25+ SM-S936N이며 Android 16입니다.
- 카메라 ID 0에서 YUV 640×480을 사용했습니다.
- 기존 앱을 삭제하지 않고 같은 release 인증서로 서명한 검증용 debug APK와 instrumentation APK를 설치했습니다. 앱 데이터는 유지했습니다.
- 검사 전 별도 `dev.halcamera.pr231` 앱이 카메라를 점유해 Camera error 2가 발생했습니다. 사용자 승인 후 해당 앱을 백그라운드로 보내고 다시 실행했습니다.

## 확인한 결과

| 검사 | 결과 |
| --- | --- |
| JVM | YUV plane 겹침·패딩·crop·buffer offset이 있는 샘플을 메타데이터의 offset/stride만으로 복원했습니다. 홀수·초과 크기·지원하지 않는 엔진·YUV off를 거절하고, 타임스탬프 확정과 완료 뒤 버퍼를 제거하는 경로를 검사했습니다. |
| 빌드·lint | `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`가 통과했습니다. |
| Camera2StillCapture 실기기 | 원본 저장 3회 뒤 옵션을 끈 기본 JPEG 쌍 촬영 1회가 성공했습니다. 각각 3개·2개 파일을 반환했습니다. |
| 파일 형식·촬영 결과 | ZIP을 열어 frame.nv21의 640×480×3/2 바이트 크기와 JSON 크기를 확인했습니다. JSON의 sensorTimestampNs·exposureTimeNs가 해당 촬영의 최종 CaptureResult와 일치했습니다. |
| UI | Live Streams에서 원본 옵션을 켜고 저장한 뒤 Live로 복귀했습니다. 셔터로 촬영하여 `HAL_20261008_012707_426_180a8d_YUV.zip`이 생성됐고, 설정에 다시 들어갔을 때 선택값이 유지됐습니다. |

기기 검사는 기존 MediaStore를 지우지 않으며 이번 검사 파일도 기기에 남겨 두었습니다. 테스트는 `am instrument -w -e original_yuv true dev.halcamera.test/dev.halcamera.cli.CliStoreInstrumentation`으로 별도 실행합니다. 카메라 권한이 허용되고 다른 앱이 해당 카메라를 사용하지 않아야 합니다.

## 남은 검증

실제 저장 공간 부족, 저장 도중 프로세스 강제 종료, 전원 차단, 카메라 변경과 촬영의 경합은 이 기록에서 실측하지 않았습니다. Android 10 미만 기기와 CameraX의 안내는 코드 분기만 확인했으며 해당 기기·엔진 UI 시험은 별도입니다. 다른 해상도·카메라의 스트림 조합도 검증하지 않았습니다. 파일 공개·정리의 한계는 [형식과 수명 계약](../design/ORIGINAL-YUV.md)에 있습니다.

## 설정 화면

![원본 NV21과 메타데이터 ZIP 추가 저장을 선택한 Live Streams 화면](assets/original-yuv-settings-20261008.png)
