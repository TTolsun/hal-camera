# 녹화 중 사진 후속 검증 · 2026-10-05

**CameraX 1.6.2의 녹화 중 사진은 이 기기에서 영상 간격을 한 프레임만큼 늘립니다.** 후면 기본·전면·초광각에서 재현했습니다. 같은 기기의 Camera2 1080p 30fps에서는 사진 3장씩을 저장해도 해당 간격 증가가 없었습니다. 영상 연속성이 중요하면 검증한 Camera2 30fps 경로를 사용합니다. [#213](https://github.com/TTolsun/hal-camera/issues/213)은 이 동작을 알려진 제약으로 수용하는 결정으로 정리하며, 미검증 항목은 후속 이슈에서 추적합니다. 프레임 문제가 수정됐다는 의미는 아닙니다.

## 환경과 방법

- 기기는 Galaxy S25+(SM-S936N), Android 16(API 36)입니다. 설치된 앱은 0.19.0(versionCode 628)이며 이번 검증에서 APK를 교체하지 않았습니다. 소스 검토 기준은 `542f9f0`입니다. 설치 APK와 이 커밋의 바이너리 동일성은 확인하지 않았습니다.
- 기본 후면은 논리 ID 0, 전면은 ID 1, 초광각은 공개 ID 2를 사용했습니다. 후면 기본 화면에서는 실제 물리 ID 5가 관찰됐습니다. 카메라 선택 목록의 물리 ID 6을 독립 카메라처럼 열지 않았습니다.
- 모든 영상은 1920×1080입니다. 기본 비교는 30fps 요청, HEVC 비교는 60fps 요청입니다. 기본 후면 CLI 대조군은 무음 녹화이며, 화면에서 시작한 녹화의 오디오 조건은 통제하지 않았습니다.
- CLI로 카메라와 스트림을 준비한 뒤 화면에서 녹화를 시작했습니다. 사진 버튼을 약 2초 간격으로 세 번 누르고 정지했습니다. 사진 없는 CameraX 대조군은 CLI 녹화로 만들었습니다. 각 조건은 1회씩이며 통계적 반복 실험이 아닙니다.
- 원본 MP4와 JPEG는 로컬 `reviews/issue213/`에 보관했습니다. 저장소에는 픽셀 원본을 넣지 않고 [해시·크기·간격 분석 JSON](assets/video-snapshot-20261005/media-analysis.json)과 [분석 스크립트](assets/video-snapshot-20261005/analyze.mjs)를 남깁니다.

MP4의 `mdhd`, `stts`, `ctts`로 표시 시각을 복원하고 정렬한 뒤 이웃 프레임 간격을 계산했습니다. 긴 간격은 해당 파일 중앙값의 1.5배를 넘는 간격입니다. HEVC에는 `ctts`가 있으므로 디코드 순서의 `stts`만으로 판정하지 않았습니다. 이 수치는 저장 파일의 시각 정보이며 HAL 내부 프레임 손실이나 화면 표시 지연의 직접 측정이 아닙니다.

분석 스크립트는 녹화가 완료되어 `moov`와 sample table이 한 파일 안에 있는 비분할 MP4를 대상으로 합니다. 초기화 정보가 없는 미디어 조각이나 fragmented MP4는 지원하지 않으며 명시적인 오류로 종료합니다.

## 저장 결과와 영상 간격

| 조건 | 사진 저장 | 영상 프레임 수 | 간격 중앙값 / 최대값 | 긴 간격 |
| --- | --- | ---: | --- | --- |
| CameraX, 후면 0, 사진 없음 | 해당 없음 | 561 | 33.511 / 67.022ms | 시작 부분 1회 |
| CameraX, 후면 0, 사진 3장 | 3장, 기본 크기 4080×3060 | 1,110 | 33.511 / 67.022ms | 시작 부분 1회 + 촬영 구간 3회 |
| Camera2, 후면 0, 사진 3장 | 3장, 1920×1080 | 1,313 | 33.511 / 33.511ms | 0회 |
| Camera2, 전면 1, 사진 3장 | 3장, 1920×1080 | 1,477 | 33.356 / 33.367ms | 0회 |
| CameraX, 전면 1, 사진 3장 | 3장, 1920×1080 | 889 | 33.356 / 66.711ms | 4회 |
| Camera2, 초광각 2, 사진 3장 | 3장, 1920×1080 | 842 | 33.567 / 33.578ms | 0회 |
| CameraX, 초광각 2, 사진 3장 | 3장, 1920×1080 | 771 | 33.567 / 67.133ms | 4회 |
| Camera2, 후면 0, HEVC 60fps, 사진 3장 | 3장, 1920×1080 | 2,981 | 16.678 / 33.344ms | 6회 |

기본 후면 CameraX의 사진 없는 영상은 첫 프레임 간격만 67.022ms였습니다. 사진 3장을 찍은 영상에서는 같은 시작 간격 외에 30.964초, 33.277초, 35.354초에서 67.022ms 간격이 추가됐습니다. 세 사진 파일의 생성 시각 간격도 약 2.3초와 2.1초였습니다. 시작 부분의 긴 간격을 사진으로 인한 증가에 포함하지 않습니다.

HEVC 60fps 파일은 실제 코덱이 `hvc1`이고 평균 59.86fps였습니다. 긴 간격은 표시 시각 기준 3.084초, 12.154초, 20.324초, 24.992초, 29.444초, 35.512초에 있었습니다. 사진은 녹화 끝부분에서 눌렀으므로 이 여섯 간격을 사진 세 장에 대응하는 손실로 해석하지 않습니다. 이 조건에서 영상 연속성이 항상 보장된다고 판단할 근거도 없습니다.

## 정지와 겹치는 사진

같은 ADB 셸에서 `input tap`으로 사진 버튼을 누른 직후 별도의 대기 없이 정지 버튼을 눌렀습니다. 두 입력이 동시에 전달된 것은 아니며 정확한 요청 간격은 측정하지 않았습니다.

| 엔진 | 새 JPEG | 새 MP4 | 화면 복구 |
| --- | ---: | ---: | --- |
| CameraX, 후면 0 | 0개 | 1개 | Live 프리뷰로 돌아왔습니다. |
| Camera2, 후면 0 | 1개 | 1개 | Live 프리뷰로 돌아왔습니다. |

CameraX에서 정지와 겹친 사진이 저장되지 않는 경로를 확인했습니다. 이때 오류 안내 문구는 포착하지 못했으므로 실패 안내의 정확성까지 검증했다고 보지 않습니다. 이후 전면·초광각의 CameraX 녹화 중 사진이 각각 3장씩 저장됐으므로 다음 촬영을 영구히 막는 상태는 관찰하지 않았습니다.

초광각 CameraX 검증 뒤 후면 Camera2 프리뷰로 전환한 첫 CLI 요청은 `EXECUTION_TIMEOUT`으로 끝났습니다(요청 ID `b3aa3e01-bf21-49a9-9a19-4d20e7a34b41`). 같은 명령의 다음 요청은 성공했습니다. 사진 실패와의 인과관계는 확인하지 않았으며 정상 전환으로 합산하지 않습니다.

## 소스에서 확인한 차이

Google Maven의 공식 소스 JAR을 확인했습니다.

1. [camera-core 1.6.2](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/1.6.2/camera-core-1.6.2-sources.jar)의 `imagecapture/ImagePipeline.java#createCameraRequest`는 ImageCapture 파이프라인의 출력 surface를 사진 요청에 넣습니다. 이 앱의 일반 JPEG 경로에서는 VideoCapture 인코더 출력을 추가하지 않습니다.
2. [camera-camera2 1.6.2](https://dl.google.com/dl/android/maven2/androidx/camera/camera-camera2/1.6.2/camera-camera2-1.6.2-sources.jar)의 `adapter/CaptureConfigAdapter.kt#mapToRequest`는 위 surface 목록을 CameraPipe의 요청 stream 목록으로 바꿉니다. 같은 파일의 `getStillCaptureTemplate`은 녹화 템플릿에서 사진을 찍을 때 이미 `TEMPLATE_VIDEO_SNAPSHOT`을 선택합니다. 따라서 문제를 단순히 잘못된 사진 템플릿으로 설명하면 안 됩니다.
3. 앱의 `Camera2Engine.snapshotRequest`는 `TEMPLATE_VIDEO_SNAPSHOT`에 프리뷰, 인코더, JPEG 출력을 모두 넣습니다. 사진을 처리하는 프레임에도 인코더 출력이 포함된다는 점이 다릅니다.
4. [camera-camera2 1.7.0-alpha03](https://dl.google.com/dl/android/maven2/androidx/camera/camera-camera2/1.7.0-alpha03/camera-camera2-1.7.0-alpha03-sources.jar)의 adapter에도 같은 surface 매핑과 템플릿 선택이 있습니다. 알파 APK를 설치해 검증하지 않았으며 버전 변경만으로 해결된다고 결론내리지 않습니다.

사진 요청의 출력 구성 차이는 실측 간격 증가를 설명하는 유력한 근거입니다. 실제 CameraX 사진 요청의 target 목록을 기기에서 추적한 결과나 HAL 내부 추적은 아니므로, 다른 기기의 동작까지 단정하지 않습니다.

## 이번 결정과 남은 조건

현재 공개 ImageCapture API로 인코더 surface를 사진 요청에 추가하는 경로를 확인하지 못했습니다. `Camera2Interop` 콜백에서 CameraX가 소유한 세션에 직접 `capture`를 제출하는 방식은 해당 API의 문서가 CameraX 상태 보장을 무효화할 수 있는 사용으로 설명합니다. 이 검증에서는 내부 API나 직접 세션 제출로 우회하지 않고 CameraX 1.6.2의 제약으로 유지합니다. 영구적인 해결 불가능 판정은 아닙니다.

사진 기본 크기는 이번에 통일하지 않습니다. 기존에 사용자가 선택한 JPEG 크기를 유지하며, 엔진 기본 크기가 다르다는 사실을 기록합니다. 크기를 맞춘 전면·초광각 1920×1080에서도 CameraX의 긴 간격이 남았으므로 크기 통일을 프레임 문제의 해결책으로 취급하지 않습니다.

| 남은 조건 | 상태와 필요한 검증 |
| --- | --- |
| JPEG 포함 녹화 세션 거부와 fallback 안내 | 이번 조합은 모두 사진을 수락했습니다. 거부하는 기기나 결정론적 실패 주입이 필요합니다. JPEG를 수동으로 끄는 것은 세션 거부 검증의 대체가 아닙니다. |
| 저장 공간 부족 | 실기기 공간 부족과 저장 실패 안내는 미검증입니다. |
| 망원 | 물리 ID 6의 실제 영상 출력은 미검증입니다. 논리 카메라에서 줌을 바꿔도 어두운 환경에서는 기본 센서가 유지될 수 있으므로 물리 ID 관찰이 필요합니다. |
| 플래시를 켠 녹화 중 사진 | 미검증입니다. |
| CameraX 60fps, Camera2 HEVC 30fps 등 추가 조합 | 이번에 확인한 HEVC 60fps 한 조합으로 일반화하지 않습니다. |

2026년 10월 5일 결정에 따라 안내는 문서에만 제공하고 앱에 새 경고를 추가하지 않습니다. 세션 거부·저장 실패·정지 경합은 [#220](https://github.com/TTolsun/hal-camera/issues/220), 망원·플래시·추가 FPS 및 코덱 조합은 [#221](https://github.com/TTolsun/hal-camera/issues/221)로 분리했습니다. CameraX 내부 수정은 현재 계획하지 않으며, 향후 공식 API나 라이브러리 동작이 달라지면 다시 검토할 수 있습니다.

앱 소스 변경이 없으므로 새 JVM 테스트·Android 빌드는 수행하지 않았습니다. 위 결과는 실기기 검증으로만 분류하며, 미검증 조건을 통과로 처리하지 않습니다.

## 재현과 분석

1. [HAL CAM CLI 스킬](../../skills/halcam-cli/SKILL.md)에 따라 `hello`에서 권한과 잠금 상태를 확인하고 기기 내 `halcam` 스크립트를 준비합니다.
2. `preview --camera 1 --engine Camera2 --video-size 1920x1080 --video-fps 30 --codec H264`처럼 카메라를 준비합니다. CameraX에서는 `--engine CameraX --codec Auto`를 사용합니다. HEVC 60fps 조건은 `--camera 0 --codec HEVC --video-fps 60`입니다.
3. 화면의 Video 모드에서 녹화를 시작하고 사진 버튼을 세 번 누른 다음 정지합니다. 저장 파일을 ADB로 PC의 전용 디렉터리에 가져옵니다.
4. Node.js 24로 `node docs/validation/assets/video-snapshot-20261005/analyze.mjs <원본이_있는_디렉터리>`를 실행합니다. 해당 디렉터리에 `media-analysis.json`을 만들며 JPEG 크기, SHA-256, MP4 간격을 출력합니다.

다음으로 [CameraX 엔진 설명](../../guide/engine.md)을 확인하세요.
