`app/src/main/java/dev/halcamera/diagnosis/MetricCatalog.kt`. The lookup from metric id to Korean wording, and the one rule about it: an id such as `H.1` is never shown on its own (`PRODUCT-v0.2.md` 11.5).

Each `Info` carries the expert name ("프레임 간격 p50"), the plain-language consumer name ("프레임 속도") and the display unit. Expert views append the id in parentheses; consumer views omit it entirely.

It used to also carry the benchmark `category` and short English name, which made the v0.3 layer depend on this v0.2 file. That metadata now lives in `benchmark/MetricInfo.kt`, so this catalogue serves the v0.2 UI only and can be deleted with the rest of the layer in M3.
