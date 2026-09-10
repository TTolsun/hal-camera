Stage 5, the durable form. Three destinations under `filesDir`, each versioned so an old file stays readable after the code moves on.

`files/checks/<runId>.json` (schema 2) is the Health Report: header versions, device, environment, endpoints, per-endpoint metric states and diagnosis, composed health, baseline references. `files/benchmarks/<runId>.json` (schema 3) is the benchmark run: profile, measurement contract, compatibility, device, app, subject, environment, validity, baseline and reference pointers, metrics, summary and raw events. `files/incidents/<id>.zip` is the incident bundle: summary, device, characteristics, three JSONL streams and a human-readable Markdown note.

Two habits make these files trustworthy rather than merely present. Nanoseconds are written as decimal strings so precision survives a JavaScript reader, and non-finite doubles are written as null rather than `NaN`. And the incident summary carries an explicit `omitted` map plus `measurementNotes`, naming what is *not* in the bundle — including `halDroppedFrames`, which is present and always null so it cannot be mistaken for zero.

The benchmark writer goes through a pure map codec that refuses any `schema_version` other than 3, rather than guessing at an older file.
