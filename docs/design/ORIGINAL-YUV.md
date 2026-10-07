# Live YUV 원본 저장

Camera2의 Live Streams에서 **YUV 원본 추가 저장**을 켜면 사진마다 기존 JPEG와 원본 ZIP을 저장합니다. Android 10 이상에서 YUV 출력을 켠 경우에 사용할 수 있습니다. CameraX, 녹화 중 사진, Benchmark에는 적용하지 않습니다. RAW/DNG는 #177의 후속 작업으로 남아 있습니다.

## 사용 순서

1. Camera2 Live에서 크기 표시를 눌러 Live Streams를 엽니다.
2. YUV 해상도를 선택합니다. 가로·세로가 짝수이며 NV21 크기가 16 MiB 이하인 해상도만 원본 저장을 허용합니다.
3. `YUV 원본 추가 저장`에서 `NV21 + 메타데이터 ZIP`을 선택하고 저장합니다.
4. 사진을 촬영합니다. 저장 중에는 새 사진 요청을 받지 않습니다.
5. 파일 앱의 `Download/HALCamera/<촬영명>_YUV.zip`을 엽니다. 기존 JPEG는 `DCIM/HALCamera`에 있습니다.

이 옵션은 YUV 원본을 **추가** 저장합니다. JPEG를 원본 YUV라고 표시하지 않습니다. 일반 CLI 명령은 UI 설정을 이어받지 않으므로 이 옵션을 켜지 않습니다.

## ZIP 형식: schema 1

`frame.nv21`과 UTF-8 `metadata.json`으로 구성합니다. `frame.nv21`은 Image의 crop 영역에서 읽은 8비트 Y·U·V 샘플을 재배열한 값입니다. 원본 plane 전체 메모리 덤프가 아니며, 행 패딩과 crop 밖 픽셀은 포함하지 않습니다. JPEG 압축, 색 변환, 리사이즈, 회전은 적용하지 않습니다.

| 메타데이터 | 의미 |
| --- | --- |
| `width`, `height` | 저장된 crop 영역의 가로·세로 픽셀 수입니다. |
| `byteLength` | `width × height × 3 / 2`이며 파일 크기와 같아야 합니다. |
| `planes` | Y·U·V 각각의 파일 내 offset, rowStride, pixelStride, width, height를 나타냅니다. 단위는 offset·stride가 바이트이고 크기는 샘플 수입니다. |
| `source` | 원래 Image의 크기, crop, Y·U·V plane의 bufferPosition·rowStride·pixelStride를 기록합니다. 이 stride를 packed 파일에 적용하지 않습니다. |
| `rotationAppliedDegrees` | 항상 0입니다. 파일 픽셀은 회전하지 않습니다. |
| `capture` | 같은 센서 타임스탬프를 가진 최종 CaptureResult와 요청 식별자를 기록합니다. 없는 값은 null이며 0으로 대체하지 않습니다. |

Y plane은 offset 0, rowStride `width`, pixelStride 1입니다. V는 offset `width × height`, U는 그 다음 바이트부터 시작하며 둘 다 rowStride `width`, pixelStride 2, 크기 `width/2 × height/2`입니다. 각 plane의 `(x, y)` 샘플은 `file[offset + y × rowStride + x × pixelStride]`로 읽습니다. 출력 plane 설명만으로 샘플을 복원할 수 있습니다. YUV_420_888은 색 행렬·범위를 단독으로 확정하지 않으므로 메타데이터에 임의의 BT.601/BT.709 또는 full/limited range를 선언하지 않습니다.

`capture`에는 cameraId, requestId, requestTag, sensorTimestampNs, frameNumber, sensorTimestampSource, exposureTimeNs, sensitivityIso, frameDurationNs, aeState, jpegOrientationDegrees가 있습니다. UI 촬영은 requestId가 null일 수 있습니다. 시각은 나노초이며 sensorTimestampSource가 REALTIME인 경우에만 앱의 elapsedRealtimeNanos와 비교합니다. jpegOrientationDegrees는 JPEG에 요청한 방향이고 원본 NV21에 적용된 회전이 아닙니다.

## 수명과 실패 처리

이미지 콜백에서는 데이터를 복사하고 Image를 즉시 닫습니다. JPEG 변환과 ZIP 쓰기는 전용 mediaIo 실행기에서 합니다. 원본 저장을 선택하면 센서 타임스탬프가 확정되기 전에는 출력별 두 프레임만 보관하며, 확정 뒤 다른 시각의 데이터를 제거합니다. 한 요청의 저장이 끝나기 전에는 다음 요청을 받지 않습니다.

원본은 같은 시각의 최종 CaptureResult까지 기다립니다. 이미지나 결과가 누락되면 5초 타임아웃으로 실패하고 프리뷰 결과를 대신 사용하지 않습니다. 저장 전 카메라를 닫으면 요청을 실패로 끝내며, 이미 저장을 시작했으면 카메라와 무관하게 저장을 마칩니다.

MediaStore 항목은 pending 상태로 만든 뒤 파일 쓰기가 모두 성공하면 순서대로 공개합니다. 쓰기나 공개 중 오류가 발생하면 이번 요청에서 생성한 항목 모두의 삭제를 시도하고 실패 결과를 전달합니다. 공개와 정리는 여러 MediaStore 항목에 걸친 원자적 트랜잭션이 아니므로, 프로세스 강제 종료·전원 차단 또는 삭제 자체의 실패까지 완전 정리를 보장하지 않습니다. 기존 파일은 삭제하지 않습니다.

관련 구현과 동작은 [Camera2 가이드](../../guide/engine.md)에 있습니다.
