`app/src/main/java/dev/halcamera/camera/CameraEndpointResolver.kt`(65줄)는 `CameraManager`를 공개 API만으로 endpoint 목록으로 바꿉니다.

`cameraIdList`를 읽고 ID마다 특성을 가져온 뒤, 후면 역할을 한꺼번에 계산해(`dedupeMain`이 후면 카메라 전체를 보도록) 논리 ID마다 endpoint 하나를 냅니다. API 28 이상에서는 `physicalCameraIds` 항목마다 endpoint를 하나 더 내되, 그 물리 ID가 공개 `cameraIdList`에도 없으면 `independentlyOpenable = false`로 표시합니다.

숨겨진 ID는 절대 탐색하지 않는다는 규칙이 여기서 강제됩니다. 논리 카메라 뒤의 물리 카메라는 존재를 보고할 수 있도록 **목록에는 올리지만** 앱은 열지 않으며, `BenchmarkActivity`도 `independentlyOpenable`인 endpoint만 후보로 씁니다. 모든 특성 조회는 try/catch로 감싸 null을 돌려줍니다. 제조사가 ID를 노출해 놓고 특성 조회는 거부하는 경우가 있기 때문입니다.
