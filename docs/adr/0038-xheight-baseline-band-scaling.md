# ADR-0038: x-height/baseline band scaling for lowercase crispness (completes ADR-0037)

- **Status:** Accepted (resolves the "no x-height band scaling" omission recorded in ADR-0037)
- **Date:** 2026-07-26

## Context

ADR-0037 added the opt-in `snapToPixelGrid` flag to `FreeTypeGlyphSource`: a
per-glyph min-blur **shift search** that box-downsamples the supersampled master at
a grid of sub-pixel offsets and keeps the one minimising Brogue's blur metric
`Σ sin(π·coverage)`. It aligns **one** horizontal reference by translation, so caps,
digits and box-drawing sharpen — but it explicitly **deferred** Brogue's second
text mechanism, the x-height/baseline **band snap** (`downscaleTile`'s
`map2 = round(map2)` / `map3 = round(map3)` for text tiles). That snap warps the
vertical resample so **two** references — the x-height top and the baseline — both
land on whole output rows, which is what makes **lowercase** crisp: a translation
can align a letter's baseline *or* its x-height edge, not both at once.

ADR-0037 deferred it as needing "a warped, non-uniform vertical resample that does
not fit the SAT-based uniform-box downsample," font-metric-specific and GL-only to
validate. krogue-9x7.5 is that follow-up.

Two facts made it tractable without the feared complexity:

- **A shared baseline already exists.** `drawTextGlyph` lays every glyph out on one
  line, and libGDX's `GlyphLayout.height` is the font cap height for *any* single
  glyph, so the draw origin — and hence the baseline and x-height rows — is
  **identical in every master cell**. No change to master rendering was needed.
- **The band is measurable, not metric-derived.** Brogue defines `TEXT_X_HEIGHT` as
  "the height of the 'x' outline" and `TEXT_BASELINE` as the blank below it. We do
  the same: scan the rendered master's `x` cell for its top/bottom inked rows (at
  half the cell's peak coverage). This sidesteps libGDX's ascent/descent sign
  conventions entirely and matches Brogue's own definition.

## Decision

For `snapToPixelGrid` **with `GlyphFit.TEXT`**, replace the uniform 2-D shift search
with a **band-scaled** downsample (`bandScaleDownsample`):

- Measure the x-height top and baseline once from the master `x` cell (shared by all
  cells).
- Build a piecewise-linear output-row → source-master-row map: natural slope above
  the x-height top and below the baseline, a stretched slope across the x-band, with
  both references pinned to their **rounded** output rows (Brogue's
  `round(map2)`/`round(map3)`, kept ≥ 1 row apart).
- Keep the **horizontal** shift search (uniform box + best sub-pixel x-offset) — as
  Brogue keeps the horizontal search active for text while its vertical shifts are
  disabled in favour of the band snap.
- Each output pixel is a box average over an integer-width x-span and a
  **fractional-height** y-band, linearly interpolated through the per-cell
  summed-area table and divided by the actual box area. The SAT *does* serve the
  warp — the O(1) sums are over integer x-spans; only the y-band edges are
  fractional, and those are two lerps of the SAT's column prefix.

`GlyphFit.TILE` stays translation-only under snap (single glyphs are ink-centred
independently — there is no shared baseline to align). A test-only `disableBandScale`
seam forces the translation path so a spec can A/B the two.

This remains an independent Kotlin re-implementation of the **technique** in Brogue
CE (`src/platform/tiles.c`, AGPL-3.0) — not its code; the licensing reasoning in
ADR-0037 carries over unchanged (kotile stays BSD-3-Clause).

## Consequences

- **Lowercase now shares one baseline row.** Band scaling applies a single vertical
  map to every cell, so flat-bottomed lowercase letters land on the *same* snapped
  baseline; the translation-only path picks each glyph's own offset and the line
  wanders. Measured on the `:kotile:library:freetypeVerify` harness (Ubuntu Mono,
  the shipped source's exact geometry): baseline-row spread over a varied lowercase
  row falls from **1 → 0** at a 16px cell and **2 → 1** at a 32px cell (band vs
  translation-only). This consistency — not a lower blur score — is the win, and it
  is a **driver-independent geometric guarantee**, which is what the committed GL
  spec asserts.
- **The blur metric slightly favours translation-only** (≈4–7% lower on a lowercase
  panel), *because the shift search directly minimises that exact metric per glyph*.
  It is a proxy that rewards per-glyph edge alignment at the cost of a consistent
  line; band scaling trades a hair of per-glyph blur for an even baseline, the same
  trade real text hinting makes. Band scaling still beats **no snap** by 14–17%.
- **Cost** is unchanged in character from ADR-0037: a CPU search + downsample per
  rasterise (per `prepareForCellSize`), so still off by default and best for
  fixed-size sources. The horizontal search is now 1-D (vertical is fixed by the
  snap), which is cheaper than the old 2-D grid.
- **Fallback:** if `x` has no measurable ink (a face without it), the source falls
  back to ADR-0037's uniform shift search.
- **Verification honesty (CLAUDE.md).** The committed GL spec asserts the baseline-
  consistency guarantee (band spread `< translation spread` — a genuine regression
  signal, since removing band scaling makes the two paths identical — and band spread
  `≤ 1`). Because the GL suite cannot run on the dev machine, the harness reproduces
  the committed spec's **exact** shape (direct region blit, 16×16, ss 8, same
  letters) and confirms band=0 / translation=1 there, so a green harness predicts a
  green CI. An early attempt rendered the harness mirror through an `AsciiTileWindow`
  and read nothing back — the window path depends on a window resize the harness
  didn't do — which is exactly the harness↔test environment drift CLAUDE.md warns
  about; both were moved to the window-free direct blit that the band scaling (an
  atlas-only property) is fully exercised by.

## Alternatives considered

- **Leave ADR-0037's omission standing** — rejected; the lowercase gap is the part of
  Brogue's text look the shift search cannot reach, and it turned out cheap once the
  shared baseline and `x`-measured band were recognised.
- **Forward-scatter warp (Brogue's structure)** — Brogue maps each *source* row to an
  integer target row and accumulates with a counter. Rejected in favour of the
  inverse (target-row → fractional source-band) box average, which composes directly
  with the existing SAT and the horizontal shift search rather than replacing them.
- **Snap via font metrics (ascent/descent)** — rejected as sign-convention-fragile;
  measuring the `x` outline is both simpler and faithful to Brogue's own definition.
- **A separate opt-in flag** — rejected; band scaling *is* the text half of the same
  Brogue technique `snapToPixelGrid` already advertises, so it folds into that flag
  (TEXT band-scales, TILE stays translation-only) rather than adding a second knob.
