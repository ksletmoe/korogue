# ADR-0033: TileSurface carries kotile's dynamic-tile branch, with a static fallback

- **Status:** Accepted
- **Date:** 2026-07-17

## Context

kotile's ASCII cell hierarchy has two branches (ADR-0027): `StaticAsciiTile` and
`DynamicAsciiTile` (whose built-in impl `AnimatedAsciiTile` cycles frames on the
window's own clock — Brogue-style torch flicker). `AsciiTileWindow` tracks
dynamic cells and re-composites just them per frame (ADR-0024).

The engine's UI toolkit could not reach that branch. `TileSurface` — the seam
widgets draw through (ADR-0011) — had a single `put(x, y, z, glyph, fg, bg)`, and
`WindowSurface` always wrapped those in a `StaticAsciiTile`. So no widget could
place an animated cell; the animation showcase had to bypass the engine and drive
`AsciiTileWindow.drawTile` directly (krogue-2co).

The tension: `TileSurface` is deliberately narrow so the toolkit stays headlessly
testable against a fake (ADR-0011). Widening it to a time-varying cell risks
forcing every fake and every decorator to model a clock. Two shapes were on the
table — carry the dynamic branch on `TileSurface` itself, or push animation onto
a different seam (the presentation layer, which already resolves time-varying
appearance per frame in Kotlin: `VisualSequence`, `MapPanel` flicker).

The decisive constraint is `Game(continuousRendering = false)` (krogue-4ul): a
turn-based game sits idle without redrawing, so per-frame Kotlin resolution can't
animate a cell without pegging a CPU core. A cell that carries its own clock (a
native `DynamicAsciiTile`) is the expressive primitive that avoids that — and
letting the window own the animation is also what its per-cell dirty tracking is
for.

## Decision

`TileSurface` gains an overload `put(x, y, z, tile: AsciiTile)`. The
glyph/`fg`/`bg` `put` stays the primitive (unchanged for every existing caller);
the tile overload is additive.

Its **default** resolves the tile's first frame (`tile.resolveAt(0)`) and forwards
it through the glyph `put` — correct for a `StaticAsciiTile`, and a safe
still-frame fallback for a dynamic one. So a surface with no clock (a headless
fake, a game's own sink) degrades gracefully instead of being forced to model
time. Only surfaces backed by a per-frame render loop override it:

- `WindowSurface` hands the live tile to `AsciiTileWindow.drawTile`, so it
  animates.
- `RegionSurface` (which `UiRoot` wraps every widget in — including the map)
  translates coordinates/z and forwards the tile. Undimmed, it passes the exact
  instance through. Dimmed (a layer beneath an open modal), it wraps a *dynamic*
  tile so each resolved frame is dimmed while it keeps animating, and resolves a
  *static* tile to a dimmed static cell — never promoting a static cell to
  dynamic, which would make the window repaint it every frame for nothing.

The test fake `RecordingSurface` records the `AsciiTile` and exposes both the
resolved first frame (`glyph`/`fg`/`bg`, matching a static cell) and
`resolveAt(t)` for sampling a dynamic cell — modelling kotile's time branch with
no clock state.

## Consequences

- A korogue widget can place a `DynamicAsciiTile` declaratively; the window plays
  it, so animation survives `continuousRendering = false`. The showcase has been
  rewired to route animated tiles (glyph torches) through the engine seam via
  `TileSurface.put(tile)`, demonstrating that declarative animation now works
  end-to-end.
- The seam widens by exactly one method that generalises the existing one; it adds
  no renderer coupling and the fake stays headless, so ADR-0011's testability
  holds. `TileSurface.text`/`fill` and every existing caller are untouched.
- Faithful Rogue (ADR-0012) needs none of this — original Rogue has no animated
  cells — and indeed the port compiled and tested unchanged against the widened
  seam, which is the check that the overload is non-breaking.
- A custom `TileSurface` that a game writes and forgets to override `put(tile)` on
  shows dynamic cells frozen on frame 0 rather than failing — a deliberate, quiet
  degradation.

## Alternatives considered

- **Animation belongs on the presentation seam only** (no `TileSurface` change) —
  the existing `VisualSequence`/`MapPanel`-flicker path resolves time in Kotlin
  each frame. Rejected as the *sole* answer: it can't animate a cell while a
  turn-based game idles under `continuousRendering = false` without forcing a
  continuous redraw, and it can't express "this cell owns its own clock" at all.
  The two seams coexist — presentation for scripted, event-driven motion; the
  tile branch for a self-animating cell.
- **Make the tile overload the primitive and the glyph `put` a default that wraps
  it.** Cleaner on paper, but it routes every glyph write through a
  `StaticAsciiTile` allocation and forces `RegionSurface`/`WindowSurface` to move
  their allocation-free hot path onto the tile form — churn and per-cell GC
  pressure on the UI's busiest path, for a capability most cells never use.
- **A separate `putDynamic` / a `DynamicCell` engine type** mirroring kotile.
  Rejected as duplication: the engine already depends on kotile's ASCII types
  (`WindowSurface`), and `AsciiTile` is exactly the right vocabulary — inventing a
  parallel one buys nothing and desyncs from ADR-0027's one-for-one hierarchy.
