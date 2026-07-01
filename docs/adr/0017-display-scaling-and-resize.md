# ADR-0017: Dynamic window resize, display scaling, and centering

- **Status:** Accepted
- **Date:** 2026-06-30

## Context

kotile's renderer assumed a fixed grid pinned to the top-left of the window.
`KotileCanvas.resize` only reset a full-window pixel-ortho projection; a grid
smaller than the window anchored to a corner with dead space, and the
`fitToWindow = false` docstrings claimed a "scaled/letterboxed" behavior that was
never implemented. On resize/fullscreen there was no centering, no scaling, and
mouse→tile mapping (`pixelToTile`) assumed `pixel = tileSize × grid` with no
offset or scale. See krogue-n64.

kotile is a general engine, so it must support both common resize models rather
than bake in one:

- **Reflow** — tiles keep native size; a bigger window shows *more* tiles
  ("see more map"). Analogous to libGDX `ScreenViewport`.
- **Fixed grid** — a constant tile count (e.g. classic 80×24) is *scaled* to fill
  the window, preserving aspect ratio and letterboxing. Analogous to libGDX
  `FitViewport`.

We codesigned the scaling strategy and deliberately scoped the first cut.

## Decision

**Two resize models, selected by the existing `fitToWindow` flag.** `true`
(default) = reflow; `false` = fixed grid. The engine stays generic; the Rogue
example (fixed 80×24) uses fixed, others can reflow.

**Scaling is a pluggable `ScalePolicy`.** Two implementations ship day one:

- `IntegerScale` (**default**) — whole-number factor, floor 1, pairs with
  nearest-neighbor for crisp CP437 glyphs.
- `FitScale` — fractional factor that fills the tighter axis; intended to pair
  with sharp-bilinear filtering for smooth-but-crisp fractional fill.

**The placement math is a pure, GL-free module.** `GridLayout` +
`GridLayout.forReflow`/`forFixedGrid` compute the visible column/row count, the
on-screen (possibly scaled/fractional) tile size, and the centering offset. This
is fully unit-tested without a GL context — important because the pixel-level GL
integration tests only run when a `DISPLAY` is present (skipped on this macOS
dev box).

**`KotileCanvas` owns the layout.** It recomputes a `GridLayout` on every
`resize` and on `useFixedGrid`/`useReflow`, and bakes the offset + scaled tile
size into `drawTile`. Both `AsciiTileWindow` and `SpriteTileRenderer` inherit
centering/scaling through the shared canvas rather than duplicating it.

**Projection stays y-up ortho + manual flip for this cut.** We did *not* route
through a libGDX `Viewport` subclass now. The built-in `FitViewport`/`ScreenViewport`
only scale fractionally (they can't express the default `IntegerScale`), and
driving a `Viewport` ourselves is GL-visual work that cannot be verified locally
without a GL context. `GridLayout` already provides the letterbox (bars = clear
color) and the offset/scale-aware pixel→tile mapping.

A `Viewport` **wrapper** is planned as a deliberate follow-up (**krogue-3eu**) —
not a rewrite. It would keep the pure `ScalePolicy`/`GridLayout` as its brain and
keep the y-up camera (flip in `drawTile`), consuming the layout only to set world
size + screen bounds. Its justification is **correctness, not performance** (the
projection mechanism does not affect frame cost — see ADR follow-up krogue-drk):

- **HiDPI/retina reconciliation (primary):** `KotileCanvas` currently mixes
  `Gdx.graphics` logical points with the backbuffer pixel size. On retina /
  mixed-DPI / fractional-scaling displays these differ (e.g. an 800×400 window →
  1600×800 backbuffer). `Viewport` + `HdpiUtils.glViewport` reconcile them; today
  the mismatch is latent and only works at clean integer ratios.
- **Hard-clip letterbox:** `viewport.apply()` sets glViewport, so overflow and
  partial-tile bleed are cleanly cut. This absorbs the former krogue-vuv.

Deferring it keeps the y-up camera (avoiding the texture-V orientation surface)
and requires verifying on a real retina display before merging.

**Input mapping is layout-aware.** `GridLayout.tileAt(px, py)` subtracts the
centering offset and divides by the on-screen tile size, so it is correct under
scaling and letterboxing. `KotileInputProcessor` gains a `(layout: () -> GridLayout)`
constructor (the recommended one); `Game.kt` uses `{ window.layout }`. The
original four-lambda constructor and the `pixelToTile` free function are kept
unchanged for backward compatibility (relevant to the separate `korogue-rogue`
repo that consumes this API).

## Consequences

- Windows that are not an exact tile multiple now center the remainder instead of
  anchoring top-left (a visible behavior change; existing GL tests use exact
  multiples and are unaffected — offset stays 0).
- `KotileCanvas.tileWidthPx`/`tileHeightPx` now explicitly mean **native**
  (pre-scale) size; on-screen size lives in `canvas.layout`.
- Letterboxing is "soft": leftover space is the clear color. A fixed grid larger
  than the window at 1× (`IntegerScale` floor) overflows without a hard clip.
  Hard-clipping is folded into the Viewport-wrapper follow-up (krogue-3eu).
- HiDPI/retina pixel reconciliation is a **known latent gap** in this cut (logical
  points vs backbuffer pixels); addressed by krogue-3eu.
- Deferred scaling strategies filed as follow-ups, each depending on the
  `ScalePolicy` interface: **krogue-19k** (SDF/MSDF fonts), **krogue-yfs**
  (multi-size bitmap font asset swap; ties to krogue-kotile-font12),
  **krogue-1zo** (supersample→FBO downsample).

## Alternatives considered

- **libGDX `Viewport` (FitViewport/ScreenViewport) as the scaling mechanism** —
  rejected: the built-ins can't do integer scaling, and making the `Viewport`
  own the scaling logic would bury the math in GL code, losing headless
  testability. The scaling brain stays in the pure `GridLayout`. Note this is
  *not* a rejection of `Viewport` altogether — a thin wrapper over `GridLayout`
  is the planned krogue-3eu follow-up, justified by HiDPI correctness + hard-clip
  (not perf), deferred only because it is GL-visual work needing a real display.
- **y-down camera** (world origin top-left, no manual flip) — rejected; it would
  invert texture V-coordinates, an unverifiable-without-GL regression risk, for
  no benefit over the existing y-up + flip.
- **Single resize model** — simpler, but kotile is a general engine and both
  reflow and fixed-grid are legitimate; the `fitToWindow` switch already existed.
- **Hardwire one scaling strategy** — rejected in favor of the `ScalePolicy`
  interface so SDF/asset-swap/supersample can be added without touching callers.
