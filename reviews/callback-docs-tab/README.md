# Callback documentation tab review

- Added Callback immediately after Benchmark in the shared navigation and the home contents list.
- Removed Decisions from those two navigation surfaces. Existing decision documents and their URLs remain available for evidence links.
- Reviewed the new guide against ResultCallbackSeries, ResultCallbackTimeline, ResultCallbackGraph, and StreamConfiguration: previous-frame Shutter origin, stream identities, app buffer receipt, automatic hold, and missing-value labels.
- Visually inspected the rendered Callback page in the Codex browser at its desktop viewport and at a 390 × 844 mobile viewport. The active tab, tab order, title, and opening text render correctly. The mobile document's content width equals its client width (375 CSS px after scrollbar), with no page-wide horizontal overflow. Restored the viewport after inspection.
- [Desktop capture](desktop.png) · [Mobile capture](mobile.png)

The documentation build and source/generated-output checks pass. This change does not modify or install the Android app.
