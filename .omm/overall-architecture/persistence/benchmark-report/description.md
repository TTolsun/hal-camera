`app/src/main/java/dev/halcamera/benchmark/BenchmarkReport.kt` (140 lines). Run JSON schema 3, split deliberately into two halves.

`BenchmarkReportCodec` is a pure map codec: `toJsonMap` builds a `linkedMapOf` in a fixed key order, `fromJsonMap` reads it back and *requires* `schema_version == 3` rather than guessing at an older file. Because it moves only `Map<String, Any?>`, both directions round-trip in JVM tests — `BenchmarkReportCodecTest` exists for exactly this.

`BenchmarkReport` is the thin file boundary around it and the only place `org.json` touches the benchmark contract. `read()` returns null on any failure instead of throwing, matching the rest of the project's rule that a corrupt file must not break a screen.

Its `value()` converter encodes three conventions: enums serialise lowercase, non-finite doubles become `JSONObject.NULL` rather than the string `NaN`, and nested maps and lists recurse. `newRunId()` is `yyyyMMdd-HHmmss`, which is what makes filename order equal time order in `BenchmarkStore.files()`.
