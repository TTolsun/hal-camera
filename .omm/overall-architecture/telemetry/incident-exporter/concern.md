`device.json` writes `"appVersion" to "0.1.0"` as a literal, while `app/build.gradle.kts` declares `versionName = "0.3.1"` and `versionCode = 3`. Any incident bundle collected today therefore misreports which app produced it.

The fix is to read `BuildConfig` (or the values `AppInfo` already carries in the benchmark layer) instead of the constant.
