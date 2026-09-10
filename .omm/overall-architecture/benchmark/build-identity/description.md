`app/src/main/java/dev/halcamera/benchmark/BuildIdentity.kt`. Compares two runs across six axes instead of producing one "same build" boolean.

The reason is stated in the file header and is the whole point of the app: in camera HAL work the case that matters most is the *same* Android fingerprint with a *different* vendor binary. A single boolean would hide exactly that.

The axes are system fingerprint, vendor fingerprint, camera INFO_VERSION, app version, subject build label and subject commit. Every axis except system fingerprint and app version is nullable, and `both()` enforces the rule that produces those nulls: two values are equal only when both sides actually carry one, with blank counting as missing. An unknown is never reported as a match.

`sameCameraBuild` is the derived line the UI shows, and it is conjunctive rather than a fallback: it is true only when every *known* axis agrees, false as soon as any known axis differs, and null only when neither vendor fingerprint nor INFO_VERSION is known on both sides. Treating it as "vendor fingerprint, else INFO_VERSION" would let a matching vendor fingerprint hide a changed INFO_VERSION.

Every `RunRef` stored in a run JSON embeds this comparison, so a report can state what changed between a run and its baseline without needing the baseline file present.
