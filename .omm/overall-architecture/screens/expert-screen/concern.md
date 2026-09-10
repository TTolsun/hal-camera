566 lines holding view construction, the permission flow, engine start and close, zoom handling, incident export, CPU sampling and health rendering. It has no unit tests, and the lifecycle state it tracks by hand (`closing`, `resumed`, `destroyed`, `paused`, `ready`, `exporting`) is exactly the kind of state a camera close race exposes.

Splitting the engine lifecycle out into its own holder would make the rest testable and would also be the natural place to reuse `CheckActivity`'s driver wiring.
