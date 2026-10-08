# YUV 저장 포맷과 촬영 메타데이터

Live Streams의 **YUV Save Format**에서 기존 JPEG 또는 NV21을 선택합니다. 기본값은 JPEG입니다. 사진 모드는 포맷과 관계없이 촬영 메타데이터 JSON을 함께 저장합니다.

| 선택 | YUV 파일 | 저장 위치 |
| --- | --- | --- |
| JPEG | `<촬영명>_YUV.jpg` | DCIM/HALCamera |
| NV21 | `<촬영명>_YUV.nv21` | Download/HALCamera |
| 공통 메타데이터 | `<촬영명>_metadata.json` | Download/HALCamera |

YUV 파일은 선택한 포맷 하나만 만듭니다. 별도 JPEG 출력을 켜면 카메라가 생성한 `<촬영명>_JPEG.jpg`도 DCIM/HALCamera에 저장합니다. YUV 출력이 꺼져 있어도 JPEG 촬영의 JSON은 저장합니다. JPEG는 갤러리에서, NV21과 JSON은 파일 앱에서 확인합니다. 파일명 앞부분으로 같은 촬영을 연결합니다.

NV21은 Camera2에서만 지원하며 가로·세로가 짝수이고 프레임당 16 MiB 이하여야 합니다. CameraX는 JPEG와 JSON을 저장합니다. 녹화 중 JPEG snapshot과 Benchmark는 이 사진 모드 계약에 포함하지 않습니다. 일반 CLI 촬영은 UI 설정을 이어받지 않으므로 기본 JPEG 포맷과 JSON을 사용합니다. RAW/DNG는 아래 RAW/DNG 절을 참고합니다.

## JSON 형식: schema 1

UTF-8 JSON의 최상위 필드는 `schema`, `capture`, `outputs`입니다. `outputs`는 이미지 파일별 실제 `file`, `mime`, `byteLength`, `format`을 기록하며 JSON 파일 자체는 포함하지 않습니다.

Camera2의 `capture`는 이미지와 같은 SENSOR_TIMESTAMP를 가진 최종 CaptureResult에서 cameraId, requestId, requestTag, sensorTimestampNs, frameNumber, sensorTimestampSource, exposureTimeNs, sensitivityIso, frameDurationNs, aeState, jpegOrientationDegrees를 기록합니다. 없는 값은 null입니다. 센서 시각은 timestamp source가 REALTIME일 때만 앱의 elapsedRealtimeNanos와 비교합니다.

CameraX는 JPEG와 가장 가까운 analysis 프레임을 짝지으므로 `yuvSensorTimestampNs`, `yuvOffsetNs`, `yuvCapture`를 구분합니다. 기록된 결과 중 각 이미지와 센서 시각이 일치하는 결과만 사용합니다. 결과가 없으면 `resultStatus: unavailable`과 null 값을 저장하며 다른 프레임의 노출 값으로 대체하지 않습니다.

## NV21 배치

NV21은 Image의 crop 영역에서 읽은 8비트 Y·U·V 샘플을 재배열한 파일입니다. 패딩과 crop 밖 픽셀은 포함하지 않으며 압축·색 변환·리사이즈·회전을 적용하지 않습니다. JPEG 선택 시에는 기존 압축과 방향 보정을 적용합니다.

NV21 출력 항목에는 width, height, byteLength, planes, source, rotationAppliedDegrees를 기록합니다. `source`는 원래 이미지 크기·crop·plane의 bufferPosition/rowStride/pixelStride입니다. `planes`는 저장된 파일의 배치이므로 원본 stride와 구분합니다.

Y는 offset 0, rowStride width, pixelStride 1입니다. V는 offset width×height, U는 그 다음 바이트에서 시작하며 rowStride width, pixelStride 2입니다. 각 plane의 샘플은 `file[offset + y × rowStride + x × pixelStride]`로 복원합니다. 파일 크기는 width×height×3/2이며 회전은 0입니다. 색 행렬과 full/limited range는 임의로 선언하지 않습니다.

## RAW/DNG

Live Streams의 **RAW (DNG)**에서 RAW_SENSOR 크기를 고르면 `<촬영명>_RAW.dng`를 DCIM/HALCamera에 함께 저장합니다. 기본값은 Off이며 Camera2와 RAW capability가 있는 카메라에서만 선택할 수 있습니다. CameraX에서 RAW를 요청하면 구성을 거절하고 Camera2로 전환하라고 안내합니다.

RAW 프레임은 이미지 콜백에서 행 패딩을 뺀 16비트 샘플로 직접 버퍼에 복사하고 Image를 바로 닫습니다. 저장 스레드에서 `DngCreator`가 카메라 특성과 같은 SENSOR_TIMESTAMP의 최종 CaptureResult로 DNG를 씁니다. 화면 방향은 DNG 방향 태그로만 기록하고 샘플은 회전하지 않습니다. JSON의 DNG 항목에는 width, height, sensorTimestampNs, orientationDegrees, cfaArrangement, whiteLevel, blackLevelPattern을 기록합니다. whiteLevel과 blackLevelPattern은 카메라 특성의 고정값입니다. 카메라가 프레임별 값을 보고하면 DngCreator는 그 값을 DNG에 쓰므로, 같은 값을 dynamicBlackLevel과 dynamicWhiteLevel에도 기록합니다(Android 9 이상). Live 상단 스트림 표시에는 RAW 크기가 함께 나옵니다. RAW reader 버퍼는 두 개이며 벤치마크·녹화 세션에는 RAW 출력을 넣지 않습니다.

## 수명과 실패 처리

이미지 콜백은 데이터를 복사하고 Image를 닫습니다. JPEG 변환과 파일 쓰기는 mediaIo에서 처리합니다. Camera2는 센서 시각 확정 전 출력별 최대 두 프레임을 보관하고, 확정 뒤 다른 시각의 버퍼를 제거합니다. 이미지나 최종 결과가 누락되면 5초 뒤 실패합니다. 저장 완료 전에는 새 촬영을 받지 않습니다.

저장 전에 카메라를 닫으면 요청을 실패로 끝내고, 이미 시작한 파일 쓰기는 완료합니다. Android 10 이상에서는 모든 파일 쓰기가 성공한 뒤 MediaStore pending을 순서대로 해제합니다. 오류가 발생하면 이번 요청에서 만든 항목의 삭제를 시도합니다. 여러 항목의 공개는 원자적 트랜잭션이 아니므로 강제 종료·전원 차단·삭제 실패까지 완전 정리를 보장하지 않습니다.
