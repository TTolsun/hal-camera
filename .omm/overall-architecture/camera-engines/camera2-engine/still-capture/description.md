Camera2StillCapture는 Camera2Engine의 사진 촬영을 맡습니다. 엔진은 Host로 카메라·세션·특성, still request 생성, precapture trigger 전송을 넘겨주고, 이 클래스가 촬영 순서와 저장을 처리합니다.

모든 Live 사진은 최종 CaptureResult의 SENSOR_TIMESTAMP와 이미지 시각을 맞춘 뒤 노출 시간·ISO·프레임 주기·AE 상태·시각 소스·요청 식별자를 JSON에 저장합니다. 결과가 없으면 5초 타임아웃으로 실패합니다. StillPair는 시각 확정 전 출력별 두 프레임까지만 보관합니다. 저장 중에는 inFlight를 유지하며 이미 시작한 파일 쓰기는 카메라가 닫혀도 완료합니다.

Live 사진은 JPEG에서 선택한 YUV 또는 HAL JPEG와 선택한 RAW를 대상으로 합니다. YUV를 선택하면 mediaIo에서 encodeYuvStill로 JPEG로 변환하고 회전합니다. YUV가 분석용으로만 켜져 있으면 사진에 포함하지 않습니다. RAW는 같은 센서 시각의 이미지·최종 결과를 기다려 DNG로 저장합니다. RAW 단독도 허용합니다. MediaLibrary는 이미지와 JSON을 저장합니다. 벤치마크 still은 기존 JPEG 도착만 측정합니다.

플래시 Auto·On 사진은 AE_PRECAPTURE_TRIGGER_START를 preview capture 하나로 보낸 뒤, PrecaptureWatch가 이후 결과의 AE 상태가 PRECAPTURE를 지나 벗어날 때까지 기다리고 나서 still을 촬영합니다. 엔진의 LIVE 결과 콜백이 onLiveResult로 결과를 넘겨줍니다. AE 상태가 없으면 바로 촬영합니다. PRECAPTURE 없이 안정 상태가 보고되면 바로 믿지 않고, 안정 상태가 결과 3개 연속으로 이어질 때에만 PRECAPTURE를 건너뛰는 기기로 봅니다. 일부 HAL은 trigger 결과에 이전 상태를 적고 한두 프레임 뒤에 PRECAPTURE로 들어가므로, 첫 결과만 보고 촬영하면 사전 발광 측광을 건너뛰기 때문입니다. 3초 안에 끝나지 않으면 precapture_timeout을 기록하고 그대로 촬영하며, trigger를 보내지 못했으면 기다리지 않고 바로 촬영합니다. AE 잠금 중에는 노출이 이미 고정되어 있으므로 precapture 없이 촬영합니다. 카메라가 precapture 대기 중에 닫히면 close가 대기를 끝내 촬영 요청자에게 실패를 돌려줍니다.
