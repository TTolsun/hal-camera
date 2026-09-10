`app/src/main/java/dev/halcamera/ui/Look.kt` (78 lines). The only place colours, text styles and card/row builders are defined, mapping `docs/design/DESIGN.md` onto the two surfaces described in `PRODUCT-v0.2.md` 11.6.

Two palettes: consumer screens are a light parchment canvas (`#f5f5f7`) with white cards and a single Action Blue; expert screens are dark tiles. The rule that shapes the rest of the UI is that status colours (fail red, warn orange) mark states only and are never used for buttons, so a red never means "press me".

It also exposes small builders (`text`, `card`, `row`) that the activities use instead of XML layouts.
