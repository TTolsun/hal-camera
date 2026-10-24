# App Bokeh 처리 계약

App Bokeh는 일반 Photo 촬영이 저장한 JPEG 한 장을 입력으로 사용한다. 제조사 확장 세션이나 센서 Depth 스트림을 요청하지 않는다. 앱의 `MainAppBokehBridge`가 `MediaCapture.capturePhoto` 완료 후 JPEG URI를 편집기에 전달한다.

App Bokeh 진입 시 Camera2의 일반 Preview와 촬영 JPEG 두 출력만 구성한다. 분석용 YUV 스트림은 끈다. 이미지 버퍼는 JPEG ImageReader로 받고 CaptureResult는 일반 촬영 메타데이터에만 사용한다. JPEG 디코딩 후 RGB 텐서를 만들고 모델 출력 Float 배열을 깊이 맵으로 사용하므로 HAL의 DEPTH16 버퍼와 관계없다.

## 처리와 저장

1. `AppBokehProcessor`가 EXIF 방향을 반영하고 긴 변을 최대 1600픽셀로 줄인다. 원본 파일은 수정하지 않는다.
2. RGB를 256×256으로 리사이즈하고 공식 Android 예제와 같은 `(channel - 115) / 58` 정규화를 적용한다.
3. LiteRT Interpreter의 CPU 스레드 4개로 MiDaS 상대 역깊이를 추론한다. 상·하위 2% 범위로 0–1 정규화한다. 값이 클수록 가까우며 실제 거리로 해석하지 않는다.
4. 사용자가 선택한 지점 주변 3×3 깊이의 중앙값을 기준으로 더 먼 영역에 연속적인 혼합 가중치를 부여한다. 전경 색이 배경으로 번지는 현상을 줄이기 위해 배경 가중치로 색을 정규화한 세 번의 분리형 box blur를 적용한다. 완전한 광학적 보케나 가려진 배경의 복원은 보장하지 않는다.
5. JPEG, 깊이 PNG, 원본 URI와 모델 해시를 포함한 메타데이터를 `MediaLibrary.transaction`으로 저장한다. 원본은 이미 독립적으로 저장되어 있어 처리·저장 실패에도 남는다. Benchmark/incident에는 픽셀을 추가하지 않는다.

모델 추론과 렌더·저장은 편집기 전용 worker에서 실행한다. 슬라이더 변경 시 이전 세대의 결과는 화면에 적용하지 않는다. Activity 종료 시 편집기를 닫고 후속 UI 갱신을 막는다. 화면 재진입 시 원본 URI로 다시 계산하며 편집 매개변수는 초기화한다.

## 모델과 재현 가능한 빌드

- 공식 배포: [MiDaS v2.1](https://github.com/isl-org/MiDaS/releases/tag/v2_1), `model_opt.tflite`, 66,338,288 bytes.
- SHA-256: `93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd`.
- 라이선스: MIT, Copyright (c) 2019 Intel ISL. 원문을 `app/src/main/assets/licenses/MiDaS.txt`로 APK에 포함한다.
- 전처리 근거: [공식 Android 예제](https://github.com/isl-org/MiDaS/blob/master/mobile/android/lib_support/src/main/java/org/tensorflow/lite/examples/classification/tflite/ClassifierFloatEfficientNet.java).
- 런타임: `com.google.ai.edge.litert:litert:1.4.2`, Interpreter API, CPU.

`prepareDepthModel`은 공식 배포에서 빌드 디렉터리로 모델을 받아 해시를 확인한 뒤 assets에 포함한다. 첫 빌드와 clean 이후 빌드에는 다운로드 연결이 필요하다. 모델 바이트는 Git에 커밋하지 않는다. 캐시 파일의 해시가 바뀌면 다시 검증된 모델을 준비한다. 실행 중에는 다운로드하지 않는다.

## 한계

깊이는 단일 이미지 추정이므로 인물·사물 모두 사용할 수 있지만 모든 장면의 정확성을 보장하지 않는다. 실시간 효과, 전경 흐림, 모델 학습, 센서 깊이와의 융합은 이 경로에서 수행하지 않는다. CPU 추론 시간과 메모리 사용은 기기에 따라 다르다. 강도 0의 픽셀 동일성은 방향 보정·축소 후의 처리용 원본을 기준으로 하며, JPEG 재인코딩까지 무손실이라는 뜻이 아니다.

사용 절차는 [Live 가이드](../../guide/live.md)를 참고한다.
