# Repository Guidelines

## Project Structure & Module Organization

HAL CAM is an Android camera measurement app with two Gradle modules: `app/` and `ctsvendor/` (vendored AOSP CTS sources; reuse these rather than transcribing cases).

Kotlin sources live in `app/src/main/java/dev/halcamera/`: `camera/` implements camera engines, `telemetry/` records events, `metrics/` computes measurements, and `benchmark/` contains Activities, pure `domain/` logic, and `platform/` adapters. `cli/` handles ADB commands; `cts/` integrates CTS; `ui/` holds shared views and tokens. Resources and assets live in `app/src/main/res/` and `assets/`. JVM tests mirror packages under `app/src/test/`; device checks live in `app/src/androidTest/`. Python utilities live in `tools/`.

## Build, Test, and Development Commands

Use JDK 17 and Android SDK 36. Run from this repository root; Windows users substitute `.\gradlew.bat` for `./gradlew`.

- `./gradlew assembleDebug`: build the debug APK.
- `./gradlew testDebugUnitTest lintDebug`: run JVM tests and Android lint; lint errors fail the build.
- `./gradlew assembleDebugAndroidTest`: build device-test APKs; does not execute them.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: install the app.
- `adb shell am start -n dev.halcamera/.MainActivity`: launch Live.
- `python -m unittest discover -s tools/halcam/tests -v`: test the Python CLI (Python 3.11+).
- `python -m unittest discover -s tools/tests -v`: test aggregation utilities.

## Coding Style & Architecture

Use official Kotlin style, four-space indentation, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants. Match filenames to primary types and reuse `ui/Look` tokens. Layouts are constructed in Kotlin. Dispatch UI updates to the main thread and close camera buffers promptly.

Keep `benchmark/domain/` and `metrics/` free of Android and `org.json` imports. Domain logic must not depend on platform adapters, screens, or CLI code; `LayerIsolationTest` enforces these boundaries. Preserve benchmark stream contracts and metric definitions.

## Testing Guidelines

Use JUnit 4, `*Test.kt` filenames, descriptive behavior names, and deterministic fakes. Cover regressions and failure paths; no numerical coverage threshold is configured. Validate camera changes on hardware and record device, Android version, and tested scenarios.

## Commit & Pull Request Guidelines

History favors short imperative subjects, such as `Add adb-only camera controls and recording`. Keep commits focused. PRs should explain behavior changes, link relevant issues, report validation, and include screenshots for UI changes and hardware limitations where applicable.

## Configuration & Documentation

Keep local SDK configuration, signing keys, and credentials untracked. Never uninstall to bypass signing mismatches. Read `guide/AGENTS.md` before editing documentation. With Node.js 24, run `node tools/docgen/docflow.mjs check --build`; regenerate marked blocks and generated `docs/` HTML rather than editing them manually.
