# ADR-0035: Two colour types — `NormalizedRgb` is the model, GDX `Color` is the presentation

- **Status:** Accepted
- **Date:** 2026-07-19

## Context

korogue exposes two colour types to consumers, and until now the convention for which
to use where lived only in scattered class docs:

- **`algorithms.color.NormalizedRgb`** — three `Double` channels, deeply immutable,
  `@Serializable`. Used in components a consumer authors and the world persists:
  `Renderable.color`, `LightEmitter.color`, and light values.
- **libGDX `Color`** — four mutable `Float` channels. Used across the kotile UI toolkit
  (`TileSurface.put`/`text`/`fill`, widget draws, `AsciiTileWindow.drawText`) and
  `world.Tile.color`/`backgroundColor`.

A consumer authoring a coloured entity reaches for `NormalizedRgb`; authoring UI or a
terrain tile reaches for `Color`; and light math converts between them. The split is
deliberate — immutable, serializable colour belongs in the model (ADR-0003), while the
draw path wants GDX's mutable float type and its batching — but two facts made it a
papercut rather than a clean seam:

1. **The boundary was undocumented.** Nothing stated which type a consumer touches for a
   given job, or where the model→presentation conversion is supposed to happen, so a
   newcomer meeting both types could not tell the rule from the accident.
2. **`NormalizedRgb` was too thin to stay on the model side.** It had no constants and no
   factories, so authoring a model colour meant borrowing GDX's palette and converting:
   `Renderable('@', Color.YELLOW.toNormalizedRgb(), …)`. Every component author imported
   the presentation type just to name a colour — the exact coupling the split exists to
   avoid. This showed up verbatim across the Rogue example and the demo.

## Decision

**`NormalizedRgb` is the model colour; GDX `Color` is the presentation colour; the
renderer converts model → presentation at the draw boundary, and only there.**

Concretely, for consumers:

- **Author entity/light colour as `NormalizedRgb`.** Use its companion palette
  (`NormalizedRgb.YELLOW`, `.WHITE`, `.BLACK`, …) instead of converting a GDX constant.
  For a named GDX colour the palette does not re-export, use `NormalizedRgb.fromColor(Color.TEAL)`
  or `NormalizedRgb.fromHex("…")`. GDX's palette stays the single source of truth — the
  companion constants re-export it into model space rather than redefining channel values.
- **Author UI and terrain (`Tile`) colour as GDX `Color`.** These live on the presentation
  side already; there is no model colour to launder them through.
- **Do not convert model → presentation yourself in gameplay code.** `NormalizedRgb.toColor()`
  exists for the renderer's boundary; the light pipeline additionally offers
  `Color.tintedByLight(...)` to fuse the tint-and-convert step. Producing a `Color` early and
  threading it through the model re-introduces the coupling this ADR removes.

To make the model side self-sufficient, `NormalizedRgb` gains: neutral constants `BLACK`/`WHITE`
(model-space literals), a common palette re-exported from GDX, `fromColor` / `fromHex` factories,
and `lerp` for model-space interpolation. Model channels remain **unclamped** — blends and scalar
scales may leave `[0, 1]`; `toColor()` clamps at the boundary (per the existing `toColor` contract).

## Consequences

- Component-authoring code no longer imports `com.badlogic.gdx.graphics.Color` merely to name a
  colour; the Rogue example and demo now read `NormalizedRgb.YELLOW`/`.WHITE`/`.GOLD` directly.
- The model↔presentation boundary is now a stated convention, not folklore — one place
  (this ADR, linked from `NormalizedRgb`'s KDoc and ARCHITECTURE.md) answers "which colour type?"
- The palette is a curated subset, so a colour GDX names but we don't re-export still needs
  `fromColor`. That is the intended escape hatch, not a gap — re-exporting all ~34 GDX names would
  trade one papercut for a maintenance list. The convention makes the fallback obvious.
- `Tile` colour stays GDX `Color`. Terrain is authored and drawn on the presentation side and is
  not tinted through the model, so pulling it into `NormalizedRgb` would add conversions, not remove
  them. The two-type split is therefore genuine, not a migration waypoint.

## Alternatives considered

- **Collapse to one colour type (GDX `Color` everywhere).** Rejected: `Color` is mutable and
  not cleanly serializable, so it fails the model's immutability/persistence contract (ADR-0003).
- **Collapse to one colour type (`NormalizedRgb` everywhere, including the draw path).** Rejected:
  the renderer and kotile want GDX's float `Color` and its batching; forcing model colour through
  the hot draw loop trades allocation and conversion cost for uniformity nobody asked for.
- **Re-export GDX's entire named palette on `NormalizedRgb`.** Rejected: a long hand-maintained
  mirror for marginal benefit. A curated common set plus `fromColor`/`fromHex` covers real usage and
  keeps the escape hatch one call away.
- **Leave `NormalizedRgb` thin and just document the seam.** Rejected: documentation alone would
  not stop component authors from importing `Color` to name a colour — the thin API *was* the reason
  the coupling kept reappearing.
