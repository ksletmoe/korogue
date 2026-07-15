# ADR-0028: the sprite and ascii render paths share one vocabulary

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

kotile ships two parallel rendering paths, documented as siblings: the sprite
path (`rendering/TileRenderer` + `SpriteTileRenderer`) and the ascii path
(`display/ascii/AsciiTileWindow`). They do the same job over different cell
content — hold a `LayeredTilemap`, mutate cells, composite z-layers, blit
through a `GridCompositeCache` (ADR-0024) — and ADR-0027 had already made their
*tile hierarchies* symmetric one-for-one (`SpriteTile`/`AsciiTile` →
static/dynamic/animated).

Their *renderer* APIs had drifted anyway (krogue-0y8), in two ways.

Same concept, different name:

| concept | sprite path | ascii path |
| --- | --- | --- |
| grid size in tiles | `windowWidth` / `windowHeight` | `widthInTiles` / `heightInTiles` |
| resize entry point | `onResize(w, h)` | `resize(w, h)` |
| cell-write content param | `staticTile` / `tile` (two overloads) | `tile` (one) |

And capability gaps — the sprite path had no `clear()`, `clearLayer(z)`,
`fill(...)`, no query method, no z-defaulted `drawTile(x, y, tile)`, and did not
expose `tileWidthPx`/`tileHeightPx`/`layout` for input wiring.

This mattered *now* rather than later because `windowWidth`, `windowHeight`, and
`onResize` are baked into the public API of the `com.sletmoe:kotile` artifact.
Renaming them is a breaking change once 1.0 is published; the missing
clear/fill/query methods are additive and could in principle wait, but deciding
them together is what makes the two APIs read as one system.

The tie-breaker for direction was the exemplary consumer: the Rogue 5.4.4 port
(`../korogue-rogue`) is built entirely on the ascii path and already speaks
`widthInTiles` / `clear()` / `create {}`. Aligning the sprite path to the ascii
path therefore costs the flagship consumer nothing, while the reverse would have
churned it for no gain.

## Decision

**The ascii path's vocabulary is canonical. The sprite path adopts it.**

Renamed on `TileRenderer` (breaking, pre-1.0):

- `windowWidth` / `windowHeight` → `widthInTiles` / `heightInTiles`
- `onResize(widthPx, heightPx)` → `resize(widthPx, heightPx)`

Collapsed on `TileRenderer`: the `drawTile(…, staticTile: StaticSpriteTile)` and
`drawTile(…, tile: DynamicSpriteTile)` overload pairs become a single
`drawTile(…, tile: SpriteTile)` taking the sealed supertype — matching
`AsciiTileWindow.drawTile(…, tile: AsciiTile)`. Callers stop choosing an overload
per branch, and a future `SpriteTile` branch needs no new overload.

Added to `TileRenderer` (additive), each mirroring the ascii path's existing
shape and semantics: `drawTile(x, y, tile)` and `clearTile(x, y)` (z=0 defaults),
`clear()`, `clearLayer(z)`, `fill(tile)`, `fill(z, tile)`, `topTileAt(x, y)`,
`topTileAt(position)`, plus the `tileWidthPx` / `tileHeightPx` / `layout`
properties.

Two asymmetries are kept deliberately, and documented in the KDoc rather than
papered over:

- **Compositing.** The ascii path is top-cell-wins; the sprite path draws every
  populated layer bottom-up so alpha blends. Consequently sprite `topTileAt`
  answers "what is on top", not "what does this cell look like" — the latter has
  no tile-shaped answer there, since `regionFor` resolves a `StaticSpriteTile`'s
  region. The ascii `topTileAt` takes an `elapsedMs` and resolves to a
  `StaticAsciiTile`; the sprite one returns the `SpriteTile` itself.
- **Construction.** `AsciiTileWindow` uses a private constructor plus
  `create {}` / `createWithCanvas {}` because it *owns* resources (canvas, font,
  background texture) and needs an ownership contract in the factory.
  `TileRenderer` is an abstract class because `regionFor` is a genuine extension
  point, and `SpriteTileRenderer(canvas, sheet)` is its concrete entry point.
  These entry points differ because the underlying problems differ, so forcing a
  shared shape here would be symmetry for its own sake.

Parity is enforced by `RenderPathParityTest`, which reflects over both classes
(no GL context needed, so it always runs) and asserts their public vocabularies
are equal modulo an explicit allowlist of path-specific members — currently just
`drawText`, since only the ascii path has a character concept. Adding a member to
one path fails the test until it is mirrored or explicitly justified.

## Consequences

- The two paths now read as one system; intuition and code transfer between
  them, and the docs' "siblings" claim is true rather than aspirational.
- The rename is a breaking change, which is exactly why it lands pre-1.0. In-repo
  callers (`kotile:demo`, `:demo`, kotile's own tests) were updated; the Rogue
  port needed no change at all, as predicted.
- Drift is now a test failure rather than something noticed a release later.
- The allowlist in `RenderPathParityTest` is a deliberate speed bump: a genuinely
  path-specific member is one line to justify, which is cheap, and the cost falls
  precisely on the case worth thinking about.
- Not addressed here: the differing compositing policies themselves (top-wins vs
  bottom-up alpha) are a real behavioural asymmetry, not just a naming one — a
  transparent background color on an ascii cell does not reveal lower layers the
  way sprite alpha does. Filed separately (krogue-8mr) rather than folded into a
  naming change.

## Alternatives considered

- **Align the ascii path to the sprite path instead** (`windowWidth`,
  `onResize`) — rejected. It churns the flagship consumer for nothing, and
  `windowWidth` is the worse name: it says "window" for a value counted in tiles,
  on a class whose pixel dimensions are a separate concept. `onResize` also reads
  as a callback the class receives rather than a method the app calls.
- **Add the missing clear/fill/query later, post-1.0** — rejected. They are
  additive and so technically safe to defer, but deciding them alongside the
  rename is what produced one coherent surface; deferred, each would have been
  designed alone against whatever the sprite path looked like by then.
- **Unify construction behind a shared factory DSL** — rejected as symmetry for
  its own sake; see the ownership/extension-point reasoning above.
- **Keep the split `StaticSpriteTile`/`DynamicSpriteTile` overloads** — rejected.
  The sealed supertype is what ADR-0027 made possible, and the split forced
  callers to name the branch (`staticTile = …`) for no benefit.
- **Enforce parity by convention/review only** — rejected. The paths drifted
  under exactly that regime; the drift is mechanical and so is the check.
