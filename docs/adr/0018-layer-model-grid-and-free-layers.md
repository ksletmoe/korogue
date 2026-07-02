# ADR-0018: kotile layer model — grid layers and free (pixel-space) layers

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

kotile renders content **snapped to the tile grid**: `AsciiTileWindow` /
`TileRenderer` composite a `LayeredTilemap` (z-ordered cell layers) and draw the
top cell of each `(x, y)` via `KotileCanvas.drawTile`. Two upcoming needs do not
fit that model:

- **Free-moving effects** (krogue-tk9): projectiles, beams, particles, and
  animations that fly across the screen at sub-tile/pixel resolution and
  interpolate smoothly between cells.
- **Graphical UI** (krogue-tvm): for a *graphical* tile game, UI elements that
  are not aligned to cells — panels, a health bar between rows, a tooltip at the
  exact cursor pixel, sliding/animated widgets.

The open questions were: should a non-grid layer be a separate rendering
subsystem? How is a grid layer declared versus a non-grid layer? Can a grid
layer and a non-grid layer overlay? The explicit constraint was **keep it
simple** — no elaborate compositing framework.

The key realization is that kotile is **already pixel-space underneath**:
`drawTile(cellX, cellY, …)` merely computes a pixel rectangle
(`offsetX + cellX * tileW`, plus the y-flip; see ADR-0017) and draws a region
there through one `SpriteBatch`. A "non-grid layer" is not a different renderer —
it is the same draw call without the cell→pixel mapping.

## Decision

**1. One pixel-space primitive.** `KotileCanvas` exposes a pixel-space draw —
`drawSprite(pxX: Float, pxY: Float, region, tint, w, h)` — as the real drawing
primitive. `drawTile(cellX, cellY, …)` becomes sugar over it (map cell → pixel
via the current `GridLayout`, then `drawSprite`). No second renderer.

**2. Layers compose by draw order, not by machinery.** A frame is an **ordered
list of layers** rendered back-to-front (painter's algorithm) within the shared
`SpriteBatch`:

```kotlin
interface Layer { fun render(canvas: KotileCanvas) /* + optional update(dtMs) */ }
```

Overlaying a grid layer with a free layer is therefore just their order in that
list — draw the grid pass, then the free pass on top. No blend/composite
passes, no per-layer framebuffers.

**3. Two layer kinds, distinguished by addressing, not by renderer.**
- **Grid layer** — cell-addressed content (`LayeredTilemap` / `AsciiTileWindow`;
  the tilemap's z-index still orders *within* the grid).
- **Free layer** — pixel-addressed content: a collection of items each with a
  float position (+ sprite/glyph, size, tint).

The **effects layer** (krogue-tk9) is the first free-layer consumer; it owns the
primitive and layer interface. The **graphical UI layer** (krogue-tvm) is the
second consumer, differing only in lifetime and input handling (persistent +
input-aware) — not in how it renders.

**4. Grid/text UI stays on the tile surface.** The existing UI toolkit (`Menu`,
`Dialog`, `PagedTextList`, `TextOverlay`, `HotkeyMenu`) draws cell-aligned to a
tile surface and remains the correct choice for ASCII/text UI. The free/pixel-
space UI layer is a *separate* consumer, needed only by graphical tile games,
and is deferred until there is a concrete graphical-UI need (krogue-tvm) rather
than built speculatively.

## Consequences

- Minimal new surface: one primitive (`drawSprite`) plus a `Layer` interface and
  a back-to-front render order at the screen level. Effects and graphical UI both
  ride on it.
- **Input hit-testing splits by layer kind:** grid layers map pixels → cells via
  `GridLayout.tileAt` (ADR-0017); free layers need their own pixel-space
  hit-testing (relevant to krogue-tvm).
- Alpha for the *sprite grid* path (krogue-ejd) is orthogonal but related:
  free layers over a grid work by draw order, but a sprite terrain tile showing
  through an entity sprite is the grid-path layering handled there.
- Free-layer rendering is **GL-visual** — verify with a display (Linux/xvfb),
  not headless on macOS.
- korogue-rogue can adopt free layers to become optionally graphical (graphical
  tileset rogue-jk6 + graphical UI via krogue-tvm) without the engine growing a
  parallel render path.

## Alternatives considered

- **A separate rendering subsystem per layer type** — rejected as over-complex;
  everything already funnels through one `SpriteBatch` in pixel space, so a
  second renderer buys nothing.
- **Put non-grid content into the `LayeredTilemap` z-layers** — rejected; those
  layers are cell-locked by definition, which is exactly what free content must
  escape.
- **Real compositing (per-layer framebuffers / blend passes)** — rejected;
  painter's-order draw in a single batch is sufficient and far simpler. (An FBO
  pass may appear later for a specific filter — see ADR-0017's krogue-1zo — but
  not as the layer-composition mechanism.)
- **A bespoke graphical-UI subsystem now** — rejected in favor of reusing the
  free-layer primitive when the need is concrete; grid/text UI already covers the
  ASCII case.
