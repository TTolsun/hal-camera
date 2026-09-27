# Callback single-line layout review

The top bar gave each of three columns one third of its width, squeezing Tools and Callback into the trailing column. The expander now occupies 48dp, and the two side columns share the remaining width equally. The expander stays centred. Button text remains 12sp; top-bar labels use one line and a minimum 48dp touch width.

## Device validation — 2026-09-27

- Galaxy S25+ (SM-S936N), Android 16, 1440 × 3120, density override 640 (360dp width), font scale 1.15. Display and font settings were not changed.
- Reproduced `Callb` / `ack` wrapping in installed build 541: [before](before-541.png).
- Updated to signed release build 542 without uninstalling or clearing app data.
- Confirmed the complete Callback label fits on one line with no overlap in [Camera2](after-542.png), [graph + expanded controls](graph-expanded-542.png), and [CameraX](camerax-542.png). Checked each screenshot visually immediately after capture.
- Callback toggle, centre expander, and engine toggle responded to taps. The centre arrow remains aligned with the shutter.
- Other display widths, font scales, and TalkBack were not exercised. This is a layout verification, not a camera performance test.

## Local validation

`assembleDebug`, `lintDebug`, and signed `assembleRelease` passed. The device-only version override is outside the repository. No application dependency or camera timing code changed.
