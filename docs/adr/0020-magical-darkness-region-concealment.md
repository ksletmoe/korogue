# ADR-0020: Magical darkness as region concealment (not just unlit)

- **Status:** Accepted (supersedes ADR-0015's "magical darkness lives in illumination" point)
- **Date:** 2026-07-02

## Context

ADR-0015 models perception in three layers (illumination, line-of-sight, and an
observer-relative perception combiner) with **composable, tagged senses**. A
sense declares `tags` (what it *is* / depends on) and the tags it `pierces`;
`Suppressor`s (on the observer) and `Concealment`s (on the target) negate senses
by matching tags. Built-ins: `Sight → {visual, light-dependent}`,
`Darkvision → {visual}`, `TrueSight → {visual}` + `pierces {visual}`.

ADR-0015 placed **magical darkness in the illumination layer**: "it sets light to
zero in a region; no perception code special-cases it." That is provably
insufficient (krogue-dta). `Darkvision` reveals line-of-sight cells *regardless
of lighting* (`requireLit = false`), so an unlit region does not stop it. Result:
**darkvision penetrates magical darkness** in-engine, when — against the 5e
genre-default bar ADR-0015 adopts — magical darkness should hard-counter
darkvision while *truesight* still pierces it. An effect that should be a hard
counter currently does nothing to darkvision.

Illumination can't express this because the distinction isn't about *light* — it
is about a region that **suppresses visual perception itself**, which darkvision
(a `{visual}`, light-independent sense) is subject to but truesight is not.

## Decision

**Model magical darkness as a *region (environmental) concealment* on the
perception layer — a third suppress mechanism alongside observer `Suppressor` and
target `Concealment` — reusing the existing tag + `pierces` machinery.** It is
**not** a lighting value and no sense special-cases it.

Concretely:

1. **A per-cell concealment layer on `Zone`** (analogous to `lightMap`): a
   `Grid<Set<String>>` of concealment **tags** per cell (empty = none). A
   magical-darkness region sets `{visual}` on its cells. Like `lightMap`, this is
   derived/effect-driven state — produced by a darkness effect/emitter + a system
   (mirroring `LightEmitter` + `LightingSystem`), or by generation; that source is
   the game's/effect's concern, the per-cell layer is the engine seam.

2. **A region-concealment step in `StandardPerception`.** Today target
   `Concealment` gates *entities only* ("concealment is a property of targets, so
   it gates entities, not terrain"). Region concealment gates **cells** (and thus
   any entity standing on them): a sense reveals a magically-dark cell only if it
   `pierces` the cell's concealment tags. Piercing applies here exactly as for
   target concealment (region concealment is a *concealment*, the pierceable
   side — not an observer suppressor).

**Consequences of the tag choice** (`{visual}`), with no sense changes:
- `Sight {visual, light-dependent}` — blocked (already blocked by zero light too).
- `Darkvision {visual}` — **now blocked** (the gap this ADR closes).
- `TrueSight {visual}` + `pierces {visual}` — **pierces it for free** → sees.
- Non-visual senses (`Tremorsense {vibration}`, `Telepathy {mental}`) — unaffected;
  you still feel/detect creatures in magical darkness, which is correct.

Magical-darkness cells are also unlit (illumination), but the *perception* block
is the region concealment, **decoupled from light** — that decoupling is exactly
what lets it stop light-independent darkvision.

This **supersedes** ADR-0015's "magical darkness lives in illumination; no
perception code special-cases it": it still special-cases nothing (it's data — a
tag on cells — interpreted uniformly), but it lives in *perception*, not
illumination.

## Consequences

- Three suppress mechanisms, cleanly delineated: observer `Suppressor` (gates
  senses, not pierceable), target `Concealment` (gates entities, pierceable),
  region concealment (gates cells, pierceable).
- Fully **headless-testable** (pure perception logic; no GL) — mac-verifiable.
- Ordinary unlit cells are unchanged: no region concealment, so darkvision still
  sees them. Only *magical* darkness carries the `{visual}` concealment tag.
- Relates to krogue-0vg (ambient/global light) and the room-visibility work
  (rogue-phf): those are illumination/policy; this is a distinct perception layer.
- **Known refinement (not blocking):** the engine's single `TrueSightSense`
  conflates truesight *and* see-invisible (it pierces `{visual}` to see
  `Invisible`). So here see-invisible also pierces magical darkness, a minor 5e
  divergence inherited from that conflation, not introduced here. A game needing
  them distinct splits the sense and gives magical darkness a dedicated tag that
  only a truesight-style `pierces` lists.

## Alternatives considered

- **Lighting-layer marker** (a distinct "magical darkness" `LightValue`) —
  rejected: forces every visual sense to special-case it (against ADR-0015's
  tag-generality ethos), and it can't be pierced through the existing `pierces`
  machinery without bespoke per-sense logic. Darkvision would still need explicit
  "except magical darkness" code.
- **Observer `Suppressor`** — wrong shape (magical darkness is environmental, not
  a property of the observer) and suppressors are deliberately *not* pierceable,
  so truesight could not see through it.
- **Per-target `Concealment` on each entity in the region** — wrong granularity:
  it can't hide terrain cells or empty cells, and would need re-tagging entities
  as they move in/out of the region.
- **Reject as a non-goal** — legitimate (many roguelikes ignore magical darkness),
  but the tag + `pierces` scaffolding already present makes this a small, general
  addition, and it closes the one remaining behavioural divergence from the 5e
  default bar (per krogue-dta's notes, the rest already matches).
