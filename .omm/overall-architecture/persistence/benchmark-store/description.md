`app/src/main/java/dev/halcamera/benchmark/BenchmarkStore.kt`. The `files/benchmarks/` directory: one JSON per run plus `index.json`.

`BenchmarkIndex` is the baseline pointer table, keyed by `comparisonContractId|endpointKey` and mapping to a run id. Keying on the contract id rather than on the device is what enforces the rule that runs driven by different profiles, or evaluated by different metric definitions, never compare against each other.

Three properties are deliberate. Baselines are explicit user choices, never created automatically — `withBaseline` is the only way one appears, and passing null removes it. A build change does not invalidate a baseline, because comparing across builds is the entire purpose. And `retaining(existingRunIds)` drops pointers to runs whose files are gone, so a deleted run cannot leave a dangling baseline.

Writes go through `AtomicFiles.write`: temp file, flush, `fd.sync()`, then an atomic rename. A process killed mid-write leaves either the previous file or a stray `.tmp`, never a truncated one, and `files()` ignores `.tmp` entries. A corrupt `index.json` is surfaced through `lastIndexError` and the log rather than being swallowed as "no baselines", because silently losing every baseline pointer is worse than reporting the failure.

M1 defines this shape without using it: the code that sets and applies baselines arrives in M4, but the index format is already part of the frozen contract so stored files stay readable.
