CliArtifactRegistry는 저장된 결과의 URI를 한 번 읽어 크기와 SHA-256을 계산하고, CommandCoordinator가 그 목록을 요청 파일로 등록합니다. 실제 픽셀 저장은 MediaLibrary가 담당하며 진단 ZIP·벤치마크 보고서와 분리합니다. MediaStore에 저장된 촬영물은 RecentMediaThumbnail이 UI에서 조회합니다. terminal 요청의 늦은 콜백은 상태를 바꾸지 않으며 파일 다운로드는 등록된 URI만 허용합니다.

MultiSession의 onPhotoOutcomes/onVideoOutcomes는 카메라 ID별 저장 URI 또는 실패를 전달합니다. MultiController는 요청의 cameras 순서로 결과와 multi_<요청ID>_<카메라ID> 파일 이름을 만듭니다. 사진은 일부 성공을 보존하고 영상은 기존 묶음 롤백 뒤 결과를 전달합니다.
