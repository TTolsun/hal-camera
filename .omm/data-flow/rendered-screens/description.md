Stage 6. The screens read the run file back; they never keep the in-memory result as their source of truth.

`RunSummary.load` picks the newest file in `files/checks` and projects it into the home card: overall level, per-endpoint lines, the consumer sentence looked up from the diagnosis rule id, and `newerAbortedRunId` when a later attempt was abandoned. `CheckResult.fromFile` reconstructs the full result screen from the same directory.

`CheckResult` exists in two constructors — `fromEvaluations` for a run that just finished, `fromFile` for a stored one — precisely so `CheckActivity` renders through one path either way. That is what makes a finished run survive process death and lets the home screen open an old run without touching the camera.

Both readers return null rather than throwing on a malformed file. A corrupt run must not stop a screen from drawing.
