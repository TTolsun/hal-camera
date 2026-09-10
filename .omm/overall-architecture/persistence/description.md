Everything the app writes lives in app-internal storage under `Context.filesDir`, plus one `SharedPreferences` file. There is no database, no network and no external storage; sharing happens only through a `FileProvider` the user triggers explicitly.

Four stores, each with its own schema version:

- `files/checks/<runId>.json` — Health Report, schema 2, written by `report/HealthReport.kt`. What the v0.2 screens read back.
- `files/benchmarks/<runId>.json` plus `index.json` — benchmark runs, schema 3, written by `benchmark/BenchmarkReport.kt` through `BenchmarkStore`.
- `files/incidents/<id>.zip` — incident bundles, summary schema 1, written by `telemetry/IncidentExporter.kt`.
- `SharedPreferences("baseline")` — v0.2 device baselines, written by `baseline/BaselineStore.kt`.

Two conventions run through all of them. Run ids are `yyyyMMdd-HHmmss` timestamps, so filename order is time order and no separate index is needed to sort. And nanosecond values are written as decimal strings, never as JSON numbers, so precision survives a JavaScript reader.
