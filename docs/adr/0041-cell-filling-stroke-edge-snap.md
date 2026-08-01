# ADR-0041: Cell-filling glyphs snap their stroke edges by a cell-edge-pinning warp

- **Status:** Accepted (completes the crispness omission recorded in ADR-0040)
- **Date:** 2026-07-31

## Context

ADR-0040 made box-drawing and block glyphs (`U+2500–U+259F`) *cell-filling*: their
design cell is mapped onto the cell rect, so strokes run edge to edge and meet the
neighbouring cell's. Making that survive `snapToPixelGrid` cost something, and
ADR-0040 recorded the debt in its own Consequences:

> **Stroke crispness under `snapToPixelGrid` is left on the table for this class.**

Both of `snapToPixelGrid`'s transforms — ADR-0037's sub-pixel **shift search** and
ADR-0038's **band-scale** warp — move the sampling window, and the clamp at the master
edge then shaves coverage off the trailing output pixel. On a glyph pinned to the cell
edge that re-opens the very seam the class exists to close, so ADR-0040 exempted the
whole class and emitted it with a plain offset-free box downsample. The exemption is
what keeps the seams shut; the price is that a stroke's *interior* edges land wherever
the uniform downsample puts them. Measured on the `freetypeVerify` harness at a square
24px cell with Cascadia Mono: `─`'s stroke is 2.3 output rows tall and straddles the
grid, rendering **175 / 255 / 157** — a solid row between two half-lit ones. `│`'s
trailing column reads **120**. That is the grey krogue-tg5 is about.

Two facts made the fix tractable, as with ADR-0038:

- **A warp with both cell edges pinned has no clamp to lose ink to.** The problem was
  never "resampling", it was *translation* — sliding the window past the master edge.
  A map with `out 0 → master 0` and `out n → master n` only redistributes rows
  *between* the edges, so the seams survive by construction rather than by exemption.
- **The stroke edges are measurable per glyph.** Unlike the text baseline (page-wide,
  ADR-0038), a box glyph's strokes are its own, but they are trivially visible in the
  cell's coverage profile — which the per-cell summed-area table already carries.

## Decision

Under `snapToPixelGrid`, cell-filling glyphs get their own alignment
(`emitCellFillingCell`) instead of being exempt: **the cell edges stay pinned and the
stroke edges are snapped between them**, per axis. This is ADR-0038's band-scale idea —
pin references, stretch between — applied to a box glyph's strokes rather than the
x-height band.

- **Measure.** Read the cell's coverage profile off its SAT (total master alpha per
  master row, and per master column). A *stroke* is a run at or above **half that
  profile's own peak** — Brogue's x-band rule applied across the cell, and
  peak-relative so an arm that spans only part of the cell (`├`) resolves the same as
  one that spans all of it (`─`). Each run boundary is refined below one master pixel
  by conserving ink, because a whole-master-pixel edge is a quarter of an output pixel
  at the default 4× supersample — enough to round to the wrong row.
- **Snap.** A stroke's *opening* edge goes to its nearest output boundary; its closing
  edge is then placed a **rounded width** away, not at its own nearest boundary.
- **Warp.** Build the piecewise-linear output→master map through `(0, 0)`, those knots,
  and `(n, n·ss)`, and box-average each output pixel over its (fractionally bounded on
  both axes) master rect. Bilinear interpolation of the SAT is *exact* for that rect —
  the master is constant within a pixel, so its integral is bilinear in the sub-pixel
  offsets — so the warp reuses the SAT rather than re-integrating the master.
- **Fall back to the identity** — i.e. to ADR-0040's offset-free `emitCell` — when an
  axis has nothing to snap: an empty cell, a **uniform** axis (`─` across x, `█` across
  either), or a cell-filling **pattern** whose edges exceed a cap (the shades
  `░ ▒ ▓`, whose periodic dither there is no point snapping). `█`, `▄`, `│`'s rows and
  `─`'s columns therefore go down byte-for-byte the pre-tg5 path.

Rounding the **width** rather than both edges independently is the one non-obvious
choice, and it was made on measured pixels. Independent rounding quantises the width to
`round(e1) − round(e0)`, which can miss the true width by a whole output pixel: `│`'s
5.35px stem landed in a 6px band and every column came out at **89%** — a stroke made
*greyer* by the snap meant to sharpen it. Rounding the width keeps the band's average
coverage at the stroke's own density (5.35px of ink in 5px reads solid), which is what
snapping is for: turn partial coverage spread over two pixels into whole pixels, never
dilute it across more.

## Consequences

- **Box-drawing strokes are crisp under snap.** On the `freetypeVerify` harness at a
  square 24px cell with Cascadia Mono, before → after: `─`'s rows
  **175/255/157 → 17/240/241/17**, `│`'s columns
  **5/216/255/255/255/255/120 → 23/238/255/255/255/225/26**. Across cell sizes 12–32
  the count of half-lit lines (neither ≥ 0.75 nor ≤ 0.25) goes from 1–2 per glyph to
  **0** everywhere.
- **The ADR-0040 seams hold.** The committed 9x7.4 specs still pass unchanged: the
  two-cell `─` strip's weakest column is 241 (was 255, both far above the 128 gate),
  `│`'s weakest row 255, `█` fills 0.998 of its cell and `▄`/`▀` exactly their half —
  the last three byte-identical, since blocks take the identity fallback.
- **Different box glyphs still meet.** The snap is a deterministic function of the
  master stroke position, and the cell-filling placement gives every member of the
  class the *same* master positions, so `─` and `┼` put the shared arm on the same
  rows (measured: both `[12, 13]`; the double line `═`/`╬` both `[10, 11, 15, 16]`).
  A monotone warp with pinned ends also leaves a *uniform* run uniform, so an axis one
  glyph warps and its neighbour doesn't still matches along the shared edge. This is
  the failure mode the warp could have introduced, so it is asserted, not assumed —
  though it is a guard rather than a regression signal: it passes before the change too.
- **A residual softness remains, and is not this fix's to close.** The snapped band's
  outermost pixels read ~225–240 rather than 255, with ~10% spilling past the boundary.
  That is the *master's own* antialiasing ramp (~2 master px wide, from the linear
  filtering of the design-cell blit), centred on the snapped boundary — the edge is on
  the grid; the ramp straddles it. Closing that needs sharpening (steepening the ramp),
  not edge-pinning, and would be a separate decision.
- **Cost** is small and bounded: profiles and knots are O(cell) per axis off a SAT that
  was already built, for 48 of 256 slots, only when `snapToPixelGrid` is on (off by
  default). No new knob — this is the box-drawing half of the same Brogue technique the
  flag already advertises, exactly as ADR-0038 folded the text half in.
- **Verification honesty (CLAUDE.md).** The GL suite cannot run on the dev machine, so
  the committed specs render through the window-free 1:1 atlas blit that the macOS
  `freetypeVerify` harness mirrors exactly — same cell size, same strips, sharing the
  same literals and helper functions (`verifyStrokeSnapCommittedShape`, `strokeRows`,
  `SNAP_SOLID_PEAK`/`SNAP_BLANK_PEAK`) rather than copies. Run against the *un-fixed*
  production code (stashed) in that same shape, the harness reports exactly the two
  half-lit-line assertions failing (`halfLit=2` for `─`, `1` for `│`) and everything
  else passing — so the new specs are coverage, not decoration. The seam-agreement
  assertions pass both ways by design; they guard the new failure mode rather than
  signal the old one. **Not verified:** the specs themselves have not been executed —
  only their mirrored shape, on macOS. CI is where they first run.
- **A stale harness image is its own kind of wrong claim.** The `cascadia-showcase.png`
  eyeball image lived in the `fontEval` harness, which requires `-PfontsDir` because it
  exists to compare *candidate* faces. That image only ever used the bundled face, so
  the gate meant it could not be regenerated in normal work — and a copy rendered before
  krogue-9x7.4 was still showing the pre-ADR-0040 gap at every cell boundary a week after
  that was fixed, which read as a live defect in this change. Measuring the same geometry
  in the current code (0 interior gaps across 8 cells vs 29 in the stale PNG) is what
  settled it. Both it and the descender probe moved to `freetypeVerify`, which every run
  refreshes. The general point is CLAUDE.md's: an artefact that does not regenerate with
  the code will eventually disagree with it, and silently.

## Alternatives considered

- **Leave ADR-0040's exemption standing** — rejected; it was filed as a follow-up, not
  as a settled position, and the grey it leaves is most visible at exactly the small
  cell sizes `snapToPixelGrid` exists for.
- **Round both stroke edges independently** (the literal reading of ADR-0038's
  `round(map2)`/`round(map3)`) — rejected on measured pixels; see the Decision. The
  text band snap can afford it because a band is tens of pixels tall, where a
  ±1px width error is invisible; a 2-pixel stroke it turns grey.
- **Reuse `bandScaleDownsample`'s vertical-only warp** — rejected: box drawing needs
  *both* axes (a `│` snaps in x, a `─` in y), and that path's references are the
  page-wide text baseline, which is meaningless for this class. The 2-D fractional
  box average is what generalises it, and bilinear SAT sampling makes that exact.
- **Snap the *pattern* glyphs too** (`░ ▒ ▓`) — rejected: their edges are a periodic
  dither, not strokes; there is no reference a snap would improve, and the knot cap
  keeps them on the uniform resample they already had.
- **Sharpen the master ramp** (nearest-neighbour filtering for the design-cell blit, so
  edges arrive hard) — would close the residual softness above, but trades the class's
  antialiasing quality on everything else (the shades' dither especially) for it.
  Out of scope here; edge-pinning is what krogue-tg5 asked for.
