# Repository Guidelines

## Project Structure & Module Organization

HAL CAM is a single-module Android application (`:app`, package `dev.halcamera`). Source lives in `app/src/main/java/dev/halcamera/`: `camera/` owns Camera2/CameraX engines and endpoint discovery; `telemetry/` records events; `metrics/` computes measurements; `benchmark/` runs profiles and comparisons; `ui/` contains shared views and styling. Activities construct layouts in Kotlin. Resources belong in `app/src/main/res/`, and JVM tests mirror packages under `app/src/test/java/dev/halcamera/`.

Product and measurement specifications live in `docs/`. `.omm/` supplies architecture descriptions; `tools/docgen/` generates the developer guide.

## Build, Test, and Development Commands

Use JDK 17 and Android SDK 36. Run commands from this repository root; on Windows, replace `./gradlew` with `.\gradlew.bat`.

- `./gradlew assembleDebug`: build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew testDebugUnitTest lintDebug`: run JVM tests and Android lint; lint errors fail the build.
- `./gradlew assembleRelease`: build the release APK using local signing configuration when available.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: install on a compatible connected device.
- `adb shell am start -n dev.halcamera/.MainActivity`: launch LIVE.

For documentation changes, use Node.js 24 and follow `.github/workflows/docs-check.yml`, including extraction, freshness checks, generation checks, and generator regression tests.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use `PascalCase` for types, `camelCase` for functions/properties, and `UPPER_SNAKE_CASE` for constants. Match filenames to their primary type. Reuse `ui/Look` tokens. Keep measurement logic independent of Android views; dispatch UI updates to the main thread and close camera buffers promptly. Avoid unrelated formatting changes.

## Testing Guidelines

Use JUnit 4 and name files `*Test.kt`. Prefer descriptive behavior names, including Kotlin backtick names, and deterministic fakes for clocks, schedulers, and drivers. No numerical coverage threshold is configured; cover changed behavior, failure paths, and regressions. Validate camera changes on hardware and report the device, Android version, and scenarios tested separately from JVM results.

## Commit & Pull Request Guidelines

Use short imperative subjects, such as `Fix capture timeout recovery`; prefixes such as `docs:` are optional. Keep commits focused. PRs should explain the problem and resulting behavior, link relevant issues, list validation, and include screenshots for UI changes. Identify hardware-dependent limitations.

## Configuration & Documentation Safety

Keep `local.properties`, `keystore.properties`, signing keys, and credentials untracked. Do not uninstall an existing app to bypass a signing mismatch. Preserve benchmark stream specifications and metric definitions. Before editing `docs/guide/`, read its scoped `AGENTS.md`; regenerate marked blocks through the documented pipeline.
