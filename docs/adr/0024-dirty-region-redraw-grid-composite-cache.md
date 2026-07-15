# ADR-0024: Grid composite cache instead of per-frame full repaint

- **Status:** Accepted
- **Date:** 2026-07-14

## Context

`TileRenderer` and `AsciiTileWindow` each recomposite their *entire* grid — every
populated cell of every z-layer — on every call to `render()`. krogue-4ul already
stops libGDX from calling `render()` at all when the app is idle
(`continuousRendering = false` + `requestRedraw()`), which fixes *how often* a
frame is drawn. It does not touch *what* gets redrawn once a frame is triggered:
`Game.render()` unconditionally does a full `glClear` before `drawFrame()`, so
any redraw — even one triggered by a single blinking HUD cell or one animated
torch tile — still reissues a draw call for every cell in the grid. This is
krogue-drk, referenced from ADR-0017 as the real rendering-perf ceiling once
krogue-4ul's frame-skipping is in place.

Because the actual screen is cleared on every real frame (a GL/libGDX given, not
something kotile controls), simply *skipping* draw calls for unchanged cells
when drawing straight to the screen is unsafe — those cells would show the clear
color instead of their last-drawn content. Any caching scheme needs a target
that persists across frames; an offscreen `FrameBuffer` is the natural fit.

Two granularities were on the table (see the krogue-drk design note):
- **Per-cell dirty tracking** — track exactly which `(x, y)` cells changed and
  redraw only those into the persistent target.
- **Whole-frame cache** — track one dirty flag per renderer; on any change,
  recomposite the *entire* grid into the persistent target (same total draw
  count as today), but skip the recomposite completely when nothing changed.

Per-cell tracking is the more powerful of the two, but it runs into a real
correctness trap: `TileRenderer.render(source, viewport)` and
`AsciiTileWindow.render(source, viewport)` accept a **caller-owned**
`LayeredTilemap` that may be shared across multiple renderers/panes (the
windowed-viewport pattern documented on those methods, e.g. one big world map
sampled by several panes at different origins). Tracking "dirty since last
render" *on the tilemap* and clearing it when consumed means whichever renderer
renders first "steals" the dirty signal from any other renderer sharing that
same source — a second consumer would wrongly see a clean state. Solving that
correctly needs per-observer generation counters on every cell, which is a much
larger surface for a P3 optimization ticket to get right without the ability to
verify pixel output automatically in this environment (see below).

**GL verification is unusually constrained in this repo right now.**
`RenderingIntegrationTest`/`ViewportIntegrationTest` gate on `HeadlessGl.available`
(`DISPLAY` env var set), which is really a Linux/xvfb heuristic; this is a macOS
box with no `DISPLAY`. Forcing the gate open and adding `-XstartOnFirstThread`
does get a real GL context to run here, but the pixel-readback assertions then
fail systematically — consistent with ADR-0017's documented, still-open HiDPI/
retina backbuffer-vs-logical-window-size gap, not a bug in this change. That
means this decision could not be verified against the existing pixel-assertion
harness on this machine; verification instead used the visible `:demo:run` /
`:kotile:demo:run` apps directly (real window, real backbuffer, screenshotted).
Given that constraint, the *simpler* of the two granularities was preferred.

## Decision

**Whole-frame composite cache, scoped to the internal-tilemap render path only.**

- `TileRenderer` and `AsciiTileWindow` each own a `GridCompositeCache`: a lazily
  allocated offscreen `FrameBuffer` + private `SpriteBatch` sized to the grid's
  native (unscaled) pixel dimensions, plus a single `dirty` flag.
- Every mutation through the renderer's own API (`drawTile`, `clearTile`, `fill`,
  `clear`, `clearLayer`) marks the cache dirty. `onResize`/`resize` also marks it
  dirty (and the cache reallocates the `FrameBuffer` to the new size).
- On `render()` / `asLayer()`: if dirty, the existing `renderGrid` compositing
  loop runs *once*, targeting the cache's `FrameBuffer` at 1:1 native resolution
  (no scale, no letterbox, no sharp-bilinear — that only matters at final
  scale-up) instead of the screen; the flag then clears. If not dirty, this step
  is skipped entirely — zero per-cell draw calls. Either way, the cache's
  resulting texture is drawn as a **single sprite** through the renderer's
  existing `canvas.drawSprite` call, so it still goes through the normal scaled/
  letterboxed/sharp-bilinear-aware path unchanged.
- **Animated tiles (`Tile` / `AnimatedAsciiTile`) opt a renderer out of the cache
  for its lifetime.** The moment one is placed, a sticky flag makes every
  subsequent `render()` call treat the whole frame as dirty (matching the
  krogue-drk design note's own framing: an animated cell "is dirty every frame
  its frame advances"). This is a deliberately coarse, conservative rule instead
  of exact per-cell animated-content bookkeeping (which would need symmetric
  increment/decrement on every write *and* every removal path — `clearTile`,
  `fill`, `clearLayer`, `clearAllLayers` — a much larger bug surface for a
  one-way flag that can only ever force *more* work, never *incorrect* work).
- **`render(source, viewport)` (external, possibly-shared tilemap) is out of
  scope and keeps today's full-repaint behavior**, for the sharing reason above.
  This is the one place the design note's "viewport scroll dirties everything"
  concern would otherwise apply — it doesn't need separate handling because this
  path was never made to use the cache.
- `TileRenderer` gains a `dispose()` (it previously owned no GPU resources of its
  own); `AsciiTileWindow.dispose()` is extended to also release its cache.

## Consequences

- A static frame (no writes, no animated content) costs one draw call
  (the cache blit) after the first paint, regardless of grid size — the
  acceptance criterion this ticket set out to meet.
- Any single write recomposites the *whole* grid, same total draw-call count as
  before plus one extra blit — no worse than today, not the finer per-cell win
  a more surgical implementation could offer.
- Any renderer that ever hosts one animated tile permanently loses the caching
  benefit for its own lifetime (falls back to today's always-full-repaint
  behavior) — acceptable for a first cut; a follow-up could track animated-cell
  count precisely if this proves too coarse in practice (e.g. a mostly-static
  map with one flickering torch would currently pay full-grid cost every frame
  the torch animates).
- `render(source, viewport)` callers (multi-pane viewport-scrolled worlds) see no
  perf change from this ticket; a correct fix needs per-observer versioning on
  `LayeredTilemap`, filed as a follow-up if/when that path becomes a hot spot.
- New `TileRenderer.dispose()` is additive but real: existing constructors of
  `TileRenderer`/`SpriteTileRenderer` in long-lived apps must now call it (short-
  lived test processes that never call `render()` never allocate the cache, so
  they're unaffected; ones that do call `render()` inside a whole-process
  `HeadlessGl` boot/exit are unaffected in practice since the GL context itself
  is torn down). Call sites updated in this repo: kotile's demo harnesses and
  korogue's `AnimationShowcaseHarness`. The separate `korogue-rogue` repo (not
  touched here) may construct renderers too and should pick this up separately.
- Verified via `:demo:animationShowcaseHarness` (renders the split sprite/glyph
  showcase room — 3 `SpriteTileRenderer`s + 1 `AsciiTileWindow` sharing one
  canvas, animated torches/creatures, `IntegerScale` fixed grid — to a PNG via a
  real, visible GL context on this machine) diffed pixel-for-pixel against the
  same scene rendered from `mainline` before this change, rather than the
  pixel-assertion GL integration harness (still unusable here, see Context).
  This caught two real bugs the isolated single-canvas unit-style GL tests
  (added to `RenderingIntegrationTest`) missed entirely, both only visible once
  more than one renderer shared a canvas at a non-1:1 scale:
  1. **GL viewport clobbering.** libGDX's `FrameBuffer.end()` unconditionally
     resets the GL viewport to the full backbuffer; doing the cache's FBO pass
     in the middle of an already-open `KotileCanvas.begin()/end()` silently
     broke the canvas's own (letterboxed/HiDPI-reconciled) viewport for every
     draw after it, shifting/cropping content. Fixed by
     `KotileCanvas.reapplyViewport()`, called right after the FBO recomposite
     and before the blit.
  2. **FrameBuffer color-texture filtering.** A plain `Texture` defaults to
     nearest; a `FrameBuffer`'s color attachment does not — left alone, the
     blit came out visibly blurred even under `IntegerScale`. Fixed by
     explicitly setting `Nearest`/`Nearest` on the cache's texture.

  Both are exactly the class of bug ADR-0017 flagged as "unverifiable without a
  live GL context" — confirming that call was right, and that this ticket's
  demo-based verification (not the automated harness) was the load-bearing
  check here, not a formality.

## Alternatives considered

- **Per-cell dirty tracking (generation counters per `(x, y)` in
  `LayeredTilemap`)** — more powerful (an animated torch wouldn't force a
  full-grid repaint, just its own cell), but requires per-observer consumption
  to stay correct for shared/external tilemaps, and needs decrement bookkeeping
  on every removal path to track animated-cell presence precisely. Deferred as a
  follow-up rather than attempted alongside a change that couldn't be verified
  against real pixel output in this environment.
- **Skip the whole `render()` call when nothing changed, with no persistent
  target at all** — unsafe as soon as anything *does* need a redraw: the outer
  `glClear` (see `Game.render()`) wipes the screen every real frame, so any
  cell not redrawn that frame would show the clear color instead of its correct
  content. Rejected outright, not just deferred.
- **Route the cache through `KotileCanvas` itself** (e.g. a canvas-level FBO mode)
  — rejected: `KotileCanvas` is shared across multiple grid renderers and free
  layers (ADR-0018) that have no dirty-tracking concept at all (effects, UI);
  baking caching in at that level would either force everything through it or
  require `KotileCanvas` to know which draws are cacheable. Keeping the cache
  private to `TileRenderer`/`AsciiTileWindow` and blitting through the existing
  `drawSprite` primitive needed zero changes to `KotileCanvas`'s public API.

## Update (2026-07-15): per-cell dirty tracking (krogue-oxi)

The whole-frame cache's real cost was exactly the case this ticket exists for:
a renderer that ever hosts one animated tile (a single flickering torch on an
otherwise-static map) permanently paid full-grid recomposite cost every frame
that tile animated. `GridCompositeCache` now tracks dirty **cells**, not one
flag, closing that gap:

- `markAllDirty()` (first paint, resize, or a bulk write — `fill`/`clear`/
  `clearLayer`/`clearAllLayers`) supersedes `markCellDirty(x, y)` (a single
  write) for that recomposite.
- `recompositeIfDirty` now has two paths. **Fully dirty**: one whole-buffer
  clear, then every cell in a single batched pass — unchanged from above.
  **Partially dirty**: each dirty cell's native-pixel rectangle is individually
  `glScissor`ed, cleared to transparent, drawn, and the batch is **flushed
  immediately** before the scissor rectangle moves to the next cell — required,
  not defensive, because `SpriteBatch` only issues buffered draws to the GPU on
  a flush, so without one a cell's geometry would still be queued when the
  *next* cell's `glScissor` took effect and would render clipped to the wrong
  rectangle. Cells never marked keep whatever pixels the persistent
  `FrameBuffer` already had.
- The sticky "any animated tile marks the whole grid forever" flag is gone.
  `TileRenderer`/`AsciiTileWindow` now keep a `HashSet<Vector2Int>` of positions
  currently holding an animated entry on *any* z-layer, and mark only those
  dirty each `render()` call. The set is **recomputed from ground truth on
  every single-cell write** (scan that position's layers, add or remove it) —
  deliberately not incrementally counted, for the same reason this ADR
  originally rejected precise bookkeeping ("a much larger bug surface" for
  symmetric increment/decrement across every write *and* removal path); a
  re-derive can't drift out of sync because it isn't counting, it's re-deriving.
  `clearLayer` scans for cells actually populated on that layer *before*
  clearing it and only marks/re-derives those (not the whole grid), so an
  overlay layer with a handful of widgets doesn't force a full recomposite.
- `render(source, viewport)` remains out of scope, unchanged, for the same
  shared-tilemap reason as the original decision. The `reapplyViewport()` and
  `Nearest`-filtering fixes above are unaffected and still required.

**New verification technique**, since the existing pixel-assertion GL harness
still can't run automatically here: two windows/renderers draw the *same* final
content — one via first-paint-then-partial-update (exercising the new
scissored path), the other by building the identical state directly in one shot
(always the trusted whole-grid path) — via temporary demo harnesses (deleted
after use), diffed pixel-for-pixel. Both the ASCII path (single-winning-layer
compositing) and the sprite path (bottom-up multi-layer compositing) came back
pixel-identical, zero difference, including a moved sprite (old cell correctly
reverts to the layer beneath it) and a cleared cell (correctly reveals the
clear color, not a stale ghost).

Consequence: a mostly-static map with a few animated cells now pays recomposite
cost proportional to the animated cell count, not the whole grid — the actual
perf goal this ADR originally deferred. New cost: every single-cell write does
a bounded scan over that position's z-layers instead of a flag set — cheap
(bounded by layer count) and the right trade for a renderer written to far less
often than it's rendered. `render(source, viewport)`'s per-observer-versioning
gap (krogue-c0q) is unchanged and still open.

## Update (2026-07-15): per-observer dirty tracking for `render(source, viewport)` (krogue-c0q)

The one deliberately-out-of-scope gap from both updates above is now closed:
`render(source, viewport)` (the caller-owned, possibly-shared tilemap overload
on both `TileRenderer` and `AsciiTileWindow`) is cached too, without
reintroducing the consumable-flag trap the original decision rejected.

- **`LayeredTilemap` gained a per-position version counter** — `versionAt(x, y)`,
  bumped by every write that touches that position (`setCell`, `removeCell`,
  both endpoints of `moveCell`; `clearLayer`/`clearAllLayers` bump every
  position, since they leave no trace of which cells were actually populated).
  This is a **query**, not a consumable event — reading it doesn't reset
  anything, so any number of observers can read it independently. That's what
  makes it safe where a single "dirty since last render" boolean on the
  tilemap was not: nothing to steal.
- **A new `ViewportDirtyTracker`** (one per renderer, paired with its own
  `GridCompositeCache` separate from the internal-tilemap one) does the actual
  per-observer bookkeeping: each screen cell's sampled logical position's
  `versionAt` is compared against what *this* tracker last recorded there. A
  changed `TileViewport` origin, or a different `source` instance (by
  reference), invalidates everything — the same "viewport scroll dirties
  everything" simplification used elsewhere, and it avoids having to translate
  a remembered-version grid when the screen-to-logical mapping shifts.
  Animated entries are handled the same way as before: an `isAnimatedAt`
  callback forces a cell dirty regardless of version.
- This does cost a full `windowWidth x windowHeight` **comparison** scan every
  render call (cheap int comparisons, no GL) even when nothing changed — there
  is no way to avoid polling when the tilemap can't push notifications to an
  arbitrary number of observers. The GL-call savings (skipping actual draws for
  unchanged cells) are unaffected; only the "did anything change" check moved
  from O(1) (an explicit per-write mark) to O(cells) (a per-render poll).

**Verified with a scenario built for exactly this**: two windows share one
world tilemap at non-overlapping viewport origins; a write is made visible only
to each; window A renders first, **then** window B — the ordering that would
trip a single consumable flag. Via a temporary demo harness (deleted after
use), B's render came back pixel-identical to a fresh reference window built
from the same final state directly — confirming the exact failure mode this
update exists to prevent does not occur. Two matching tests were added
permanently to `ViewportIntegrationTest` (GL-gated, same limitation as always
on this machine).

Not addressed by this update: `EffectsLayer`/`UiLayer` (free, non-grid-aligned
layers) still have no caching of any kind — a separate, structurally different
problem (krogue-01r) that this cell-addressed mechanism cannot extend to.
