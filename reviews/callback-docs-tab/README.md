# Callback documentation tab review

- Added Callback immediately after Benchmark in the shared navigation and the home contents list.
- Removed Decisions from those two navigation surfaces. Existing decision documents and their URLs remain available for evidence links.
- Reviewed the new guide against ResultCallbackSeries, ResultCallbackTimeline, ResultCallbackGraph, and StreamConfiguration: previous-frame Shutter origin, stream identities, app buffer receipt, automatic hold, and missing-value labels.
- Visually inspected the rendered Callback page in the Codex browser at its desktop viewport and at a 390 × 844 mobile viewport. The active tab, tab order, title, and opening text render correctly. The mobile document's content width equals its client width (375 CSS px after scrollbar), with no page-wide horizontal overflow. Restored the viewport after inspection.
- [Desktop capture](desktop.png) · [Mobile capture](mobile.png)

The documentation build and source/generated-output checks pass. This change does not modify or install the Android app.

## Whole-site editorial review

Reviewed all 12 published pages, including the home page and the preserved Decisions URL. Applied the user's requested [fluent-korean](https://github.com/snflkd/fluent-korean) guidance without changing API names, command syntax, or observed device outcomes.

| Topic | Canonical page after review |
| --- | --- |
| Frame time origin, stream rows, automatic hold, missing values | Callback |
| Verdicts, metric bars, history comparison, filters, export and retention | Benchmark |
| Live capture, recording, focus/exposure controls and generated build facts | Getting started |
| Request polling, recovery, file collection and CLI errors | CLI |
| Attaching the CLI skill and delegating tasks | Agents |
| Device observations, evidence scope and documentation maintenance | Evidence |
| Event clocks, sample selection and symptom investigation | Troubleshooting |
| Internal data paths, UI state transitions and implementation constraints | Architecture |

- Removed historical UI-change rationales from the Benchmark usage guide and duplicate CLI setup/command tables from Agents.
- Replaced repeated Callback and Benchmark descriptions in troubleshooting with focused symptom checks and links.
- Moved CTS device results to Evidence while retaining their dates, builds, combined-run scope, limitations and source link.
- Corrected the two-run comparison description against HistoryActivity and ResultPresenter: the first selected run is the temporary comparison baseline. Previous-run references remain unjudged.
- Corrected First run versus No baseline and removed the unsupported claim that 29.8 fps establishes a lost frame.
- Clarified that generated freshness records do not cover every handwritten page and that CLI does not run benchmarks.
- Integrated main's CameraX parity change (#196) before final review. Updated Callback, Live usage and troubleshooting: CameraX keeps its engine for capture/recording, pairs the nearest analysis YUV with the JPEG, and cannot observe Preview/Recording buffer arrival directly. Reviewed CameraXEngine, CameraXStillCapture and CameraXLiveRecorder for these distinctions.
- Kept historical decision inputs and validation/release documents intact. Removed only the Decisions navigation entry.
- Checked every published page at a 390 × 844 viewport: document width and content width both measured 375 CSS px after the scrollbar. Restored the viewport afterward.
- Verified Benchmark → Callback navigation with the keyboard and checked all 37 local HTML fragment links; no missing targets were found.
- Documentation regression tests: 21 passed. Source coverage passed. No Android device validation was performed for this documentation-only change.

[Benchmark desktop](benchmark-desktop.png) · [Evidence mobile](evidence-mobile.png)
