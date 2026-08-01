# ADR-0040: Box-drawing and block glyphs are edge-snapped to the cell, not ink-centred

- **Status:** Accepted. The stroke-crispness-under-`snapToPixelGrid` omission recorded
  below is resolved in ADR-0041, which replaces this class's *exemption* from the snap
  transforms with a warp that pins the cell edges and snaps the stroke edges between them.
- **Date:** 2026-07-30

## Context

`FreeTypeGlyphSource` places each glyph in its atlas cell by one of two strategies
(ADR-0036 tier 3, refined by krogue-ns5/ux6): `GlyphFit.TEXT` lays glyphs out on a
shared baseline at a uniform em, shrunk so the face's full ink box (ascent incl.
accent room, plus descent) fits the cell; `GlyphFit.TILE` centres each glyph's ink
box and scale-fits it. Both **centre** the glyph within the cell.

That is right for a letter and wrong for the box-drawing and block/shade slots
(CP437 `0xB0–0xDF`: `─ │ ┼ ╔ █ ▄ ░ ▒ ▓`). Those glyphs are *cell-filling* by
design — `─` spans the face's whole design cell horizontally, `│` vertically, `█`
both — and a frame or a wall only reads correctly if each glyph's strokes run edge
to edge and meet the neighbouring cell's. A centred placement reaches the cell edge
only when the target cell's aspect happens to match the face's own
`advance : line-height`; otherwise the glyph is scaled by whichever axis binds and
leaves a gap on the other. On a **square** cell with a typical (tall) mono face,
measured on real pixels: a two-cell `─` strip had **~4 empty columns each side of
the boundary** (peak ink 0 across the seam) and the full block covered **0.42** of
its cell. That is the seam krogue-9x7.4 (a follow-up filed when tier 3 shipped)
records.

Two further constraints shaped the fix:

- `snapToPixelGrid` re-samples each cell on the CPU — a sub-pixel **shift search**
  (ADR-0037) and, for TEXT, a **band-scale warp** (ADR-0038). Both move the sampling
  window, and the clamp at the master edge then shaves coverage off the trailing
  output pixel. Applied to a glyph pinned to the cell edge, that re-opens the seam.
- The metrics a glyph reports (`BitmapFont.Glyph`) are the **rasterised bitmap** box,
  which FreeType expands to whole master pixels — so up to one master pixel per side
  is antialiasing fringe rather than solid ink (vertically it usually is, since the
  ascent/descent aren't integers).

## Decision

Treat "cell-filling" as a **glyph class** with its own placement, orthogonal to
`GlyphFit`, applied under both fits and with no new knob:

- **The class** is Unicode's two cell-filling blocks — Box Drawing `U+2500–U+257F`
  and Block Elements `U+2580–U+259F`, contiguous, exactly CP437 slots 176–223.
  Deliberately excluded: blocky-looking but *centred* ornaments the face never meant
  to tile (`■ U+25A0`, `▬ U+25AC`, arrows, triangles), which keep the ordinary
  ink-centred placement.
- **The placement** (`drawCellFillingGlyph`) maps the face's **design cell** affinely
  onto the whole cell rect, independently per axis, and draws every member through
  that one map. Edges therefore land on cell edges and, because neighbouring cells
  share the map, strokes meet with matching position *and* weight.
- **The design cell is measured, not derived**: it is the ink box of the face's own
  full block `U+2588` — the one glyph that *is* the design cell — inset by one master
  pixel per side for the antialiasing fringe, so the **solid** part maps to the cell
  and the fringe spills just past it, where the per-cell scissor drops it. A face
  with no inked block yields no map and keeps the old per-fit placement.
- **Cell-filling glyphs are exempt from both `snapToPixelGrid` transforms**: they are
  emitted by a plain, offset-free box downsample (`emitCell` at offset `(0, 0)`,
  bypassing the band warp). Their alignment is already fixed by the cell rect, whose
  edges are output-pixel boundaries by construction, so there is nothing for a
  sub-pixel search to improve — only ink for it to lose.

## Consequences

- **Box drawing and blocks tile seamlessly at any cell aspect.** Measured on the
  `:kotile:library:freetypeVerify` harness at a square 24px cell with Cascadia Mono
  (a tall face — the mismatched case), before → after: the weakest column of a
  two-cell `─` strip **0 → 255**, the weakest row of a stacked `│` strip **5 → 255**,
  full-block cell coverage **0.42 → 0.998**, and `▄`/`▀` from 0.39/0.42 to 0.998 of
  their exact half. Identical numbers with `snapToPixelGrid` on, which is what the
  exemption buys.
- **An aspect mismatch now costs stroke *weight*, not continuity.** Mapping the
  design cell per axis means horizontal and vertical strokes scale by different
  factors, so on a square cell with a tall face the verticals come out proportionally
  heavier. That is the deliberate trade: a visible weight difference beats a visible
  gap, and it vanishes as the cell aspect approaches the face's.
- **Rendered output changes for existing consumers** — box drawing gets bigger and
  reaches the cell edges. This is a defect fix rather than a preference, so it is on
  by default and unconditional; an opt-out would only be an option to render frames
  with holes in them. Older GL specs that sampled a block's *central half* (because
  the block didn't reach the edges) now assert the full cell.
- **Stroke crispness under `snapToPixelGrid` is left on the table for this class.**
  Exempting them from the shift search means a box-drawing stroke's *interior* edges
  are wherever the uniform downsample puts them, so a thin line can straddle two
  output rows. Closing that needs an edge-pinning warp *within* the cell (the
  band-scale idea applied to stroke edges rather than the x-band) — filed as a
  follow-up rather than bundled here.
- **Verification honesty (CLAUDE.md).** The GL suite cannot run on the dev machine,
  so the committed specs render through a window-free 1:1 atlas blit that the macOS
  `freetypeVerify` harness mirrors exactly — same cell size, same grids, same
  measurements, sharing the same literals and helper functions rather than copies
  (`verifyCellFillSeamsCommittedShape`). The specs were then run against the *un-fixed*
  production code (stashed) in that same shape and fail there, so they are coverage
  rather than decoration.

## Alternatives considered

- **Uniform (isotropic) cover-scale + clip** — scale the design cell by
  `max(scaleX, scaleY)`, centre, and let the overflow be scissored. Keeps stroke
  weight even across axes and still fills the cell, but a cell-filling *pattern* (the
  shades `░ ▒ ▓`, whose period would then be clipped rather than mapped) breaks phase
  at the cell boundary, and half-blocks survive only because the clip happens to cut
  in the right place. Rejected: the anisotropic map is exact for every member of the
  class, which is what "seamless" has to mean.
- **Derive the design cell from font metrics** (`xadvance` × `lineHeight`, positioned
  by ascent/descent) — rejected for the same reason ADR-0038 rejected metric-derived
  band snapping: sign- and convention-fragile across faces, and it assumes the face
  draws its blocks in the box its metrics imply. Measuring `█` is both simpler and
  self-calibrating; whatever box the face actually uses is the box we map.
- **Draw box-drawing programmatically** (what terminal emulators do — synthesise the
  strokes per cell and ignore the font's glyphs) — the gold standard for seams and
  weight, but it discards the face's letterforms for a whole class of glyphs, needs a
  hand-written table of ~130 shapes, and would have to be redone per line style.
  Rejected as far more machinery than the seam warrants; the measured design-cell map
  gets the same continuity out of the font that is already there.
- **Put it behind a flag** — rejected; see above. The pre-fix behaviour is not a style
  anyone would choose.
