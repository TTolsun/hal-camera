BenchmarkReport는 files/benchmarks/<runId>.json에 schema 4를 기록하고 schema 3·4를 읽습니다. profile, 측정 계약, 기기·앱·subject·환경, validity, 지표와 원시 이벤트를 저장합니다. files/incidents/의 ZIP은 별도의 incident 기록입니다. 현재 앱은 v0.2 HealthReport를 작성하지 않습니다.

사진·동영상은 앱 내부 실행 JSON과 별도로 DCIM/HALCamera 앨범에 저장합니다. Android 10 이상에서는 IS_PENDING을 사용하고, 사진 쌍 저장 중 실패하면 생성한 두 항목을 삭제합니다.

새 실행에는 raw.device_instance_id가 선택적으로 추가됩니다. 외부 schema 3·4 JSON은 ProfileArchive가 별도 files/profile-imports/<sha256>.json에 원문을 보관합니다. 같은 바이트는 중복으로 처리하고 run_id가 같은 다른 바이트는 별도 원본으로 보관합니다. 이 파일은 BenchmarkStore와 baseline 인덱스에 넣지 않습니다.
