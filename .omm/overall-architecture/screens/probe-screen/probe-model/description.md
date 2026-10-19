camera/CameraProbe.kt는 Probe의 순수 Kotlin 모델과 렌더러입니다. ProbeRow·ProbeSection·CameraProbeEntry·CameraProbeSnapshot이 카메라별 섹션 표를 담고, 물리 카메라는 "논리ID.물리ID" 키로 구분합니다. ProbeFormat이 값의 표기(크기·범위·enum 이름)를 통일하고, CameraProbeText가 TXT를, toJsonMap이 JSON을 만듭니다. 이 파일은 Android를 모르므로 JVM 테스트로 검증합니다.

camera/CameraProbeReader.kt만 CameraManager를 알고, 공개 카메라 ID와 논리 카메라의 physicalCameraIds를 순회하며 CameraCharacteristics를 섹션별로 읽어 스냅샷을 채웁니다. 섹션 하나가 예외로 실패해도 나머지를 계속 읽고 실패 목록에 남깁니다. SESSION KEYS와 MANDATORY STREAM COMBINATIONS는 API 28·29 이상에서만 읽고, STREAMS는 SCALER_STREAM_CONFIGURATION_MAP의 출력 형식마다 별도 섹션으로 만듭니다.

DepthCapabilities는 DEPTH_OUTPUT, DEPTH16 해상도와 DEPTH_DEPTH_IS_EXCLUSIVE를 독립적으로 읽습니다. DepthSupport는 DEPTH 섹션의 지원 여부·해상도·컬러 동시 요청 가능 여부를 만듭니다. null이나 읽기 실패는 Unknown 또는 Read error로 보존하고, HAL이 동시 요청을 허용해도 출력 조합을 별도로 확인해야 한다고 표시합니다. 단독으로 열 수 없는 물리 ID는 LIVE에서 직접 선택할 수 없다고 안내합니다. 같은 행을 화면·TXT·JSON에 사용합니다.
