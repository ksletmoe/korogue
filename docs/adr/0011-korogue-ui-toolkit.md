# ADR-0011: A korogue-side TUI widget toolkit; kotile stays a renderer

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

The original korogue had a multi-pane layout (topbar + sidebar); it was dropped in the
renderer cutover onto kotile (3b-2), leaving a single full-screen map pane. We now need
real UI: a health/mana bar and status line (the immediate blocker for player-death feedback,
krogue-4zi), a scrollable game log, an inventory side panel, and modal dialogs with selectable
menus (save / exit / settings), all with bordered windows. The eventual playable-Rogue example
(krogue-sdh) will exercise all of it, in both ASCII and graphical forms.

The decision (krogue-ui-layout): **where does this live — kotile or korogue — and how?**

Forces:

- **kotile is a general-purpose tile renderer** heading to a 1.0 publish. A widget framework is
  opinionated *UI policy* (panels, focus, scrolling, input routing, menus); baking that into the
  renderer would bloat its surface right when we want to freeze it.
- **kotile already gives us enough.** `AsciiTileWindow` is a z-layered, cell-addressable buffer:
  `drawTile(x, y, z, …)` / `drawText(x, y, z, …)` write any cell on any layer, and
  `render(elapsedMs)` composites them **top-cell-wins**. That is all a text-UI toolkit needs.
- **Testability.** The renderer has no headless tests (it is GL-bound). A UI toolkit must not
  inherit that — its layout/draw logic should be unit-testable without a GL context.

## Decision

Build a small **retained-mode TUI widget toolkit in korogue**, on top of kotile's existing
`AsciiTileWindow`. **kotile gains nothing** — it stays a renderer.

- **Draw-surface seam.** Define a thin korogue interface (a cell sink: `put(x, y, z, glyph, fg, bg)`
  + `width`/`height`) that an adapter implements over `AsciiTileWindow`. Widgets draw into the
  surface, never the window directly, so a fake surface makes the whole toolkit headlessly
  testable (assert which glyphs land in which cells) — closing the rendering-layer test gap for
  UI.
- **Widgets own a screen rect + z-layer** and draw into the surface within that rect. A `UiRoot`
  holds a **stack of layers**, renders them bottom-up into the window in a **single render pass**
  (`window.render(elapsedMs)`), and **routes input to the topmost modal** (a focus stack);
  non-modal frames let keys fall through to gameplay.
- **Layout is rect-splitting** (`splitRight(n)`, `splitBottom(n)`, …), not a constraint engine —
  enough for roguelike UIs.
- **The map is just a widget.** `MapPanel` confines the existing zone rendering to a screen rect:
  it does its own viewport→screen math and writes cells at the rect's offset (instead of kotile's
  full-window `render(source, viewport)`), so map and widgets compose in the one pass.
- **Modal dimming uses top-wins compositing, not alpha.** A modal dialog is **opaque**; the area
  it does not cover is drawn **dimmed** (the frame's other widgets render their colors × a dim
  factor while a modal is active). No per-layer alpha blending, no kotile change.

Concrete widgets, built incrementally: `Frame` (border + title), `BarWidget` (HP/mana),
`TextPanel`/`LogPanel` (scrollable lines), `Dialog` (modal + dim-behind), `Menu` (selectable
list). CP437 supplies box-drawing and block glyphs for borders and bars.

## Consequences

- kotile's public surface stays frozen for its 1.0 publish; every UI concern is added in korogue
  without touching the renderer.
- The draw-surface seam makes UI layout/drawing **unit-testable** headlessly — unlike the current
  GL-bound renderer — so the toolkit grows under test.
- One render pass over a single z-layered window keeps compositing and input simple (no
  multi-window canvas sharing, no multi-batch ordering).
- `MapPanel` requires moving the zone renderer off full-window `render(source, viewport)` to
  writing into the window grid at an offset — a contained refactor of `KotileZoneRenderer`.
- The toolkit is **ASCII-first**; the graphical/tileset path (krogue-sdh) will later need the same
  widgets backed by sprites. The surface seam is the place that abstraction will plug in.
- If we ever genuinely need *see-through* translucency or shared-canvas multi-window panes, those
  are deliberate future kotile changes — explicitly out of scope here because opaque-dialog +
  dim-behind covers the need.

## Alternatives considered

- **kotile pane/widget primitives** — add panes, screen-offset rendering, and a widget framework
  to kotile. Rejected: UI is policy, not rendering, and this bloats kotile's surface right before
  its 1.0 freeze.
- **Multiple `AsciiTileWindow`s sharing a `KotileCanvas`** (`createWithCanvas`, which kotile
  already offers) — rejected: it shares GPU resources but has **no pane positioning** (every
  window renders from screen `(0,0)`), and it multiplies render batches. A single z-layered window
  with korogue-owned rects is simpler and sufficient.
- **Minimal HUD overlay only** (second render pass for HP text, no real widgets) — rejected: it
  punts the actual layout need that inventory/log/dialogs require.
- **Per-layer alpha compositing for see-through dimming** — deferred: unnecessary, since dialogs
  are opaque and "dim the rest" is achieved by rendering the uncovered widgets darker.
