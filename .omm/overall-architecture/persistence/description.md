BenchmarkReport는 실행·이벤트를 schema 5로 기록하고 schema 3·4·5를 읽습니다. BenchmarkStore와 BaselineManager는 실행 파일과 baseline 실행 목록을 관리합니다. SubjectPrefs는 직전 형상 정보를 기억하고 note는 재사용하지 않습니다. HistoryActivity는 JSON·CSV를 FileProvider로 공유합니다. 사진·동영상은 MediaLibrary가 MediaStore의 DCIM/HALCamera에 저장하며 앱 내부 파일과 구분합니다.

앱 내부 실행·baseline·기기 식별자와 CLI 요청은 자동 복원으로 다른 기기에 옮기지 않습니다. allowBackup=false를 유지하고 Android 12 이상에는 data_extraction_rules로 cloud backup과 device transfer에서 모든 앱 데이터 영역을 제외합니다. 사진·동영상의 공용 MediaStore 저장과 사용자의 명시적 내보내기는 별도입니다.
