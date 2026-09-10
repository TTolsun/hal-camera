`app/src/main/java/dev/halcamera/home/RunSummary.kt` (55 lines). A read-only projection of the newest Health Report run JSON, shaped for the home card.

It lists `files/checks/*.json` sorted by last-modified then name, and parses one into `runId`, overall `level`, per-endpoint lines (role, level, diagnosis rule), whether a baseline was created, the consumer sentence for the diagnosis rule, and `aborted`. It also carries `newerAbortedRunId`: when the most recent file is an aborted run, the card still shows the last completed one but can say a newer attempt was abandoned.

Contract: it never throws. Every missing or malformed field becomes null, because a corrupt run file must not stop the launcher from drawing.
