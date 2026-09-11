> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/check/CameraEndpoint.kt` (62 lines). The capability model from `PRODUCT-v0.2.md` 9.2, plus the pure `LensRoles` helpers.

A `CameraEndpoint` is one measurable thing, identified by `key` = logical id, or `logical.physical` when it sits behind a logical camera. It records what the app is allowed to do with it (`independentlyOpenable`, `selectableByZoom`, `exposedToCameraX`) alongside the characteristics a later comparison needs (`equivalentFocalMm`, `timestampSource`, `hardwareLevel`, zoom range). `primary` marks the ones CDD latency requirements actually apply to: a top-level rear MAIN or a FRONT camera, never a physical sub-camera.

`LensRoles` holds the inference and is pure so it can be tested without a `CameraManager`. `equivalentFocalMm` converts focal length and physical sensor size to a 35 mm equivalent via the 43.27 mm diagonal. `roleFor` bands it: under 20 mm is ULTRA_WIDE, 20 to 35 mm is MAIN, above is TELE. `dedupeMain` keeps only the first MAIN among rear cameras and demotes later candidates to UNKNOWN, because several rear lenses can land in the same band. `checkOrder` fixes the priority when more endpoints exist than a run will visit: MAIN, FRONT, ULTRA_WIDE, TELE, UNKNOWN, EXTERNAL.
