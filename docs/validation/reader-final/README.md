# Reader polish verification — 2026-10-17

- Browser widths: 320, 390, 1024 and 1440px. Eight public pages checked at each width; no document or visible table horizontal overflow (responsive-checks.json).
- Three-or-more-column comparison tables become labelled row cards at <=700px. Desktop tables stay unchanged. Semantic table, row, cell and column-header roles are retained; decorative repeated labels are aria-hidden.
- Two-column tables wrap within the mobile viewport. Hidden disclosures are enhanced when the document loads as well.
- Search suggestions: 다운로드 queries APK (4 documents / 9 results), 사진 (13 / 52), RAW (6 / 15). Queries were checked against the built site in the browser.
- Live retains the verified 2026-10-11 Galaxy S25+ / Android 16 Live and WB screenshots with concise dated captions. The outdated expanded-controls illustration is removed; current control instructions remain. No new device screenshot is published or claimed as a current device test.
- Screenshots: engine-mobile-320.png, engine-desktop-1440.png and search-mobile-320.png.
- Validation: docflow six-stage check; 36 Node tests; browser search and responsive layout checks. No Android application code changed.
