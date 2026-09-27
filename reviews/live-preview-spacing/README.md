# Live preview spacing review

Reviewed the requested layout and typography changes against main (772bdf5).
No blocking findings in the changed code.

- Live readout and zoom labels: 12sp to 11sp. Photo/video labels: 14sp to 12sp.
- Vertical padding and margins shrink by 78dp in total, excluding the additional
  reduction from the smaller readout text. The preview surface itself is unchanged.
- Photo/video targets narrow from 96dp to 80dp and remain 48dp tall.
- Zoom circles shrink from 32dp to 28dp while targets remain 48dp. The viewport
  shrinks from 52dp to 48dp, and the shutter remains 72dp. Removing the footer's
  remaining 8dp padding lowers the capture/mode rows by 8dp, zoom by 10dp and the
  readout by 12dp relative to the first compact layout with 11sp zoom labels.
- Initial padding and window-inset padding agree; navigation-bar and cutout
  insets remain applied. The diagnostics panel still derives its offset from
  the actual event-save footer height.
- Zoom expansion, selection, scrolling, timeout, camera lifecycle, capture,
  recording and physical-camera-ID reporting are unchanged.

Validation: release build, debug lint and final JVM unit tests passed.
Galaxy S25+ (SM-S936N), Android 16: signed release updated over wireless ADB
without uninstalling; cold launch succeeded; the local photo-mode screenshot was
inspected for clipping and overlap. This is a layout check, not a new capture,
recording, large-font or TalkBack validation.

The docflow evidence diff contains only these two Kotlin files. Reviewed the
affected architecture, data-flow, state-transition, tool-handoff and zoom
descriptions and architecture/troubleshooting prose against the layout-only
diff; their behavioral descriptions remain applicable. The review record is
attributed to Codex for this user-requested review, not an independent human review.

The screenshot remains local and is not included in this repository.
