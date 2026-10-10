JPEG 선택은 앱 YUV 변환과 HAL JPEG 중 하나입니다. 각각 _YUV.jpg와 _JPEG.jpg를 DCIM/HALCamera에 저장하며 사진 모드에서는 촬영 JSON을 Download/HALCamera에 함께 저장합니다. NV21 파일 저장은 제거했습니다.

YuvPacking은 변환할 프레임의 crop과 stride를 반영해 인코더용 NV21 버퍼를 만듭니다. MediaLibrary는 선택한 이미지와 JSON 쓰기가 모두 성공한 뒤 공개하며 실패하면 이번 요청의 파일 삭제를 시도합니다. PhotoResult.artifacts는 실제 파일명과 MIME을 전달합니다. RAW는 RawFrame과 DngOutput을 사용합니다.

RAW/DNG를 켜면 RawFrame이 RAW_SENSOR 샘플을 행 패딩 없이 직접 버퍼로 복사하고, DngOutput이 저장 스레드에서 DngCreator로 `_RAW.dng`를 DCIM/HALCamera에 씁니다. DNG는 같은 센서 시각의 최종 CaptureResult를 사용하며 방향은 태그로만 기록합니다.
