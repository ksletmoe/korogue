# ADR-0042: The bundled vector faces stay whole — no CP437 subsetting

- **Status:** Accepted (settles a question ADR-0039 left open)
- **Date:** 2026-08-01

## Context

ADR-0039 bundles two full TrueType faces — Cascadia Mono Bold (~570 KB) and DejaVu
Sans Mono Bold (~325 KB) — while `FreeTypeGlyphSource` only ever rasterises the
253-code-point CP437 repertoire in `Cp437.kt`. That ADR deferred subsetting them,
recording it as an open trade to evaluate ("bundling full faces keeps the OFL/DejaVu
'used as-is, unmodified' position simplest"), which became krogue-c5o.

The evaluation was run. Each face was subset with `pyftsubset` to exactly the
repertoire parsed out of `Cp437.kt` (not a hand-copied list), OpenType layout tables
dropped — nothing in the pipeline shapes text; gdx-freetype rasterises one code point
at a time — and the hinting bytecode **kept**, so the `ss == 1` `Hinting.Slight` path
in `generateGlyphFont` renders exactly as it does today:

| face | bundled | subset | glyphs |
|---|---|---|---|
| `CascadiaMono-Bold.ttf` | 581,736 B | 49,152 B | 4319 → 254 |
| `DejaVuSansMono-Bold.ttf` | 331,992 B | 33,760 B | 3316 → 254 |
| **total** | **914 KB** | **83 KB** | **−831 KB (−91%)** |

Also dropping the hinting bytecode reaches ~49 KB total, but changes what `ss == 1`
renders, so it was not on the table.

The percentage is dramatic only because the faces carry ~4300 and ~3300 glyphs to
serve 253. The absolute saving is what matters, and it is under a megabyte.

Two findings shape the decision:

- **The size win is real but immaterial.** 831 KB is noise in a desktop JAR that
  ships a game engine, its renderer, and bitmap CP437 sheets.
- **The licence obstacle ADR-0039 anticipated is weaker than stated, but the
  *structural* cost is the real one.** On the licence texts themselves: Cascadia's
  OFL 1.1 reserves the string **"Cascadia Code"**, and the bundled face is *Cascadia
  Mono* — clause 3 does not literally bite. DejaVu's Bitstream Vera terms forbid only
  "Bitstream" or "Vera" in a derivative's name, which "DejaVu Sans Mono" already
  satisfies. So neither licence strictly compels a rename. What actually costs is
  **becoming a font modifier at all**: renamed name tables (OFL convention, even
  where unforced), provenance notes rewritten off "used as-is, unmodified" to
  derived-work language in `cascadia-mono.license.txt` /
  `dejavu-sans-mono.license.txt`, a checked-in fontTools subsetting tool with its own
  Python toolchain to keep working, a coverage regression test to guard against a bad
  subset shipping, and this ADR. That standing maintenance surface is the price, not
  a legal bar.

## Decision

**Keep both bundled faces whole and unmodified.** No subsetting, no derived font
files, no subsetting tool in the repo. ADR-0039's "used as-is, unmodified" licence
position stands as written, and the provenance notes on the classpath stay accurate
as they are.

krogue-c5o is closed as won't-do, with the measured numbers above recorded on it.

## Consequences

- The JAR carries ~831 KB it does not strictly need. Accepted.
- The licence story stays the simplest one available: two upstream faces, shipped
  byte-for-byte with their unmodified licence texts alongside. No rename question, no
  derived-work provenance chain, nothing for a downstream consumer to re-audit.
- No Python/fontTools dependency enters the build or the contributor toolchain.
- Swapping or adding a bundled face later stays a drop-in — copy the TTF and its
  licence text in — rather than a subset-and-rename pipeline run.
- **The reopen trigger is a size-constrained target, not a tidiness urge.**
  krogue-pp2 (iOS) and krogue-mvm (Android) are open; 831 KB reads differently in an
  app bundle than in a desktop JAR. If either lands, re-evaluate — the method and the
  numbers here are the starting point, and the licence analysis above still holds.

## Alternatives considered

- **Subset and rename the name tables** (e.g. to a `Kotile Mono CP437` family) — the
  conservative, OFL-convention-following version. Rejected: it buys 831 KB for the
  full modifier cost, and renaming makes the shipped faces harder to identify against
  their upstreams for anyone auditing the JAR.
- **Subset but keep the upstream family names** — defensible on the letter of both
  licences (see Context), and it avoids the rename churn. Rejected anyway: it
  distributes modified faces under their original names, which is against OFL practice
  and can confuse font caches, all for the same immaterial saving.
- **Subset and also drop the hinting bytecode** (~49 KB total, −865 KB) — the largest
  win available, but it changes `ss == 1` rendering, so it trades the pipeline's
  small-size legibility path for JAR bytes. Rejected on both counts.
- **Subset at build time from the full faces**, keeping only the whole faces in git —
  would leave the repo's fonts unmodified, but puts Python and fontTools on the
  critical path of every build and every contributor's machine. Clearly worse than
  either shipping whole faces or checking in subsets.
