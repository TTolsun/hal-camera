# UI consistency follow-up · 2026-10-09

## Changes

- Dual keeps the screen on while streaming, and releases it when leaving or stopping.
- Dual Live Streams shows the actual engine, physical pair, preview size and fixed recording output. Direct size-label navigation and Lab use the same read-only screen. Unsupported single-camera controls and Apply are absent.
- The Dual top control chevron is disabled instead of duplicating camera selection. The lower camera button still selects the pair. Metrics still opens diagnostics.
- Manual keeps its header and parameter tabs stationary; only the editor body scrolls.
- CameraX Manual is disabled before entry, with an accessible unsupported label.

## Local checks

- JDK 17: testDebugUnitTest (638 tests, zero failures/errors), lintDebug and signed assembleRelease passed.
- Documentation regression/workflow checks: 21 passed. docflow check --build passed all six stages.

## Device checks

SM-S936N, Android 16 / API 36, 1440×3120, font scale 1.15. Existing app data retained by reinstalling with the same release signature; local verification versionCode 642.

- Camera2 Photo: opened Manual, changed Auto to Manual, selected ISO, Shutter, Focus and WB. All tab centers remained y=1093; shutter remained (720,2548). Reset and Hide worked.
- CameraX Photo: Manual was dimmed; tapping it did not open a panel.
- CameraX Dual V: disabled upper chevron did not open camera selection. Size label and Lab → Live Streams showed CameraX, physical IDs 5/6 and 1280×720, without RAW/NV21 or Apply.
- Screen timeout temporarily changed from 600000 to 15000 ms. CameraX Dual V remained Awake and recording at 28 seconds without another touch. Recording was stopped, and timeout restored to 600000 ms. dumpsys window attributed the screen hold to DualPreviewActivity; leaving for Live Streams cleared the hold.
- Final APK: Camera2 Dual V showed 1440×1080 and CameraX Dual V showed fixed 1280×720. Both returned to preview after closing Live Streams.

Screenshots remain local in the review directory; camera pixels are not included in this repository. Full CTS, long Benchmark, landscape and TalkBack execution were not part of this UI follow-up.
