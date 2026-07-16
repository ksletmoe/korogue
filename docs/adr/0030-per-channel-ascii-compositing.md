# ADR-0030: an ASCII cell's glyph and background resolve per channel

- **Status:** Accepted
- **Amends:** ADR-0029 (its background-resolution half; the glyph half stands)
- **Date:** 2026-07-16

## Context

ADR-0029 measured what [AsciiTileWindow] actually did with layers and concluded
that top-cell-wins was correct rather than accidental: the highest-z non-null
cell supplied the whole cell — glyph, foreground *and* background — and everything
beneath it was discarded. That conclusion was right about the **glyph** and wrong
about the **background**, and it left the consequence standing rather than fixing
it:

| overlay at z=1, over `'#'` white on **blue** | before |
| --- | --- |
| `'@'` red on **CLEAR** | `b=0.00` — terrain gone |
| `'@'` red on **BLACK** | `b=0.00` — byte-identical |

`Color.CLEAR` and `Color.BLACK` were indistinguishable once layered, so
`StaticAsciiTile.backgroundColor`'s alpha did nothing at all for a layered
consumer. ADR-0029 fixed the KDoc that claimed otherwise and filed krogue-7va to
decide whether the capability should exist.

The argument in ADR-0029 for keeping top-cell-wins is still sound, but it only
ever proves something about *glyphs*: two glyphs cannot share a cell, so drawing
both bottom-up overlaps them and the upper cell can only avoid that by painting
an opaque background over the lower glyph. None of that reasoning applies to the
background channel, which has no such conflict — exactly one background is drawn
per cell either way. ADR-0029 generalised a glyph constraint to the whole cell.

The cost was paid by consumers. Every overlay had to restate the colors of
whatever it covered, which means overlays must know about terrain: a creature
cell had to carry the floor's background, and a background-only effect —
a targeting highlight, Brogue-style lighting, a damage flash — was simply not
expressible, because tinting the background meant erasing the creature standing
on it. The layered usage in `AsciiTileWindow`'s own class KDoc (terrain z=0,
creature z=1, effect z=2) did not work the way it read.

## Decision

**Resolve the two channels independently.**

- **Glyph and foreground** — from the top-most non-null cell. Unchanged; ADR-0029
  stands here.
- **Background** — from the top-most cell that actually paints one, i.e. whose
  `backgroundColor` is not fully transparent. A fully transparent background now
  means *"I do not paint a background"* and defers to the cell below.

Alpha is **not** blended between layers. The first cell that paints a background
wins outright and its color is used as-is; a half-transparent background is not
composited over the one beneath it. This is the cheap, predictable rule
krogue-7va preferred, and nothing yet needs the expensive one — a real use for
partial blending can revisit it.

Measured after the change, same fixture:

| overlay at z=1 | result |
| --- | --- |
| `'@'` red on **CLEAR** | `b=0.72` — terrain's blue survives under the glyph |
| `'@'` red on **BLACK** | `b=0.00` — paints its own black |
| `'@'` red on **GREEN** | `g=0.72, b=0.00` — opaque still wins outright |
| `' '` on CLEAR | `b=1.00` — background survives; the glyph channel is still taken |

(The 0.72 rather than 1.00 is the `'@'` glyph itself covering roughly a quarter
of the cell — not a blend.)

This also fixes a latent bug rather than only adding capability. The viewport
render path marked a cell as animated by testing whether the *top* cell was a
`DynamicAsciiTile`. Once a background can come from underneath, a dynamic cell
below a static glyph changes the cell's appearance every frame while never being
the top cell — it would have frozen. That check now tests every layer at the
position, which is also what the sprite path already did.

## Consequences

- The documented roguelike layering now works as it reads: only the terrain need
  supply a background; a creature on `CLEAR` keeps the floor's color without
  knowing it; a highlight layer can tint a background while the creature's glyph
  still shows.
- `backgroundColor`'s alpha means something for the first time, and `CLEAR` is no
  longer a synonym for `BLACK`. The KDoc ADR-0029 corrected is corrected again —
  this time to describe a capability that exists.
- **Behaviour change, landing pre-1.0 deliberately.** A consumer relying on a
  `CLEAR`-background overlay to blank a cell to the clear color now sees the
  layer below instead. Nothing in-tree relied on that: a survey of the engine,
  the Rogue port and the demos found *no* use of transparent backgrounds at all —
  unsurprising, since until now they did nothing. `RegionSurface`'s dim-behind
  effect uses opaque backgrounds and is unaffected.
- One footgun survives, narrower than before: a space still wins the glyph
  channel (it is a glyph that happens to be keyed out), so it hides the glyph
  beneath while the background now shows through. `clearTile` still beats writing
  a blank.
- Cost: resolving a background walks down the stack instead of stopping at the
  top cell, bounded by layer count and only on cells the composite cache already
  marked dirty (ADR-0024). Not measured as a problem; measure before optimising.
- `LayeredTilemap` gains `layersTopDown`, the mirror of `layersBottomUp`, so the
  scan does not depend on the undocumented iteration order of `layerKeys`.

## Alternatives considered

- **Keep ADR-0029's decision as-is** — rejected. It is defensible for glyphs and
  indefensible for backgrounds, and the evidence for that is in ADR-0029 itself:
  a public property whose alpha measurably does nothing is a defect, whether or
  not the KDoc admits it.
- **Alpha-blend backgrounds down the stack** — rejected for now. More expressive
  and more expensive, and no in-tree consumer wants it. First-painter-wins is
  predictable and is what krogue-7va recommended; blending can be layered on
  later without breaking this rule, since today no one passes partial alpha.
- **Resolve the foreground per channel too** (glyph from the top cell, its color
  from elsewhere) — rejected as incoherent: a glyph and its color are one thing,
  and splitting them has no use case anyone asked for.
- **A separate "background layer" API instead of alpha** — rejected as a second
  concept to learn where an existing property already carries the meaning; alpha
  on `backgroundColor` is the obvious place for consumers to look.
