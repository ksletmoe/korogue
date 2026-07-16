# ADR-0029: the ASCII path composites top-cell-wins, and that is not a defect

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

ADR-0028 made kotile's two render paths share one vocabulary but deliberately
left one asymmetry standing, and filed krogue-8mr to settle it: the sprite path
([TileRenderer]) composites **bottom-up** — every populated z-layer is drawn and
alpha-blended — while the ASCII path ([AsciiTileWindow]) is **top-cell-wins**:
the highest-z non-null cell is drawn and the layers under it are not.

The suspicion in krogue-8mr was that this is not intrinsic to glyphs but merely
an accident, and that it silently discards content a consumer would reasonably
expect to show. Two things supported that. `StaticAsciiTile.backgroundColor`
documented the opposite behaviour outright — *"use a fully transparent color
(e.g. `Color.CLEAR`) to overlay text without obscuring whatever is already
drawn"* — and `AsciiTileWindow`'s own class doc advertised a layered roguelike
usage (terrain z=0, creature z=1, effect z=2) that reads as if lower layers
survive.

Measured, with z=0 holding `'#'` white on **blue** and one 10x10 cell:

| overlay at z=1 | result |
| --- | --- |
| none | `b=1.00` — terrain shows |
| `'@'` red on **CLEAR** | `b=0.00` — terrain gone |
| `' '` (space) on CLEAR | `r=g=b=0.00` — cell renders nothing, terrain still gone |
| `'@'` red on **BLACK** | `b=0.00` — byte-identical to the CLEAR row |

So the documented feature does not exist: `CLEAR` and `BLACK` backgrounds are
indistinguishable once layered, and a cell that draws nothing at all still hides
everything beneath it.

## Decision

**Top-cell-wins stays. The documentation was wrong, not the behaviour.**

Matching the sprite path literally — drawing every populated layer bottom-up —
would be *worse*, not merely different. A cell is one glyph in one font at one
position. Bottom-up compositing of `'#'` under `'@'` does not produce a creature
standing on a floor; it produces both glyphs overlapping in the same cell, with
the lower one visible through the gaps in the upper one's strokes. The upper cell
can only avoid that by painting an opaque background over the lower glyph — which
is top-cell-wins, arrived at the long way round.

The asymmetry is therefore intrinsic to the content type. Sprites are images and
blending them is the point; ASCII cells are atomic glyph/foreground/background
triples that two layers cannot meaningfully share. Same z-index concept, same
"higher draws on top" rule, different resolution — because the thing being
resolved is different.

What changes is the documentation, which now states the policy plainly on both
paths (KDoc on `TileRenderer`, `AsciiTileWindow`, and
`StaticAsciiTile.backgroundColor`) including the two consequences that bite:

- A transparent background reveals the canvas clear color, not the layer below.
- A blank cell (a space, whose glyph is keyed out) still occupies its position
  and hides what is under it. Use `clearTile`, not a space, to let lower layers
  through.

`RenderPathParityTest` guards the vocabulary; the policy itself is now pinned by
pixel tests in `RenderingIntegrationTest` (search krogue-8mr), so a future change
to either path's compositing fails a test rather than surprising a consumer.

## Consequences

- The paths stay asymmetric on this point, on purpose, with the reason recorded
  rather than rediscovered. ADR-0028's "kept deliberately" note now has an answer
  behind it.
- Consumers layering ASCII must give each cell the background it should have.
  That is the honest cost of atomic cells and is now documented rather than
  contradicted by the KDoc.
- Not addressed here: **per-channel compositing** — resolving the glyph from the
  top-most cell but the background from the top-most *opaque* one. That is what
  would make `CLEAR` backgrounds meaningful and would enable background-only
  overlays (targeting highlights, Brogue-style lighting) without each overlay
  restating the terrain's colors. It is a genuinely useful capability and a real
  feature, not a bug fix, so it is filed separately (krogue-7va) rather than
  smuggled in under a documentation correction.

## Alternatives considered

- **Composite the ASCII path bottom-up, to match sprites** — rejected; this is
  the option krogue-8mr proposed and the measurements above are why. It overlaps
  glyphs. Symmetry with the sprite path is not worth rendering garbage.
- **Per-channel compositing now** — rejected *for this ADR*, not on the merits.
  It changes behaviour and adds capability, and folding it into a doc-correction
  would bury a real design change. See krogue-7va.
- **Keep the behaviour and the misleading KDoc** — rejected, obviously. It
  promised a feature that measurement shows does nothing, on a class that is part
  of the 1.0 public API.
- **Drop `backgroundColor`'s alpha entirely, since `CLEAR` == `BLACK` when
  layered** — rejected. Alpha still does something real for an *unlayered* cell
  and for the composite the window blits (transparent cells reveal the canvas
  clear color); removing it would break that. The fix is to describe what it does
  rather than remove it.
