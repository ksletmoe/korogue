# ADR-0044: Glyph atlas cells are packed with an extruded one-texel gutter

- **Status:** Accepted
- **Date:** 2026-08-13

## Context

Both resolution-independent glyph sources publish their cells as regions of one
`Linear`-filtered page: `FreeTypeGlyphSource` a 16×16 CP437 page (ADR-0036), and
`TileSheetGlyphSource` a `columns × rows` page of artist tiles (ADR-0043). Both
packed their cells edge to edge, with nothing between them.

Drawn at 1:1 that is exact — every screen pixel samples a texel centre. Drawn at
any other scale (the `FitScale` / `fitToWindow` / sharp-bilinear composition
paths, and any consumer calling `KotileCanvas.drawSprite` at its own size) the
GPU's bilinear unit samples **up to half a texel past the region's outer edge**,
which in a tight page is the *neighbouring cell*. At a magnifying scale that is
not a rounding error: the outermost drawn pixel column lands `0.5 - 0.5/scale`
of a texel out, and the columns behind it taper off from there.

This was invisible while every glyph was ink-centred and stopped short of its
cell edges. It stopped being invisible with ADR-0040/0041, which edge-snap the
cell-filling class (box drawing and blocks) so it *does* reach the cell edge, and
with ADR-0043, where a full-bleed tile — a wall texture that must meet its
neighbours — reaches the edge by design (krogue-wcw; CodeRabbit flagged the
tilesheet half of it on PR #43, and it was deliberately left for one decision
covering both pages rather than two divergent ones).

Measured on real pixels (macOS, `freetypeVerify`, a 16px cell drawn at 8×): the
empty top half of `▄` picked up a decaying 48/25/9 of 255 in from the edge it
shares with `█`, and `█`'s own edge strips — solid by construction — were *dimmed*
to 0.78–0.88 of full by sampling into the empty cells around them. The bug reads
as a fringe on one side of the seam and a gap on the other.

## Decision

**Both pages pack every cell with a one-texel gutter, filled by extruding the
cell's own edge row and column into it** (`GlyphAtlasPadding`, shared by both
sources). A sample that reaches past a region edge then reads a copy of that
edge texel — the behaviour `CLAMP_TO_EDGE` gives a whole texture, emulated at
each *cell* boundary. (`CLAMP_TO_EDGE` itself is per texture object: on an atlas
it only guards the page's outer border, which is why the tilesheet page's own
left/top edges never showed this.) No per-cell textures, no shader.

**Extruded, not cleared.** A transparent gutter fixes the fringe and keeps the
gap: it would fade a full-bleed cell out at its border, re-opening the very seam
ADR-0040/0043 exist to close. Extrusion fixes both halves of the defect, which is
why the specs assert both — that a cell takes no neighbouring ink *and* that a
full-bleed cell stays solid to its edges.

**One texel is enough** because bilinear reach is half a texel and these pages
carry no mipmaps; a mipmapped page would need a gutter per level.

**The gutter is added once, at upload.** Everything upstream — the per-cell
scissor, the downsample and SAT strides, the shift search, the brightness curve —
keeps working on a tightly packed page, and `rasterize` repacks it just before
`Texture(...)`. The alternative (threading a padded stride through the whole
pipeline, as the issue first proposed) touches every piece of the crispness
machinery for no observable gain, and that machinery is verifiable only on CI.

## Consequences

- A page grows by `2 px` per cell per axis: 32 px on a 16×16 CP437 page, i.e.
  a 384×384 atlas becomes 416×416 (+17% texels). Both sources check the **padded**
  page against `GL_MAX_TEXTURE_SIZE`, since that is what gets uploaded —
  `TileSheetGlyphSource` already had such a check and now measures the padded size;
  `FreeTypeGlyphSource` had none at all (it capped only its supersampled master, so
  an over-large cell uploaded as garbage) and gained one, which closes the
  pre-existing hole this change would otherwise have widened by 32 px (krogue-y1o).
  Both are `check`s: a cell too large for the GPU is now a thrown failure rather
  than a texture the driver silently refuses. The spans are computed as `Long`,
  so a cell size large enough to wrap an `Int` product cannot slip under the limit
  as a negative — the one way an oversized page could still get past the guard.
  That arithmetic is pure, so unlike everything else here it is unit-tested
  GL-free (`GlyphAtlasPaddingTest`) and runs on macOS.
- One extra cell-resolution pixmap copy per rasterise — negligible beside the
  supersampled render and the CPU downsample it follows, but it is per resize
  step on the `resolutionIndependent` path.
- Region origins are no longer `slot × cell`; both sources ask
  `GlyphAtlasPadding.cellOriginPx` instead. Nothing outside the sources depends on
  the page's layout — consumers only ever see `TextureRegion`s.
- The bitmap `Font` / `TileSheet` (tier 1) is **not** changed here. It is drawn at
  an integer scale with a nearest mag filter, where no bleed exists; its
  minification case is mipmap halo, a different defect with an existing
  documented mitigation (`spacing`/`useMipMaps=false`).
- The regression is pinned by a GL spec per source that draws one region magnified
  8× — bleed exists at any scale off 1:1, and magnification is what makes it large
  enough to measure (0.4375 of a texel out at 8×, against a 1:1 draw that lands on
  the texel centre and shows nothing). Those specs run on CI; each has a macOS
  mirror at its own FBO size, camera, draw and sample rects, and it is the mirrors
  that were run locally — `freetypeVerify` 5 of 6 checks FAIL → ALL PASS,
  `tileSheetVerify` 3 of 6 FAIL → ALL PASS, un-fixed vs fixed.

## Alternatives considered

- **A transparent gutter.** Cheaper to reason about, and wrong: see above.
- **Clamp-to-edge sampling per cell** (one texture per cell, or a shader that
  clamps UVs to the region). A texture per cell gives up the single-page atlas and
  its batching; a shader would have to be threaded through every path that draws a
  glyph, including consumers' own `drawSprite` calls, and kotile already spends its
  fragment-shader budget on sharp-bilinear and the gamma downsample.
- **Keep the pages 1:1 and rely on the resolution-independent path** (which
  re-rasterises at the on-screen cell px and is exact). That is the recommended
  route, but it is not the only supported one — `FitScale`, `fitToWindow` and
  free-layer sprites are public API, and a defect that only appears "if you compose
  it the other way" is still a defect.
