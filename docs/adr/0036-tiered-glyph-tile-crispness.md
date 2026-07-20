# ADR-0036: Tiers of glyph/tile crispness — fixed bitmap, supersample-downscale, and high-res glyph source

- **Status:** Accepted
- **Date:** 2026-07-19

## Context

kotile renders a grid of cells — ASCII glyphs (`Font`/`AsciiTileWindow`) and
sprite tiles (`SpriteTileRenderer`), often **in the same window** (the animation
showcase draws DawnLike sprites and a CP437 glyph half side by side on one shared
canvas). Today the grid is rasterized once at a font's/sheet's **native** pixel
size and the composited result is scaled to the window by a pluggable
`ScalePolicy` (ADR-0017): `IntegerScale` (whole-number + nearest — crisp only at
1×/2×/3×…) or `FitScale` (fractional + sharp-bilinear — fills but softens). That
is the right, simple default, but it either letterboxes or softens at arbitrary
window sizes.

Brogue is the fidelity bar. Reading Brogue CE (`src/platform/tiles.c`) shows its
crispness is **not a special font**: it keeps a **high-resolution master** atlas
(~128×232 px per glyph) and re-rasterizes each glyph to the exact cell pixel size,
downsampling in **linear (gamma-correct) light**, with per-region sub-pixel
alignment and per-glyph brightness curves (an offline pass caches the shifts).

The decisive realisation — the one that separates two goals people conflate under
"Brogue-like":

- **"Crisp at any window size"** is a *scaling* property. You can get it while
  keeping pixel art.
- **"Brogue's smooth glyph aesthetic"** is a *glyph-source* property. It exists
  because Brogue's glyphs are **high-resolution, non-pixel** shapes downscaled to
  size. You cannot recover it by supersampling an 8×16/12×12 **bitmap** — a 12px
  glyph only ever carries 12px of information; upscale-then-downsample yields crisp
  *pixels*, not smooth glyphs. Matching Brogue's look requires a genuinely
  high-resolution or vector glyph source.

ADR-0017 deferred three strategies as coequal `ScalePolicy` follow-ups —
**krogue-19k** (SDF), **krogue-yfs** (multi-size bitmap swap), **krogue-1zo**
(supersample→FBO). Evaluating them against the two goals above: none is really a
`ScalePolicy` (that returns only a `Float`); they serve different goals; and one
is strictly dominated. This ADR sorts them into tiers.

## Decision

Offer **three tiers of crispness, chosen by the consumer**, and build what those
tiers need — no more.

**Tier 1 — fixed bitmap (default, shipped).** A bitmap `Font` (bundled
`cp437_10x10`/`cp437_12x12`, or bring-your-own) scaled by `IntegerScale`. Dead
simple, pixel-perfect at integer scales, zero extra cost. Stays the default;
`krogue-kotile-font12` belongs to this tier and is done.

**Tier 2 — resolution-independent *pixel* crispness (opt-in).** The
**supersample-to-FBO downsample** path (`krogue-1zo`): render the whole grid —
glyphs *and* sprite tiles — into an offscreen framebuffer at a large integer tile
size, then draw it to the window at the exact fractional scale, downsampling in
**linear/gamma-correct** space (the cheap, high-value lesson from Brogue's
`downscaleTile`). Crisp at any window size, one mechanism for both layers, reuses
today's fonts/sheets, preserves the CP437 pixel aesthetic. It is a canvas-level
render-target mode in `KotileCanvas` (consuming `GridLayout`'s rect), **not** a
`ScalePolicy` and **not** a glyph-source change. This does *not* reproduce
Brogue's smooth-glyph look, and does not claim to.

**Tier 3 — Brogue-fidelity smooth glyphs (opt-in).** Introduce a size-parametric
**`GlyphSource` seam**: the thing that, given a target cell pixel size, yields a
glyph texture at that size. The existing `Font` becomes the bitmap (size-agnostic)
implementation, so tiers 1–2 are unaffected. The Brogue-fidelity implementation is
a **high-resolution / vector glyph source rasterized at the cell size** — the
pragmatic route is **gdx-freetype rasterising a TTF at the exact cell px** on
resize (FreeType supplies hinted, high-quality AA *coverage* — it outputs an
alpha mask and is agnostic to colour space, so gamma-correct compositing is the
render pipeline's job: blend the coverage in linear space, exactly as tier 2's
downsample does), cached per size. An **SDF glyph source** (`krogue-19k`) is a
second implementation
behind the same seam, aimed at smoothly-scaled **UI/menu text** where the pixel
aesthetic is not wanted; it is glyph-only and softer, so it is an alternative, not
the primary Brogue route.

This **refines, not overturns, ADR-0017**: its core (two resize models, pluggable
`ScalePolicy`, GL-free `GridLayout`) stands; only its "three coequal deferred
strategies" bullet is superseded by this tiering.

## Consequences

- The easy path is unchanged: `Fonts.cp437_12x12()` + `IntegerScale` works exactly
  as today. Tiers 2 and 3 are additive and opt-in.
- Tiers 2 and 3 are **independent** and can land in either order. Tier 2 is a
  canvas render-target mode; tier 3 is a glyph-source seam + a new source. They
  compose (a TTF glyph source can also be supersampled), but neither blocks the
  other.
- The `GlyphSource` seam is now justified because tier 3 provides ≥2 non-bitmap
  implementations (freetype, SDF); it was *not* justified for tier 2 alone (an
  earlier draft of this ADR proposed tier 2 as "the Brogue case" and dropped the
  seam — that was wrong on both counts and is corrected here).
- Both new tiers are GL-visual work that must be verified on real pixels and a
  retina display, and should land on top of the HiDPI/Viewport reconciliation
  (krogue-3eu), not before it.
- `krogue-yfs` is closed as superseded; `krogue-19k` is reframed and reparented
  under tier 3 rather than sitting as a standalone "ScalePolicy."

## Alternatives considered

- **Pre-rendered multi-size bitmap swap (krogue-yfs)** — rejected/closed. Bundling
  several sizes and picking the nearest still scales from a bitmap (never
  arbitrary-scale crisp), adds asset-management complexity and shipped bytes, and
  is dominated by tier 2 for pixel crispness and by tier 3 for the smooth look. If
  tier 2 ever proves too costly on a device, one extra bundled size + IntegerScale
  is a trivial fallback needing no dedicated feature.
- **Treating tier 2 (supersample-FBO) as "the Brogue case"** — rejected (this
  ADR's own earlier draft). It delivers crisp *pixels* at any size, not Brogue's
  high-res-derived *smooth glyphs*; a bitmap source has no high-frequency detail to
  recover. Kept as tier 2 for what it genuinely is.
- **A single unified `GlyphSource` that also absorbs scaling** — rejected. Scaling
  (tier 2, a render-target concern spanning glyphs+sprites) and glyph provenance
  (tier 3, per-glyph) are different axes; conflating them would put sprite scaling
  behind a "glyph" abstraction. Keep them orthogonal.
- **Porting Brogue's full `downscaleTile`** (per-region sub-pixel alignment +
  offline `optimizeTiles` cache + per-glyph brightness curves) — deferred as a
  possible tier-3 refinement. gdx-freetype's at-size rasterisation captures most of
  the quality without the hand-rolled C downscaler and its 2-minute offline pass.
- **SDF as the primary Brogue route** — rejected. SDF is glyph-only (leaves sprite
  tiles on the old path), needs an SDF asset pipeline, and softens hard pixel
  detail; it is better positioned as a tier-3 source for smoothly-scaled UI text
  (its home in `krogue-19k`) than as the way to render the play-field font.
