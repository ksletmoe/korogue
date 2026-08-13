# ADR-0043: An artist tilesheet is a coverage mask, downscaled by a fractional box filter

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

ADR-0036 gave kotile three tiers of cell crispness, and two of them are about a
*glyph source*: a fixed bitmap [`Font`] scaled by the window (tier 1), and a
TrueType face rasterised at the cell size (tier 3, `FreeTypeGlyphSource`). Neither
covers the route **Brogue** actually takes for its map: one PNG of hand-drawn
tiles at far above display resolution (`assets/tiles.png`, 128×232 px per tile),
each resolved down to whatever the cell size currently is. That is how a wall gets
texture and a monster gets a silhouette a font cannot draw, without the artist
authoring a sheet per size (krogue-9x7.7).

Adding that source raised two decisions that are hard to reverse, because they are
visible in the public API and in what an artist has to deliver.

**1. What are the sheet's pixels?** Brogue's tiles are single-channel **coverage
masks**, tinted per cell at draw time — its downscaler reads one byte per pixel
(`src & 0xff`) and the colour comes from the game, not the sheet. That is why
Brogue's whole crispness pipeline — the gamma-correct resolve *and* the
`optimizeTiles` shift search — applies to its "graphical" tiles as readily as to
its glyphs. Full-RGB sprite art is a different object: the gamma-correct
downsample still carries over, but the blur metric `Σ sin(π·coverage)` assumes
coverage, and a per-cell tint (which kotile's ASCII path applies unconditionally,
ADR-0030) multiplies the art rather than colouring it.

**2. How does the master get to cell size?** `FreeTypeGlyphSource` rasterises its
master at exactly `supersample`× the cell, so the ratio is a power of two and the
shrink is a chain of exact GPU 2:1 halvings (`GammaDownsample`). An artist's sheet
has a **fixed** master resolution and the cell is whatever the window gives:
128 → 14 px is 9.142857 master px per output px. The halving chain cannot express
that.

## Decision

**`TileSheetGlyphSource` treats a sheet as a coverage mask by default, and offers
full colour as an explicit, documented second ink** (`TileInk.COVERAGE` /
`TileInk.FULL_COLOR`). Coverage is read as `luma × alpha`, so a light-on-black
sheet (Brogue's convention) and a white-on-transparent sheet both work unchanged;
the atlas is built as white RGB with coverage in alpha, exactly like a `Font`'s
glyph page, so a tile tints per cell and one silhouette serves every palette
entry. `FULL_COLOR` carries RGB through, and its documentation states the two
things it costs: the cell tint now multiplies (paint those cells white), and
pixel-grid alignment falls back to the tile's alpha silhouette.

**The shrink is a fractional box filter on the CPU, not the GPU halving chain.**
Each output pixel area-averages its (fractional) source rect — Brogue's own
`downscaleTile` shape — made O(1) per pixel by a per-cell summed-area table
sampled *bilinearly* at the rect corners, which is exact rather than approximate.
Coverage is straight-averaged, because coverage is a linear quantity like alpha;
`FULL_COLOR` averages RGB in **linear light weighted by alpha**, which is the
`GammaDownsample` shader's arithmetic restated for a fractional footprint.

**The ADR-0037 shift search carries over, gated per axis by detected full-bleed
edges.** Offsets are fractional now (a quarter of an output pixel, as before), and
an axis whose border carries ink sits the search out — the same exemption
box-drawing glyphs get in ADR-0040/0041, for the same reason: translating a tile
that must meet its neighbour shaves its trailing edge and opens a seam. Detected
from the pixels rather than declared, so a mixed sheet needs no per-tile table.

Aspect mismatch is handled by one coarse knob, `TileScaling.STRETCH` (default —
terrain must fill its cell) or `PRESERVE_ASPECT` (centred, letterboxed — figures
should not be squashed). Brogue's fuller per-tile `TileProcessing` table is not
ported (krogue-itq).

## Consequences

- An artist can hand kotile one hi-res sheet and get resolution-independent tiles
  that still participate in the grid's tint, layering, and dirty-region redraw —
  no new render path, because the source is just another `GlyphSource`.
- The whole sheet is resampled on the CPU per rasterise, i.e. on every changed
  `prepareForCellSize`. Cost scales with the master's pixel count and, under
  `snapToPixelGrid`, with the search grid. This is the tier-3 trade-off already
  accepted for `FreeTypeGlyphSource`, but the constant is larger: a Brogue-sized
  sheet is millions of master pixels. Documented on the class; the mitigation is a
  fixed cell size, or leaving snapping off.
- The master pixels are held for the source's life (one byte per pixel for
  coverage, four for colour) so a resize needs no re-read.
- Two shift-search implementations now exist — the integer-ratio one inside
  `FreeTypeGlyphSource` and the fractional one in `TileSheetResample`. They share a
  technique, not code. Unifying them is filed (krogue-8gy) rather than done here,
  because the freetype path's tuning (band scaling, the cell-filling stroke warp)
  is entangled with its power-of-two ratios and is verifiable only on CI.
- Because the resampler is pure (arrays in, arrays out), its maths is unit-tested
  **GL-free** and runs on macOS — a first for this corner of kotile, where every
  earlier crispness change could only be checked on CI or through a bespoke
  harness.

## Alternatives considered

- **Full colour as the primary (or only) ink** — matches the intuition of "artist
  tilesheet", but loses the per-cell tint that the ASCII path is built around, and
  the alignment metric degrades. Kept, but as the explicit second choice.
- **Reuse the GPU 2:1 halving chain** — would need the master padded or
  pre-resampled to a power-of-two multiple of the cell, i.e. a fractional resample
  anyway, with a GL round-trip on top.
- **Bilinear/mipmapped texture sampling at draw time** — free, but it is exactly
  the softness tier 2 and tier 3 exist to avoid, and it cannot shift-align.
- **A per-tile processing table now** (Brogue's `TileProcessing`) — more faithful,
  but it needs an authoring format kotile has no consumer for yet, and the two
  cases that matter (stretch vs preserve aspect, snap vs full-bleed) are covered by
  one enum and one detector. Deferred to krogue-itq.
