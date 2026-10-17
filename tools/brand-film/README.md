# HAL CAM 60초 소개 영상

`HALCamera60`은 기존 v8의 0–40초를 유지하고 Automation을 18초로 확장한 뒤 2초 브랜드 엔딩으로 마무리합니다. 1920×1080, 24fps, 1,440프레임입니다.

`HALCameraMobile60`은 1080×1920 세로형이며 동일하게 60초입니다. 실제 앱 캡처를 확대해 관련 영역을 보여주고, CLI와 그 실행 결과인 앱 화면을 별도 장면으로 번갈아 배치합니다. 주요 제목은 84px, 명령과 설명은 56px입니다. 305px 폭의 플레이어에서는 각각 약 24px, 16px로 표시됩니다. 원본 UI의 작은 세부 글자까지 모두 같은 크기로 확대하는 구성은 아닙니다.

모바일 구성은 도입부 0–10초, Callback·Probe·CTS·Benchmark 10–26초, 원본 PIP·Multi 26–34초, 요청·스킬 읽기·CLI/앱 교대 34–58초, 앱 전체를 소개하는 엔딩 58–60초입니다. 원본 촬영 장면, 실제 앱 UI, 음악은 기존 영상의 자산을 재사용합니다. 첫 Shell 명령 이후 명령 인자는 ADB 접두어를 생략한 발췌입니다.

홈페이지는 최초 진입 때 700px 이하 화면 또는 1100px 이하 터치 화면에서 세로형을 선택합니다. 재생 중 회전·크기 변경에는 소스를 바꾸지 않아 재생 위치가 유지됩니다. PC와 JavaScript 미지원 환경에서는 승인된 v13 가로형을 사용합니다. 두 버전 모두 명시적 재생 전에는 MP4를 미리 내려받지 않습니다.

모바일 출력:

```sh
npx remotion render HALCameraMobile60 out/camera-hal-mobile-v1.mp4 --codec=h264 --crf=16
ffmpeg -i out/camera-hal-mobile-v1.mp4 -t 60 -c:v copy -c:a aac -b:a 192k -movflags +faststart ../../guide/assets/media/camera-hal-mobile-v1.mp4
```

## 미리보기와 출력

이 디렉터리에서 Node.js 24로 실행합니다.

```sh
npm ci
npm run dev
npm run lint
npx remotion render HALCamera60 out/camera-hal-automation-v13.mp4 --codec=h264 --crf=14
ffmpeg -i out/camera-hal-automation-v13.mp4 -t 60 -c:v copy -c:a aac -b:a 192k -movflags +faststart ../../guide/assets/media/camera-hal-automation-v13.mp4
```

`Automation18`은 확장 장면만 확인하는 컴포지션입니다. 공용 미디어 경로는 `../../guide/assets`이며 원본 v8 MP4와 기존 스크린샷을 참조합니다. 출력은 공용 경로 밖의 `out/`에 만듭니다. FFmpeg 단계는 AAC 패딩까지 포함한 최종 길이를 정확히 60초로 맞추고 웹 재생용 메타데이터를 파일 앞으로 옮깁니다. 최종 MP4를 검토한 뒤 저장소 루트에서 `node tools/docgen/docflow.mjs site build`로 홈페이지를 재생성합니다.

## Automation 구성

| 전체 영상 시각 | 내용 |
|---|---|
| 40–45초 | 전체 화면의 AI agent 작업 기록에서 사용자 요청, `skills/halcam-cli/SKILL.md` 읽기, Shell 도구의 ADB 명령 실행을 차례로 보여줍니다. |
| 45–47초 | 큰 CLI 실행창의 `preview --camera 0`과 실제 Photo 화면을 함께 보여줍니다. |
| 47–49초 | CLI의 JPEG 설정 명령과 실제 Live Streams 구성을 보여줍니다. |
| 49–51.5초 | CLI 명령에 맞춰 실제 Photo·Video 화면을 전환합니다. |
| 51.5–56초 | Probe·Custom CTS 실행, AOSP 케이스 목록, Benchmark 명령과 화면을 연결합니다. |
| 56–58초 | AI agent가 결과 파일을 회수하는 흐름을 보여줍니다. |
| 58–60초 | 브랜드와 “촬영부터 분석·검증까지.”로 마무리합니다. |

## 사실과 연출의 경계

명령과 옵션은 `skills/halcam-cli/SKILL.md`와 `app/src/main/assets/halcam.sh`의 계약을 기준으로 합니다. 화면의 명령은 광고용 발췌이며, 실제 실행에서는 `adb -s SERIAL shell sh /data/local/tmp/halcam` 접두어를 사용합니다. Custom CTS 예시는 가이드의 `custom:fast_on_off` 키를 사용하고, AOSP 장면은 `cts cases`로 목록을 조회합니다. `REQUEST_ID`는 실행 시 받은 실제 ID로 대체합니다. 앱의 자체 자연어 처리 기능을 뜻하지 않으며, 스킬을 사용하는 외부 에이전트가 CLI를 실행합니다.

카메라·설정 UI는 1440×3120 실제 앱 캡처를 사용하며 버튼·탭·상태 문자열을 다시 그리지 않습니다. 영상 전체의 센서 프리뷰에는 앞 15초에 나온 피아노·프리즘·물결·석양 장면만 사용합니다. 기존 Callback·PIP·Multi Device의 프리뷰는 이미 해당 장면으로 구성되어 있습니다. CTS와 Benchmark에 남아 있던 검은 화면·다른 촬영 장면 및 Automation의 프리뷰는 공통 소스로 교체합니다. 도구 화면도 기존 저장소 스크린샷입니다. 실제 기기에서 새로 녹화하거나 측정한 결과가 아닙니다. 지원 스트림 조회, CTS 케이스 선택, 각 작업의 완료 대기와 파일 회수는 실제로 필요하지만 광고에서는 시간을 압축했습니다.

음악은 v8의 40–46초, 32–40초, 32–40초 오디오를 1초씩 크로스페이드해 20초로 연결했습니다. 마지막 2초에는 페이드아웃을 적용했습니다. 추가 음원은 사용하지 않습니다.

중간 프레임은 무손실 PNG로 렌더링하고 최종 H.264는 CRF 14로 출력합니다. 캡처는 CSS 확대 후 다시 축소하지 않고 최종 표시 크기로 직접 리샘플링합니다.

공통 소스 `opening-sensor.mp4`는 v8의 첫 7초와 제목·앱 UI가 없는 원본 석양 5초를 연결한 12초 루프입니다. `SensorPreview.tsx`는 실제 프리뷰 사각형에만 이를 합성하며, 앞부분의 CTS·Benchmark에서는 기존 화면 이동의 타이밍·이징·가림 영역을 함께 적용합니다. `AutomationTail20`은 마지막 20초를 따로 검토하는 용도입니다. 최종 출력에는 앞부분의 프리뷰 교체도 필요하므로 반드시 `HALCamera60` 전체를 렌더링합니다.
