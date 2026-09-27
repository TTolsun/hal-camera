Live 프리뷰를 짧게 터치하면 그 지점으로 초점(AF)을 옮기고, 길게 누르면 그 지점으로 노출(AE)을 맞춘 뒤 잠급니다(#168). 삼성 카메라의 사진 모드와 같은 조작입니다. FocusRing이 프리뷰 위의 투명한 View로 탭과 길게 누르기를 받고, 현재 엔진의 TouchMetering.meterAt을 호출한 뒤 결과를 그립니다.

- 짧게 터치: 모서리만 그린 사각형이 나타납니다. AF가 스캔하는 동안에는 흰색이고, 초점을 잡으면 초록색, 실패하면 빨간색입니다. 결과가 나온 뒤 5초가 지나면 AF가 전체 화면 기준으로 돌아가고 사각형이 사라집니다. AF 잠금이 켜져 있으면 사각형만 사라지고, 터치한 지점의 잠금은 AF 잠금을 풀 때까지 유지됩니다.
- 길게 누르기: 진동과 함께 원이 나타나고, 남아 있던 AF 사각형과 AF 지점은 끝납니다. AE가 그 지점에서 수렴하면 FocusRing이 LiveControlBar.setAeLock으로 AE 잠금을 겁니다. AE 버튼과 같은 제어 값을 쓰므로 AE 버튼에도 잠금이 표시됩니다. 잠금이 걸리면 원 위쪽의 틈에 자물쇠가 그려지고, 3초 뒤 원이 흐려집니다. 원은 다음 터치, 다음 길게 누르기, AE 버튼으로 잠금을 풀 때까지 남습니다. 길게 누르기는 AE 버튼으로 건 잠금이 있어도 먼저 풀고 다시 측광합니다. 잠긴 AE는 새 지점을 측광할 수 없기 때문입니다.
- 길게 눌러 잠근 상태에서 짧게 터치하면 AE 잠금을 풀고 새 지점에 AF를 맞춥니다. AE 버튼으로 건 잠금은 짧은 터치로 풀지 않습니다.

엔진이 거절하면(카메라 준비 전이거나 해당 영역을 지원하지 않는 경우) 누른 자리에 안내를 1.5초 동안 표시합니다. 고정 초점 카메라는 짧은 터치를 거절하고 길게 누르기만 받습니다. FocusRing은 프리뷰 호스트 안에 있으므로 카메라를 바꿀 때 프리뷰와 함께 새로 만들어지고, 표시마다 올리는 토큰으로 이전 터치의 늦은 콜백을 무시합니다. TalkBack에서는 화면 전체를 덮는 이름 없는 대상이 되지 않도록 접근성 트리에서 제외합니다.

좌표 변환은 두 단계입니다. 먼저 TextureView의 변환 행렬(fill-crop과 가로 화면 회전)을 역으로 적용해, 기기 기본 방향으로 세운 그림 안의 정규화 좌표를 얻습니다. 다음으로 TouchMeter.toSensor가 전면 카메라의 좌우 반전을 되돌린 뒤 SENSOR_ORIENTATION만큼 반시계 방향으로 돌려 센서 좌표로 바꿉니다. TouchMeter.region은 그 점을 중심으로 보이는 영역의 짧은 변의 1/6 크기 정사각형을 만듭니다. 16:9 프리뷰는 4:3 active array의 가운데 띠만 보여 주므로 점을 그 띠 안에 놓고, 사각형도 띠 안으로 제한합니다. CONTROL_ZOOM_RATIO를 쓰는 API 30 이상에서는 영역 좌표가 줌 이후 기준이어서 active array 전체가 줌된 화각이 되고, 그보다 낮은 API에서는 crop region이 기준입니다.

Camera2에서는 Camera2TouchFocus가 AF 지점과 AE 지점을 따로 가집니다. AF 지점은 repeating request의 AF 영역과 AUTO AF 모드가 되고, AF_TRIGGER_START를 capture 하나로 보냅니다. 연속 AF 모드는 이미 초점이 맞았다고 판단하면 이전 지점에서 바로 잠글 수 있으므로, 탭마다 새 스캔을 시작하는 AUTO 모드를 씁니다. 결과 판정은 TouchFocusWatch가 합니다. 탭마다 세대 번호를 올리고, 그 탭의 trigger capture가 끝난 뒤의 결과만 읽습니다. trigger 이전에 이미 전송 중이던 repeating 결과에 예전 잠금의 FOCUSED_LOCKED가 남아 있을 수 있기 때문입니다. FOCUSED_LOCKED는 성공, NOT_FOCUSED_LOCKED는 실패이고, AF 상태가 없는 기기는 바로 성공으로 봅니다. 3초 안에 결과가 없으면 실패로 처리합니다. 유지 시간이 끝나면 AF 영역을 지우고 연속 AF로 돌아가며 AF_TRIGGER_CANCEL을 보냅니다.

AE 지점은 repeating request의 AE 영역이 됩니다. TouchExposureWatch가 AE 상태가 CONVERGED·FLASH_REQUIRED로 결과 2개 연속 이어지면 측광이 끝난 것으로 봅니다. AeRelock과 같은 규칙이며, 새 영역의 첫 결과가 이전 영역의 CONVERGED를 그대로 담고 있을 수 있기 때문입니다. AE 상태가 없는 기기는 바로, 2초 안에 수렴하지 않으면 그 시점에 측광이 끝난 것으로 봅니다. 녹화 시작·정지로 세션이 바뀌면 AF 지점은 버리지만 AE 지점은 남기므로, AeRelock이 같은 지점을 다시 측광한 뒤 잠급니다.

CameraX에서는 PreviewView.meteringPointFactory가 좌표를 변환합니다. 짧은 터치는 FocusMeteringAction(AF, 5초 뒤 자동 취소), 길게 누르기는 FocusMeteringAction(AE, 자동 취소 없음)으로 처리합니다. CameraX 경로에는 AE 잠금이 없으므로 길게 누르기는 측광만 하고 자물쇠를 그리지 않습니다. 새 터치가 이전 요청을 취소하면 이전 요청은 결과를 보고하지 않습니다.

Telemetry의 request_observed에는 요청한 afRegions·aeRegions가, capture_result에는 HAL이 적용한 afRegions·aeRegions가 기록됩니다. 엔진은 touch_meter(kind AF·AE, 세대, 정규화 좌표, 영역), touch_af_trigger, touch_meter_result(FOCUSED·FAILED·METERED), touch_meter_end 이벤트를 남깁니다. 삼성 카메라의 길게 누르기 원 아래에 있는 밝기 슬라이더는 아직 없으며, 노출 보정은 EV 버튼으로 합니다.
