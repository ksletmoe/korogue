# ADR-0037: Per-glyph pixel-grid alignment for tier-3 glyphs — opt-in min-blur shift search

- **Status:** Accepted (partially revises ADR-0036's deferral of `optimizeTiles`). The
  x-height/baseline band-scaling omission recorded below is resolved in ADR-0038.
- **Date:** 2026-07-25

## Context

ADR-0036 shipped the tier-3 `FreeTypeGlyphSource` (Brogue-style smooth glyphs: a
high-res master downsampled in linear light) and **deferred porting Brogue's full
`downscaleTile`** — its per-region sub-pixel alignment, offline `optimizeTiles`
shift cache, and per-glyph brightness curves — judging that gdx-freetype's at-size
rasterisation "captures most of the quality without the hand-rolled C downscaler
and its 2-minute offline pass."

krogue-9x7.3 revisited that after a **direct side-by-side against Brogue CE** at
Brogue's exact pipeline, transcribed from `tmewett/BrogueCE`
`src/platform/tiles.c` + `Rogue.h`: a fixed **128×232 px master per cell** (aspect
16:29), downsampled to the on-screen cell (`outputWidth/COLS × outputHeight/ROWS`,
COLS=100 ROWS=34) in **linear light at gamma 2.0** (`dst += value*value`). kotile
reproduces that master exactly (`16×29 @ ss8`, `32×58 @ ss4`), yet Brogue read
**crisper and brighter**. Two causes were separable:

- *Brighter* was largely an unfair comparison — kotile's sample used a grey field
  where Brogue uses pure black. On black the brightness gap mostly closed.
- *Crisper* was real, and reading Brogue's `downscaleTile`/`optimizeTiles` in full
  identified three mechanisms kotile lacked:
  1. a **per-glyph sub-pixel shift search** that minimises a blur metric
     (`blur += sin(π·value/255²)`) so stems land on output pixels;
  2. **x-height/baseline band snapping** — `map2 = round(map2)`, `map3 = round(map3)`
     plus a slight stretch of the middle band so **two** horizontal references
     (x-height top and baseline bottom) land on whole pixel rows at once;
  3. a **"reduce boldness" contrast curve** on text (`v < ½ ? v/2 : 3v/2 − ½`).

  Plus a non-pipeline factor: Brogue ships a **hand-tuned bold tile font**;
  kotile uses general-purpose Ubuntu Mono.

Two cheap tries were measured and rejected. A **contrast curve** just thinned
Ubuntu Mono (Brogue's curve assumes its bolder art), and a **naive translation
snap** (align the pen + a shared baseline) was marginal for text — aligning a
single reference point cannot land a letter's interior stems, which sit at assorted
sub-pixel offsets from the pen. The one lever that measurably helped was mechanism
(1): a genuine per-glyph min-blur shift search.

## Decision

Add an **opt-in** `snapToPixelGrid` flag to `FreeTypeGlyphSource`
(`Fonts.ubuntuMono(...)` passthrough), **off by default**, implementing mechanism
(1) only. In the downsample, for each CP437 glyph it tries a grid of sub-pixel
offsets, box-downsamples the supersampled master at each, and keeps the offset that
**minimises Brogue's blur metric** `Σ sin(π·coverage)` — fewest half-lit,
grey-edged pixels — so stems land on whole output pixels. A **per-cell summed-area
table** makes each candidate's box average O(1), so the whole search is one CPU
pass rather than `supersample²` GL readbacks. Coverage is straight-averaged to match
`GammaDownsample`'s alpha handling.

Scope is deliberately **translation-only**: no x-height band scaling, no offline
shift cache, no contrast curve. This is a Kotlin re-implementation of the
**technique** in Brogue CE (`optimizeTiles`/`downscaleTile`, **AGPL-3.0**) — the
algorithm, not its code — attributed in the source and KDoc.

## Consequences

- **Measurably crisper**: ~**17–21% lower** blur metric on the Brogue-parameter
  sample (`:kotile:library:freetypeVerify` → `freetype-brogue.png`), most visible on
  horizontal strokes and box-drawing at small cell sizes. This is the tier-3 tuning
  ADR-0036 deferred, now available to consumers who want it.
- **Cost**: a CPU search + downsample **per rasterise**, i.e. on every
  `prepareForCellSize` resize. The SAT keeps it to sub-second for the whole 256-glyph
  page, but it is not free — hence off by default and recommended for **fixed-size**
  sources, not resize-heavy `resolutionIndependent` windows.
- **Not full parity**, by two explicit omissions:
  - **No x-height band scaling.** A translation aligns *one* horizontal reference;
    Brogue's band stretch aligns *two* (x-height + baseline), which is what makes
    **lowercase** crisp. It was skipped because it requires a **warped, non-uniform
    vertical resample** that does not fit the SAT-based uniform-box downsample (the
    SAT's whole value is O(1) *uniform* box sums), and it is font-metric-specific and
    higher-risk to validate — the GL suite cannot run on the dev machine, only CI. So
    caps, digits, and box-drawing gain more from this change than lowercase does.
  - **No offline shift cache.** Brogue precomputes shifts for *all* sizes (5–64px)
    once at startup and caches them; kotile recomputes on each rasterise. Skipped
    because the primary use case (a fixed-size source) computes once anyway; a
    size-keyed cache is a clean follow-up that would pay off only for resize-heavy use.
- **Licensing**: kotile is **BSD 3-Clause**; Brogue CE is **AGPL-3.0** (strong
  copyleft, incompatible with copying into a permissive project). This is an
  **independent implementation of the *technique*** — a min-blur shift search and its
  metric — written in original Kotlin from a reading of Brogue's source, with a
  different structure (a summed-area table for O(1) box averages vs Brogue's direct
  per-candidate accumulation). **No Brogue code was copied or line-by-line ported.**
  Copyright protects the specific code expression, not ideas/algorithms/methods (US
  17 U.S.C. §102(b)) or mathematical formulas, so the independent implementation does
  not create a derivative work of Brogue's code and does not trigger AGPL — kotile
  stays BSD. The attribution in the source/KDoc records intellectual **provenance**;
  crediting an AGPL project as the *source of an idea* is not a licence grant and
  imposes no AGPL obligation. (Not legal advice; noted so the reasoning is on record.
  Copying or closely porting the actual C would be a different matter and was avoided.)
- Leaves ADR-0036's other deferrals intact: the band scaling, the cache, and the
  contrast curve remain unported (the last rejected outright here).

## Alternatives considered

- **Naive translation snap** (align pen + a single shared, grid-snapped baseline) —
  implemented first, then replaced. It aligns one reference point, so interior stems
  still straddle pixels; the blur reduction was marginal for text.
- **Brogue's "reduce boldness" contrast curve** — rejected. Designed for Brogue's
  bold tile art, it thins a normal-weight face like Ubuntu Mono, worst at small sizes.
- **Full `optimizeTiles` port** (band scaling + offline all-sizes cache) — deferred,
  not rejected. It closes the remaining lowercase gap and removes the per-resize cost,
  but needs the warped resample and the cache machinery; filed as follow-up work rather
  than blocking this increment.
- **Accepting ADR-0036's deferral unchanged** — rejected once the side-by-side showed a
  real, *measurable* crispness gap that the shift search closes at bounded cost.
