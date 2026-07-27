# ADR-0039: Bundle Cascadia Mono + DejaVu Sans Mono (Bold), drop Ubuntu Mono

- **Status:** Accepted
- **Date:** 2026-07-26

## Context

`FreeTypeGlyphSource` (ADR-0036 tier 3, krogue-9x7.2) rasterises a bundled TrueType
face at the cell pixel size. The original bundled face was **Ubuntu Mono (Regular)**,
chosen for supposedly broad CP437 coverage. krogue-9x7 identified the *font itself* as
the single biggest remaining crispness lever versus Brogue: Brogue's tileset is
effectively bold/high-contrast, whereas a regular-weight text face thins further under
the gamma-correct downscale and reads soft at small cell sizes. krogue-9x7.8 set out to
evaluate a heavier, screen-oriented replacement with broad CP437 coverage under a
permissive licence.

Two gates decided it, run over the actual pipeline (a throwaway harness,
`:kotile:library:fontEval` + `FontEval.kt`, driving the real `FreeTypeGlyphSource` with
`snapToPixelGrid` on, TEXT band-scale, ss4), plus a `fontTools` cmap audit against the
exact 253-code-point repertoire from `Cp437.kt`:

**Gate 1 — CP437 coverage** (of 253 distinct non-space code points):

| Face | Coverage | Note |
|---|---|---|
| Cascadia Mono Bold / SemiBold | **253/253** | complete |
| DejaVu Sans Mono Bold | **253/253** | complete |
| JetBrains Mono (all weights) | 232/253 | no ☺♥♦♣♠♪♫, ⌂ |
| IBM Plex Mono (all weights) | 210/253 | **no Greek** — disqualified |
| Ubuntu Mono (Regular/Bold) | 212/253 | no half-blocks ▀▄▌▐, no arrows ←↑→↓ |

The surprise: the incumbent Ubuntu Mono `.notdef`s ~41 slots — including the half-blocks
and every arrow — so its original "broad coverage" rationale did not hold.

**Gate 2 — crispness under downscale** (metrics over the downsampled page; lower
blurRatio and grey% = sharper, higher weight/solid% = bolder), at a hard 16px cell:

| Face | weight | solid% | grey% | blurRatio |
|---|---|---|---|---|
| Ubuntu Mono R (was default) | 0.526 | 29.1% | 50.4% | 0.942 |
| JetBrains Mono Bold | 0.669 | 45.2% | 39.6% | 0.560 |
| DejaVu Sans Mono Bold | 0.654 | 46.8% | 39.3% | 0.567 |
| **Cascadia Mono Bold** | **0.674** | **51.9%** | **31.8%** | **0.471** |

Cascadia Bold is the crispest at the small cell that matters most; DejaVu Bold edges it
only at 20px and is a wider, more neutral letterform. Both are Bold and both have
complete coverage. The user's call was to bundle both, defaulting to Cascadia, and remove
Ubuntu Mono outright.

## Decision

- **Bundle two vector faces**, both Bold, both complete CP437 coverage:
  - **Cascadia Mono Bold** (SIL OFL 1.1) — the **recommended default**,
    `Fonts.cascadiaMono(...)`.
  - **DejaVu Sans Mono Bold** (DejaVu / Bitstream Vera + Arev licence) — a wider,
    heavier alternative, `Fonts.dejaVuSansMono(...)`.
- **Remove Ubuntu Mono** — the `Fonts.ubuntuMono(...)` factory, `UbuntuMono-R.ttf`, and
  its licence files are deleted. The pipeline's GL-gated tests and the macOS
  `freetypeVerify` harness (whose assertions are relative/structural, not face-specific)
  are repointed to `cascadiaMono`; all pass, including the band-scale baseline-spread
  thresholds.
- Each bundled TTF ships with its unmodified upstream licence text plus a provenance
  note on the classpath (`cascadia-mono.license.txt`, `dejavu-sans-mono.license.txt`).

## Consequences

- The default look is now bold and high-contrast (closer to Brogue) and every CP437
  slot renders a real glyph — no more `.notdef` arrows/half-blocks.
- **Breaking API change:** consumers calling `Fonts.ubuntuMono(...)` must switch to
  `Fonts.cascadiaMono(...)` (identical signature). Acceptable pre-1.0.
- JAR grows by ~0.9 MB for two extra TTFs (Cascadia Bold ~570 KB, DejaVu Bold ~325 KB),
  partly offset by dropping Ubuntu Mono (~200 KB). A follow-up could subset each face to
  the CP437 repertoire to shrink this (filed as needed); bundling full faces keeps the
  OFL/DejaVu "used as-is, unmodified" position simplest.
- `FontEval.kt` + the `fontEval` Gradle task remain as the reproducible method for any
  future font comparison.

## Alternatives considered

- **DejaVu Sans Mono Bold as default** — equally complete coverage and marginally crisper
  at 20px, but wider (lower text density) and a more generic letterform; kept as the
  alternative rather than the default.
- **JetBrains Mono (Medium/SemiBold/Bold)** — screen-tuned and crisp, but 232/253 coverage
  (missing card suits, music notes, smileys, ⌂), so it would reintroduce `.notdef` gaps.
- **IBM Plex Mono** — disqualified: no Greek (210/253).
- **Keep Ubuntu Mono, just use its Bold weight** — Ubuntu Mono Bold is still 212/253
  (same coverage holes) and downscales less crisply than Cascadia Bold.
- **Subset the bundled faces to CP437 now** — deferred; full faces are simpler licence-wise
  and the size cost is modest.
